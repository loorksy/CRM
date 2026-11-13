package cloud.lork.audit.engine

enum class ParseMode { PARAGRAPH, FIXED3, LINES, PARAGRAPH_THEN_LINES }

/** نفس MessageParser في bulk-sender 3.3.5، مع المسار الذي تستخدمه الفقاعة: فقرة ثم سطر إن كانت النتيجة فارغة. */
fun parseMessages(rawText: String, mode: ParseMode): List<String> {
    return when (mode) {
        ParseMode.FIXED3 -> chunkNonBlank(rawText, 3)
        ParseMode.LINES -> nonBlankLines(rawText)
        ParseMode.PARAGRAPH -> splitParagraphs(rawText)
        ParseMode.PARAGRAPH_THEN_LINES -> {
            val paragraphs = splitParagraphs(rawText)
            if (paragraphs.isEmpty()) nonBlankLines(rawText) else paragraphs
        }
    }
}

private fun nonBlankLines(raw: String): List<String> =
    raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

private fun chunkNonBlank(raw: String, size: Int): List<String> {
    val lines = nonBlankLines(raw)
    if (lines.isEmpty()) return emptyList()
    return lines.chunked(size).map { it.joinToString("\n") }.filter { it.isNotBlank() }
}

private fun splitParagraphs(raw: String): List<String> =
    Regex("\\n\\s*\\n+").split(raw).map { it.trim() }.filter { it.isNotEmpty() }

/** قسمة صحيحة كما في BulkSession.delayMs: max(60000 / mpm, 50) و mpm بين 1 و 1000. */
fun delayMs(messagesPerMinute: Int): Long {
    val mpm = messagesPerMinute.coerceIn(1, 1000)
    val gap = 60000L / mpm
    return maxOf(gap, 50L)
}

const val SEND_CLICK_DELAY_MS = 180L
const val MAX_MPM = 1000
const val DEFAULT_MPM = 10

data class SendItem(
    val id: String,
    val position: Int,
    val body: String,
    val status: String,
)

data class SendSessionState(
    val state: String,
    val index: Int,
    val mpm: Int,
    val chatKey: String?,
)

data class ChatObservation(
    val accessibilityBound: Boolean,
    val overlayGranted: Boolean,
    val supportedPackage: Boolean,
    val entryFound: Boolean,
    val sendFound: Boolean,
    val entryText: String,
    val chatKey: String?,
    val chatNameReadable: Boolean,
)

sealed class SendAction {
    data class PrepareText(val itemId: String, val text: String) : SendAction()
    data class PressSend(val itemId: String) : SendAction()
    data class Halt(val reason: String, val sessionState: String, val uncertainItemId: String?) : SendAction()
    data object Wait : SendAction()
    data object Done : SendAction()
}

fun restoreAfterRestart(session: SendSessionState, items: List<SendItem>): Pair<SendSessionState, List<SendItem>> {
    val fixed = items.map { item ->
        if (item.status == "EXECUTION_STARTED") item.copy(status = "UNCERTAIN") else item
    }
    val paused = if (session.state == "SENDING" || session.state == "WAITING_CHAT") {
        session.copy(state = "PAUSED")
    } else {
        session
    }
    return paused to fixed
}

fun rememberChat(session: SendSessionState, obs: ChatObservation): SendSessionState {
    if (session.chatKey == null && obs.supportedPackage && obs.entryFound && obs.chatNameReadable && !obs.chatKey.isNullOrEmpty()) return session.copy(chatKey = obs.chatKey)
    return session
}

fun afterPress(session: SendSessionState, items: List<SendItem>, itemId: String): Pair<SendSessionState, List<SendItem>> {
    val updated = items.map { if (it.id == itemId) it.copy(status = "SEND_PRESSED") else it }
    val next = session.index + 1
    val state = if (next >= updated.size) "DONE" else "SENDING"
    return session.copy(state = state, index = next) to updated
}

fun nextSendAction(session: SendSessionState, items: List<SendItem>, obs: ChatObservation): SendAction {
    if (session.state == "DONE") return SendAction.Done
    if (session.state == "IDLE" || session.state == "PAUSED" || session.state == "STOPPED") return SendAction.Wait
    val item = items.getOrNull(session.index) ?: return SendAction.Done
    if (item.status == "SEND_PRESSED" || item.status == "SKIPPED" || item.status == "FAILED") {
        return SendAction.Done
    }
    if (item.status == "UNCERTAIN" || item.status == "EXECUTION_STARTED") {
        return SendAction.Halt(
            "الرسالة في حالة غير مؤكدة ولن تُعاد تلقائياً.",
            "PAUSED",
            item.id,
        )
    }
    if (!obs.accessibilityBound) {
        return SendAction.Halt("انقطعت خدمة إمكانية الوصول.", "PAUSED", null)
    }
    if (!obs.overlayGranted) {
        return SendAction.Halt("إذن الظهور فوق التطبيقات غير ممنوح.", "PAUSED", null)
    }
    // As in the original sender, allow WhatsApp to open before binding a chat.
    if (session.state == "WAITING_CHAT" && session.chatKey == null &&
        (!obs.supportedPackage || !obs.entryFound || !obs.chatNameReadable)) {
        return SendAction.Wait
    }
    if (!obs.supportedPackage) {
        return SendAction.Halt("التطبيق الظاهر ليس واتساب. توقف الإرسال.", "PAUSED", uncertainIfPrepared(item))
    }
    if (!obs.entryFound) {
        return SendAction.Halt("تعذر العثور على حقل الكتابة في واتساب.", "PAUSED", uncertainIfPrepared(item))
    }
    if (item.status == "TEXT_PREPARED" && !obs.sendFound) {
        return SendAction.Halt("تعذر العثور على زر الإرسال.", "PAUSED", uncertainIfPrepared(item))
    }
    if (!obs.chatNameReadable || obs.chatKey.isNullOrEmpty()) {
        return SendAction.Halt("تعذر التأكد من المحادثة المفتوحة.", "PAUSED", uncertainIfPrepared(item))
    }
    if (session.chatKey != null && session.chatKey != obs.chatKey) {
        return SendAction.Halt("تغيّرت المحادثة عن التي بدأ عندها الإرسال.", "PAUSED", uncertainIfPrepared(item))
    }
    if (item.status == "READY") {
        if (obs.entryText.isNotBlank() && obs.entryText != item.body) {
            return SendAction.Halt("يوجد نص في حقل الكتابة غير الرسالة التالية. لن يُستبدل.", "PAUSED", null)
        }
        return SendAction.PrepareText(item.id, item.body)
    }
    if (item.status == "TEXT_PREPARED") {
        if (obs.entryText != item.body) {
            return SendAction.Halt("النص داخل المحادثة لا يطابق الرسالة المجهزة.", "PAUSED", item.id)
        }
        return SendAction.PressSend(item.id)
    }
    return SendAction.Halt("حالة رسالة غير معروفة.", "PAUSED", item.id)
}

private fun uncertainIfPrepared(item: SendItem): String? =
    if (item.status == "TEXT_PREPARED" || item.status == "EXECUTION_STARTED") item.id else null

interface NodeView {
    val viewId: String?
    val className: String
    val text: String
    val description: String
    val editable: Boolean
    val clickable: Boolean
    val enabled: Boolean
    val focused: Boolean
    val visible: Boolean
    val packageName: String
    val childCount: Int
    fun child(index: Int): NodeView?
    fun parent(): NodeView?
}

class MemNode(
    override val viewId: String? = null,
    override val className: String = "",
    override val text: String = "",
    override val description: String = "",
    override val editable: Boolean = false,
    override val clickable: Boolean = false,
    override val enabled: Boolean = true,
    override val focused: Boolean = false,
    override val visible: Boolean = true,
    override val packageName: String = "",
    val children: List<MemNode> = emptyList(),
    var parentRef: MemNode? = null,
) : NodeView {
    override val childCount: Int get() = children.size
    override fun child(index: Int): NodeView? = children.getOrNull(index)
    override fun parent(): NodeView? = parentRef

    companion object {
        fun link(root: MemNode): MemNode {
            fun walk(n: MemNode) {
                n.children.forEach {
                    it.parentRef = n
                    walk(it)
                }
            }
            walk(root)
            return root
        }
    }
}

object WhatsAppMatch {
    val knownPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val entryIds = listOf("entry", "conversation_entry")
    private val sendIds = listOf("send", "conversation_entry_action_button", "send_container")
    private val sendWords = listOf("send", "إرسال", "ارسال", "enviar", "envoyer", "senden", "invia")
    private val nameIds = listOf("conversation_contact_name", "conversation_contact")

    fun isSupportedPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()
        return lower in knownPackages
    }

    /**
     * لا نكتب في أي حقل قابل للتحرير. الحقل المقبول هو معرّف واتساب المعروف داخل حزمة واتساب فقط.
     * النسخة الأصلية كانت تعود إلى أي EditText ظاهر؛ هذا التطبيق يرفض ذلك.
     */
    fun findEntry(root: NodeView?): NodeView? {
        if (root == null || !isSupportedPackage(root.packageName)) return null
        for (pkg in knownPackages) {
            for (id in entryIds) {
                val hits = findBySuffix(root, "$pkg:id/$id")
                val preferred = hits.firstOrNull { it.editable || it.focused }
                if (preferred != null) return preferred
                if (hits.isNotEmpty()) return hits.first()
            }
        }
        return null
    }

    fun findSend(root: NodeView?): NodeView? {
        if (root == null || !isSupportedPackage(root.packageName)) return null
        for (pkg in knownPackages) {
            for (id in sendIds) {
                val hits = findBySuffix(root, "$pkg:id/$id")
                val preferred = hits.firstOrNull { it.clickable || it.enabled }
                if (preferred != null) return clickableSelfOrParent(preferred)
                if (hits.isNotEmpty()) return clickableSelfOrParent(hits.first())
            }
        }
        return findSendByDescription(root)
    }

    fun chatName(root: NodeView?): String? {
        if (root == null || !isSupportedPackage(root.packageName)) return null
        for (pkg in knownPackages) {
            for (id in nameIds) {
                val hit = findBySuffix(root, "$pkg:id/$id").firstOrNull()
                val value = hit?.text?.trim().orEmpty().ifEmpty { hit?.description?.trim().orEmpty() }
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun findSendByDescription(node: NodeView): NodeView? {
        val blob = (node.description + " " + node.text).lowercase()
        if (node.clickable && sendWords.any { blob.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.child(i) ?: continue
            val found = findSendByDescription(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickableSelfOrParent(node: NodeView): NodeView {
        if (node.clickable) return node
        var parent = node.parent()
        var hops = 0
        while (parent != null && hops < 5) {
            if (parent.clickable) return parent
            parent = parent.parent()
            hops++
        }
        return node
    }

    private fun findBySuffix(root: NodeView, fullId: String): List<NodeView> {
        val out = mutableListOf<NodeView>()
        fun walk(n: NodeView) {
            if (n.viewId == fullId) out += n
            for (i in 0 until n.childCount) n.child(i)?.let { walk(it) }
        }
        walk(root)
        return out
    }
}

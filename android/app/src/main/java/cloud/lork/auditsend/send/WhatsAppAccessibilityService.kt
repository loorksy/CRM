package cloud.lork.auditsend.send

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import cloud.lork.auditengine.normalizeForNumber

object SendQueue {
    data class Item(val phone: String, val text: String)

    private val items = mutableListOf<Item>()
    @Volatile var index: Int = 0
        private set
    @Volatile var running: Boolean = false
        private set
    @Volatile var paused: Boolean = false
    @Volatile var awaitingSend: Boolean = false
    @Volatile var clicked: Boolean = false
    @Volatile var openedAt: Long = 0L
    @Volatile var status: String = ""
        private set

    var onChange: (() -> Unit)? = null

    fun snapshot(): List<Item> = items.toList()

    fun current(): Item? = items.getOrNull(index)

    fun start(list: List<Item>) {
        items.clear()
        items.addAll(list)
        index = 0
        running = list.isNotEmpty()
        paused = false
        awaitingSend = false
        clicked = false
        status = if (list.isEmpty()) "لا توجد أرقام" else "0/${list.size}"
        onChange?.invoke()
    }

    fun stop() {
        running = false
        paused = false
        awaitingSend = false
        status = "متوقف"
        onChange?.invoke()
    }

    fun advance() {
        if (!running) return
        index += 1
        awaitingSend = false
        clicked = false
        status = "$index/${items.size}"
        if (index >= items.size) {
            running = false
            status = "اكتمل ${items.size}"
        }
        onChange?.invoke()
    }

    fun togglePause() {
        if (!running) return
        paused = !paused
        status = if (paused) "متوقف مؤقتاً $index/${items.size}" else "$index/${items.size}"
        onChange?.invoke()
    }

    fun phoneDigits(raw: String): String {
        val normalized = normalizeForNumber(raw)
        val digits = normalized.filter { it.isDigit() }
        return if (digits.startsWith("00")) digits.drop(2) else digits
    }
}

class WhatsAppAccessibilityService : android.accessibilityservice.AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!SendQueue.running) return
            lookForSend()
            handler.postDelayed(this, 700)
        }
    }

    override fun onServiceConnected() {
        instance = this
        pump()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        lookForSend()
    }

    override fun onInterrupt() = Unit

    private fun pump() {
        if (!SendQueue.running || SendQueue.paused || SendQueue.awaitingSend) return
        val item = SendQueue.current() ?: return
        val packageName = installedWhatsApp()
        if (packageName == null) {
            SendQueue.stop()
            return
        }
        SendQueue.awaitingSend = true
        SendQueue.clicked = false
        SendQueue.openedAt = System.currentTimeMillis()
        val uri = Uri.parse(
            "https://api.whatsapp.com/send?phone=${item.phone}&text=${Uri.encode(item.text)}",
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 700)
    }

    private fun lookForSend() {
        if (!SendQueue.running || SendQueue.paused || !SendQueue.awaitingSend || SendQueue.clicked) return
        if (System.currentTimeMillis() - SendQueue.openedAt > 15_000) {
            SendQueue.advance()
            pump()
            return
        }
        if (System.currentTimeMillis() - SendQueue.openedAt < 900) return
        val root = rootInActiveWindow ?: return
        val node = findSend(root)
        if (node != null) {
            SendQueue.clicked = true
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({
                SendQueue.advance()
                pump()
            }, 800)
        }
    }

    private fun findSend(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val match = id.endsWith("/send") ||
            desc.contains("إرسال") ||
            desc.contains("send", ignoreCase = true) ||
            text == "إرسال"
        if (match && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSend(child)
            if (found != null) return found
        }
        return null
    }

    private fun installedWhatsApp(): String? {
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        return packages.firstOrNull { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        @Volatile var instance: WhatsAppAccessibilityService? = null
        fun pump() { instance?.pump() }
    }
}

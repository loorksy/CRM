package cloud.lork.audit.send

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ClipboardManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import cloud.lork.audit.db.KvEntity
import cloud.lork.audit.engine.ParseMode
import cloud.lork.audit.engine.parseMessages
import cloud.lork.audit.engine.sendQueueReplacementError
import org.json.JSONObject
import java.util.UUID
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import cloud.lork.audit.LorkApp
import cloud.lork.audit.MainActivity
import cloud.lork.audit.R
import cloud.lork.audit.data.itemsOf
import cloud.lork.audit.data.sessionOf
import cloud.lork.audit.db.SendItemEntity
import cloud.lork.audit.db.SendSessionEntity
import cloud.lork.audit.engine.ChatObservation
import cloud.lork.audit.engine.MemNode
import cloud.lork.audit.engine.SEND_CLICK_DELAY_MS
import cloud.lork.audit.engine.SendAction
import cloud.lork.audit.engine.WhatsAppMatch
import cloud.lork.audit.engine.afterPress
import cloud.lork.audit.engine.delayMs
import cloud.lork.audit.engine.nextSendAction
import cloud.lork.audit.engine.rememberChat
import cloud.lork.audit.engine.restoreAfterRestart

class AuditAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var posted = false
    private var pressing = false
    private val tick = Runnable {
        posted = false
        if (!pressing) {
            step()
            val session = sessionOf(dao().session())
            if ((session.state == "SENDING" || session.state == "WAITING_CHAT") && !pressing) {
                schedule(delayMs(session.mpm))
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val session = sessionOf(dao().session())
        if (session.state == "SENDING" || session.state == "WAITING_CHAT") schedule(300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val session = sessionOf(dao().session())
        if (session.state != "SENDING" && session.state != "WAITING_CHAT") return
        if (pressing || posted) return
        schedule(150)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance == this) instance = null
        super.onDestroy()
    }

    fun startLoop() = schedule(400)

    fun stopLoop() {
        handler.removeCallbacksAndMessages(null)
        posted = false
        pressing = false
    }

    private fun schedule(delay: Long) {
        if (posted || pressing) return
        posted = true
        handler.postDelayed(tick, delay)
    }

    private fun step() {
        val db = dao()
        var session = sessionOf(db.session())
        val items = itemsOf(db.items()).toMutableList()
        val root = findChatRoot()
        val tree = root?.let { MemNode.link(snapshot(it)) }
        val name = WhatsAppMatch.chatName(tree)
        val obs = ChatObservation(
            accessibilityBound = true,
            overlayGranted = android.provider.Settings.canDrawOverlays(this),
            supportedPackage = tree?.let { WhatsAppMatch.isSupportedPackage(it.packageName) } == true,
            entryFound = WhatsAppMatch.findEntry(tree) != null,
            sendFound = WhatsAppMatch.findSend(tree) != null,
            entryText = WhatsAppMatch.findEntry(tree)?.text.orEmpty(),
            chatKey = if (name.isNullOrBlank()) null else (tree?.packageName.orEmpty() + "|" + name),
            chatNameReadable = !name.isNullOrBlank(),
        )
        root?.recycle()
        session = rememberChat(session, obs)
        saveSession(session)
        when (val action = nextSendAction(session, items, obs)) {
            SendAction.Done -> saveSession(session.copy(state = "DONE"))
            SendAction.Wait -> Unit
            is SendAction.Halt -> {
                val uncertainId = action.uncertainItemId
                if (uncertainId != null) {
                    db.updateItem(uncertainId, "UNCERTAIN", action.reason)
                }
                saveSession(session.copy(state = action.sessionState))
                FloatingBubbleService.show(this, action.reason)
            }
            is SendAction.PrepareText -> {
                if (!setEntryText(action.text)) {
                    saveSession(session.copy(state = "WAITING_CHAT"))
                    return
                }
                db.updateItem(action.itemId, "TEXT_PREPARED", "تم تجهيز النص")
                pressing = true
                handler.postDelayed({ press(action.itemId, action.text) }, SEND_CLICK_DELAY_MS)
            }
            is SendAction.PressSend -> {
                pressing = true
                press(action.itemId, items.first { it.id == action.itemId }.body)
            }
        }
    }

    private fun press(itemId: String, expected: String) {
        val db = dao()
        val currentSession = sessionOf(db.session())
        if (currentSession.state != "SENDING" && currentSession.state != "WAITING_CHAT") {
            pressing = false
            return
        }
        val root = findChatRoot()
        val tree = root?.let { MemNode.link(snapshot(it)) }
        val entryText = WhatsAppMatch.findEntry(tree)?.text.orEmpty()
        val sendId = WhatsAppMatch.findSend(tree)?.viewId
        val name = WhatsAppMatch.chatName(tree)
        val chatKey = name?.let { tree?.packageName.orEmpty() + "|" + it }
        if (entryText != expected || sendId.isNullOrBlank() || chatKey == null || chatKey != currentSession.chatKey) {
            root?.recycle()
            db.updateItem(itemId, "UNCERTAIN", "تغيّر الحقل قبل الضغط")
            saveSession(sessionOf(db.session()).copy(state = "PAUSED"))
            pressing = false
            return
        }
        db.updateItem(itemId, "EXECUTION_STARTED", "بدأ التنفيذ قبل الضغط")
        val node = root?.let { nodeById(it, sendId) }
        val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        node?.recycle()
        root?.recycle()
        pressing = false
        if (!clicked) {
            db.updateItem(itemId, "UNCERTAIN", "لم يؤكد النظام الضغط على زر الإرسال")
            saveSession(sessionOf(db.session()).copy(state = "PAUSED"))
            return
        }
        val current = sessionOf(db.session())
        val previous = db.items().associate { it.itemId to it.detail }
        val updated = afterPress(current, itemsOf(db.items()), itemId)
        db.clearItems()
        db.insertItems(
            updated.second.map {
                val detail = if (it.id == itemId) {
                    "تم الضغط على زر الإرسال. هذا لا يثبت وصول الرسالة."
                } else {
                    previous[it.id].orEmpty()
                }
                SendItemEntity(it.id, it.position, it.body, it.status, detail)
            },
        )
        saveSession(updated.first)
        if (updated.first.state == "SENDING") schedule(delayMs(updated.first.mpm))
    }

    private fun setEntryText(text: String): Boolean {
        val root = findChatRoot() ?: return false
        val copy = AccessibilityNodeInfo.obtain(root)
        val viewId = WhatsAppMatch.findEntry(MemNode.link(snapshot(copy)))?.viewId
        copy.recycle()
        val node = nodeById(root, viewId)
        val ok = node?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            },
        ) == true
        node?.recycle()
        root.recycle()
        return ok
    }

    private fun nodeById(root: AccessibilityNodeInfo, viewId: String?): AccessibilityNodeInfo? {
        if (viewId.isNullOrBlank()) return null
        val hits = root.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        val chosen = hits.firstOrNull()
        hits.drop(1).forEach { it.recycle() }
        return chosen
    }

    private fun findChatRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && WhatsAppMatch.isSupportedPackage(active.packageName?.toString())) return active
        active?.recycle()
        val windows = windows ?: return null
        // The composer/keyboard can be the active window. Prefer the highest
        // WhatsApp window underneath it, including in split-screen mode.
        for (window in windows.sortedWith(compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isFocused }
            .thenByDescending { it.isActive }.thenByDescending { it.layer })) {
            val root = window.root ?: continue
            if (WhatsAppMatch.isSupportedPackage(root.packageName?.toString())) return root
            root.recycle()
        }
        return null
    }

    fun hasVisibleWhatsApp(): Boolean {
        val root = findChatRoot() ?: return false
        root.recycle()
        return true
    }

    private fun dao() = (application as LorkApp).db.dao()

    private fun saveSession(session: cloud.lork.audit.engine.SendSessionState) {
        val old = dao().session()
        dao().saveSession(
            SendSessionEntity(
                state = session.state,
                idx = session.index,
                mpm = session.mpm,
                chatKey = session.chatKey,
                rawText = old?.rawText.orEmpty(),
            ),
        )
    }

    companion object {
        @Volatile
        var instance: AuditAccessibilityService? = null
    }
}

private fun snapshot(node: AccessibilityNodeInfo, depth: Int = 0, budget: IntArray = intArrayOf(0)): MemNode {
    budget[0]++
    val kids = mutableListOf<MemNode>()
    if (depth < 28 && budget[0] < 700) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            kids += snapshot(child, depth + 1, budget)
            child.recycle()
        }
    }
    return MemNode(
        viewId = node.viewIdResourceName,
        className = node.className?.toString().orEmpty(),
        text = node.text?.toString().orEmpty(),
        description = node.contentDescription?.toString().orEmpty(),
        editable = node.isEditable,
        clickable = node.isClickable,
        enabled = node.isEnabled,
        focused = node.isFocused,
        visible = node.isVisibleToUser,
        packageName = node.packageName?.toString().orEmpty(),
        children = kids,
    )
}

class FloatingBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var panel: View? = null
    private var counterView: TextView? = null
    private var panelStatus: TextView? = null
    private var panelProgress: ProgressBar? = null
    private var panelFeedback: TextView? = null
    private var panelSignature: String? = null
    private var draftText = ""
    private var draftMpm = 100
    private val uiTick = object : Runnable {
        override fun run() {
            updateCounter()
            handler.postDelayed(this, 1000)
        }
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
        loadDraft()
        if (Settings.canDrawOverlays(this)) showBubble() else stopSelf()
        handler.post(uiTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pauseOrStop(stop = true)
                stopSelf()
            }
            ACTION_PAUSE -> pauseOrStop(stop = false)
            ACTION_RESUME -> resumeQueue()
            ACTION_SHOW_PANEL -> if (panel == null && Settings.canDrawOverlays(this)) showPanel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        panel?.let { runCatching { windowManager?.removeView(it) } }
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.bubble_channel), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bubble_running))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(7, notification)
        }
    }

    private fun showBubble() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val circle = TextView(this).apply {
            text = "➤"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bubbleTeal)
            }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        val counter = TextView(this).apply {
            text = "0 / 0"
            gravity = Gravity.CENTER
            setTextColor(bubbleTeal)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
        }
        counterView = counter
        column.addView(circle)
        column.addView(counter)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(24)
        params.y = dp(180)
        column.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0
            var dy = 0
            var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        moved = false
                        dx = event.rawX.toInt() - params.x
                        dy = event.rawY.toInt() - params.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        moved = true
                        params.x = event.rawX.toInt() - dx
                        params.y = event.rawY.toInt() - dy
                        windowManager?.updateViewLayout(column, params)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved && event.rawY > resources.displayMetrics.heightPixels - dp(100)) {
                            pauseOrStop(true)
                            hidePanel()
                            stopSelf()
                        } else if (!moved || event.eventTime - event.downTime < 220) togglePanel()
                    }
                }
                return true
            }
        })
        bubble = column
        windowManager?.addView(column, params)
        updateCounter()
    }

    private fun loadDraft() {
        val dao = (application as LorkApp).db.dao()
        val json = dao.kv(DRAFT_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val session = dao.session()
        draftText = json?.optString("text") ?: session?.rawText.orEmpty()
        draftMpm = (json?.optInt("mpm", 100) ?: session?.mpm ?: 100).coerceIn(1, 1000)
    }

    private fun persistDraft() {
        (application as LorkApp).db.dao().saveKv(
            KvEntity(DRAFT_KEY, JSONObject().put("text", draftText).put("mpm", draftMpm).toString()),
        )
    }

    private fun queueSignature(): String {
        val dao = (application as LorkApp).db.dao()
        return dao.session()?.state.orEmpty() + "|" + dao.items()
            .filter { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }
            .joinToString { it.itemId + it.status }
    }

    private fun updateCounter() {
        val dao = (application as LorkApp).db.dao()
        val items = dao.items()
        val session = dao.session()
        val done = items.count { it.status == "SEND_PRESSED" }
        counterView?.text = "$done / ${items.size}"
        panelStatus?.text = "${stateLabel(session?.state)} · $done / ${items.size}"
        panelProgress?.max = items.size.coerceAtLeast(1)
        panelProgress?.progress = done
        if (panel != null && panelSignature != queueSignature()) {
            hidePanel()
            showPanel()
        }
    }

    private fun stateLabel(state: String?): String = when (state) {
        "WAITING_CHAT" -> "بانتظار محادثة واتساب"
        "SENDING" -> "جارٍ الإرسال"
        "PAUSED" -> "الإرسال متوقف مؤقتاً"
        "STOPPED" -> "أُوقفت الدفعة"
        "DONE" -> "اكتمل الضغط على الإرسال"
        else -> "جاهز"
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager?.removeView(it) } }
        panel = null
        panelStatus = null
        panelProgress = null
        panelFeedback = null
        panelSignature = null
    }

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun feedback(message: String) {
        panelFeedback?.text = message
        show(this, message)
    }

    private fun showPanel() {
        if (panel != null) return
        val dao = (application as LorkApp).db.dao()
        val items = dao.items()
        val session = dao.session()
        val uncertain = items.firstOrNull { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }
        val replacementError = sendQueueReplacementError(sessionOf(session), itemsOf(items))
        val mayCompose = replacementError == null
        if (mayCompose) loadDraft() else if (session != null) {
            draftText = session.rawText
            draftMpm = session.mpm.coerceIn(1, 1000)
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label("الإرسال", 18f, bubbleInk, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label("إغلاق ×", 14f, bubbleTeal, false).apply { setOnClickListener { hidePanel() } })
        card.addView(heading)
        panelStatus = label("${stateLabel(session?.state)} · ${items.count { it.status == "SEND_PRESSED" }} / ${items.size}", 13f, bubbleTeal, true)
        card.addView(panelStatus)
        panelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = items.size.coerceAtLeast(1)
            progress = items.count { it.status == "SEND_PRESSED" }
            progressTintList = android.content.res.ColorStateList.valueOf(bubbleTeal)
        }
        card.addView(panelProgress)
        card.addView(label("الصق الرسائل هنا وافصل بينها بسطر فارغ. الوجهة هي المحادثة المفتوحة.", 13f, bubbleMuted, false))
        val input = EditText(this).apply {
            setText(draftText)
            hint = "نص الرسائل"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(bubbleInk)
            setHintTextColor(bubbleMuted)
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isEnabled = mayCompose
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF8FAFC.toInt())
                setStroke(dp(1), 0xFFE2E8F0.toInt())
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(input, LinearLayout.LayoutParams(-1, -2))
        val count = label("${parseMessages(draftText, ParseMode.PARAGRAPH_THEN_LINES).size} رسالة", 13f, bubbleTeal, true)
        card.addView(count)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                draftText = value?.toString().orEmpty()
                count.text = "${parseMessages(draftText, ParseMode.PARAGRAPH_THEN_LINES).size} رسالة"
                persistDraft()
            }
        })
        card.addView(actionButton("لصق", Color.WHITE, bubbleTeal) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) feedback("الحافظة فارغة أو لا تحتوي نصاً.") else input.setText(text)
        }.apply { isEnabled = mayCompose; alpha = if (mayCompose) 1f else 0.45f })
        val speed = label("$draftMpm رسالة / دقيقة", 14f, bubbleInk, true)
        card.addView(speed)
        val seek = SeekBar(this).apply { max = 999; progress = draftMpm - 1 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                draftMpm = (progress + 1).coerceIn(1, 1000)
                speed.text = "$draftMpm رسالة / دقيقة"
                persistDraft()
                dao.session()?.let { dao.saveSession(it.copy(mpm = draftMpm)) }
            }
        })
        card.addView(seek)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(10, 60, 300, 1000).forEach { rate ->
            presets.addView(chip(rate.toString(), 0xFFE8F5F1.toInt(), bubbleTeal).apply {
                gravity = Gravity.CENTER
                setOnClickListener { seek.progress = rate - 1 }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        card.addView(presets)
        val lastNotice = getSharedPreferences("lork-send", MODE_PRIVATE).getString("last", "").orEmpty()
        panelFeedback = label(listOf(replacementError.orEmpty(), lastNotice).filter { it.isNotBlank() }.distinct().joinToString("\n"), 13f, bubbleMuted, false)
        card.addView(panelFeedback)
        if (uncertain != null) {
            card.addView(label("رسالة غير مؤكدة: ${uncertain.body.take(180)}", 14f, 0xFFB45309.toInt(), true))
            card.addView(actionButton("راجعت المحادثة، إعادة تجهيز الرسالة", bubbleTeal, Color.WHITE) {
                dao.items().filter { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }.forEach {
                    dao.updateItem(it.itemId, "READY", "أُعيد تجهيزها بعد مراجعة. لن تُرسل حتى تضغط استئناف.")
                }
                dao.session()?.let { dao.saveSession(it.copy(state = "PAUSED")) }
                hidePanel()
                showPanel()
            })
        } else if (session?.state == "SENDING" || session?.state == "WAITING_CHAT") {
            card.addView(actionButton("إيقاف مؤقت", bubbleTeal, Color.WHITE) {
                pauseOrStop(false)
                hidePanel()
                showPanel()
            })
        } else if (session?.state == "PAUSED" && items.any { it.status == "READY" || it.status == "TEXT_PREPARED" }) {
            card.addView(actionButton("استئناف الدفعة المحفوظة", bubbleTeal, Color.WHITE) { resumeQueue() })
        }
        if (mayCompose) {
            val confirmed = CheckBox(this).apply {
                text = "المحادثة المفتوحة هي الوجهة المطلوبة"
                setTextColor(bubbleInk)
                textSize = 13f
            }
            card.addView(confirmed)
            card.addView(actionButton(if (session?.state == "DONE") "إرسال دفعة جديدة" else "إرسال", bubbleTeal, Color.WHITE) {
                if (!confirmed.isChecked) feedback("أكّد أن المحادثة المفتوحة هي الوجهة قبل البدء.") else beginDraft()
            })
        }
        if (!mayCompose) card.addView(actionButton("إنهاء الدفعة الحالية", Color.WHITE, 0xFFDC2626.toInt()) {
            pauseOrStop(true)
            hidePanel()
            showPanel()
        })
        card.addView(label("الضغط على الإرسال لا يؤكد وصول الرسالة. اسحب الفقاعة إلى أسفل الشاشة لإغلاقها.", 12f, bubbleMuted, false))
        val scroll = ScrollView(this).apply { addView(card) }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.76f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel = scroll
        panelSignature = queueSignature()
        windowManager?.addView(scroll, params)
    }

    private fun beginDraft() {
        val app = application as LorkApp
        val dao = app.db.dao()
        val service = AuditAccessibilityService.instance
        if (service == null) {
            feedback("خدمة إمكانية الوصول غير متصلة. فعّلها ثم أعد فتح الفقاعة.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            feedback("إذن الظهور فوق التطبيقات غير ممنوح.")
            return
        }
        val replacementError = sendQueueReplacementError(sessionOf(dao.session()), itemsOf(dao.items()))
        if (replacementError != null) {
            feedback(replacementError)
            return
        }
        val messages = parseMessages(draftText, ParseMode.PARAGRAPH_THEN_LINES)
        if (messages.isEmpty()) {
            feedback("لا توجد رسائل. الصق النص أولاً وافصل الرسائل بسطر فارغ.")
            return
        }
        val visible = service.hasVisibleWhatsApp()
        val launchers = listOf("com.whatsapp", "com.whatsapp.w4b").mapNotNull {
            packageManager.getLaunchIntentForPackage(it)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!visible && launchers.isEmpty()) {
            feedback("واتساب غير مثبت أو غير متاح من هذا الملف الشخصي.")
            return
        }
        getSharedPreferences("lork-send", MODE_PRIVATE).edit().remove("last").apply()
        service.stopLoop()
        val batch = UUID.randomUUID().toString()
        app.db.runInTransaction {
            dao.clearItems()
            dao.insertItems(messages.mapIndexed { index, body ->
                SendItemEntity("$batch-$index", index, body, "READY", "جاهزة")
            })
            dao.saveSession(SendSessionEntity(state = "WAITING_CHAT", idx = 0, mpm = draftMpm, chatKey = null, rawText = draftText))
            persistDraft()
        }
        hidePanel()
        if (!visible) {
            val chooser = Intent.createChooser(launchers.first(), "اختر واتساب")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchers.size > 1) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, launchers.drop(1).toTypedArray())
            runCatching { startActivity(chooser) }.onFailure {
                dao.session()?.let { dao.saveSession(it.copy(state = "PAUSED")) }
                showPanel()
                feedback("تعذر فتح واتساب. افتح المحادثة المطلوبة ثم استأنف الدفعة.")
                return
            }
        }
        service.startLoop()
    }

    private fun resumeQueue() {
        val dao = (application as LorkApp).db.dao()
        val session = dao.session() ?: return
        if (AuditAccessibilityService.instance == null || !Settings.canDrawOverlays(this)) {
            feedback("خدمة إمكانية الوصول أو إذن الظهور غير متاح. أعد تفعيلهما قبل الاستئناف.")
            return
        }
        if (dao.items().any { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }) {
            feedback("راجع الرسالة غير المؤكدة قبل الاستئناف.")
            return
        }
        if (session.state != "PAUSED") return
        dao.saveSession(session.copy(state = if (session.chatKey == null) "WAITING_CHAT" else "SENDING", mpm = draftMpm))
        hidePanel()
        AuditAccessibilityService.instance?.startLoop()
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 8, 0, 8)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun chip(text: String, background: Int, color: Int): TextView = label(text, 13f, color, true).apply {
        setPadding(24, 12, 24, 12)
        this.background = GradientDrawable().apply {
            cornerRadius = 32f
            setColor(background)
        }
    }

    private fun actionButton(text: String, background: Int, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(24, 28, 24, 28)
        this.background = GradientDrawable().apply {
            cornerRadius = 40f
            setColor(background)
            if (background == Color.WHITE) setStroke(3, color)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 16 }
    }

    private val bubbleTeal = 0xFF0F766E.toInt()
    private val bubbleInk = 0xFF1B2A4A.toInt()
    private val bubbleMuted = 0xFF64748B.toInt()

    private fun pauseOrStop(stop: Boolean) {
        val dao = (application as LorkApp).db.dao()
        val session = dao.session() ?: return
        if (stop) {
            dao.items().filter { it.status == "READY" || it.status == "TEXT_PREPARED" }.forEach {
                dao.updateItem(it.itemId, "SKIPPED", "أُوقف الإرسال")
            }
            dao.saveSession(session.copy(state = "STOPPED"))
        } else {
            dao.saveSession(session.copy(state = "PAUSED"))
        }
        AuditAccessibilityService.instance?.stopLoop()
    }

    companion object {
        const val ACTION_SHOW_PANEL = "cloud.lork.audit.SHOW_SEND_PANEL"
        const val ACTION_STOP = "cloud.lork.audit.STOP_BUBBLE"
        const val ACTION_PAUSE = "cloud.lork.audit.PAUSE_BUBBLE"
        const val ACTION_RESUME = "cloud.lork.audit.RESUME_BUBBLE"
        private const val CHANNEL = "lork-send"
        private const val DRAFT_KEY = "send-composer-draft"

        fun show(context: android.content.Context, message: String) {
            // الحالة تُحفظ في قاعدة البيانات وتظهر داخل التطبيق؛ لا نعرض توستاً يختفي خلف نافذة.
            context.getSharedPreferences("lork-send", MODE_PRIVATE).edit().putString("last", message).apply()
        }
    }
}

fun recoverSendQueue(app: LorkApp) {
    val dao = app.db.dao()
    val entity = dao.session() ?: return
    val restored = restoreAfterRestart(sessionOf(entity), itemsOf(dao.items()))
    dao.clearItems()
    dao.insertItems(
        restored.second.map {
            SendItemEntity(
                it.id,
                it.position,
                it.body,
                it.status,
                if (it.status == "UNCERTAIN") "أُغلقت العملية حول لحظة التنفيذ وتحتاج مراجعة" else "",
            )
        },
    )
    dao.saveSession(
        entity.copy(state = restored.first.state, idx = restored.first.index, chatKey = restored.first.chatKey),
    )
}

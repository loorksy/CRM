package cloud.lork.audit.send

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
        db.updateItem(itemId, "EXECUTION_STARTED", "بدأ التنفيذ قبل الضغط")
        val root = findChatRoot()
        val tree = root?.let { MemNode.link(snapshot(it)) }
        val entryText = WhatsAppMatch.findEntry(tree)?.text.orEmpty()
        val sendId = WhatsAppMatch.findSend(tree)?.viewId
        root?.recycle()
        if (entryText != expected || sendId.isNullOrBlank()) {
            db.updateItem(itemId, "UNCERTAIN", "تغيّر الحقل قبل الضغط")
            saveSession(sessionOf(db.session()).copy(state = "PAUSED"))
            pressing = false
            return
        }
        val fresh = findChatRoot()
        val node = fresh?.let { nodeById(it, sendId) }
        val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        node?.recycle()
        fresh?.recycle()
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
        for (window in windows) {
            val root = window.root ?: continue
            if (WhatsAppMatch.isSupportedPackage(root.packageName?.toString())) return root
            root.recycle()
        }
        return null
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pauseOrStop(stop = true)
                stopSelf()
            }
            ACTION_PAUSE -> pauseOrStop(stop = false)
            ACTION_RESUME -> {
                val dao = (application as LorkApp).db.dao()
                val session = dao.session()
                if (session != null) dao.saveSession(session.copy(state = "SENDING"))
                AuditAccessibilityService.instance?.startLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
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
        val view = TextView(this).apply {
            text = "إرسال"
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF4F46E5.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 40
        params.y = 240
        view.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0
            var dy = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = event.rawX.toInt() - params.x
                        dy = event.rawY.toInt() - params.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = event.rawX.toInt() - dx
                        params.y = event.rawY.toInt() - dy
                        windowManager?.updateViewLayout(view, params)
                    }
                    MotionEvent.ACTION_UP -> {
                        val screen = resources.displayMetrics.heightPixels
                        if (params.y > screen * 0.82) {
                            pauseOrStop(stop = true)
                            stopSelf()
                        } else if (event.eventTime - event.downTime < 250) {
                            togglePanel()
                        }
                    }
                }
                return true
            }
        })
        bubble = view
        windowManager?.addView(view, params)
    }

    private fun togglePanel() {
        if (panel != null) {
            runCatching { windowManager?.removeView(panel) }
            panel = null
            return
        }
        val dao = (application as LorkApp).db.dao()
        val session = dao.session()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xF0FFFFFF.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        box.addView(TextView(this).apply {
            text = "الحالة: ${session?.state ?: "IDLE"} · ${session?.idx ?: 0}"
            setTextColor(0xFF111827.toInt())
        })
        box.addView(Button(this).apply {
            text = "إيقاف مؤقت"
            setOnClickListener { pauseOrStop(false) }
        })
        box.addView(Button(this).apply {
            text = "استئناف"
            setOnClickListener {
                val current = dao.session() ?: return@setOnClickListener
                dao.saveSession(current.copy(state = "SENDING"))
                AuditAccessibilityService.instance?.startLoop()
            }
        })
        box.addView(Button(this).apply {
            text = "إيقاف"
            setOnClickListener {
                pauseOrStop(true)
                stopSelf()
            }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.CENTER
        panel = box
        windowManager?.addView(box, params)
    }

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
        const val ACTION_STOP = "cloud.lork.audit.STOP_BUBBLE"
        const val ACTION_PAUSE = "cloud.lork.audit.PAUSE_BUBBLE"
        const val ACTION_RESUME = "cloud.lork.audit.RESUME_BUBBLE"
        private const val CHANNEL = "lork-send"

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

package cloud.lork.auditsend.send

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import cloud.lork.auditsend.R

class BubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            (bubble as? TextView)?.text = label()
            if (bubble != null) handler.postDelayed(this, 400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(42, notification())
        if (bubble == null) showBubble()
        SendQueue.onChange = { handler.post { (bubble as? TextView)?.text = label() } }
        WhatsAppAccessibilityService.pump()
        handler.post(refresh)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        SendQueue.onChange = null
        bubble?.let { windowManager?.removeView(it) }
        bubble = null
        super.onDestroy()
    }

    private fun label(): String {
        val pause = if (SendQueue.paused) "\nمتوقف مؤقتاً" else "\nاضغط للإيقاف المؤقت"
        return SendQueue.status + pause
    }

    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = TextView(this).apply {
            text = label()
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(28, 20, 28, 20)
            setBackgroundResource(R.drawable.bubble_bg)
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }
        view.setOnTouchListener(dragListener(layout, view, wm))
        wm.addView(view, layout)
        bubble = view
        params = layout
    }

    private fun dragListener(
        layout: WindowManager.LayoutParams,
        view: View,
        wm: WindowManager,
    ): View.OnTouchListener {
        var downY = 0f
        var startY = 0
        var moved = false
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > 10) moved = true
                    layout.y = startY + dy
                    wm.updateViewLayout(view, layout)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val screen = if (Build.VERSION.SDK_INT >= 30) {
                        wm.currentWindowMetrics.bounds.height()
                    } else {
                        resources.displayMetrics.heightPixels
                    }
                    if (layout.y > (screen * 0.78f)) {
                        SendQueue.stop()
                        stopSelf()
                    } else if (!moved) {
                        SendQueue.togglePause()
                        if (!SendQueue.paused) WhatsAppAccessibilityService.pump()
                        (view as TextView).text = label()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "send-bubble"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.bubble_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.bubble_channel_desc) },
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bubble_notification))
            .setSmallIcon(R.drawable.ic_stat_send)
            .setOngoing(true)
            .build()
    }
}

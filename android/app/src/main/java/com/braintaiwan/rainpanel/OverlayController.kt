package com.braintaiwan.rainpanel

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** 浮在所有 App 上面的紅綠燈小方塊。可拖曳。 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private lateinit var params: WindowManager.LayoutParams

    fun show() {
        if (view != null) return
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_panel, null)
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        v.setOnTouchListener(DragListener())
        wm.addView(v, params)
        view = v
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun render(a: Assessment) {
        val v = view ?: return
        val dot = v.findViewById<View>(R.id.panelDot)
        val text = v.findViewById<TextView>(R.id.panelText)
        val colorRes = when (a.light) {
            Light.GREEN -> R.color.green
            Light.AMBER -> R.color.amber
            Light.RED -> R.color.red
            Light.WAITING, Light.NODATA -> R.color.amber
        }
        (dot.background as? GradientDrawable)?.setColor(ContextCompat.getColor(ctx, colorRes))
        text.text = a.message
    }

    private fun dp(x: Int) = (x * ctx.resources.displayMetrics.density).roundToInt()

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).roundToInt()
                    params.y = startY + (e.rawY - touchY).roundToInt()
                    view?.let { wm.updateViewLayout(it, params) }
                }
            }
            return true
        }
    }
}

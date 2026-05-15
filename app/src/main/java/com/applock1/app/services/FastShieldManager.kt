package com.applock1.app.services

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows a full-screen black overlay to cover the transition between
 * detecting a locked app and the BiometricPrompt appearing.
 * Removed by LockOverlayActivity on start, or after 5s failsafe.
 */
object FastShieldManager {

    private var view: View? = null
    private var wm: WindowManager? = null
    @Volatile var isVisible = false
        private set
    private var failsafe: Job? = null

    fun show(context: Context) {
        if (isVisible) return
        try {
            val w = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            ).also { it.gravity = Gravity.CENTER }

            view = View(context.applicationContext).apply { setBackgroundColor(0xFF000000.toInt()) }
            w.addView(view, lp)
            isVisible = true

            // Auto-remove failsafe after 5 seconds
            failsafe?.cancel()
            failsafe = CoroutineScope(Dispatchers.Main).launch {
                delay(5_000)
                hide()
            }
        } catch (_: Exception) {
            // No SYSTEM_ALERT_WINDOW permission yet — proceed anyway
        }
    }

    fun hide() {
        failsafe?.cancel()
        failsafe = null
        if (!isVisible) return
        try { wm?.removeView(view) } catch (_: Exception) {}
        view = null
        wm = null
        isVisible = false
    }
}

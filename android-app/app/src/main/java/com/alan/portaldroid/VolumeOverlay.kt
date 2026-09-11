package com.alan.portaldroid

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Cartelito que aparece un momento al subir/bajar el volumen con los botones
 * físicos, para que se vea el número sin tener que abrir la app.
 *
 * Hace falta porque al interceptar los botones de volumen (ver
 * TouchAccessibilityService.onKeyEvent) Android deja de mostrar su propio
 * cartel: el evento se consume antes de llegar al sistema. Sin este
 * reemplazo, tocar los botones quedaría completamente mudo.
 *
 * Mismo tipo de ventana que PointerOverlay (TYPE_ACCESSIBILITY_OVERLAY): no
 * hace falta pedir el permiso de "mostrar sobre otras apps" aparte.
 */
class VolumeOverlay(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var vista: TextView? = null

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(90)
    }

    private val ocultar = Runnable {
        val v = vista ?: return@Runnable
        vista = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    /**
     * Muestra "🔊 NN% a la PC" un momento y lo esconde solo.
     *
     * Si ya estaba mostrado, sólo cambia el texto y estira el tiempo: así
     * mantener apretado el botón (que en Android repite la tecla solo) no
     * hace parpadear la ventana creándola y destruyéndola sin parar.
     */
    fun mostrar(porcentaje: Int) {
        main.post {
            val texto = "🔊 $porcentaje% a la PC"
            val actual = vista
            if (actual != null) {
                actual.text = texto
            } else {
                val nueva = TextView(service).apply {
                    text = texto
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(22).toFloat()
                        setColor(Color.argb(225, 18, 20, 26))
                    }
                }
                try {
                    wm.addView(nueva, params)
                    vista = nueva
                } catch (e: Exception) {
                    android.util.Log.w("PortalDroid", "no se pudo mostrar el cartel de volumen: ${e.message}")
                }
            }
            main.removeCallbacks(ocultar)
            main.postDelayed(ocultar, 1100)
        }
    }

    fun destruir() {
        main.removeCallbacks(ocultar)
        main.post {
            vista?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            vista = null
        }
    }
}

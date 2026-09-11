package com.alan.portaldroid

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Barra de volumen propia, al costado de la pantalla.
 *
 * Hace falta porque al interceptar los botones de volumen (ver
 * TouchAccessibilityService.onKeyEvent) Android deja de mostrar su propio
 * panel: el evento se consume antes de llegar al sistema. Sin este reemplazo,
 * apretar los botones quedaría completamente mudo.
 *
 * Se parece al panel nativo a propósito — barra vertical, a la derecha, se va
 * sola — así no hay que aprender nada nuevo: se lee igual que el volumen de
 * siempre. La diferencia es qué mide: esto es lo que le llega a la PC, no el
 * parlante del celular.
 *
 * Mismo tipo de ventana que PointerOverlay (TYPE_ACCESSIBILITY_OVERLAY): no
 * hace falta pedir el permiso de "mostrar sobre otras apps" aparte.
 */
class VolumeOverlay(private val service: AccessibilityService) {

    companion object {
        /** Tope de la escala, en porcentaje. Coincide con el máximo que deja
         *  poner AudioStreamService.setVolumen (2.0 = 200%). */
        private const val MAXIMO = 200
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var panel: LinearLayout? = null
    private var barra: Barra? = null
    private var etiqueta: TextView? = null

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Pegada a la derecha y centrada, como el panel del sistema.
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        x = dp(12)
    }

    private val ocultar = Runnable {
        val p = panel ?: return@Runnable
        panel = null
        barra = null
        etiqueta = null
        try { wm.removeView(p) } catch (_: Exception) {}
    }

    /**
     * Muestra la barra con el nivel dado y la esconde sola.
     *
     * Si ya estaba en pantalla sólo actualiza el nivel y estira el tiempo: así
     * mantener apretado el botón (que en Android repite la tecla solo) no
     * hace parpadear la ventana creándola y destruyéndola sin parar.
     */
    fun mostrar(porcentaje: Int) {
        main.post {
            if (panel == null) crear()
            barra?.nivel = porcentaje.coerceIn(0, MAXIMO) / MAXIMO.toFloat()
            barra?.invalidate()
            etiqueta?.text = "$porcentaje%"
            main.removeCallbacks(ocultar)
            main.postDelayed(ocultar, 1400)
        }
    }

    private fun crear() {
        val caja = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.argb(238, 28, 32, 39))
            }
        }

        val b = Barra(service)
        caja.addView(b, LinearLayout.LayoutParams(dp(26), dp(150)))

        val pct = TextView(service).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, 0)
        }
        caja.addView(pct)

        val pie = TextView(service).apply {
            text = "a la PC"
            textSize = 10f
            setTextColor(Color.argb(255, 154, 160, 166))
        }
        caja.addView(pie)

        try {
            wm.addView(caja, params)
            panel = caja
            barra = b
            etiqueta = pct
        } catch (e: Exception) {
            android.util.Log.w("PortalDroid", "no se pudo mostrar la barra de volumen: ${e.message}")
        }
    }

    fun destruir() {
        main.removeCallbacks(ocultar)
        main.post {
            panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            panel = null
            barra = null
            etiqueta = null
        }
    }

    /**
     * La barra en sí: una pista apagada y el relleno que sube desde abajo.
     *
     * El 100% queda a la MITAD de la pista, no arriba del todo, porque la
     * escala llega hasta 200%. Es a propósito: si el 100% fuera el tope, no
     * habría forma de ver de un vistazo que todavía se puede subir más.
     */
    private class Barra(context: Context) : View(context) {

        /** 0.0 a 1.0 sobre la escala completa (0% a 200%). */
        var nivel = 0.5f

        private val pista = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 58, 64, 73)
        }
        private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 80, 200, 255)
        }
        private val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 232, 234, 237)
        }

        private val caja = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f

            // Pista completa.
            caja.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(caja, r, r, pista)

            // Relleno desde abajo. Se recorta a la pista redondeada para que
            // no se le escapen las esquinas al llegar cerca del tope.
            val alto = height * nivel.coerceIn(0f, 1f)
            if (alto > 0f) {
                canvas.save()
                canvas.clipRect(0f, height - alto, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(caja, r, r, relleno)
                canvas.restore()
            }

            // Marquita del 100%, para saber dónde está el volumen "normal".
            val y = height / 2f
            canvas.drawRect(width * 0.25f, y - dpf(1f), width * 0.75f, y + dpf(1f), marca)
        }

        private fun dpf(v: Float) = v * resources.displayMetrics.density
    }
}

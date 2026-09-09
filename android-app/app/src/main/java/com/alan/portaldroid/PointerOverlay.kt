package com.alan.portaldroid

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * El puntero que se dibuja ENCIMA de todo lo que haya en la pantalla del cel.
 *
 * Sin esto estás manejando a ciegas: movés el mouse pero no sabés dónde va a
 * caer el toque hasta que ya tocaste.
 *
 * Por qué no hace falta el permiso de "mostrar sobre otras apps":
 * un Servicio de Accesibilidad puede usar el tipo de ventana
 * TYPE_ACCESSIBILITY_OVERLAY, que Android le concede por ser lo que es. Si
 * usáramos el tipo normal (TYPE_APPLICATION_OVERLAY) habría que mandar al
 * usuario a Ajustes a habilitar SYSTEM_ALERT_WINDOW aparte.
 *
 * Ojo con FLAG_NOT_TOUCHABLE: sin ese flag la ventanita del puntero se comería
 * los toques que ella misma está tratando de mostrar.
 *
 * ---- Por qué la ventana es de pantalla completa y el círculo se mueve adentro ----
 *
 * La primera versión usaba una ventana chiquita del tamaño del círculo y la
 * reubicaba con updateViewLayout() en cada movimiento. Eso se veía con un lag
 * tremendo: updateViewLayout le pide al PROCESO DEL SISTEMA que recalcule la
 * ventana, y hacerlo 60 veces por segundo lo satura.
 *
 * Ahora la ventana se crea una sola vez, ocupa toda la pantalla y no se mueve
 * nunca. El círculo es una vista hija que se corre con translationX/Y, que es
 * sólo un dibujo: no toca al sistema, no recalcula nada.
 */
class PointerOverlay(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "PortalDroid"
        /** Lado del recuadro (invisible) que contiene al puntero, en píxeles.
         *  Es más grande que el círculo a propósito: el resplandor se dibuja
         *  hacia afuera y si el recuadro fuera justo, quedaría recortado. */
        private const val BOX = 96
    }

    // Todo lo que toque vistas tiene que correr en el hilo principal.
    // Los comandos llegan por el socket, que está en un hilo aparte.
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private var dot: PointerView? = null

    // Última posición pedida. Se escribe desde el hilo del socket y se lee
    // desde el principal, por eso va @Volatile.
    @Volatile private var wantX = 0f
    @Volatile private var wantY = 0f

    private val applyMove = Runnable {
        val d = dot ?: return@Runnable
        d.translationX = wantX - BOX / 2f
        d.translationY = wantY - BOX / 2f
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // ESTE es el que alinea el puntero con el toque real. Ver abajo.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0

        // Por qué FLAG_LAYOUT_IN_SCREEN + fitInsetsTypes = 0:
        //
        // Por defecto, Android mide la posición de una ventana desde donde
        // termina la barra de estado, NO desde el borde físico de arriba.
        // Así que el 0 de arriba caía ~100 px más abajo de lo que creíamos.
        // dispatchGesture, en cambio, SIEMPRE usa el borde físico.
        //
        // Resultado: el círculo se dibujaba una fila de teclado más abajo
        // que donde caía el toque de verdad. Con estos dos, la ventana usa
        // las mismas coordenadas que el gesto y quedan alineados.
        fitInsetsTypes = 0
        // Lo mismo pero para el notch / agujero de la cámara.
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Instante en que se pidió mostrar el puntero, para medir cuánto tarda
     *  en DIBUJARSE de verdad. Recibir la orden y pintar el pixel son cosas
     *  distintas, y el retraso que se percibe es el segundo. */
    @Volatile private var pedidoEn = 0L

    fun show() {
        pedidoEn = System.currentTimeMillis()
        main.post {
            if (root != null) return@post
            val container = FrameLayout(service)
            val d = PointerView(service)
            container.addView(d, FrameLayout.LayoutParams(BOX, BOX))
            try {
                wm.addView(container, params)
                root = container
                dot = d
                applyMove.run()
                // Chequeo de alineación: la ventana ocupa toda la pantalla, así
                // que su esquina tiene que caer justo en el 0,0 físico. Si no,
                // ese delta es exactamente el desfase entre el círculo y el toque.
                d.alPrimerDibujo = {
                    Log.i(TAG, "puntero DIBUJADO ${System.currentTimeMillis() - pedidoEn} ms " +
                        "después de recibir la orden")
                }
                container.post {
                    val real = IntArray(2)
                    container.getLocationOnScreen(real)
                    if (real[0] != 0 || real[1] != 0) {
                        Log.w(TAG, "PUNTERO DESALINEADO: se corrió dx=${real[0]} dy=${real[1]}")
                    } else {
                        Log.i(TAG, "Puntero alineado con el toque real.")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo mostrar el puntero: ${e.message}")
            }
        }
    }

    fun hide() {
        main.post {
            val c = root ?: return@post
            root = null
            dot = null
            try { wm.removeView(c) } catch (_: Exception) {}
        }
    }

    /** Mueve el puntero. Recibe el centro deseado, en píxeles de pantalla. */
    fun moveTo(cx: Float, cy: Float) {
        wantX = cx
        wantY = cy
        // removeCallbacks + post = "descartá el movimiento viejo que todavía no
        // se dibujó y quedate con este". Sin esto se forma una cola: si llegan
        // más movimientos de los que la pantalla alcanza a dibujar, el puntero
        // queda dibujando posiciones cada vez más viejas y el retraso crece
        // solo. Así siempre dibuja la ÚLTIMA posición conocida.
        main.removeCallbacks(applyMove)
        main.post(applyMove)
    }

    /** true mientras el "dedo" está apoyado: el puntero se agranda y se rellena. */
    fun setPressed(pressed: Boolean) {
        main.post { dot?.held = pressed }
    }

    private class PointerView(context: Context) : View(context) {

        // Se llama 'held' y no 'pressed' a propósito: View ya tiene un
        // setPressed() propio de Android y Kotlin lo pisaría sin querer.
        /** Se llama una sola vez, cuando el puntero se pinta por primera vez. */
        var alPrimerDibujo: (() -> Unit)? = null

        var held = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(28, 80, 200, 255)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.argb(235, 80, 200, 255)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(85, 80, 200, 255)
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 255, 255)
        }

        override fun onDraw(canvas: Canvas) {
            alPrimerDibujo?.let { it(); alPrimerDibujo = null }
            val cx = width / 2f
            val cy = height / 2f
            val r = if (held) width * 0.30f else width * 0.23f

            // Resplandor a mano: tres anillos cada vez más grandes y transparentes.
            // Se hace así y no con setShadowLayer porque el shadow layer obliga a
            // dibujar por software y acá queremos que sea barato y fluido.
            for (i in 3 downTo 1) canvas.drawCircle(cx, cy, r + i * 7f, glow)

            // El círculo es hueco a propósito: si fuera relleno taparía justo
            // el botón que estás por tocar.
            if (held) canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, if (held) 6f else 4f, core)
        }
    }
}

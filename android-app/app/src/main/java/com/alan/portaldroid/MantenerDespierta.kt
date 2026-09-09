package com.alan.portaldroid

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Evita que la pantalla del celular se apague.
 *
 * Por qué una ventana invisible y no un "wake lock": los wake locks que
 * mantenían la pantalla encendida están descontinuados desde hace años y
 * Android los ignora. La forma que sigue funcionando es tener una ventana en
 * pantalla con FLAG_KEEP_SCREEN_ON.
 *
 * Se usa una ventana de 1x1 completamente transparente, del mismo tipo que el
 * puntero (TYPE_ACCESSIBILITY_OVERLAY), así funciona con CUALQUIER app
 * adelante y no sólo mientras la pantalla de PortalDroid está abierta. Que es
 * justo lo que hace falta: el celular tiene que quedarse despierto mientras
 * mirás TikTok, no mientras mirás la app.
 */
class MantenerDespierta(private val service: AccessibilityService) {

    companion object { private const val TAG = "PortalDroid" }

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var vista: View? = null

    private val params = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSPARENT
    )

    val encendido: Boolean get() = vista != null

    fun prender() {
        main.post {
            if (vista != null) return@post
            val v = View(service)
            try {
                wm.addView(v, params)
                vista = v
                Log.i(TAG, "pantalla: no se va a apagar")
            } catch (e: Exception) {
                Log.w(TAG, "pantalla: no pude mantenerla encendida: ${e.message}")
            }
        }
    }

    fun apagar() {
        main.post {
            val v = vista ?: return@post
            vista = null
            try { wm.removeView(v) } catch (_: Exception) {}
            Log.i(TAG, "pantalla: vuelve a apagarse sola")
        }
    }
}

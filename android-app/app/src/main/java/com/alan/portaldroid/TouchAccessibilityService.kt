package com.alan.portaldroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * Servicio de Accesibilidad: es el único que tiene permiso de Android para
 * "tocar" la pantalla en nombre de otra app. Acá recibimos los comandos que
 * manda la PC por TCP y los convertimos en gestos reales (dispatchGesture).
 *
 * Truco para que un arrastre (swipe) se sienta continuo y no como varios
 * toques cortados: en vez de mandar un gesto entero de una, vamos
 * "continuando" el mismo trazo con continueStroke() a medida que llegan
 * eventos "move" desde la PC. Así el dedo virtual nunca se levanta hasta
 * que llega el "up".
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PortalDroid"
        const val PORT = 7099
        /** Duración del swipe de la rueda del mouse. Corto = se siente ágil;
         *  demasiado corto y TikTok lo toma como toque en vez de deslizar. */
        private const val SCROLL_MS = 130L
        var instance: TouchAccessibilityService? = null
    }

    private var socket: Socket? = null
    private var hilo: Thread? = null
    @Volatile private var running = false
    /** Para que la pantalla de la app pueda mostrar si está conectado. */
    @Volatile var conectado = false
        private set
    @Volatile var pcName: String = ""
        private set

    private var pointer: PointerOverlay? = null
    /** Mantiene la pantalla encendida si el usuario lo pidió. */
    var despierta: MantenerDespierta? = null
        private set

    // Trazo en curso (mientras el "dedo" está apoyado)
    private var currentStroke: StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        pointer = PointerOverlay(this)
        despierta = MantenerDespierta(this)
        // La preferencia sobrevive a reiniciar el celular, así que se aplica
        // apenas arranca el servicio y no cuando se abre la app.
        if (Pairing.pantallaSiempreEncendida(this)) despierta?.prender()
        arrancarEnlace()

        // Pedirle a Android que muestre la barra de "volumen de accesibilidad"
        // en SU PROPIO panel de volumen, al lado de la del parlante.
        //
        // Antes esto se hacía interceptando los botones y dibujando un
        // medidor propio. Quedó mal por dos razones: era un medidor más para
        // aprender, y consumir la tecla mata la repetición automática de
        // Android, así que mantener apretado el botón no hacía nada.
        //
        // Con esta bandera el trabajo lo hace el sistema: aparece una barra
        // más en el panel de siempre, se arrastra como cualquier otra, y los
        // botones físicos la mueven con su repetición normal. Nosotros sólo
        // leemos dónde quedó (ver observadorVolumen) y lo usamos de ganancia.
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME
            serviceInfo = info
            Log.i(TAG, "flags del servicio tras pedir la barra de volumen: ${info.flags}")
        } else {
            Log.w(TAG, "serviceInfo es null, no pude pedir la barra de volumen")
        }

        // El volumen puede cambiar desde el panel, desde los botones o desde
        // otra app. Un observador sobre los ajustes avisa de todas por igual.
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, observadorVolumen
        )
        aplicarVolumenDeAccesibilidad()

        Log.i(TAG, "Servicio de accesibilidad conectado")
    }

    /**
     * Avisa cuando cambia cualquier volumen del sistema.
     *
     * Se mira el ajuste entero y no sólo el stream de accesibilidad porque
     * Android no publica un aviso específico por stream que sea público y
     * estable. Releer dos enteros cuando el usuario mueve un volumen es
     * barato de sobra.
     */
    private val observadorVolumen = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            aplicarVolumenDeAccesibilidad()
        }
    }

    /**
     * Pasa la barra de accesibilidad del sistema a la ganancia del audio que
     * va a la PC.
     *
     * En este celular el stream va de 1 a 15, así que el mínimo no llega a
     * silencio del todo: es como lo define Android, no algo que podamos
     * cambiar desde acá.
     */
    private fun aplicarVolumenDeAccesibilidad() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val tope = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ACCESSIBILITY)
        if (tope <= 0) return
        val actual = am.getStreamVolume(android.media.AudioManager.STREAM_ACCESSIBILITY)
        val ganancia = (actual / tope.toFloat()).coerceIn(0f, 1f)
        if (kotlin.math.abs(ganancia - AudioStreamService.volumen) < 0.001f) return
        AudioStreamService.setVolumen(this, ganancia)
        Log.i(TAG, "volumen a la PC: ${Math.round(ganancia * 100)}% (barra $actual de $tope)")
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try { socket?.close() } catch (_: Exception) {}
        pointer?.hide()
        pointer = null
        despierta?.apagar()
        despierta = null
        try { contentResolver.unregisterContentObserver(observadorVolumen) } catch (_: Exception) {}
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // El tamaño de pantalla se preguntaba en CADA comando. Eso es una consulta
    // al proceso del sistema, 60 veces por segundo, para un dato que no cambia
    // salvo que rotes el celular. Ahora se calcula una vez y se guarda.
    @Volatile private var cachedSize: Point? = null

    private fun screenSize(): Point {
        cachedSize?.let { return it }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val size = Point(bounds.width(), bounds.height())
        cachedSize = size
        return size
    }

    // Si rota la pantalla, el tamaño cacheado deja de valer.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        cachedSize = null
    }

    /**
     * Se conecta a la PC y se queda escuchando sus comandos.
     *
     * Antes era al revés: este servicio abría un ServerSocket y la PC lo
     * llamaba a una IP escrita a mano. Se cambió porque esa IP se rompe sola
     * cuando cambia el DHCP, y porque dos PCs podían conectarse a la vez y
     * pisarse el gesto en curso (el trazo es uno solo, ver currentStroke).
     *
     * Ahora la PC muestra un QR, el celular lo escanea y llama. Reintenta
     * solo: si la PC está apagada o fuera de la red, sigue probando sin
     * molestar a nadie.
     */
    fun arrancarEnlace() {
        if (running) return
        running = true
        hilo = Thread {
            while (running) {
                val par = Pairing.leer(this)
                if (par == null) {
                    // Todavía no escaneó ningún QR: no hay a quién llamar.
                    Thread.sleep(3000)
                    continue
                }
                try {
                    conectarUnaVez(par)
                } catch (e: Exception) {
                    Log.w(TAG, "enlace: ${e.message}")
                }
                conectado = false
                pointer?.setPressed(false)
                pointer?.hide()
                if (running) Thread.sleep(2000)
            }
        }.apply { isDaemon = true }
        hilo?.start()
    }

    /** Vuelve a intentar ya mismo, sin esperar el reintento (al escanear un QR). */
    fun reconectarYa() {
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun conectarUnaVez(par: Pairing) {
        val size = screenSize()
        val s = Socket()
        // Sin tiempo límite, un intento contra una PC apagada se cuelga
        // minutos y el reintento no corre nunca.
        s.connect(java.net.InetSocketAddress(par.host, par.controlPort), 4000)
        s.tcpNoDelay = true
        // Sin tráfico, esta conexión se caía sola cada 10-15 segundos: el
        // ahorro de energía del WiFi o el router la daban por muerta. La PC
        // manda un latido cada 4 s; esto es la red de abajo haciendo lo mismo.
        s.keepAlive = true
        socket = s
        try {
            val out = PrintWriter(s.getOutputStream(), true)
            // Saludo: la clave del QR es lo que prueba que somos nosotros.
            out.println(
                JSONObject()
                    .put("token", par.token)
                    .put("name", android.os.Build.MODEL)
                    .put("screenW", size.x)
                    .put("screenH", size.y)
                    .toString()
            )
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val respuesta = reader.readLine() ?: return
            val ok = try { JSONObject(respuesta).optBoolean("ok", false) } catch (_: Exception) { false }
            if (!ok) {
                Log.w(TAG, "enlace: la PC rechazó la clave; hay que escanear el QR de nuevo")
                Thread.sleep(5000)
                return
            }

            conectado = true
            pcName = par.pcName
            Log.i(TAG, "enlace: conectado con ${par.pcName} (${par.host})")
            AudioStreamService.avisarPar(this, par)

            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    handleCommand(JSONObject(line))
                } catch (e: Exception) {
                    Log.w(TAG, "Comando inválido: $line (${e.message})")
                }
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
            if (socket === s) socket = null
            Log.i(TAG, "enlace: desconectado")
        }
    }

    private fun handleCommand(cmd: JSONObject) {
        val type = cmd.optString("type")
        val size = screenSize()

        fun px(normX: Double, normY: Double): Pair<Float, Float> {
            val x = (normX.coerceIn(0.0, 1.0) * size.x).toFloat()
            val y = (normY.coerceIn(0.0, 1.0) * size.y).toFloat()
            return Pair(x, y)
        }

        when (type) {
            // La PC entró al "modo teléfono": aparece el puntero.
            "enter" -> pointer?.show()

            // La PC se está cerrando. Cortamos el audio ya, sin esperar los
            // 30 segundos de paciencia: si no, la notificación roja queda
            // puesta un rato largo después de cerrar la app de Windows.
            "bye" -> {
                pointer?.setPressed(false)
                pointer?.hide()
                if (AudioStreamService.running) {
                    Log.i(TAG, "la PC se cerró: corto el audio")
                    stopService(android.content.Intent(this, AudioStreamService::class.java))
                }
            }

            // La PC volvió a Windows: desaparece.
            "leave" -> {
                pointer?.setPressed(false)
                pointer?.hide()
            }

            // Movimiento SIN tocar. Es el que hace que dejes de manejar a ciegas:
            // el puntero te muestra dónde vas a caer antes de que aprietes.
            "hover" -> {
                val (x, y) = px(cmd.getDouble("x"), cmd.getDouble("y"))
                pointer?.moveTo(x, y)
            }

            "down" -> {
                val (x, y) = px(cmd.getDouble("x"), cmd.getDouble("y"))
                pointer?.moveTo(x, y)
                pointer?.setPressed(true)
                lastX = x; lastY = y
                val path = Path()
                path.moveTo(x, y)
                // duration corta; willContinue=true = "no levantes el dedo todavía"
                val stroke = StrokeDescription(path, 0, 60, true)
                dispatch(stroke)
                currentStroke = stroke
            }

            "move" -> {
                val (x, y) = px(cmd.getDouble("x"), cmd.getDouble("y"))
                pointer?.moveTo(x, y)
                val stroke = currentStroke ?: return
                val path = Path()
                path.moveTo(lastX, lastY)
                path.lineTo(x, y)
                lastX = x; lastY = y
                val next = stroke.continueStroke(path, 0, 60, true)
                dispatch(next)
                currentStroke = next
            }

            "up" -> {
                pointer?.setPressed(false)
                val stroke = currentStroke ?: return
                val path = Path()
                path.moveTo(lastX, lastY)
                // último tramo, ahora sí willContinue=false: levanta el dedo
                val next = stroke.continueStroke(path, 0, 20, false)
                dispatch(next)
                currentStroke = null
            }

            "tap" -> {
                val (x, y) = px(cmd.getDouble("x"), cmd.getDouble("y"))
                pointer?.moveTo(x, y)
                val path = Path()
                path.moveTo(x, y)
                val stroke = StrokeDescription(path, 0, 50, false)
                dispatch(stroke)
            }

            // Rueda del mouse. dy viene normalizado y con signo: negativo = el
            // dedo va hacia arriba = "siguiente video" en TikTok.
            "scroll" -> {
                val (x, y) = px(cmd.getDouble("x"), cmd.getDouble("y"))
                scroll(x, y, (cmd.getDouble("dy") * size.y).toFloat())
            }

            // Teclado de la PC
            "text" -> typeText(cmd.getString("ch"))
            "key" -> specialKey(cmd.optString("name"))

            "ping" -> { /* solo para probar conexión */ }
        }
    }

    // ---------------- Rueda del mouse ----------------

    /** Hasta cuándo hay un gesto de scroll en vuelo. */
    @Volatile private var scrollBusyUntil = 0L

    private fun scroll(x: Float, y: Float, dy: Float) {
        // Dos gestos no pueden solaparse: si mandamos uno arriba del otro,
        // Android cancela los dos y no se mueve nada. Mientras hay uno en
        // vuelo, ignoramos. Con SCROLL_MS de 130 ms eso deja pasar unos
        // 7 scrolls por segundo, de sobra para pasar videos.
        val now = System.currentTimeMillis()
        if (now < scrollBusyUntil) {
            Log.w(TAG, "scroll ignorado: hay otro gesto en vuelo")
            return
        }
        if (currentStroke != null) {
            // Esto pasaba si un arrastre quedó sin su 'up' (por ejemplo si se
            // cortó el WiFi con el botón apretado): el trazo quedaba abierto
            // para siempre y bloqueaba todos los scrolls de ahí en adelante.
            Log.w(TAG, "scroll ignorado: había un arrastre abierto; se cierra")
            currentStroke = null
        }
        scrollBusyUntil = now + SCROLL_MS + 20

        val size = screenSize()
        // El swipe necesita lugar para recorrer. Si el puntero está muy cerca
        // de un borde, corremos el tramo entero hacia adentro en vez de
        // recortarlo (recortarlo haría un swipe corto que TikTok ignora).
        val margin = size.y * 0.08f
        var sy = y
        var ey = y + dy
        if (ey < margin) { val d = margin - ey; sy += d; ey += d }
        if (ey > size.y - margin) { val d = ey - (size.y - margin); sy -= d; ey -= d }
        sy = sy.coerceIn(margin, size.y - margin)
        ey = ey.coerceIn(margin, size.y - margin)

        Log.i(TAG, "scroll: de ($x, $sy) a ($x, $ey) en ${SCROLL_MS}ms")
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(linePath(x, sy, x, ey), 0, SCROLL_MS, false))
            .build()
        // El callback es la única forma de saber si Android EJECUTÓ el gesto.
        // Sin esto, un gesto rechazado se ve igual que uno que nunca se mandó.
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                Log.i(TAG, "scroll OK")
            }
            override fun onCancelled(d: GestureDescription?) {
                Log.w(TAG, "scroll CANCELADO por Android")
            }
        }, null)
        if (!ok) Log.w(TAG, "scroll RECHAZADO: dispatchGesture devolvió false")
    }

    private fun linePath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        return p
    }

    // ---------------- Teclado ----------------

    /**
     * El campo de texto que está enfocado ahora mismo en el celular.
     * Devuelve null si no hay ninguno (por ejemplo si el foco está en un botón
     * o si estás en TikTok mirando videos).
     */
    private fun focusedField(): AccessibilityNodeInfo? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable) node else null
    }

    /**
     * Escribe texto en el campo enfocado.
     *
     * OJO con cómo funciona esto: la API de accesibilidad no manda "teclas".
     * Lo único que puede hacer es REEMPLAZAR todo el contenido del campo. Así
     * que para agregar una letra leemos lo que hay, insertamos la letra donde
     * está el cursor, y reescribimos el campo entero.
     */
    private fun typeText(ch: String) {
        val node = focusedField()
        if (node == null) {
            Log.w(TAG, "No hay campo de texto enfocado; se descarta: $ch")
            return
        }
        val cur = realTextOf(node)
        val (lo, hi) = selectionOf(node, cur)
        replaceText(node, cur.substring(0, lo) + ch + cur.substring(hi), lo + ch.length)
    }

    /**
     * El texto REAL del campo, distinguiéndolo del texto de ayuda en gris.
     *
     * Cuando un campo está vacío, Android devuelve en getText() la frase de
     * ayuda que se ve en gris (el "Agregar comentario" de TikTok). Si la
     * tomáramos como contenido, la primera letra que escribís se agregaría al
     * final de esa frase y la convertiría en texto de verdad. Al borrarla el
     * campo vuelve a quedar vacío, la frase gris reaparece, y el bucle no
     * termina más. Es exactamente el bug que apareció comentando en TikTok.
     */
    private fun realTextOf(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString() ?: ""
        // La bandera oficial: "lo que te estoy devolviendo es la ayuda, no
        // algo que el usuario haya escrito".
        if (node.isShowingHintText) {
            Log.i(TAG, "campo vacío (mostrando ayuda: \"$raw\")")
            return ""
        }
        // Red de seguridad para apps que no marcan esa bandera: si el
        // contenido es EXACTAMENTE la frase de ayuda y el cursor nunca se
        // movió del principio, es la frase gris. Si vos hubieras tipeado esa
        // misma frase, el cursor estaría al final y no acá.
        val hint = node.hintText?.toString()
        if (!hint.isNullOrEmpty() && raw == hint && node.textSelectionStart <= 0) {
            Log.i(TAG, "campo vacío por red de seguridad (la app no marca isShowingHintText)")
            return ""
        }
        return raw
    }

    /** Borrar: saca el caracter anterior al cursor, o lo que esté seleccionado. */
    private fun backspace() {
        val node = focusedField() ?: return
        val cur = realTextOf(node)
        // Si está vacío (o mostrando la frase gris) no hay nada que borrar.
        // Sin este corte, cada borrar reescribía el campo con "" y hacía
        // reaparecer la frase de ayuda una y otra vez.
        if (cur.isEmpty()) return
        val (lo, hi) = selectionOf(node, cur)
        if (lo != hi) {
            replaceText(node, cur.substring(0, lo) + cur.substring(hi), lo)
        } else if (lo > 0) {
            replaceText(node, cur.substring(0, lo - 1) + cur.substring(lo), lo - 1)
        }
    }

    /** Dónde está el cursor (o la selección), acotado a lo que realmente existe. */
    private fun selectionOf(node: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        val a = node.textSelectionStart
        val b = node.textSelectionEnd
        // Cuando no hay cursor válido, Android devuelve -1: escribimos al final.
        if (a < 0 || b < 0 || a > text.length || b > text.length) {
            return Pair(text.length, text.length)
        }
        return Pair(minOf(a, b), maxOf(a, b))
    }

    private fun replaceText(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // Después de reescribir, el cursor se va al principio. Lo devolvemos
        // a donde corresponde, si no cada letra nueva saldría al revés.
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private fun specialKey(name: String) {
        when (name) {
            "backspace" -> backspace()
            "enter" -> {
                val node = focusedField() ?: return
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            // Estas dos andan siempre, haya o no un campo de texto: son los
            // botones del sistema. Sirven para salir de una app en la que
            // entraste sin querer sin tener que tocar el celular.
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> Log.w(TAG, "Tecla especial desconocida: $name")
        }
    }

    /** Toque de prueba en el centro de la pantalla, para probar sin la PC. */
    fun tapCenter() {
        val size = screenSize()
        val path = Path()
        path.moveTo(size.x / 2f, size.y / 2f)
        dispatch(StrokeDescription(path, 0, 50, false))
        Log.i(TAG, "tapCenter disparado en ${size.x / 2}, ${size.y / 2}")
    }

    private fun dispatch(stroke: StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}

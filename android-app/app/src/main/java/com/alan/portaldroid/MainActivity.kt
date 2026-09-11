package com.alan.portaldroid

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * La pantalla de la app.
 *
 * Está armada como una lista de PASOS en orden, no como un panel de
 * diagnóstico. La versión anterior mostraba IPs, puertos y estados sueltos:
 * información correcta pero inútil si no sabés qué hacer con ella.
 *
 * Cada paso se pinta según cómo está y sólo ofrece un botón el que falta.
 * Cuando los tres están listos, la pantalla lo dice y no hay nada que tocar.
 *
 * Todo se construye en código, sin archivos de layout: la pantalla es simple
 * y así queda junta, más fácil de seguir de una sola lectura.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var resumen: TextView
    private lateinit var pasoAccesibilidad: Tarjeta
    private lateinit var pasoEmparejar: Tarjeta
    private lateinit var pasoAudio: Tarjeta
    private lateinit var pasoPantalla: Tarjeta
    private lateinit var tarjetaBateria: View

    private val FONDO = Color.parseColor("#12151A")
    private val TARJETA = Color.parseColor("#1C2027")
    private val TEXTO = Color.parseColor("#E8EAED")
    private val SUAVE = Color.parseColor("#9AA0A6")
    private val CELESTE = Color.parseColor("#50C8FF")
    private val VERDE = Color.parseColor("#7EE2A8")
    private val ROJO = Color.parseColor("#F0A1A1")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fondoRedondo(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    /** Una tarjeta de paso: número o tilde, título, detalle y a veces un botón. */
    private inner class Tarjeta(val numero: Int, val titulo: String) {
        val caja = LinearLayout(this@MainActivity)
        private val encabezado = TextView(this@MainActivity)
        private val detalle = TextView(this@MainActivity)
        private val boton = Button(this@MainActivity)

        init {
            caja.orientation = LinearLayout.VERTICAL
            caja.setPadding(dp(18), dp(16), dp(18), dp(16))
            caja.background = fondoRedondo(TARJETA)

            encabezado.textSize = 16f
            encabezado.setTextColor(TEXTO)
            caja.addView(encabezado)

            detalle.textSize = 13f
            detalle.setTextColor(SUAVE)
            detalle.setPadding(0, dp(5), 0, 0)
            caja.addView(detalle)

            caja.addView(boton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }

        /**
         * @param listo       pinta el título en verde con tilde
         * @param textoBoton  null esconde el botón: un paso terminado no tiene
         *                    que ofrecer nada para tocar
         */
        fun pintar(listo: Boolean, textoDetalle: String, textoBoton: String?, alTocar: (() -> Unit)? = null) {
            encabezado.text = (if (listo) "✔" else "$numero.") + "  " + titulo
            encabezado.setTextColor(if (listo) VERDE else TEXTO)
            detalle.text = textoDetalle
            if (textoBoton == null) {
                boton.visibility = View.GONE
            } else {
                boton.visibility = View.VISIBLE
                boton.text = textoBoton
                boton.setOnClickListener { alTocar?.invoke() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(FONDO)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(20), dp(28), dp(20), dp(28))

        val titulo = TextView(this)
        titulo.text = "PortalDroid"
        titulo.textSize = 26f
        titulo.setTextColor(TEXTO)
        raiz.addView(titulo)

        val bajada = TextView(this)
        bajada.text = "Manejá este celular con el mouse de tu PC."
        bajada.textSize = 14f
        bajada.setTextColor(SUAVE)
        bajada.setPadding(0, dp(4), 0, dp(4))
        raiz.addView(bajada)

        resumen = TextView(this)
        resumen.textSize = 15f
        resumen.gravity = Gravity.CENTER
        resumen.setPadding(dp(14), dp(14), dp(14), dp(14))
        resumen.background = fondoRedondo(TARJETA)
        raiz.addView(resumen, anchoCompleto(dp(18)))

        pasoAccesibilidad = Tarjeta(1, "Permitir que controle la pantalla")
        pasoEmparejar = Tarjeta(2, "Emparejar con tu PC")
        pasoAudio = Tarjeta(3, "Escuchar el celular en la PC")
        pasoPantalla = Tarjeta(4, "Que la pantalla no se apague")
        raiz.addView(pasoAccesibilidad.caja, anchoCompleto(dp(12)))
        tarjetaBateria = controlBateria()
        raiz.addView(tarjetaBateria, anchoCompleto(dp(12)))
        raiz.addView(pasoEmparejar.caja, anchoCompleto(dp(12)))
        raiz.addView(pasoAudio.caja, anchoCompleto(dp(12)))
        raiz.addView(controlParlante(), anchoCompleto(dp(12)))
        raiz.addView(pasoPantalla.caja, anchoCompleto(dp(12)))

        val ayuda = TextView(this)
        ayuda.text = "El audio sale también por el parlante del celular. Si molesta " +
                "escuchar doble, bajá el volumen del parlante de arriba: la PC lo " +
                "sigue escuchando igual de fuerte. Para el volumen que va a la PC, " +
                "abrí el panel de volumen del celular: además de la barra del " +
                "parlante vas a ver la de accesibilidad, que es esa."
        ayuda.textSize = 12f
        ayuda.setTextColor(SUAVE)
        ayuda.setPadding(dp(4), dp(22), dp(4), 0)
        raiz.addView(ayuda)

        scroll.addView(raiz)
        setContentView(scroll)
    }

    /** ¿Android ya dejó a esta app afuera de su optimización de batería? */
    private fun sinOptimizacionDeBateria(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Tarjeta que pide sacar la app de la optimización de batería.
     *
     * Por qué hace falta: algunos Android (Motorola entre ellos) matan el
     * proceso de la app apenas pasa un rato en segundo plano, aunque el
     * Servicio de Accesibilidad siga marcado como activado en Ajustes. El
     * síntoma es justo lo que ve el usuario: "emparejado pero no lo
     * encuentro" en el celular mientras la PC todavía cree estar conectada, y
     * la pantalla vuelve a pedir el permiso de accesibilidad aunque nunca se
     * desactivó a mano. Sacando la app de la optimización, Android dejar de
     * matarla y el servicio sobrevive en segundo plano.
     *
     * Sólo se muestra si hace falta: una vez concedido, `refrescar()` la
     * esconde sola.
     */
    private fun controlBateria(): View {
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(18), dp(16), dp(18), dp(16))
        caja.background = fondoRedondo(TARJETA)

        val titulo = TextView(this)
        titulo.text = "Evitar que Android mate la app"
        titulo.textSize = 16f
        titulo.setTextColor(TEXTO)
        caja.addView(titulo)

        val detalle = TextView(this)
        detalle.text = "Algunos celulares apagan solos el servicio si pasa un rato en " +
                "segundo plano, y hay que reactivarlo a mano. Sacando la app de la " +
                "optimización de batería, Android la deja tranquila."
        detalle.textSize = 13f
        detalle.setTextColor(SUAVE)
        detalle.setPadding(0, dp(5), 0, dp(10))
        caja.addView(detalle)

        val boton = Button(this)
        boton.text = "Sacar la optimización de batería"
        boton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            try { startActivity(intent) } catch (_: Exception) {
                // Algunos fabricantes no tienen esta pantalla; mandamos a la
                // lista general de apps con batería sin restricciones.
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Exception) {
                    Toast.makeText(this, "Buscá PortalDroid en Ajustes → Batería", Toast.LENGTH_LONG).show()
                }
            }
        }
        caja.addView(boton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return caja
    }

    /**
     * Tarjeta con la perilla del volumen REAL del parlante del celular
     * (`STREAM_MUSIC`, el mismo de siempre — pero ojo: mientras se está
     * mandando audio a la PC, los botones físicos dejan de tocar esto y
     * pasan a controlar el volumen enviado, ver
     * TouchAccessibilityService.onKeyEvent). Esta perilla de acá sigue
     * siendo la única forma de bajar el parlante en ese momento.
     *
     * Sirve para cortar el eco de escuchar todo doble sin perder nada del
     * lado de la PC: la captura de audio (`AudioPlaybackCapture`) lee las
     * muestras ANTES de esta etapa, así que bajar esto a 0 deja mudo el
     * parlante pero no toca un solo bit de lo que se manda por WiFi.
     */
    private fun controlParlante(): View {
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(18), dp(16), dp(18), dp(16))
        caja.background = fondoRedondo(TARJETA)

        val audioMgr = getSystemService(AUDIO_SERVICE) as AudioManager
        val maximo = maxOf(1, audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        val titulo = TextView(this)
        titulo.text = "Volumen del parlante del celular"
        titulo.textSize = 16f
        titulo.setTextColor(TEXTO)
        titulo.layoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        fila.addView(titulo)

        val valorTexto = TextView(this)
        valorTexto.textSize = 16f
        valorTexto.setTextColor(CELESTE)
        fila.addView(valorTexto)
        caja.addView(fila)

        val detalle = TextView(this)
        detalle.text = "Bajalo a 0 para que no suene por el parlante mientras mirás " +
                "todo desde la PC. Es el volumen normal del celular: lo mismo que " +
                "suben y bajan los botones físicos."
        detalle.textSize = 13f
        detalle.setTextColor(SUAVE)
        detalle.setPadding(0, dp(5), 0, dp(8))
        caja.addView(detalle)

        val barra = SeekBar(this)
        barra.max = maximo
        val inicial = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC)
        barra.progress = inicial
        valorTexto.text = "${inicial * 100 / maximo}%"
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, valor: Int, desdeUsuario: Boolean) {
                valorTexto.text = "${valor * 100 / maximo}%"
                // flags=0: cambia el volumen sin mostrar el cartel grande de
                // "Volumen multimedia" que Android tira por encima de todo.
                if (desdeUsuario) audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, valor, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        caja.addView(barra)

        return caja
    }

    private fun anchoCompleto(margenArriba: Int) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = margenArriba }

    private val reloj = Handler(Looper.getMainLooper())
    private val latir = object : Runnable {
        override fun run() {
            refrescar()
            reloj.postDelayed(this, 1500)
        }
    }

    /**
     * Mientras la pantalla está a la vista se refresca sola.
     *
     * Antes sólo miraba al abrir y un segundo después. El problema: conectarse
     * a la PC tarda un par de segundos, así que justo después de escanear el
     * QR la pantalla decía "no la encuentro" y se quedaba con ese cartel
     * aunque un segundo más tarde ya estuviera conectada. Parecía que el
     * emparejamiento había fallado cuando en realidad había funcionado.
     */
    override fun onResume() {
        super.onResume()
        reloj.removeCallbacks(latir)
        reloj.post(latir)
    }

    override fun onPause() {
        super.onPause()
        // Con la pantalla no visible no tiene sentido seguir mirando.
        reloj.removeCallbacks(latir)
    }

    private fun refrescar() {
        val activado = accesibilidadActivada()
        val svc = TouchAccessibilityService.instance
        val corriendo = svc != null
        val par = Pairing.leer(this)
        val enlazado = svc?.conectado == true
        val audio = AudioStreamService.running

        // Una vez que el usuario saca la app de la optimización de batería,
        // la tarjeta que se lo pide ya no sirve para nada.
        tarjetaBateria.visibility = if (sinOptimizacionDeBateria()) View.GONE else View.VISIBLE

        when {
            activado && corriendo -> pasoAccesibilidad.pintar(
                true, "Listo. Android le dio permiso de tocar la pantalla.", null)
            activado -> pasoAccesibilidad.pintar(
                false,
                "Android dice que está activado pero el servicio se apagó solo, " +
                    "seguramente por ahorro de batería. Probá apagarlo y prenderlo de " +
                    "nuevo, y sacá la app de la optimización de batería más abajo para " +
                    "que no vuelva a pasar.",
                "Abrir Ajustes de accesibilidad") { abrirAjustes() }
            else -> pasoAccesibilidad.pintar(
                false,
                "Buscá PortalDroid en la lista y activalo. Android te va a advertir " +
                    "que el permiso es fuerte: es justamente el que le deja tocar la pantalla.",
                "Abrir Ajustes de accesibilidad") { abrirAjustes() }
        }

        when {
            enlazado -> pasoEmparejar.pintar(
                true, "Conectado con ${svc?.pcName}.",
                "Emparejar con otra PC") { pedirCamaraYEscanear() }
            par != null -> pasoEmparejar.pintar(
                false,
                "Emparejado con ${par.pcName}, pero no la encuentro ahora. Fijate que " +
                    "la PC tenga PortalDroid abierto y esté en la misma red.",
                "Escanear otro código") { pedirCamaraYEscanear() }
            else -> pasoEmparejar.pintar(
                false,
                "Abrí PortalDroid en la PC y escaneá el código que muestra. No hace " +
                    "falta escribir ninguna dirección.",
                "Escanear código de la PC") { pedirCamaraYEscanear() }
        }

        when {
            audio -> pasoAudio.pintar(
                true, "Mandando el audio a la PC.", "Cortar el audio") { alternarAudio() }
            !enlazado -> pasoAudio.pintar(
                false, "Primero emparejá con la PC.", null)
            else -> pasoAudio.pintar(
                false,
                "Android te va a pedir permiso para capturar el audio. Hay que darlo " +
                    "cada vez: no se puede recordar.",
                "Mandar el audio a la PC") { alternarAudio() }
        }

        val despierta = svc?.despierta?.encendido == true
        when {
            !corriendo -> pasoPantalla.pintar(
                false, "Primero activá el permiso de accesibilidad.", null)
            despierta -> pasoPantalla.pintar(
                true,
                "La pantalla se queda encendida. Gasta más batería, así que " +
                    "conviene tener el celular enchufado.",
                "Dejar que se apague sola") { alternarPantalla() }
            else -> pasoPantalla.pintar(
                false,
                "Si la pantalla se apaga no podés manejar el celular desde la PC. " +
                    "Con esto se queda encendida mientras uses cualquier app.",
                "Mantener la pantalla encendida") { alternarPantalla() }
        }

        val (texto, color) = when {
            !activado || !corriendo -> Pair("Falta activar el permiso de accesibilidad", ROJO)
            par == null -> Pair("Falta emparejar con tu PC", CELESTE)
            !enlazado -> Pair("Buscando a ${par.pcName}…", CELESTE)
            audio -> Pair("Todo listo, con audio", VERDE)
            else -> Pair("Listo para usar", VERDE)
        }
        resumen.text = texto
        resumen.setTextColor(color)
    }

    private fun abrirAjustes() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * Prende o apaga el "que no se apague la pantalla".
     *
     * La preferencia se guarda en disco y la aplica el servicio de
     * accesibilidad, no esta pantalla: tiene que seguir valiendo con TikTok
     * adelante, no sólo mientras mirás PortalDroid.
     */
    private fun alternarPantalla() {
        val svc = TouchAccessibilityService.instance ?: return
        val nuevo = !(svc.despierta?.encendido == true)
        Pairing.guardarPantallaSiempreEncendida(this, nuevo)
        if (nuevo) svc.despierta?.prender() else svc.despierta?.apagar()
        Handler(Looper.getMainLooper()).postDelayed({ refrescar() }, 400)
    }

    // ---------------- Emparejamiento por QR ----------------

    private val lector = registerForActivityResult(ScanContract()) { resultado ->
        val texto = resultado.contents ?: return@registerForActivityResult   // canceló
        val par = Pairing.desdeQR(texto)
        if (par == null) {
            Toast.makeText(this, "Ese código no es de PortalDroid", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        Pairing.guardar(this, par)
        Toast.makeText(this, "Emparejado con ${par.pcName}", Toast.LENGTH_LONG).show()
        // Reconectar ya, sin esperar el reintento de dos segundos.
        TouchAccessibilityService.instance?.reconectarYa()
        Handler(Looper.getMainLooper()).postDelayed({ refrescar() }, 1200)
    }

    private val pedirCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) abrirEscaner()
        else Toast.makeText(this, "Sin cámara no se puede leer el código", Toast.LENGTH_LONG).show()
    }

    private fun pedirCamaraYEscanear() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            abrirEscaner()
        } else {
            pedirCamara.launch(Manifest.permission.CAMERA)
        }
    }

    private fun abrirEscaner() {
        lector.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Apuntá al código que muestra la PC")
                .setBeepEnabled(false)
                // true = se queda como está la app al abrir el escáner, que
                // es vertical. Con false, la pantalla de escaneo rota sola a
                // horizontal y el código queda incómodo de apuntar.
                .setOrientationLocked(true)
                .setCaptureActivity(EscanerVertical::class.java)
        )
    }

    // ---------------- Audio hacia la PC ----------------

    /**
     * Android exige que el usuario acepte un cartel del sistema antes de dejar
     * capturar el audio de otras apps. No se puede saltear ni recordar: hay
     * que pedirlo cada vez que se arranca la transmisión.
     */
    private val pedirCaptura = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode != RESULT_OK || resultado.data == null) {
            Toast.makeText(this, "Sin ese permiso no se puede mandar el audio", Toast.LENGTH_SHORT).show()
            refrescar()
            return@registerForActivityResult
        }
        val svc = Intent(this, AudioStreamService::class.java)
        svc.putExtra(AudioStreamService.EXTRA_RESULT_CODE, resultado.resultCode)
        svc.putExtra(AudioStreamService.EXTRA_RESULT_DATA, resultado.data)
        startForegroundService(svc)
        Handler(Looper.getMainLooper()).postDelayed({ refrescar() }, 800)
    }

    private val pedirNotificaciones = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { arrancarCaptura() }

    private fun alternarAudio() {
        if (AudioStreamService.running) {
            stopService(Intent(this, AudioStreamService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ refrescar() }, 500)
            return
        }
        // El servicio en primer plano necesita mostrar una notificación, y
        // desde Android 13 eso es un permiso aparte.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        arrancarCaptura()
    }

    private fun arrancarCaptura() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        pedirCaptura.launch(mgr.createScreenCaptureIntent())
    }

    /**
     * Le preguntamos a Android directamente si el servicio está activado.
     * Es la única fuente confiable: una variable propia queda vieja apenas el
     * usuario lo apaga desde Ajustes.
     */
    private fun accesibilidadActivada(): Boolean {
        val cn = ComponentName(this, TouchAccessibilityService::class.java)
        val largo = cn.flattenToString()
        val corto = cn.flattenToShortString()
        val lista = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return lista.split(':').any {
            it.equals(largo, ignoreCase = true) || it.equals(corto, ignoreCase = true)
        }
    }
}

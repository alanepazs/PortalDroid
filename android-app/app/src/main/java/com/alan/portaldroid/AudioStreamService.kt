package com.alan.portaldroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import java.io.OutputStream
import java.net.Socket

/**
 * Manda el audio que suena en el celular a la PC, por WiFi.
 *
 * Cómo hace para escuchar a OTRAS apps sin root: Android tiene una API
 * (AudioPlaybackCapture, de Android 10 para arriba) que deja capturar el audio
 * que reproducen otras aplicaciones. No es libre:
 *
 * 1. El usuario tiene que dar permiso explícito en un cartel del sistema
 *    ("¿Empezar a grabar o transmitir?"). Ese permiso lo pide MainActivity.
 * 2. Sólo se puede capturar audio de tipo multimedia o juego. Las llamadas
 *    telefónicas y las notificaciones quedan afuera, a propósito.
 * 3. Cada app puede negarse poniendo allowAudioPlaybackCapture="false" en su
 *    manifiesto. Spotify y Netflix se niegan. TikTok no (se verificó leyendo
 *    su manifiesto), así que el caso de uso de este proyecto funciona.
 *
 * Capturar NO silencia el celular: el sonido sigue saliendo por su parlante.
 * Si molesta escuchar doble, bajale el volumen al celular.
 *
 * Va por un puerto aparte del de los toques. Es a propósito: el audio son
 * ~1,5 Mbit por segundo y si compartiera socket con los comandos del mouse,
 * los movimientos del puntero quedarían haciendo cola atrás del audio y
 * volvería el lag que tanto costó sacar.
 */
class AudioStreamService : Service() {

    companion object {
        private const val TAG = "PortalDroid"
        /** Avisa que cambió el emparejamiento, para reconectar ya mismo. */
        fun avisarPar(ctx: android.content.Context, p: Pairing) { /* el hilo lo relee solo */ }
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "portaldroid_audio"
        private const val NOTIF_ID = 1

        // 48 kHz es la frecuencia nativa de la mayoría de los Android: usarla
        // evita que el sistema tenga que reconvertir la señal.
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2

        /** Cuánto se espera a la PC antes de cortar solo. Tiene que ser más
         *  largo que un reinicio de la app de Windows (que tarda segundos),
         *  para no obligar a dar el permiso de captura de nuevo por gusto. */
        private const val SIN_PC_MS = 30_000L

        @Volatile var running = false
            private set

        /**
         * Ganancia aplicada al audio ANTES de mandarlo, 0.0 a 2.0. Es la que
         * controla el usuario desde la pantalla de la app (ver MainActivity).
         *
         * Por qué existe: bajar el volumen DEL CELULAR no cambia nada de lo
         * que llega a la PC, porque AudioPlaybackCapture toma el audio antes
         * del control de volumen del sistema (ver comentario de la clase).
         * Esta es la única perilla que de verdad afecta lo que escucha la PC.
         *
         * Se lee al arrancar la transmisión y se puede cambiar en caliente
         * mientras está sonando: el hilo de envío la relee en cada bloque.
         */
        @Volatile var volumen = 1f

        /** Cambia la ganancia y la guarda, para la próxima vez que se abra la app. */
        fun setVolumen(ctx: android.content.Context, valor: Float) {
            volumen = valor.coerceIn(0f, 2f)
            Pairing.guardarVolumenEnviado(ctx, volumen)
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var socket: Socket? = null
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * El usuario sacó la app de las recientes: se corta el audio.
     *
     * Un servicio en primer plano sobrevive a eso por diseño, pero acá queda
     * mal: la notificación roja se queda puesta y la única forma de sacarla
     * era volver a abrir la app y tocar "cortar". Cerrar la app tiene que
     * cortar el envío, que es lo que uno espera.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "audio: se cerró la app, corto el envío")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (alive) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            Log.w(TAG, "audio: falta el permiso de captura, no arranco")
            stopSelf()
            return START_NOT_STICKY
        }

        // ORDEN OBLIGATORIO en Android 14+: primero el servicio tiene que estar
        // en primer plano, y RECIÉN DESPUÉS se puede pedir la proyección. Al
        // revés, Android tira una excepción y mata el servicio.
        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = mgr.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            Log.w(TAG, "audio: no se pudo obtener la proyección")
            stopSelf()
            return START_NOT_STICKY
        }
        // Registrar el callback es obligatorio desde Android 14. Además nos
        // avisa si el usuario corta la transmisión desde la notificación.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "audio: el usuario cortó la transmisión")
                stopSelf()
            }
        }, null)
        projection = proj

        alive = true
        running = true
        // Por si se cerró el proceso desde la última vez: releer lo que el
        // usuario dejó guardado. Si sigue vivo, `volumen` ya tiene lo último
        // que se tocó en la pantalla y esto no cambia nada.
        volumen = Pairing.leerVolumenEnviado(applicationContext)
        startCapture(proj)
        arrancarEnvio()
        Log.i(TAG, "audio: capturando y mandando a la PC")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        record = null
        projection = null
        Log.i(TAG, "audio: detenido")
    }

    private fun startCapture(proj: MediaProjection) {
        // Qué tipos de audio queremos. MEDIA cubre videos y música; GAME los
        // juegos; UNKNOWN las apps que no declaran nada. No se puede pedir
        // llamadas ni notificaciones aunque quisiéramos: Android no lo permite.
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        // Buffer holgado, no mínimo.
        //
        // La primera versión usaba 8192 bytes (~42 ms) buscando poco retraso.
        // Se midió que cuando el celular está ocupado moviendo el puntero y
        // ejecutando gestos, el hilo que manda el audio se queda hasta 1,5
        // segundos sin correr. Con un buffer de 42 ms, todo ese audio se
        // pierde. Con 400 ms sobrevive a la pausa y se manda apenas el hilo
        // vuelve. No agrega retraso: el buffer se usa sólo cuando hay atasco,
        // y en marcha normal se vacía apenas se llena.
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 4, SAMPLE_RATE * CHANNELS * 2 * 400 / 1000))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        rec.startRecording()
        record = rec
    }

    /**
     * Llama a la PC y le manda el audio hasta que se corte.
     *
     * Antes este servicio ESCUCHABA y la PC lo llamaba. Se dio vuelta junto
     * con el resto (ver TouchAccessibilityService.arrancarEnlace). De paso
     * arregla un bug feo: cuando escuchaba, dos PCs podían conectarse a la vez
     * y cada `pump` leía del MISMO AudioRecord. Cada muestra le toca a un solo
     * lector, así que las dos PCs recibían la mitad del audio, picada, sin
     * ninguna forma de darse cuenta de por qué.
     */
    /** Última vez que hubo conexión con la PC, para saber cuándo rendirse. */
    @Volatile private var ultimaConexion = 0L

    private fun arrancarEnvio() {
        ultimaConexion = System.currentTimeMillis()
        Thread {
            // Prioridad de audio. Con prioridad normal perdía contra el trabajo
            // de la pantalla: dejaba de mandar más de un segundo y después
            // largaba todo junto, y la PC no daba abasto con el golpe.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (alive) {
                val par = Pairing.leer(this)
                if (par == null) { Thread.sleep(3000); continue }
                var s: Socket? = null
                try {
                    s = Socket()
                    // Sin tiempo límite, un intento contra una PC apagada se
                    // cuelga minutos y no se reintenta nunca.
                    s.connect(java.net.InetSocketAddress(par.host, par.audioPort), 4000)
                    s.tcpNoDelay = true
                    socket = s
                    val out = s.getOutputStream()

                    // Saludo con la clave, igual que el canal de toques.
                    out.write((org.json.JSONObject()
                        .put("token", par.token)
                        .put("name", android.os.Build.MODEL)
                        .toString()).toByteArray())
                    out.write(10)
                    // Y la cabecera con el formato de lo que sigue.
                    out.write(("""{"sampleRate":$SAMPLE_RATE,"channels":$CHANNELS,"bits":16}""").toByteArray())
                    out.write(10)
                    out.flush()
                    Log.i(TAG, "audio: mandando a ${par.pcName} (${par.host})")

                    // ~10 ms por vuelta (48000 * 2 canales * 2 bytes / 100).
                    val buf = ByteArray(1920)
                    while (alive) {
                        val rec = record ?: break
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            aplicarGanancia(buf, n, volumen)
                            out.write(buf, 0, n)
                        } else if (n < 0) break
                    }
                    // Si llegamos acá, la PC cortó: se reinicia el reloj de
                    // paciencia sólo cuando SÍ hubo conexión.
                    ultimaConexion = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.i(TAG, "audio: sin PC (${e.message})")
                } finally {
                    try { s?.close() } catch (_: Exception) {}
                    if (socket === s) socket = null
                }

                // Si la PC no aparece por un buen rato, se corta solo. Sin
                // esto, cerrar la app de Windows dejaba el celular
                // reintentando para siempre con la notificación puesta.
                if (alive && System.currentTimeMillis() - ultimaConexion > SIN_PC_MS) {
                    Log.i(TAG, "audio: la PC no aparece hace rato, corto el envío")
                    stopSelf()
                    return@Thread
                }
                if (alive) Thread.sleep(2000)
            }
            Log.i(TAG, "audio: envío detenido")
        }.apply { isDaemon = true }.start()
    }

    /**
     * Multiplica cada muestra del bloque por `ganancia`, en el lugar.
     *
     * PCM 16 bits con signo, little endian, L y R intercalados: cada muestra
     * son 2 bytes. Se recorta a los límites de un short para no desbordar y
     * volverse ruido si la ganancia es mayor a 1.
     *
     * En 1.0 no hace nada: es el caso normal y correr un loop por gusto en
     * cada bloque de audio (100 veces por segundo) sería desperdiciar el hilo
     * de prioridad urgente que tanto costó conseguir.
     */
    private fun aplicarGanancia(buf: ByteArray, n: Int, ganancia: Float) {
        if (ganancia == 1f) return
        var i = 0
        while (i + 1 < n) {
            val muestra = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            val escalada = (muestra * ganancia).toInt().coerceIn(-32768, 32767)
            buf[i] = escalada.toByte()
            buf[i + 1] = (escalada shr 8).toByte()
            i += 2
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Audio hacia la PC", NotificationManager.IMPORTANCE_LOW
            )
        )

        // Tocar la notificación abre la app. Sin esto no hacía nada, que es
        // justo lo contrario de lo que uno espera: es el único cartel visible
        // mientras el audio va, así que es el atajo natural para volver.
        //
        // CLEAR_TOP + SINGLE_TOP en vez de abrir otra copia: si la pantalla ya
        // estaba abierta atrás, vuelve a esa misma y no apila una arriba.
        val abrir = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val alTocar = PendingIntent.getActivity(
            this, 0, abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PortalDroid")
            .setContentText("Mandando el audio del celular a la PC")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(alTocar)
            .setOngoing(true)
            .build()
    }
}

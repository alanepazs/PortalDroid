package com.alan.portaldroid

import android.content.Context
import org.json.JSONObject

/**
 * Con qué PC está emparejado este celular.
 *
 * Se guarda en el disco a propósito: si hubiera que escanear el QR cada vez
 * que arranca la app, sería inusable. Se escanea una vez y desde ahí el
 * celular llama solo a esa PC cada vez que la encuentra.
 *
 * El QR trae los nombres cortos para que el código sea poco denso y fácil de
 * leer con la cámara: h=host, c=puerto de toques, a=puerto de audio,
 * t=clave, n=nombre de la PC.
 */
data class Pairing(
    val host: String,
    val controlPort: Int,
    val audioPort: Int,
    val token: String,
    val pcName: String,
) {
    companion object {
        private const val PREFS = "portaldroid"
        private const val KEY = "pairing"

        /** Lee un QR. Devuelve null si no es uno nuestro. */
        fun desdeQR(texto: String): Pairing? {
            return try {
                val o = JSONObject(texto)
                val host = o.optString("h")
                val token = o.optString("t")
                if (host.isEmpty() || token.isEmpty()) return null
                Pairing(
                    host = host,
                    controlPort = o.optInt("c", 7099),
                    audioPort = o.optInt("a", 7100),
                    token = token,
                    pcName = o.optString("n", "PC"),
                )
            } catch (_: Exception) {
                null
            }
        }

        fun guardar(ctx: Context, p: Pairing) {
            val json = JSONObject()
                .put("h", p.host).put("c", p.controlPort).put("a", p.audioPort)
                .put("t", p.token).put("n", p.pcName)
                .toString()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json).apply()
        }

        fun leer(ctx: Context): Pairing? {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return null
            return desdeQR(json)
        }

        private const val KEY_DESPIERTA = "pantallaSiempreEncendida"

        /** Si el usuario pidió que la pantalla no se apague nunca. */
        fun pantallaSiempreEncendida(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESPIERTA, false)

        fun guardarPantallaSiempreEncendida(ctx: Context, valor: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DESPIERTA, valor).apply()
        }

        fun borrar(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        }
    }
}

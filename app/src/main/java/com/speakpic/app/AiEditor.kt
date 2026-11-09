package com.speakpic.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claude (Anthropic Messages API) ile fotoğraf düzenleme önerisi alır.
 *
 * ÖNEMLİ: Claude görüntü ÜRETMEZ/PİKSEL DÜZENLEMEZ; fotoğrafa ve yönergeye bakıp filtre/ayar DEĞERLERİ önerir.
 * Bu değerleri uygulama cihazda uygular. Nesne silme, arka plan değiştirme gibi üretken düzenlemeler yapılamaz.
 *
 * Güvenlik: API anahtarı uygulamaya GÖMÜLMEZ; kullanıcı kendi anahtarını girer ve yalnızca bu cihazda saklanır.
 */
object AiEditor {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-5-5"
    private const val PREFS = "speakpic_ai"
    private const val KEY = "api_key"

    class AiException(message: String) : Exception(message)

    data class Suggestion(
        val params: EditEngine.Params,
        val rotateDegrees: Int,   // mevcut görünüme göre saat yönünde
        val explanation: String
    )

    fun getKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun setKey(ctx: Context, key: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (key.isNullOrBlank()) remove(KEY) else putString(KEY, key.trim())
        }.apply()
    }

    private const val SYSTEM_PROMPT =
        "You are the editing engine of a photo app. You receive a photo and the user's edit instruction " +
        "(any language). Choose adjustments from the fixed toolset below and reply with ONLY one JSON object, " +
        "no markdown, no extra text.\n" +
        "Schema: {\"filter\":\"none|bw|sepia|vivid|warm|cool|vintage\",\"brightness\":int,\"contrast\":int," +
        "\"saturation\":int,\"warmth\":int,\"rotate\":0|90|180|270,\"explanation\":\"...\"}\n" +
        "brightness, contrast, saturation, warmth are ABSOLUTE final values from -100 to 100 (0 = neutral) and " +
        "replace the current settings; the current settings are given so you can make relative changes " +
        "(e.g. 'a bit brighter' = current + 15). rotate is clockwise degrees relative to the current view.\n" +
        "Look at the photo to choose sensible values (e.g. lift a dark photo, cool down a yellow cast).\n" +
        "You CANNOT remove objects, change backgrounds, retouch faces or add content. If the request needs that, " +
        "apply the closest reasonable global adjustment (or leave values unchanged) and say clearly in " +
        "'explanation' what you could not do. 'explanation' must be 1-2 short sentences in the language requested."

    /** Ağ çağrısı yapar; ARKA PLAN iş parçacığında çağrılmalıdır. */
    fun suggest(
        apiKey: String,
        jpegBase64: String,
        instruction: String,
        current: EditEngine.Params,
        explanationLanguage: String
    ): Suggestion {
        val currentJson = JSONObject()
            .put("filter", current.filter)
            .put("brightness", current.brightness)
            .put("contrast", current.contrast)
            .put("saturation", current.saturation)
            .put("warmth", current.warmth)
        val userText = "Instruction: $instruction\nCurrent settings: $currentJson\n" +
            "Write 'explanation' in: $explanationLanguage"

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 700)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray()
                        .put(JSONObject()
                            .put("type", "image")
                            .put("source", JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", jpegBase64)))
                        .put(JSONObject().put("type", "text").put("text", userText)))
            ))

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AiException(errorMessage(code, text))
            return parse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (e: Exception) { null }
        return "HTTP $code" + (if (!msg.isNullOrBlank()) " – $msg" else "")
    }

    private fun parse(response: String): Suggestion {
        val content = JSONObject(response).optJSONArray("content") ?: throw AiException("empty response")
        var text = ""
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") { text = block.optString("text"); break }
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("unexpected answer")
        val j = JSONObject(text.substring(start, end + 1))

        val filter = j.optString("filter", "none").lowercase().let { if (it in EditEngine.FILTERS) it else "none" }
        fun v(name: String) = j.optInt(name, 0).coerceIn(-100, 100)
        val rotate = ((j.optInt("rotate", 0) % 360) + 360) % 360
        val snapped = (rotate / 90) * 90
        return Suggestion(
            EditEngine.Params(filter, v("brightness"), v("contrast"), v("saturation"), v("warmth")),
            snapped,
            j.optString("explanation", "")
        )
    }
}

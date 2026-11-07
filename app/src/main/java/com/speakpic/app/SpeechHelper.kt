package com.speakpic.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * SpeechRecognizer için ortak sarmalayıcı (kamera ekranı ve fotoğraf görüntüleyici kullanır).
 * Tüm geri çağırmalar ana iş parçacığında gelir.
 */
class SpeechHelper(
    private val ctx: Context,
    private val onState: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,   // boş metin = anlaşılamadı
    private val onFailure: (String?) -> Unit   // null = sessiz iptal
) {
    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(languageTag: String) {
        cancel()
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (p.isNotBlank()) onPartial(p)
            }

            override fun onResults(results: Bundle?) {
                setListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                onFinal(text)
            }

            override fun onError(error: Int) {
                setListening(false)
                val msg: String? = when (error) {
                    SpeechRecognizer.ERROR_CLIENT -> null
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ctx.getString(R.string.speech_no_match)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        ctx.getString(R.string.mic_permission_needed)
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ctx.getString(R.string.speech_network)
                    12, 13 -> ctx.getString(R.string.speech_lang_unavailable)
                    else -> ctx.getString(R.string.speech_error, error)
                }
                onFailure(msg)
            }
        })
        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        sr.startListening(intent)
        setListening(true)
    }

    /** Kullanıcı bitirmek istediğinde: o ana kadarki konuşmayı sonuçlandırır. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Sonuç beklemeden iptal eder ve kaynakları serbest bırakır. */
    fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (listening) setListening(false)
    }

    private fun setListening(value: Boolean) {
        if (listening == value) return
        listening = value
        onState(value)
    }
}

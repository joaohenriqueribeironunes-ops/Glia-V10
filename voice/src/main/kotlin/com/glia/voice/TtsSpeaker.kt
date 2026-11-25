package com.glia.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fala em pt-BR.
 *
 * Preferência: voz neural embutida (Piper faber int8 via sherpa-onnx).
 * Se o modelo não puder ser carregado, cai para o motor TTS do aparelho,
 * sem travar a conversa.
 */
class TtsSpeaker(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var ptBrAvailable = false
    private val pending = HashMap<String, CompletableDeferred<Unit>>()
    private val neural = NeuralTts(context)

    val isPtBr: Boolean get() = neural.isReady || ptBrAvailable

    val neuralReady: Boolean get() = neural.isReady

    val engineLabel: String
        get() = when {
            neural.isReady -> "neural · ${NeuralTts.DISPLAY_NAME}"
            neural.lastFailure != null -> "sistema · neural indisponível"
            else -> "sistema"
        }

    /** Sobe a voz neural (copia os assets na primeira vez). */
    suspend fun initialize(): Boolean {
        if (neural.prepare()) return true
        return initializeSystem()
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        VoiceBus.setState(VoiceState.SPEAKING)
        val spoken = runCatching { neural.speak(text) }.getOrElse {
            android.util.Log.e("TtsSpeaker", "voz neural falhou", it)
            false
        }
        if (!spoken) speakWithSystem(text)
        if (VoiceBus.state.value == VoiceState.SPEAKING) VoiceBus.reset()
    }

    private suspend fun initializeSystem(): Boolean = withContext(Dispatchers.Main) {
        if (ready) return@withContext true
        val status = CompletableDeferred<Int>()
        val tts = TextToSpeech(context) { code -> status.complete(code) }
        engine = tts
        val code = status.await()
        if (code != TextToSpeech.SUCCESS) {
            ready = false
            return@withContext false
        }
        val result = tts.setLanguage(Locale.forLanguageTag("pt-BR"))
        ptBrAvailable = result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (!ptBrAvailable) tts.setLanguage(Locale.getDefault())
        tts.setSpeechRate(1.02f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                pending.remove(utteranceId)?.complete(Unit)
            }
        })
        ready = true
        true
    }

    private suspend fun speakWithSystem(text: String) {
        if (!initializeSystem()) return
        val tts = engine ?: return
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        withContext(Dispatchers.Main) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
        deferred.await()
    }

    fun stop() {
        neural.stop()
        runCatching { engine?.stop() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        if (VoiceBus.state.value == VoiceState.SPEAKING) VoiceBus.reset()
    }

    fun shutdown() {
        stop()
        neural.shutdown()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}

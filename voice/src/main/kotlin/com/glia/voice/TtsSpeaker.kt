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
 * Fala em pt-BR usando o motor TTS do próprio aparelho (offline, sem rede).
 * Em HyperOS a voz pt-BR vem do Google TTS; se não estiver instalada,
 * caímos para a voz padrão sem travar a conversa.
 */
class TtsSpeaker(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var ptBrAvailable = false
    private val pending = HashMap<String, CompletableDeferred<Unit>>()

    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
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

    val isPtBr: Boolean get() = ptBrAvailable

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!initialize()) return
        val tts = engine ?: return
        VoiceBus.setState(VoiceState.SPEAKING)
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        withContext(Dispatchers.Main) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
        deferred.await()
        if (VoiceBus.state.value == VoiceState.SPEAKING) VoiceBus.reset()
    }

    fun stop() {
        runCatching { engine?.stop() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        if (VoiceBus.state.value == VoiceState.SPEAKING) VoiceBus.reset()
    }

    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}

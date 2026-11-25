package com.glia.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Captura PCM 16 kHz mono e alimenta um Recognizer do Vosk.
 *
 * - Palavra-chave: gramática restrita ["glia","[unk]"] → custo de CPU muito baixo.
 * - Comando: vocabulário aberto, com VAD simples por energia para encerrar.
 */
class VoskEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskEngine"
        private const val SAMPLE_RATE = 16_000
        const val WAKE_GRAMMAR = "[\"glia\", \"[unk]\"]"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var commandMode = false

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun loadModel(): Result<Unit> {
        if (model != null) return Result.success(Unit)
        VoskModelLocator.ensureFromAssets(context)
        val dir: File = VoskModelLocator.modelDir(context)
        if (!dir.exists()) {
            return Result.failure(IllegalStateException("modelo Vosk ausente em ${dir.absolutePath}"))
        }
        return runCatching {
            model = Model(dir.absolutePath)
            Log.i(TAG, "modelo Vosk carregado")
        }
    }

    /**
     * Inicia a escuta. `onResult` recebe o texto final; `onPartial` o parcial.
     * Em modo comando, encerra após silêncio prolongado.
     */
    fun start(
        grammar: String?,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        commandTimeoutMs: Long = 8_000,
        silenceStopMs: Long = 1_100,
    ): Boolean {
        if (running) stop()
        val loaded = loadModel()
        if (loaded.isFailure) {
            onError(loaded.exceptionOrNull()?.message ?: "modelo de voz ausente")
            return false
        }
        val loadedModel = model ?: return false
        if (!hasPermission()) {
            onError("permissão de microfone não concedida")
            return false
        }
        return runCatching {
            recognizer = if (grammar.isNullOrBlank()) {
                Recognizer(loadedModel, SAMPLE_RATE.toFloat())
            } else {
                Recognizer(loadedModel, SAMPLE_RATE.toFloat(), grammar)
            }
            commandMode = grammar.isNullOrBlank()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE / 2)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            record = recorder
            running = true
            thread = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val buffer = ShortArray(2048)
                var heardSpeech = false
                var lastVoiceAt = 0L
                val startedAt = System.currentTimeMillis()
                try {
                    recorder.startRecording()
                    while (running) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        val rec = recognizer ?: break
                        val finished = rec.acceptWaveForm(buffer, read)
                        val now = System.currentTimeMillis()
                        var energy = 0.0
                        for (i in 0 until read) {
                            val sample = buffer[i].toDouble()
                            energy += sample * sample
                        }
                        val rms = kotlin.math.sqrt(energy / read.coerceAtLeast(1))
                        if (rms > 900) {
                            heardSpeech = true
                            lastVoiceAt = now
                        }
                        if (finished) {
                            val text = extract(rec.result, "text")
                            if (text.isNotBlank()) {
                                onResult(text)
                                if (commandMode) break
                            }
                        } else {
                            val partial = extract(rec.partialResult, "partial")
                            if (partial.isNotBlank()) onPartial(partial)
                        }
                        if (commandMode) {
                            val silentFor = now - maxOf(lastVoiceAt, startedAt)
                            if (heardSpeech && silentFor > silenceStopMs) {
                                val text = extract(rec.finalResult, "text")
                                if (text.isNotBlank()) onResult(text)
                                break
                            }
                            if (now - startedAt > commandTimeoutMs) {
                                val text = extract(rec.finalResult, "text")
                                if (text.isNotBlank()) onResult(text)
                                break
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "falha na captura", t)
                    onError(t.message ?: "falha de áudio")
                } finally {
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                    record = null
                    running = false
                    runCatching { recognizer?.close() }
                    recognizer = null
                }
            }, "glia-voice").apply { start() }
            true
        }.getOrElse {
            onError(it.message ?: "não foi possível iniciar o microfone")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        thread?.join(400)
        thread = null
    }

    fun close() {
        stop()
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    private fun extract(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(key) }.getOrDefault("")
    }
}

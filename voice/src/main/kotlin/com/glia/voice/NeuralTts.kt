package com.glia.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Voz neural offline: Piper (VITS) pt-BR "faber medium" quantizada em int8,
 * rodando em CPU pelo sherpa-onnx/onnxruntime.
 *
 * O modelo e o espeak-ng-data vêm nos assets e são copiados uma única vez
 * para `filesDir/tts/faber` (o sherpa-onnx precisa de caminhos reais para o
 * ONNX e para os dicionários fonéticos).
 *
 * ~18 MB de modelo + ~18 MB de fonemas; síntese em tempo real no celular,
 * sem rede e sem voz do sistema.
 */
class NeuralTts(private val context: Context) {

    companion object {
        private const val TAG = "NeuralTts"
        private const val ASSET_DIR = "tts/faber"
        private const val MODEL_NAME = "pt_BR-faber-medium.onnx"
        private const val TOKENS_NAME = "tokens.txt"
        private const val ESPEAK_DIR = "espeak-ng-data"
        private const val VERSION = "faber-int8-1"

        const val DISPLAY_NAME = "Piper faber int8 (pt-BR)"
    }

    @Volatile
    private var engine: OfflineTts? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopRequested = false

    private val lock = Any()

    val isReady: Boolean get() = engine != null
    val lastFailure: String? get() = failure

    fun modelDir(): File = File(context.filesDir, "tts/faber")

    /** Extrai os assets e cria o sintetizador. Idempotente e seguro entre threads. */
    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext true
        synchronized(lock) {
            if (engine != null) return@withContext true
            runCatching {
                val dir = ensureExtracted()
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, MODEL_NAME).absolutePath,
                            tokens = File(dir, TOKENS_NAME).absolutePath,
                            dataDir = File(dir, ESPEAK_DIR).absolutePath,
                        ),
                        numThreads = 2,
                        debug = false,
                    ),
                    maxNumSentences = 1,
                )
                engine = OfflineTts(config = config)
                failure = null
                Log.i(TAG, "voz neural pronta (${engine?.sampleRate()} Hz)")
            }.onFailure {
                Log.e(TAG, "não foi possível iniciar a voz neural", it)
                failure = it.message ?: it::class.java.simpleName
            }
            engine != null
        }
    }

    /** Sintetiza e reproduz. Devolve false quando a voz neural não está disponível. */
    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false
        if (!prepare()) return@withContext false
        val tts = engine ?: return@withContext false
        stopRequested = false
        val audio = runCatching { tts.generate(text, 0, 1.0f) }.getOrElse {
            Log.e(TAG, "falha ao sintetizar", it)
            failure = it.message ?: it::class.java.simpleName
            return@withContext false
        }
        val samples = audio.samples
        val sampleRate = audio.sampleRate
        if (samples.isEmpty() || sampleRate <= 0) return@withContext false

        val pcm = ShortArray(samples.size) { index ->
            ((samples[index].coerceIn(-1f, 1f)) * 32_767f).toInt().toShort()
        }
        play(pcm, sampleRate)
        true
    }

    private suspend fun play(pcm: ShortArray, sampleRate: Int) {
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track = player
        try {
            player.write(pcm, 0, pcm.size)
            player.play()
            // Acompanha o ponteiro de reprodução para respeitar STOP/tela desligada.
            while (!stopRequested && player.playbackHeadPosition < pcm.size) {
                delay(50)
            }
        } finally {
            runCatching { player.stop() }
            runCatching { player.release() }
            if (track === player) track = null
        }
    }

    fun stop() {
        stopRequested = true
        val player = track ?: return
        runCatching { player.pause() }
        runCatching { player.flush() }
    }

    fun shutdown() {
        stop()
        synchronized(lock) {
            runCatching { engine?.release() }
            engine = null
        }
    }

    // --------------------------------------------------------------- assets

    private fun ensureExtracted(): File {
        val dir = modelDir()
        val marker = File(dir, ".ready-$VERSION")
        if (marker.isFile) return dir
        dir.mkdirs()
        copyAsset(MODEL_NAME, File(dir, MODEL_NAME))
        copyAsset(TOKENS_NAME, File(dir, TOKENS_NAME))
        copyTree(ESPEAK_DIR, File(dir, ESPEAK_DIR))
        marker.writeText(VERSION)
        Log.i(TAG, "assets da voz extraídos em ${dir.absolutePath}")
        return dir
    }

    private fun copyAsset(name: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open("$ASSET_DIR/$name").use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    private fun copyTree(assetDir: String, targetDir: File) {
        val children = context.assets.list("$ASSET_DIR/$assetDir").orEmpty()
        if (children.isEmpty()) {
            copyAsset(assetDir, targetDir)
            return
        }
        targetDir.mkdirs()
        for (child in children) {
            copyTree("$assetDir/$child", File(targetDir, child))
        }
    }
}

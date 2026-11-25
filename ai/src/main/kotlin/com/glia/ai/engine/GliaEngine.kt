package com.glia.ai.engine

import android.util.Log
import com.glia.ai.LlmEngine
import com.glia.ai.SamplingParams
import java.io.File

/**
 * Motor local: GGUF via mmap + kernels NEON/TQ2_0 num núcleo C++.
 *
 * - O arquivo de pesos nunca é copiado para o heap: mmap com MAP_PRIVATE.
 * - O KV cache é fp32 e cabe em poucos MB com contexto curto (512–1024).
 * - Ao receber pressão de memória, `close()` desanexa o mmap; o recarregamento
 *   custa apenas page faults, não I/O completo.
 */
class GliaEngine private constructor(
    private val handle: Long,
    val info: String,
    override val contextSize: Int,
    private val eosToken: Int,
    private val bosToken: Int,
) : LlmEngine {

    private val lock = Any()
    @Volatile private var closed = false

    override val label: String = "Bonsai local · $info"
    override val isReady: Boolean get() = !closed && handle != 0L

    override fun generate(
        prompt: String,
        params: SamplingParams,
        onToken: (String) -> Boolean,
    ): String = synchronized(lock) {
        if (!isReady) return ""
        val ids = GliaNative.nativeTokenize(handle, prompt) ?: return ""
        if (ids.isEmpty()) return ""

        val reserve = params.maxTokens.coerceAtLeast(32)
        var promptIds = ids.toList()
        val budget = (contextSize - reserve).coerceAtLeast(64)
        if (promptIds.size > budget) {
            promptIds = promptIds.takeLast(budget)
        }

        GliaNative.nativeReset(handle)
        var total = listOf(bosToken) + promptIds
        var nPast = GliaNative.nativeEval(handle, intArrayOf(bosToken), 0)
        nPast = GliaNative.nativeEval(handle, promptIds.toIntArray(), nPast)
        if (nPast >= contextSize) return ""

        val generated = ArrayList<Int>(params.maxTokens)
        var emitted = ""
        val builder = StringBuilder()

        for (i in 0 until params.maxTokens) {
            if (closed) break
            val recent = total.takeLast(256).toIntArray()
            val next = GliaNative.nativeSample(
                handle,
                params.temperature,
                params.topK,
                params.topP,
                params.repeatPenalty,
                recent,
            )
            if (next < 0 || next == eosToken) break
            generated.add(next)
            total = total + next

            val text = GliaNative.nativeDecode(handle, generated.toIntArray())
            if (text.length > emitted.length) {
                val delta = text.substring(emitted.length)
                emitted = text
                builder.append(delta)
                if (!onToken(delta)) break
            }
            nPast = GliaNative.nativeEval(handle, intArrayOf(next), nPast)
            if (nPast >= contextSize) break
        }

        builder.toString()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (handle != 0L) GliaNative.nativeFreeModel(handle)
        }
    }

    companion object {
        private const val TAG = "GliaEngine"

        @Volatile
        private var current: GliaEngine? = null

        fun current(): GliaEngine? = current

        /** Carrega (ou reutiliza) o modelo ativo. Seguro chamar de thread de IO. */
        @Synchronized
        fun obtain(modelFile: File, requestedContext: Int = 1024, threads: Int = 4): Result<GliaEngine> {
            current?.let { if (it.isReady) return Result.success(it) }
            if (!GliaNative.available) {
                return Result.failure(IllegalStateException(GliaNative.error ?: "JNI indisponível"))
            }
            val context = MemoryGovernor.recommendContext(requestedContext)
            val handle = GliaNative.nativeLoadModel(modelFile.absolutePath, context, threads)
            if (handle == 0L) {
                return Result.failure(IllegalStateException("não foi possível abrir o modelo"))
            }
            if (!GliaNative.nativeIsReady(handle)) {
                val error = runCatching { GliaNative.nativeLastError(handle) }
                    .getOrDefault("modelo incompatível")
                GliaNative.nativeFreeModel(handle)
                return Result.failure(IllegalStateException(error))
            }
            val info = runCatching { GliaNative.nativeConfig(handle) }.getOrDefault("")
            val engine = GliaEngine(
                handle = handle,
                info = info,
                contextSize = GliaNative.nativeContextSize(handle),
                eosToken = GliaNative.nativeEosToken(handle),
                bosToken = GliaNative.nativeBosToken(handle),
            )
            Log.i(TAG, "modelo pronto ($info) ctx=${engine.contextSize}")
            current = engine
            return Result.success(engine)
        }

        fun release() {
            current?.close()
            current = null
        }

        /** Linhas residentes do processo, em MB (para o painel de diagnóstico). */
        fun rssMb(): Int {
            if (!GliaNative.available) return 0
            return runCatching { GliaNative.nativeRssKb() / 1024 }.getOrDefault(0)
        }
    }
}

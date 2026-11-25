package com.glia.ai.engine

/** Ponte JNI para o núcleo C++ (mmap + kernels NEON). */
internal object GliaNative {

    @Volatile
    private var loaded = false
    private var loadError: String? = null

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded
        }

    val error: String?
        get() {
            ensureLoaded()
            return loadError
        }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("glia_llm")
            loaded = true
        } catch (t: Throwable) {
            loadError = t.message ?: "biblioteca nativa indisponível"
        }
    }

    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeLastError(handle: Long): String
    external fun nativeIsReady(handle: Long): Boolean
    external fun nativeFreeModel(handle: Long)
    external fun nativeTokenize(handle: Long, text: String): IntArray?
    external fun nativePiece(handle: Long, id: Int): String
    external fun nativeDecode(handle: Long, ids: IntArray): String
    external fun nativeEval(handle: Long, ids: IntArray, nPast: Int): Int
    external fun nativeSample(
        handle: Long,
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float,
        recent: IntArray
    ): Int

    external fun nativeReset(handle: Long)
    external fun nativeContextSize(handle: Long): Int
    external fun nativeEosToken(handle: Long): Int
    external fun nativeBosToken(handle: Long): Int
    external fun nativeConfig(handle: Long): String
    external fun nativeRssKb(): Int
}

package com.glia.assistant

import android.content.Context
import com.glia.ai.LlmEngine
import com.glia.ai.engine.GliaEngine
import com.glia.ai.engine.MemoryGovernor
import com.glia.ai.engine.ModelStore
import com.glia.ai.engine.OfflinePersonaEngine
import com.glia.calendar.CalendarRepository
import com.glia.devicecontrol.DeviceActions
import com.glia.devicecontrol.DeviceStateReader
import com.glia.devicecontrol.ToolExecutor
import com.glia.ui.model.ModelStatus
import com.glia.voice.TtsSpeaker
import com.glia.voice.VoiceController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Injeção de dependências manual (sem Hilt): um grafo por processo.
 * Nada aqui abre rede.
 */
class AppGraph(private val context: Context) {

    val settings = SettingsRepository(context)
    val calendar = CalendarRepository(context)
    val reader = DeviceStateReader(context)
    val deviceActions = DeviceActions(context)
    val tools = ToolExecutor(context, calendar, reader, deviceActions)
    val voice = VoiceController(context)
    val tts = TtsSpeaker(context)
    val router = AssistantRouter(context, calendar, reader)
    val models = ModelStore

    @Volatile
    private var engine: LlmEngine = OfflinePersonaEngine()

    @Volatile
    var lastEngineError: String? = null
        private set

    val currentEngine: LlmEngine get() = engine

    fun modelStatus(): ModelStatus {
        val file = ModelStore.activeModel(context)
        val bundled = if (file == null) ModelStore.bundledSize(context) else 0L
        val bytes = file?.length() ?: bundled
        return if (bytes > 0L) {
            val size = "%.0f".format(bytes / (1024.0 * 1024.0))
            val origin = if (file == null) "embutido no APK · Q2_0" else "GGUF local"
            val detail = when {
                engine.isReady ->
                    "$origin com $size MB · llama.cpp · contexto ${modelContext()}"
                lastEngineError != null ->
                    "$origin com $size MB, mas o motor recusou: $lastEngineError"
                else -> "$origin com $size MB · carregando…"
            }
            ModelStatus(
                installed = true,
                title = "Bonsai 2B · $size MB",
                detail = detail,
                path = file?.absolutePath ?: "assets/${ModelStore.ASSET_MODEL}",
            )
        } else {
            ModelStatus(
                installed = false,
                title = "Modelo local ausente",
                detail = "Envie o GGUF ternário para ${ModelStore.modelsDir(context).absolutePath} " +
                    "e toque em Recarregar.",
                path = null,
            )
        }
    }

    private fun modelContext(): Int = GliaEngine.current()?.contextSize ?: 0

    /** Carrega o modelo se houver arquivo. Deve rodar em thread de IO. */
    suspend fun ensureEngine(): LlmEngine = withContext(Dispatchers.IO) {
        val existing = GliaEngine.current()
        if (existing != null && existing.isReady) {
            engine = existing
            return@withContext existing
        }
        val file: File? = ModelStore.activeModel(context) ?: ModelStore.bundledModel(context)
        if (file == null) {
            lastEngineError = "nenhum arquivo .gguf em ${ModelStore.modelsDir(context).absolutePath}"
            engine = OfflinePersonaEngine()
            return@withContext engine
        }
        // Ordem de tentativa: o que a RAM sugere, caindo até 512 tokens.
        // Em aparelho apertado ainda vale tentar com contexto curto em vez de
        // cair direto no modo essencial.
        val preferred = when {
            MemoryGovernor.availableRamMb(context) < 1400 -> 512
            MemoryGovernor.availableRamMb(context) < 3000 -> 1024
            else -> 2048
        }
        val ladder = linkedSetOf(preferred, 1024, 512).filter { it <= preferred }
        val threads = recommendThreads()
        var lastError: String? = null
        for (candidate in ladder) {
            val result = GliaEngine.obtain(modelFile = file, requestedContext = candidate, threads = threads)
            if (result.isSuccess) {
                engine = result.getOrThrow()
                lastEngineError = null
                return@withContext engine
            }
            lastError = result.exceptionOrNull()?.message
            android.util.Log.w("AppGraph", "carga com ctx=$candidate falhou: $lastError")
        }
        lastEngineError = lastError
        engine = OfflinePersonaEngine()
        engine
    }

    /** 4 threads no Helio G96 (2 big + 6 little): usa metade dos núcleos, no máximo 4. */
    private fun recommendThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    fun releaseEngine() {
        if (engine is GliaEngine) GliaEngine.release()
        engine = OfflinePersonaEngine()
    }

    fun diagnostics(): String {
        val model = modelStatus()
        val ram = MemoryGovernor.availableRamMb(context)
        val rss = GliaEngine.rssMb()
        val wake = if (voice.isWakeEnabled()) "ligada" else "desligada"
        val accessibility = com.glia.devicecontrol.AccessibilityBridge.connected
        return buildString {
            append("modelo: ").append(if (model.installed) "instalado" else "ausente").append('\n')
            append("motor: ").append(currentEngine.label).append('\n')
            lastEngineError?.let { append("erro do motor: ").append(it).append('\n') }
            append("RAM livre: ").append(ram).append(" MB")
            if (rss > 0) append(" · RSS ").append(rss).append(" MB")
            append('\n')
            append("threads: ").append(recommendThreads())
            append('\n')
            append("escuta: ").append(wake)
            append(" · acessibilidade: ").append(if (accessibility) "ativa" else "inativa")
            append('\n')
            append("vosk: ").append(if (voice.isModelInstalled()) "pronto" else "sem modelo pt-BR")
            append('\n')
            append("voz: ").append(tts.engineLabel)
        }
    }

    fun modelInstructions(): String =
        "O app já vem com o Ternary Bonsai 2 2B (Q2_0) embutido no APK e roda " +
            "offline via llama.cpp.\n" +
            "Para usar outro GGUF, conecte o aparelho por USB e rode:\n" +
            "   adb push modelo.gguf /sdcard/Android/data/com.glia.assistant/files/models/\n" +
            "e toque em Recarregar.\n" +
            "A voz de resposta é neural (Piper faber pt-BR, int8) e já vem no APK; " +
            "se ela falhar, o app usa o TTS do aparelho.\n" +
            "Para reconhecer a fala, instale o Vosk pt-BR em " +
            "${context.filesDir}/vosk-model (adb push da pasta do modelo)."
}

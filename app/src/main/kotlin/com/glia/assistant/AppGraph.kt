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

    val currentEngine: LlmEngine get() = engine

    fun modelStatus(): ModelStatus {
        val file = ModelStore.activeModel(context)
        return if (file != null) {
            ModelStatus(
                installed = true,
                title = "Ternary Bonsai 2 · 2B",
                detail = "GGUF local com ${"%.0f".format(file.length() / (1024.0 * 1024.0))} MB · " +
                    "mmap + kernels NEON · contexto ${modelContext()}",
                path = file.absolutePath,
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
        val file: File? = ModelStore.activeModel(context) ?: ModelStore.ensureAssetModel(context)
        if (file == null) {
            engine = OfflinePersonaEngine()
            return@withContext engine
        }
        if (MemoryGovernor.isLowMemory(context)) {
            engine = OfflinePersonaEngine()
            return@withContext engine
        }
        val result = GliaEngine.obtain(
            modelFile = file,
            requestedContext = if (MemoryGovernor.availableRamMb(context) < 1400) 512 else 1024,
            threads = 4,
        )
        engine = result.getOrElse { OfflinePersonaEngine() }
        engine
    }

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
            append("RAM livre: ").append(ram).append(" MB")
            if (rss > 0) append(" · RSS ").append(rss).append(" MB")
            append('\n')
            append("escuta: ").append(wake)
            append(" · acessibilidade: ").append(if (accessibility) "ativa" else "inativa")
            append('\n')
            append("vosk: ").append(if (voice.isModelInstalled()) "pronto" else "sem modelo pt-BR")
        }
    }

    fun modelInstructions(): String =
        "1. Baixe o GGUF do Bonsai 2B (ternário/llama.cpp).\n" +
            "2. Conecte o Redmi por USB e rode:\n" +
            "   adb push modelo.gguf /sdcard/Android/data/com.glia.assistant/files/models/\n" +
            "3. Em Ajustes → Modelo, toque em Recarregar.\n" +
            "Para a voz offline, instale o Vosk pt-BR em " +
            "${context.filesDir}/vosk-model (adb push da pasta do modelo)."
}

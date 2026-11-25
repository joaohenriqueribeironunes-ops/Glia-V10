package com.glia.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fachada usada pela interface e pelo tile: dispara a escuta no serviço
 * (evita disputa pelo microfone) e devolve o comando reconhecido.
 */
class VoiceController(private val context: Context) {

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun isWakeEnabled(): Boolean = VoiceBus.wakeEnabled

    fun isModelInstalled(): Boolean = VoskModelLocator.isInstalled(context)

    fun enableWake() {
        if (!hasMicPermission()) return
        VoiceBus.wakeEnabled = true
        WakeWordService.start(context)
    }

    fun disableWake() {
        VoiceBus.wakeEnabled = false
        WakeWordService.stop(context)
    }

    /** Abre o microfone para um comando único. */
    fun listenOnce() {
        if (!hasMicPermission()) return
        WakeWordService.listenNow(context)
    }

    fun pauseWake() = WakeWordService.pause(context)

    fun resumeWake() {
        if (VoiceBus.wakeEnabled) WakeWordService.start(context)
    }

    suspend fun awaitCommand(timeoutMs: Long = 12_000): String? =
        withTimeoutOrNull(timeoutMs) { VoiceBus.commands.first() }
}

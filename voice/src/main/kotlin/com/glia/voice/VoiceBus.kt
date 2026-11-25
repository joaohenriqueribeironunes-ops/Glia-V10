package com.glia.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class VoiceState { IDLE, LISTENING_WAKE, LISTENING_COMMAND, THINKING, SPEAKING }

/**
 * Barramento único entre serviço de escuta, tile, UI e orquestrador.
 * Estado é observável; comandos reconhecidos são eventos (SharedFlow).
 */
object VoiceBus {

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val commands: SharedFlow<String> = _commands

    private val _wakeDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val wakeDetected: SharedFlow<Unit> = _wakeDetected

    @Volatile
    var wakeEnabled: Boolean = false

    @Volatile
    private var uiOwner: String? = null

    private var lastClaimAt = 0L

    /** Quem está em tela: esse consumidor tem prioridade sobre os comandos. */
    fun acquireUi(owner: String) {
        uiOwner = owner
    }

    fun releaseUi(owner: String) {
        if (uiOwner == owner) uiOwner = null
    }

    /**
     * Garante que cada comando seja tratado por um único consumidor, mesmo
     * com a UI principal e o painel sobre a tela bloqueada vivos ao mesmo tempo.
     */
    @Synchronized
    fun claimCommand(requester: String): Boolean {
        val owner = uiOwner
        if (owner != null && owner != requester) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastClaimAt < 700L) return false
        lastClaimAt = now
        return true
    }

    fun setState(value: VoiceState) {
        _state.value = value
    }

    fun setPartial(value: String) {
        _partial.value = value
    }

    fun emitCommand(text: String) {
        if (text.isBlank()) return
        _partial.value = ""
        _commands.tryEmit(text.trim())
    }

    fun emitWake() {
        _state.value = VoiceState.LISTENING_COMMAND
        _wakeDetected.tryEmit(Unit)
    }

    fun reset() {
        _partial.value = ""
        _state.value = if (wakeEnabled) VoiceState.LISTENING_WAKE else VoiceState.IDLE
    }
}

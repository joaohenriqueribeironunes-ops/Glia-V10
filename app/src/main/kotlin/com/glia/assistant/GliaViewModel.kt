package com.glia.assistant

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glia.ai.ChatTurn
import com.glia.ai.Role
import com.glia.devicecontrol.AccessibilityBridge
import com.glia.ui.model.AssistantPhase
import com.glia.ui.model.ChatMessage
import com.glia.ui.model.GliaActions
import com.glia.ui.model.GliaUiState
import com.glia.ui.model.PendingConfirmation
import com.glia.ui.model.PermissionStatus
import com.glia.ui.model.SettingKey
import com.glia.voice.VoiceBus
import com.glia.voice.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GliaViewModel(application: Application) : AndroidViewModel(application) {

    private val graph: AppGraph = (application as GliaApplication).graph
    private val engine = ConversationEngine(graph)

    private val _state = MutableStateFlow(
        GliaUiState(settings = graph.settings.settings.value),
    )
    val state: StateFlow<GliaUiState> = _state

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 12)
    val events: SharedFlow<AppEvent> = _events

    private val history = ArrayList<ChatTurn>()
    private var messageId = 0L
    private var sendJob: Job? = null

    val actions: GliaActions = GliaActions(
        onDraftChange = { text -> _state.update { it.copy(draft = text) } },
        onSend = { send() },
        onSendText = { send(it) },
        onMicToggle = { onMicToggle() },
        onStopSpeaking = { graph.tts.stop() },
        onToggleSetting = ::onToggleSetting,
        onPermission = ::requestPermission,
        onOpenAppSettings = {
            emit(AppEvent.OpenSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true))
        },
        onOpenAccessibility = {
            emit(AppEvent.OpenSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, false))
        },
        onOpenNotificationAccess = {
            emit(AppEvent.OpenSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false))
        },
        onOpenBattery = {
            emit(AppEvent.OpenSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, false))
        },
        onOpenAutostart = { _events.tryEmit(AppEvent.OpenAutostart) },
        onReloadModel = { reloadModel() },
        onModelHelp = {
            _events.tryEmit(
                AppEvent.ShowText("Instalar o modelo local", graph.modelInstructions()),
            )
        },
        onFinishOnboarding = {
            graph.settings.setOnboardingDone()
            _state.update { it.copy(showOnboarding = false) }
        },
        onConfirm = { accepted ->
            _state.update { it.copy(pendingConfirmation = null) }
            engine.resolveConfirmation(accepted)
        },
        onClearHistory = { clearHistory() },
    )

    init {
        observeVoice()
        observeSettings()
        refresh()
        viewModelScope.launch { graph.tts.initialize() }
    }

    // ------------------------------------------------------------------ UI state

    fun refresh() {
        _state.update {
            it.copy(
                permissions = buildPermissions(),
                model = graph.modelStatus(),
                diagnostic = graph.diagnostics(),
                showOnboarding = !graph.settings.onboardingDone(),
            )
        }
        refreshPhase()
        viewModelScope.launch {
            graph.ensureEngine()
            _state.update {
                it.copy(model = graph.modelStatus(), diagnostic = graph.diagnostics())
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            graph.settings.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
                if (settings.wakeWord && !VoiceBus.wakeEnabled && graph.voice.hasMicPermission()) {
                    graph.voice.enableWake()
                }
                if (!settings.wakeWord && VoiceBus.wakeEnabled) graph.voice.disableWake()
            }
        }
    }

    private fun observeVoice() {
        viewModelScope.launch {
            VoiceBus.state.collect { voice -> refreshPhase(voice) }
        }
        viewModelScope.launch {
            VoiceBus.partial.collect { partial ->
                _state.update { it.copy(partialTranscript = partial) }
            }
        }
        viewModelScope.launch {
            VoiceBus.commands.collect { command ->
                val pending = _state.value.pendingConfirmation
                if (pending != null) {
                    val lower = command.lowercase()
                    val yes = lower.startsWith("sim") || lower.contains("pode") ||
                        lower.contains("confirmo") || lower.contains("claro") ||
                        lower.contains("manda") || lower.contains("liga")
                    val no = lower.startsWith("não") || lower.startsWith("nao") ||
                        lower.contains("cancela") || lower.contains("deixa") ||
                        lower.contains("não quero")
                    if (yes || no) {
                        engine.resolveConfirmation(yes)
                        _state.update { it.copy(pendingConfirmation = null) }
                        return@collect
                    }
                }
                send(command, fromVoice = true)
            }
        }
    }

    private fun refreshPhase(voice: VoiceState = VoiceBus.state.value) {
        val mapped = when (voice) {
            VoiceState.LISTENING_WAKE, VoiceState.LISTENING_COMMAND -> AssistantPhase.LISTENING
            VoiceState.SPEAKING -> AssistantPhase.SPEAKING
            VoiceState.THINKING -> AssistantPhase.THINKING
            VoiceState.IDLE -> if (_state.value.busy) AssistantPhase.THINKING else AssistantPhase.IDLE
        }
        _state.update { it.copy(phase = mapped) }
    }

    // ------------------------------------------------------------------ conversa

    fun send(text: String = "", fromVoice: Boolean = false) {
        val message = text.ifBlank { _state.value.draft }.trim()
        if (message.isEmpty() || _state.value.busy) return

        val pending = _state.value.pendingConfirmation
        if (pending != null) {
            val lower = message.lowercase()
            val no = lower.startsWith("não") || lower.startsWith("nao") || lower.contains("cancela")
            val yes = lower.startsWith("sim") || lower.contains("pode") || lower.contains("claro")
            if (yes || no) {
                appendUser(message)
                _state.update { it.copy(draft = "", pendingConfirmation = null) }
                engine.resolveConfirmation(yes)
                return
            }
        }

        _state.update { it.copy(draft = "", pendingConfirmation = null) }
        appendUser(message)
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, statusLine = "preparando") }
            refreshPhase()
            var assistantId: Long? = null
            val streamed = StringBuilder()
            var looksLikeJson = false

            val listener = object : ConversationEngine.Listener {
                override fun onStatus(text: String) {
                    _state.update { it.copy(statusLine = text) }
                }

                override fun onDelta(text: String) {
                    streamed.append(text)
                    val trimmed = streamed.trimStart()
                    if (trimmed.startsWith("{") || trimmed.startsWith("```")) looksLikeJson = true
                    if (looksLikeJson) return
                    if (assistantId == null) {
                        assistantId = appendAssistant("", streaming = true)
                    }
                    updateMessage(assistantId!!, streamed.toString(), streaming = true)
                }

                override fun onConfirmation(question: String, tool: String) {
                    _state.update {
                        it.copy(pendingConfirmation = PendingConfirmation(question, tool))
                    }
                    viewModelScope.launch {
                        // Fala primeiro; só abre o microfone quando o TTS terminar,
                        // para não transcrever a própria pergunta.
                        if (_state.value.settings.speakReplies) graph.tts.speak(question)
                        delay(300)
                        if (graph.voice.hasMicPermission()) {
                            _state.update { it.copy(phase = AssistantPhase.LISTENING) }
                            graph.voice.listenOnce()
                        }
                    }
                }

                override fun onConfirmationCleared() {
                    _state.update { it.copy(pendingConfirmation = null) }
                }
            }

            try {
                val reply = engine.handle(message, history.toList(), listener)
                if (assistantId == null) assistantId = appendAssistant(reply)
                else updateMessage(assistantId!!, reply, streaming = false)

                history.add(ChatTurn(Role.USER, message))
                history.add(ChatTurn(Role.ASSISTANT, reply))
                while (history.size > 24) history.removeAt(0)

                if (_state.value.settings.speakReplies) {
                    graph.tts.speak(reply)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                val text = "Tive um problema: ${t.message ?: "erro desconhecido"}"
                if (assistantId == null) appendAssistant(text, error = true)
                else updateMessage(assistantId!!, text, error = true)
            } finally {
                _state.update { it.copy(busy = false, statusLine = "") }
                refreshPhase()
                _state.update { it.copy(diagnostic = graph.diagnostics()) }
            }
        }
    }

    private fun appendUser(text: String) {
        _state.update { current ->
            current.copy(
                messages = current.messages + ChatMessage(
                    id = ++messageId,
                    fromUser = true,
                    text = text,
                ),
            )
        }
    }

    private fun appendAssistant(
        text: String,
        streaming: Boolean = false,
        error: Boolean = false,
    ): Long {
        val id = ++messageId
        _state.update { current ->
            current.copy(
                messages = current.messages + ChatMessage(
                    id = id,
                    fromUser = false,
                    text = text,
                    streaming = streaming,
                    error = error,
                ),
            )
        }
        return id
    }

    private fun updateMessage(
        id: Long,
        text: String,
        streaming: Boolean = false,
        error: Boolean = false,
    ) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map {
                    if (it.id == id) it.copy(text = text, streaming = streaming, error = error) else it
                },
            )
        }
    }

    private fun clearHistory() {
        history.clear()
        _state.update { it.copy(messages = emptyList()) }
    }

    private fun reloadModel() {
        viewModelScope.launch {
            _state.update { it.copy(statusLine = "carregando modelo") }
            graph.ensureEngine()
            _state.update {
                it.copy(
                    model = graph.modelStatus(),
                    diagnostic = graph.diagnostics(),
                    statusLine = "",
                )
            }
        }
    }

    // ------------------------------------------------------------------ voz

    private fun onMicToggle() {
        val phase = _state.value.phase
        if (phase == AssistantPhase.SPEAKING || _state.value.busy) {
            graph.tts.stop()
            return
        }
        if (phase == AssistantPhase.LISTENING) return
        if (!graph.voice.hasMicPermission()) {
            requestPermission("mic")
            return
        }
        if (!graph.voice.isModelInstalled()) {
            _events.tryEmit(
                AppEvent.Message("Falta o modelo de voz pt-BR (Vosk). Veja Ajustes → Modelo."),
            )
            return
        }
        graph.voice.listenOnce()
    }

    private fun onToggleSetting(key: SettingKey, value: Boolean) {
        when (key) {
            SettingKey.WAKE_WORD -> {
                if (value && !graph.voice.hasMicPermission()) {
                    requestPermission("mic")
                    _events.tryEmit(AppEvent.Message("Autorize o microfone para a escuta contínua."))
                    return
                }
                if (value && !graph.voice.isModelInstalled()) {
                    _events.tryEmit(
                        AppEvent.Message(
                            "Falta o modelo de voz pt-BR (Vosk) em files/vosk-model. " +
                                "Veja Ajustes → Modelo.",
                        ),
                    )
                    return
                }
                graph.settings.set(key, value)
                if (value) {
                    VoiceBus.wakeEnabled = true
                    graph.voice.enableWake()
                    _events.tryEmit(
                        AppEvent.Message("Escuta contínua ligada. Em HyperOS, libere a bateria do app."),
                    )
                } else {
                    graph.voice.disableWake()
                }
            }
            else -> {
                graph.settings.set(key, value)
                if (key == SettingKey.SPEAK_REPLIES && !value) graph.tts.stop()
            }
        }
        _state.update { it.copy(diagnostic = graph.diagnostics()) }
    }

    // ------------------------------------------------------------------ permissões

    fun onResume() {
        refresh()
        if (_state.value.settings.wakeWord && !VoiceBus.wakeEnabled) {
            graph.voice.enableWake()
        }
    }

    private fun requestPermission(key: String) {
        when (key) {
            "mic" -> emitPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
            "calendar" -> emitPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
            "contacts" -> emitPermissions(arrayOf(Manifest.permission.READ_CONTACTS))
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    emitPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    emit(AppEvent.OpenSystemSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, true))
                }
            }
            "accessibility" ->
                emit(AppEvent.OpenSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, false))
            "notification_listener" ->
                emit(AppEvent.OpenSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false))
            "write_settings" -> _events.tryEmit(
                AppEvent.OpenWriteSettingsSettings(getApplication<Application>().packageName),
            )
        }
    }

    private fun emit(event: AppEvent) {
        _events.tryEmit(event)
    }

    private fun emitPermissions(permissions: Array<String>) {
        _events.tryEmit(AppEvent.RequestPermissions(permissions))
    }

    private fun buildPermissions(): List<PermissionStatus> {
        val context = getApplication<Application>()
        fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(
            context, permission,
        ) == PackageManager.PERMISSION_GRANTED

        val notificationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val listenerEnabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)

        return listOf(
            PermissionStatus(
                key = "mic",
                title = "Microfone",
                rationale = "Ouvir \"Glia\" e seus comandos de voz.",
                granted = granted(Manifest.permission.RECORD_AUDIO),
            ),
            PermissionStatus(
                key = "calendar",
                title = "Agenda",
                rationale = "Ler e criar compromissos.",
                granted = granted(Manifest.permission.READ_CALENDAR),
            ),
            PermissionStatus(
                key = "contacts",
                title = "Contatos",
                rationale = "Encontrar quem ligar ou para quem escrever.",
                granted = granted(Manifest.permission.READ_CONTACTS),
            ),
            PermissionStatus(
                key = "notifications",
                title = "Notificações",
                rationale = "Manter a escuta visível e resumir o que chegou.",
                granted = notificationEnabled,
            ),
            PermissionStatus(
                key = "accessibility",
                title = "Controle de tela",
                rationale = "Tocar, rolar, digitar e ler a tela de outros apps.",
                granted = AccessibilityBridge.connected,
                actionLabel = "Ativar",
            ),
            PermissionStatus(
                key = "notification_listener",
                title = "Acesso a notificações",
                rationale = "Ler as notificações ativas quando você pedir.",
                granted = listenerEnabled,
                actionLabel = "Ativar",
            ),
            PermissionStatus(
                key = "write_settings",
                title = "Modificar configurações",
                rationale = "Ajustar o brilho da tela por comando.",
                granted = Settings.System.canWrite(context),
                actionLabel = "Liberar",
            ),
        )
    }
}


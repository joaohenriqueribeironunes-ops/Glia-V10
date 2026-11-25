package com.glia.ui.model

/** Estado visual do assistente (também dirige o orbe). */
enum class AssistantPhase { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val error: Boolean = false,
)

enum class SettingKey { WAKE_WORD, SPEAK_REPLIES, GLASS, ANIMATIONS, DARK_THEME }

data class GliaSettings(
    val wakeWord: Boolean = false,
    val speakReplies: Boolean = true,
    val glass: Boolean = true,
    val animations: Boolean = true,
    val darkTheme: Boolean = true,
)

data class PermissionStatus(
    val key: String,
    val title: String,
    val rationale: String,
    val granted: Boolean,
    val special: Boolean = false,
    val actionLabel: String = "Permitir",
)

data class ModelStatus(
    val installed: Boolean,
    val title: String,
    val detail: String,
    val path: String? = null,
)

data class PendingConfirmation(
    val question: String,
    val toolName: String,
)

data class GliaUiState(
    val messages: List<ChatMessage> = emptyList(),
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val partialTranscript: String = "",
    val draft: String = "",
    val settings: GliaSettings = GliaSettings(),
    val permissions: List<PermissionStatus> = emptyList(),
    val model: ModelStatus = ModelStatus(false, "Modelo local", "verificando…"),
    val busy: Boolean = false,
    val statusLine: String = "",
    val pendingConfirmation: PendingConfirmation? = null,
    val showOnboarding: Boolean = false,
    val diagnostic: String = "",
)

/** Ações que a UI dispara; o app decide como executar. */
data class GliaActions(
    val onDraftChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onSendText: (String) -> Unit = {},
    val onMicToggle: () -> Unit = {},
    val onStopSpeaking: () -> Unit = {},
    val onToggleSetting: (SettingKey, Boolean) -> Unit = { _, _ -> },
    val onPermission: (String) -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onOpenAccessibility: () -> Unit = {},
    val onOpenNotificationAccess: () -> Unit = {},
    val onOpenBattery: () -> Unit = {},
    val onOpenAutostart: () -> Unit = {},
    val onReloadModel: () -> Unit = {},
    val onModelHelp: () -> Unit = {},
    val onFinishOnboarding: () -> Unit = {},
    val onConfirm: (Boolean) -> Unit = {},
    val onClearHistory: () -> Unit = {},
)

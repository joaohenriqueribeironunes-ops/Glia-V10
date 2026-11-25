package com.glia.assistant

/** Eventos de plataforma que só a Activity pode executar. */
sealed interface AppEvent {
    data class RequestPermissions(val permissions: Array<String>) : AppEvent
    data class OpenSystemSettings(val action: String, val withPackage: Boolean = false) : AppEvent
    data class OpenWriteSettingsSettings(val packageName: String) : AppEvent
    data object OpenAutostart : AppEvent
    data class ShowText(val title: String, val message: String) : AppEvent
    data class Message(val text: String) : AppEvent
}

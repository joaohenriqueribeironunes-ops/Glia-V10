package com.glia.assistant

import android.content.Context
import com.glia.ui.model.GliaSettings
import com.glia.ui.model.SettingKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("glia_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<GliaSettings> = _settings

    fun set(key: SettingKey, value: Boolean) {
        prefs.edit()
            .putBoolean(key.name, value)
            .apply()
        _settings.value = read()
    }

    fun setOnboardingDone() {
        prefs.edit().putBoolean(ONBOARDING, true).apply()
    }

    fun onboardingDone(): Boolean = prefs.getBoolean(ONBOARDING, false)

    private fun read(): GliaSettings = GliaSettings(
        wakeWord = prefs.getBoolean(SettingKey.WAKE_WORD.name, false),
        speakReplies = prefs.getBoolean(SettingKey.SPEAK_REPLIES.name, true),
        glass = prefs.getBoolean(SettingKey.GLASS.name, true),
        animations = prefs.getBoolean(SettingKey.ANIMATIONS.name, true),
        darkTheme = prefs.getBoolean(SettingKey.DARK_THEME.name, true),
    )

    companion object {
        private const val ONBOARDING = "onboarding_done"
    }
}

package com.glia.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.glia.voice.VoiceBus
import com.glia.voice.WakeWordService

/**
 * Reativa a escuta da palavra-chave após reinício, se o usuário habilitou.
 * No Android 14+ o sistema pode bloquear FGS de microfone iniciado no boot;
 * o app informa isso no onboarding e a escuta volta ao abrir o app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val settings = SettingsRepository(context)
        if (!settings.settings.value.wakeWord) return
        VoiceBus.wakeEnabled = true
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_START),
            )
        }
    }
}

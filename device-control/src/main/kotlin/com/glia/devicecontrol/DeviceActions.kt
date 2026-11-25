package com.glia.devicecontrol

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.glia.ai.tools.ToolOutcome
import java.time.LocalTime

/**
 * Ações de dispositivo usando apenas APIs oficiais. O que o Android não permite
 * a apps comuns (ligar/desligar Wi-Fi e Bluetooth por código) abre o painel
 * correspondente para o usuário confirmar com um toque.
 */
class DeviceActions(private val context: Context) {

    private val appsDirectory = AppsDirectory(context)
    private val contactsDirectory = ContactsDirectory(context)

    @Volatile
    private var torchCameraId: String? = null

    fun openApp(name: String): ToolOutcome {
        val app = appsDirectory.find(name)
            ?: return ToolOutcome(false, "Não encontrei o app \"$name\" instalado.", "Não achei esse app.")
        return if (appsDirectory.launch(app)) {
            ToolOutcome(true, "Abri ${app.label}.", "Abrindo ${app.label}.")
        } else {
            ToolOutcome(false, "Não consegui abrir ${app.label}.", "Não consegui abrir esse app.")
        }
    }

    fun setVolume(percent: Int): ToolOutcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolOutcome(false, "Áudio indisponível.")
        val target = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val value = (target * max) / 100
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
            .onFailure { return ToolOutcome(false, "Sem permissão para mudar o volume.") }
        return ToolOutcome(true, "Volume de mídia em $target%.", "Volume em $target por cento.")
    }

    fun setBrightness(percent: Int): ToolOutcome {
        val target = percent.coerceIn(5, 100)
        if (!Settings.System.canWrite(context)) {
            openSettings(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return ToolOutcome(
                false,
                "Preciso da permissão \"Modificar configurações do sistema\" para ajustar o brilho. Abri a tela.",
                "Preciso de uma permissão para ajustar o brilho. Abri a tela de configurações.",
            )
        }
        val raw = target * 255 / 100
        val ok = runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        }.getOrDefault(false)
        return if (ok) ToolOutcome(true, "Brilho em $target%.", "Brilho em $target por cento.")
        else ToolOutcome(false, "Não consegui ajustar o brilho.")
    }

    fun setFlashlight(on: Boolean): ToolOutcome {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolOutcome(false, "Lanterna indisponível.")
        return runCatching {
            val id = torchCameraId ?: manager.cameraIdList.firstOrNull { candidate ->
                val chars = manager.getCameraCharacteristics(candidate)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            if (id == null) ToolOutcome(false, "Este aparelho não tem lanterna.") else {
                torchCameraId = id
                manager.setTorchMode(id, on)
                ToolOutcome(true, if (on) "Lanterna ligada." else "Lanterna desligada.")
            }
        }.getOrElse {
            ToolOutcome(false, "Não consegui controlar a lanterna: ${it.message}")
        }
    }

    fun openWifiPanel(): ToolOutcome {
        val intent = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Abri o painel de Wi-Fi. O Android não deixa apps comuns ligarem o Wi-Fi sozinhos.")
    }

    fun openBluetoothPanel(): ToolOutcome {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Abri as configurações de Bluetooth.")
    }

    fun setDnd(on: Boolean): ToolOutcome {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolOutcome(false, "Modo não perturbe indisponível.")
        if (!nm.isNotificationPolicyAccessGranted) {
            launch(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Preciso de acesso ao modo não perturbe. Abri a tela para você permitir.",
            )
            return ToolOutcome(
                false,
                "Preciso de acesso à política de notificações para ativar o não perturbe.",
                "Preciso de uma permissão para o não perturbe. Abri a tela de configurações.",
            )
        }
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
        else NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(filter)
        return ToolOutcome(true, if (on) "Não perturbe ativado." else "Não perturbe desativado.")
    }

    fun setAlarm(hour: Int, minute: Int, label: String?): ToolOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Glia")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        return launch(intent, "Alarme definido para ${time}.")
    }

    fun setTimer(seconds: Int): ToolOutcome {
        val safe = seconds.coerceIn(1, 24 * 3600)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safe)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Glia")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val label = if (safe >= 60) "${safe / 60} minuto(s)" else "$safe segundo(s)"
        return launch(intent, "Timer de $label iniciado.")
    }

    fun playMusic(query: String?): ToolOutcome {
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            if (!query.isNullOrBlank()) putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
            putExtra(android.app.SearchManager.QUERY, query.orEmpty())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(search, 0) != null) {
            return launch(search, "Tocando música.")
        }
        val app = appsDirectory.find("música") ?: appsDirectory.find("youtube music") ?:
            appsDirectory.find("spotify")
        if (app != null && appsDirectory.launch(app)) {
            return ToolOutcome(true, "Abri ${app.label} para tocar música.", "Abrindo ${app.label}.")
        }
        return ToolOutcome(false, "Não encontrei um app de música instalado.")
    }

    fun call(target: String): ToolOutcome {
        val contact = contactsDirectory.findByNumberOrName(target)
        val number = contact?.number ?: target.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) return ToolOutcome(false, "Não encontrei \"$target\" nos contatos.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolOutcome(
                false,
                "Preciso da permissão de telefone para ligar.",
                "Preciso da permissão de telefone para ligar.",
            )
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Ligando para ${contact?.name ?: number}.")
    }

    fun sendMessage(target: String, message: String): ToolOutcome {
        val contact = contactsDirectory.findByNumberOrName(target)
        val number = contact?.number ?: target.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) return ToolOutcome(false, "Não encontrei \"$target\" nos contatos.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Sem permissão de SMS: abrimos o app de mensagens com o texto pronto.
        return launch(intent, "Mensagem preparada para ${contact?.name ?: number}.")
    }

    fun readScreen(): ToolOutcome {
        if (!AccessibilityBridge.connected) {
            return ToolOutcome(
                false,
                "A automação de tela ainda não está ativa. Ative em Ajustes → Acessibilidade → Glia.",
                "Preciso do serviço de acessibilidade para ler a tela.",
            )
        }
        val content = AccessibilityBridge.readScreen()
        if (content.isBlank()) return ToolOutcome(false, "A tela atual não tem texto legível.")
        val spoken = if (content.length > 220) {
            "A tela tem bastante texto. No topo: " + firstLine(content)
        } else {
            "Na tela: $content"
        }
        return ToolOutcome(
            ok = true,
            summary = "Tela atual:\n$content",
            spoken = spoken,
        )
    }

    private fun firstLine(text: String): String = text.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.take(180)
        ?: "conteúdo"

    fun tapOnScreen(text: String?): ToolOutcome {
        if (!AccessibilityBridge.connected) return accessibilityMissing()
        return if (!text.isNullOrBlank() && AccessibilityBridge.tapByText(text)) {
            ToolOutcome(true, "Toquei em \"$text\".")
        } else {
            ToolOutcome(false, "Não encontrei esse elemento na tela.")
        }
    }

    fun typeOnScreen(text: String): ToolOutcome {
        if (!AccessibilityBridge.connected) return accessibilityMissing()
        return if (AccessibilityBridge.typeText(text)) ToolOutcome(true, "Texto digitado.")
        else ToolOutcome(false, "Não encontrei um campo de texto ativo.")
    }

    fun scrollScreen(forward: Boolean): ToolOutcome {
        if (!AccessibilityBridge.connected) return accessibilityMissing()
        return if (AccessibilityBridge.scroll(forward)) ToolOutcome(true, "Rolei a tela.")
        else ToolOutcome(false, "Não consegui rolar esta tela.")
    }

    fun screenBack(): ToolOutcome {
        if (!AccessibilityBridge.connected) return accessibilityMissing()
        AccessibilityBridge.back()
        return ToolOutcome(true, "Voltei.")
    }

    fun screenHome(): ToolOutcome {
        if (!AccessibilityBridge.connected) return accessibilityMissing()
        AccessibilityBridge.home()
        return ToolOutcome(true, "Fui para a tela inicial.")
    }

    fun launchBoard(intent: Intent, message: String): ToolOutcome = launch(intent, message)

    fun apps(): AppsDirectory = appsDirectory

    fun contacts(): ContactsDirectory = contactsDirectory

    private fun accessibilityMissing(): ToolOutcome = ToolOutcome(
        false,
        "Preciso do serviço de acessibilidade ativo para interagir com a tela.",
        "Preciso ativar o serviço de acessibilidade.",
    )

    private fun launch(intent: Intent, successMessage: String): ToolOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { ToolOutcome(true, successMessage) },
                onFailure = {
                    ToolOutcome(
                        false,
                        "Não consegui abrir a tela do sistema (${it.message}).",
                        "Não consegui abrir a tela do sistema.",
                    )
                },
            )
    }

    private fun openSettings(intent: Intent) = launch(intent, "Abrindo configurações.")
}

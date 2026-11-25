package com.glia.devicecontrol

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.app.ActivityManager
import android.media.AudioManager

/** Leituras reais do aparelho, sem rede. */
class DeviceStateReader(private val context: Context) {

    data class Battery(val percent: Int, val charging: Boolean, val temperatureC: Float)
    data class Memory(val freeMb: Long, val totalMb: Long)
    data class Storage(val freeGb: Double, val totalGb: Double)

    fun battery(): Battery {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(percent, charging, temp)
    }

    fun memory(): Memory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val total = info.totalMem / (1024L * 1024L)
        val available = info.availMem / (1024L * 1024L)
        return Memory(available, total)
    }

    fun storage(): Storage {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val free = stat.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val total = stat.totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return Storage(free, total)
    }

    fun network(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "indisponível"
        val network = cm.activeNetwork ?: return "sem conexão"
        val caps = cm.getNetworkCapabilities(network) ?: return "sem conexão"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dados móveis"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "outra conexão"
        }
        val validated = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "" else " (sem internet validada)"
        return transport + validated
    }

    fun volumePercent(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun brightnessPercent(): Int {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (raw * 100 / 255).coerceIn(0, 100)
    }

    fun installedAppCount(): Int = AppsDirectory(context).installedCount()

    fun notificationCount(): Int = NotificationBridge.snapshot().size

    /** Resumo curto injetado no prompt do modelo. */
    fun summary(): String {
        val battery = battery()
        val memory = memory()
        val storage = storage()
        return "bateria ${battery.percent}%${if (battery.charging) " carregando" else ""}, " +
            "RAM livre ${memory.freeMb} MB, armazenamento livre ${"%.1f".format(storage.freeGb)} GB, " +
            "rede ${network()}, volume ${volumePercent()}%"
    }

    fun describe(topic: String): String? {
        return when (topic.lowercase().trim()) {
            "battery", "bateria", "carga" -> {
                val battery = battery()
                "Sua bateria está em ${battery.percent}%${if (battery.charging) " e está carregando" else ""}. " +
                    "Temperatura: ${"%.1f".format(battery.temperatureC)} °C."
            }
            "ram", "memoria", "memória" -> {
                val memory = memory()
                "A RAM tem ${memory.totalMb} MB no total, com ${memory.freeMb} MB livres agora."
            }
            "storage", "armazenamento", "espaco", "espaço" -> {
                val storage = storage()
                "O armazenamento tem ${"%.1f".format(storage.totalGb)} GB, " +
                    "com ${"%.1f".format(storage.freeGb)} GB livres."
            }
            "network", "rede", "wifi" -> "Sua conexão atual: ${network()}."
            "volume" -> "O volume de mídia está em ${volumePercent()}%."
            "brightness", "brilho" -> "O brilho da tela está em ${brightnessPercent()}%."
            "notifications", "notificações", "notificacoes" -> {
                val items = NotificationBridge.snapshot()
                if (items.isEmpty()) "Não há notificações ativas."
                else "Você tem ${items.size} notificações: " +
                    items.take(5).joinToString("; ") { "${it.app}: ${it.title}" } + "."
            }
            "apps", "aplicativos" -> "Há ${installedAppCount()} aplicativos instalados com ícone na tela inicial."
            else -> null
        }
    }
}

package com.glia.devicecontrol

import android.content.Context
import com.glia.ai.tools.ToolCall
import com.glia.ai.tools.ToolOutcome
import com.glia.ai.tools.ToolSchemaValidator
import com.glia.ai.tools.ToolSpec
import com.glia.calendar.CalendarRepository
import com.glia.calendar.TemporalResolver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Executa a ferramenta escolhida pelo modelo (ou pelo roteador local).
 * Toda chamada é validada contra o schema antes de tocar no sistema.
 */
class ToolExecutor(
    private val context: Context,
    private val calendar: CalendarRepository,
    private val reader: DeviceStateReader,
    private val actions: DeviceActions,
) {

    val specs: List<ToolSpec> get() = ToolCatalog.specs

    fun spec(name: String): ToolSpec? = ToolCatalog.spec(name)

    fun execute(call: ToolCall): ToolOutcome {
        val spec = spec(call.name)
            ?: return ToolOutcome(false, "Ferramenta desconhecida: ${call.name}.")
        val errors = ToolSchemaValidator.validate(spec.schema, call.args)
        if (errors.isNotEmpty()) {
            return ToolOutcome(
                false,
                "Parâmetros inválidos: ${errors.joinToString("; ")}",
                "Não entendi todos os dados necessários para essa ação.",
            )
        }
        return runCatching { dispatch(call) }.getOrElse {
            ToolOutcome(false, "A ação falhou: ${it.message}", "Não consegui concluir a ação.")
        }
    }

    private fun dispatch(call: ToolCall): ToolOutcome {
        val args = call.args
        return when (call.name) {
            "time_now" -> ToolOutcome(true, TemporalResolver.clockAnswer())
            "agenda_list" -> {
                val phrase = when (args.optString("period", "hoje")) {
                    "amanha" -> "amanhã"
                    "semana" -> "esta semana"
                    "proximo" -> "próximo compromisso"
                    else -> "hoje"
                }
                val answer = TemporalResolver.agendaAnswer(calendar, phrase)
                    ?: "Não encontrei compromissos."
                ToolOutcome(true, answer)
            }
            "agenda_create" -> createEvent(args)
            "device_status" -> {
                val topic = args.optString("topic", "battery")
                ToolOutcome(true, reader.describe(topic) ?: reader.summary())
            }
            "app_open" -> actions.openApp(args.optString("app"))
            "volume_set" -> actions.setVolume(args.optInt("percent", 50))
            "brightness_set" -> actions.setBrightness(args.optInt("percent", 60))
            "flashlight_set" -> actions.setFlashlight(args.optBoolean("on", true))
            "wifi_panel" -> actions.openWifiPanel()
            "bluetooth_panel" -> actions.openBluetoothPanel()
            "dnd_set" -> actions.setDnd(args.optBoolean("on", true))
            "alarm_set" -> actions.setAlarm(
                args.optInt("hour", 7),
                args.optInt("minute", 0),
                args.optString("label").takeIf { it.isNotBlank() },
            )
            "timer_set" -> actions.setTimer(args.optInt("seconds", 300))
            "music_play" -> actions.playMusic(args.optString("query").takeIf { it.isNotBlank() })
            "phone_call" -> actions.call(args.optString("target"))
            "message_send" -> actions.sendMessage(
                args.optString("target"),
                args.optString("message"),
            )
            "screen_read" -> actions.readScreen()
            "screen_tap" -> actions.tapOnScreen(args.optString("text").takeIf { it.isNotBlank() })
            "screen_type" -> actions.typeOnScreen(args.optString("text"))
            "screen_scroll" -> actions.scrollScreen(args.optBoolean("forward", true))
            "screen_back" -> actions.screenBack()
            "screen_home" -> actions.screenHome()
            "notification_list" -> {
                val items = NotificationBridge.snapshot()
                if (items.isEmpty()) ToolOutcome(true, "Não há notificações ativas.")
                else ToolOutcome(
                    true,
                    "Notificações ativas:\n" + items.take(8).joinToString("\n") {
                        "${it.app}: ${it.title} — ${it.text}"
                    },
                )
            }
            else -> ToolOutcome(false, "Ferramenta não implementada: ${call.name}.")
        }
    }

    private fun createEvent(args: org.json.JSONObject): ToolOutcome {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return ToolOutcome(false, "O evento precisa de um título.")
        val rawStart = args.optString("start").trim()
        val start = parseDate(rawStart)
            ?: return ToolOutcome(false, "Data inválida: \"$rawStart\". Use o formato 2026-05-20T14:30.")
        val minutes = args.optInt("duration_min", 60).coerceIn(5, 1440)
        val end = start.plusMinutes(minutes.toLong())
        val reminder = args.optInt("reminder_min", 10)
        if (!calendar.canWrite()) {
            return ToolOutcome(
                false,
                "Sem permissão de escrita na agenda.",
                "Preciso de permissão para criar eventos na agenda.",
            )
        }
        val id = calendar.createEvent(
            title = title,
            start = start.atZone(ZoneId.systemDefault()).toInstant(),
            end = end.atZone(ZoneId.systemDefault()).toInstant(),
            location = args.optString("location").takeIf { it.isNotBlank() },
            reminderMinutes = reminder,
        ) ?: return ToolOutcome(false, "Não consegui criar o evento.")
        return ToolOutcome(
            true,
            "Criei \"$title\" em ${TemporalResolver.prettyDate(start.toLocalDate())} às " +
                "${start.toLocalTime()}.",
        )
    }

    private fun parseDate(raw: String): LocalDateTime? {
        val cleaned = raw.trim().replace(' ', 'T')
        runCatching { return LocalDateTime.parse(cleaned) }
        runCatching {
            val date = LocalDate.parse(cleaned.substringBefore('T'))
            return date.atTime(9, 0)
        }
        return null
    }
}

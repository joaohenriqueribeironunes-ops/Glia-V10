package com.glia.assistant

import android.content.Context
import com.glia.ai.tools.ToolCall
import com.glia.calendar.CalendarRepository
import com.glia.calendar.TemporalResolver
import com.glia.devicecontrol.DeviceStateReader
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Roteador determinístico que roda antes do modelo.
 *
 * Motivo: consultas de hora/bateria/agenda são exatas e não devem custar
 * segundos de inferência. O modelo continua sendo o cérebro para linguagem
 * livre e para escolher ferramentas quando o roteador não reconhece o pedido.
 */
class AssistantRouter(
    private val context: Context,
    private val calendar: CalendarRepository,
    private val reader: DeviceStateReader,
) {

    sealed interface Route {
        data class Answer(val text: String) : Route
        data class Action(
            val tool: String,
            val args: JSONObject,
            val sensitive: Boolean = false,
            val confirmation: String? = null,
        ) : Route

        data object None : Route
    }

    fun route(raw: String): Route {
        val text = raw.trim()
        if (text.isEmpty()) return Route.None
        val q = text.lowercase()

        // Agenda: consultas primeiro.
        if (q.contains("agenda") || q.contains("compromisso") || q.contains("reunião") ||
            q.contains("reuniao") || q.contains("evento") || q.contains("calendário") ||
            q.contains("calendario")
        ) {
            if (q.contains("cria") || q.contains("marca") || q.contains("agenda um") ||
                q.contains("adiciona")
            ) {
                createEventRoute(text)?.let { return it }
            }
            TemporalResolver.agendaAnswer(calendar, q)?.let { return Route.Answer(it) }
        }

        // Ações de tela e aparelho.
        screenRoutes(q)?.let { return it }
        appRoute(q)?.let { return it }
        deviceActionRoutes(q)?.let { return it }
        communicationRoutes(q)?.let { return it }

        // Consultas de estado.
        deviceQuery(q)?.let { return Route.Answer(it) }

        // Data/hora e calendário.
        TemporalResolver.resolve(q)?.let { return Route.Answer(it) }

        if (q.contains("quem é você") || q.contains("quem e voce") || q.contains("seu nome")) {
            return Route.Answer(
                "Sou a Glia, sua assistente local. Rodo no seu Redmi sem internet, " +
                    "vejo sua agenda, o estado do aparelho e executo comandos com a sua confirmação.",
            )
        }

        return Route.None
    }

    private fun screenRoutes(q: String): Route? {
        if (q.contains("o que está na tela") || q.contains("o que esta na tela") ||
            q.contains("leia a tela") || q.contains("lê a tela") || q.contains("ler a tela")
        ) {
            return Route.Action("screen_read", JSONObject())
        }
        Regex("(?:toca|toque|clica|clique) (?:em|no|na) (.+)").find(q)?.let {
            val target = it.groupValues[1].trim()
            return Route.Action(
                "screen_tap",
                JSONObject().put("text", target),
                sensitive = true,
                confirmation = "Posso tocar em \"$target\" na tela?",
            )
        }
        Regex("(?:digita|digite|escreve|escreva) (.+)").find(q)?.let {
            val value = it.groupValues[1].trim()
            return Route.Action(
                "screen_type",
                JSONObject().put("text", value),
                sensitive = true,
                confirmation = "Posso digitar isso no app aberto?",
            )
        }
        if (q.contains("rola") || q.contains("role") || q.contains("rolar")) {
            val forward = !(q.contains("para cima") || q.contains("sobe"))
            return Route.Action("screen_scroll", JSONObject().put("forward", forward))
        }
        if (q == "voltar" || q.startsWith("volta") || q.contains("botão voltar") ||
            q.contains("botao voltar")
        ) {
            return Route.Action("screen_back", JSONObject())
        }
        if (q.contains("tela inicial") || q.contains("ir para home") || q.contains("vá para home")) {
            return Route.Action("screen_home", JSONObject())
        }
        return null
    }

    private fun appRoute(q: String): Route? {
        val patterns = listOf(
            Regex("^(?:abre|abrir|abre o|abre a|lança|lancar) (?:o |a )?(.+)$"),
            Regex("^(?:abre|abrir) (?:o app )?(.+)$"),
        )
        for (pattern in patterns) {
            val match = pattern.find(q) ?: continue
            val target = match.groupValues[1].trim()
            if (target.isEmpty()) continue
            if (target.startsWith("lanterna") || target.startsWith("wifi") ||
                target.startsWith("bluetooth") || target.startsWith("brilho") ||
                target.startsWith("volume") || target.startsWith("agenda")
            ) {
                continue
            }
            return Route.Action("app_open", JSONObject().put("app", target))
        }
        return null
    }

    private fun deviceActionRoutes(q: String): Route? {
        val turnOn = !(q.contains("desliga") || q.contains("desligar") || q.contains("apaga") ||
            q.contains("desative") || q.contains("tira"))
        if (q.contains("lanterna") || q.contains(" flashlight")) {
            return Route.Action("flashlight_set", JSONObject().put("on", turnOn))
        }
        if (q.contains("wi-fi") || q.contains("wifi")) {
            return Route.Action("wifi_panel", JSONObject())
        }
        if (q.contains("bluetooth")) {
            return Route.Action("bluetooth_panel", JSONObject())
        }
        if (q.contains("não perturbe") || q.contains("nao perturbe") || q.contains("não incomode")) {
            return Route.Action("dnd_set", JSONObject().put("on", turnOn))
        }
        Regex("(?:volume|som)(?: (?:em|para|a))? ?(\\d{1,3})").find(q)?.let {
            val percent = it.groupValues[1].toIntOrNull() ?: return@let
            return Route.Action("volume_set", JSONObject().put("percent", percent.coerceIn(0, 100)))
        }
        if (q.contains("aumenta o volume") || q.contains("aumentar o volume")) {
            val next = (reader.volumePercent() + 15).coerceAtMost(100)
            return Route.Action("volume_set", JSONObject().put("percent", next))
        }
        if (q.contains("abaixa o volume") || q.contains("diminui o volume")) {
            val next = (reader.volumePercent() - 15).coerceAtLeast(0)
            return Route.Action("volume_set", JSONObject().put("percent", next))
        }
        Regex("brilho(?: (?:em|para|a))? ?(\\d{1,3})").find(q)?.let {
            val percent = it.groupValues[1].toIntOrNull() ?: return@let
            return Route.Action(
                "brightness_set",
                JSONObject().put("percent", percent.coerceIn(5, 100)),
            )
        }
        Regex("(?:alarme|me acorda)(?: para| às| as)? ?(\\d{1,2})(?:[:h ](\\d{2}))?").find(q)?.let {
            val hour = it.groupValues[1].toIntOrNull() ?: return@let
            val minute = it.groupValues[2].toIntOrNull() ?: 0
            return Route.Action(
                "alarm_set",
                JSONObject().put("hour", hour.coerceIn(0, 23)).put("minute", minute.coerceIn(0, 59)),
            )
        }
        Regex("(?:timer|temporizador|cronômetro|cronometro)(?: de)? (\\d{1,4}) ?(segundo|seg|minuto|min|hora|h)")
            .find(q)?.let { match ->
                val amount = match.groupValues[1].toLongOrNull() ?: return@let
                val unit = match.groupValues[2]
                val seconds = when {
                    unit.startsWith("seg") -> amount
                    unit.startsWith("min") -> amount * 60
                    else -> amount * 3600
                }
                return Route.Action("timer_set", JSONObject().put("seconds", seconds.toInt()))
            }
        if (q.contains("toca música") || q.contains("tocar música") || q.contains("coloca música") ||
            q.contains("toque música")
        ) {
            val query = q.substringAfter("música").removePrefix(" de").trim()
            val args = JSONObject()
            if (query.isNotEmpty() && query.length < 40 && !query.startsWith("para")) {
                args.put("query", query)
            }
            return Route.Action("music_play", args)
        }
        return null
    }

    private fun communicationRoutes(q: String): Route? {
        Regex("(?:liga|ligar|ligue|chama|chamar) (?:para|o|a|pro|pra)? ?(.+)").find(q)?.let {
            val target = it.groupValues[1].trim()
            if (target.isBlank() || target.contains("música") || target.contains("volume")) return null
            return Route.Action(
                "phone_call",
                JSONObject().put("target", target),
                sensitive = true,
                confirmation = "Posso ligar para $target agora?",
            )
        }
        Regex("(?:manda|mandar|envia|enviar|envie) (?:uma )?(?:mensagem|sms|whatsapp) " +
            "(?:para|pro|pra) ([^,]+?)(?: dizendo| falando| com o texto) (.+)").find(q)?.let {
            val target = it.groupValues[1].trim()
            val message = it.groupValues[2].trim()
            return Route.Action(
                "message_send",
                JSONObject().put("target", target).put("message", message),
                sensitive = true,
                confirmation = "Posso enviar \"$message\" para $target?",
            )
        }
        return null
    }

    private fun deviceQuery(q: String): String? {
        val topic = when {
            q.contains("bateria") || q.contains("carga") -> "battery"
            q.contains("armazenamento") || q.contains("espaço") || q.contains("espaco") -> "storage"
            q.contains("memória ram") || q.contains("memoria ram") || q == "ram" ||
                q.contains("quantas mem") -> "ram"
            q.contains("rede") || q.contains("conexão") || q.contains("conexao") ||
                q.contains("wi-fi está") -> "network"
            q.contains("notificação") || q.contains("notificacao") || q.contains("notificações") ->
                "notifications"
            q.contains("quantos apps") || q.contains("aplicativos instalados") -> "apps"
            else -> null
        }
        return topic?.let { reader.describe(it) }
    }

    private fun createEventRoute(text: String): Route? {
        val now = ZonedDateTime.now()
        val normalized = text.lowercase()
        var date = LocalDate.now()
        if (normalized.contains("amanhã") || normalized.contains("amanha")) date = date.plusDays(1)
        if (normalized.contains("depois de amanhã") || normalized.contains("depois de amanha")) {
            date = date.plusDays(2)
        }
        date = Regex("(\\d{1,2})/(\\d{1,2})").find(normalized)?.let {
            val day = it.groupValues[1].toIntOrNull() ?: return@let date
            val month = it.groupValues[2].toIntOrNull() ?: return@let date
            runCatching { LocalDate.of(now.year, month, day) }.getOrDefault(date)
        } ?: date

        val time = Regex("(?:às|as|para) (\\d{1,2})(?:[:h](\\d{2}))?").find(normalized)?.let {
            val hour = it.groupValues[1].toIntOrNull() ?: 9
            val minute = it.groupValues[2].toIntOrNull() ?: 0
            LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        } ?: LocalTime.of(9, 0)

        val title = Regex("(?:chamad[oa]|com o nome|título|titulo) ([^,]+?)(?: amanhã| amanha| hoje| às| as|$)")
            .find(normalized)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length in 2..60 }
            ?: "Compromisso"

        val start = LocalDateTime.of(date, time)
        return Route.Action(
            "agenda_create",
            JSONObject()
                .put("title", title.replaceFirstChar { it.uppercase() })
                .put("start", start.toString())
                .put("duration_min", 60)
                .put("reminder_min", 10),
        )
    }

    /** Converte uma rota em ToolCall para o executor. */
    fun toCall(route: Route.Action): ToolCall = ToolCall(route.tool, route.args)

    @Suppress("unused")
    private fun packageNameHint(): String = context.packageName
}

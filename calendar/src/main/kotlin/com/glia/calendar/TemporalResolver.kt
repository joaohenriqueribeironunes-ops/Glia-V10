package com.glia.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Resolve perguntas de calendário sem LLM: determinístico, instantâneo e offline.
 * Cobre "que dia é hoje", "daqui a N dias", "25 de dezembro", "semana que vem".
 */
object TemporalResolver {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val br: Locale = Locale.forLanguageTag("pt-BR")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", br)
    private val dateFormat = DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", br)

    private val weekdays = mapOf(
        DayOfWeek.MONDAY to "segunda-feira",
        DayOfWeek.TUESDAY to "terça-feira",
        DayOfWeek.WEDNESDAY to "quarta-feira",
        DayOfWeek.THURSDAY to "quinta-feira",
        DayOfWeek.FRIDAY to "sexta-feira",
        DayOfWeek.SATURDAY to "sábado",
        DayOfWeek.SUNDAY to "domingo",
    )

    private val months = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )

    fun weekdayName(date: LocalDate): String = weekdays[date.dayOfWeek] ?: date.dayOfWeek.name

    fun prettyDate(date: LocalDate): String =
        "${weekdayName(date)}, ${date.dayOfMonth} de ${months[date.monthValue - 1]} de ${date.year}"

    fun prettyDateTime(value: ZonedDateTime): String {
        val now = ZonedDateTime.now(zone)
        val prefix = when (value.toLocalDate()) {
            now.toLocalDate() -> "hoje"
            now.plusDays(1).toLocalDate() -> "amanhã"
            else -> weekdayName(value.toLocalDate())
        }
        return "$prefix às ${value.format(timeFormat)}"
    }

    fun eventSummary(event: AgendaEvent): String {
        val start = event.begin.atZone(zone)
        return if (event.allDay) {
            "${event.title} (dia inteiro, ${weekdayName(start.toLocalDate())})"
        } else {
            "${event.title} — ${prettyDateTime(start)}"
        }
    }

    fun clockAnswer(now: ZonedDateTime = ZonedDateTime.now(zone)): String {
        val time = now.format(timeFormat)
        return "Agora são $time. Hoje é ${prettyDate(now.toLocalDate())}."
    }

    /**
     * Devolve uma resposta pronta ou null se a frase não for temporal.
     */
    fun resolve(query: String, now: ZonedDateTime = ZonedDateTime.now(zone)): String? {
        val q = query.lowercase(br).trim()
        val today = now.toLocalDate()

        if (q.matches(Regex(".*\\b(que horas|hora atual|horas são)\\b.*"))) return clockAnswer(now)

        if (q.contains("depois de amanhã")) {
            val target = today.plusDays(2)
            return "Depois de amanhã é ${prettyDate(target)}."
        }
        if (q.matches(Regex(".*\\bamanhã\\b.*")) && q.matches(Regex(".*\\b(que dia|data)\\b.*"))) {
            val target = today.plusDays(1)
            return "Amanhã é ${prettyDate(target)}."
        }
        if (q.matches(Regex(".*\\bque dia (é|e) (hoje|agora)\\b.*")) ||
            q.matches(Regex(".*\\bdata de hoje\\b.*"))
        ) {
            return "Hoje é ${prettyDate(today)}."
        }

        Regex("(?:(?:daqui a|em)\\s*(\\d{1,3})\\s*dias?)").find(q)?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: return null
            val target = today.plusDays(days.toLong())
            return "Daqui a $days dias será ${prettyDate(target)}."
        }
        Regex("(?:(?:daqui a|em)\\s*(\\d{1,2})\\s*semanas?)").find(q)?.let { match ->
            val weeks = match.groupValues[1].toIntOrNull() ?: return null
            val target = today.plusWeeks(weeks.toLong())
            return "Daqui a $weeks semanas será ${prettyDate(target)}."
        }
        Regex("(?:(?:daqui a|em)\\s*(\\d{1,2})\\s*mes(?:es)?)").find(q)?.let { match ->
            val monthsAhead = match.groupValues[1].toIntOrNull() ?: return null
            val target = today.plusMonths(monthsAhead.toLong())
            return "Daqui a $monthsAhead meses será ${prettyDate(target)}."
        }

        // "que dia da semana será 25 de dezembro"
        Regex("(\\d{1,2})\\s*(?:de\\s*)?([a-zç]{3,9})(?:\\s*(?:de\\s*)?(\\d{4}))?").findAll(q).forEach { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            val monthName = match.groupValues[2].removeSuffix("de").trim()
            val monthIndex = months.indexOfFirst { it.startsWith(monthName.take(4)) } + 1
            if (monthIndex <= 0) return@forEach
            val year = match.groupValues[3].toIntOrNull() ?: today.year
            val date = runCatching { LocalDate.of(year, monthIndex, day) }.getOrNull() ?: return@forEach
            val target = if (date.isBefore(today) && match.groupValues[3].isBlank()) {
                date.plusYears(1)
            } else {
                date
            }
            val weekdayOnly = q.contains("dia da semana")
            return if (weekdayOnly) {
                "${target.dayOfMonth} de ${months[target.monthValue - 1]} de ${target.year} cai em ${weekdayName(target)}."
            } else {
                "${prettyDate(target)}."
            }
        }

        // 25/12 ou 25/12/2027
        Regex("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?").find(q)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            var year = match.groupValues[3].toIntOrNull() ?: today.year
            if (year in 0..99) year += 2000
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return@let
            val target = if (date.isBefore(today) && match.groupValues[3].isBlank()) date.plusYears(1) else date
            return "${prettyDate(target)}."
        }

        // Horário: "às 7 da manhã", "14h30"
        Regex("(\\d{1,2})\\s*(?::|h)\\s*(\\d{2})?").find(q)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val pm = q.contains("da tarde") || q.contains("da noite")
            val adjusted = when {
                pm && hour < 12 -> hour + 12
                else -> hour
            }
            if (adjusted in 0..23 && minute in 0..59 && (q.contains("h") || q.contains(":"))) {
                val time = LocalTime.of(adjusted, minute)
                val target = if (LocalDateTime.of(today, time).isAfter(now.toLocalDateTime())) {
                    today
                } else {
                    today.plusDays(1)
                }
                val suffix = if (target == today) "hoje" else "amanhã"
                return "Esse horário é $suffix, às ${time.format(timeFormat)}."
            }
        }

        return null
    }

    fun agendaAnswer(
        repository: CalendarRepository,
        query: String,
        now: ZonedDateTime = ZonedDateTime.now(zone),
    ): String? {
        if (!repository.canRead()) {
            return "Ainda não tenho permissão para ler sua agenda. Você pode liberar em Ajustes."
        }
        val q = query.lowercase(br)
        return when {
            q.contains("próximo compromisso") || q.contains("proximo compromisso") ||
                q.contains("próxima reunião") || q.contains("proxima reuniao") -> {
                val next = repository.nextEvent()
                if (next == null) "Não encontrei compromissos nos próximos 30 dias."
                else "Seu próximo compromisso é ${eventSummary(next)}."
            }
            q.contains("amanhã") || q.contains("amanha") -> listAnswer("Amanhã", repository.tomorrow())
            q.contains("semana") -> listAnswer("Esta semana", repository.thisWeek())
            q.contains("hoje") || q.contains("agenda") || q.contains("compromissos") ->
                listAnswer("Hoje", repository.today())
            else -> null
        }
    }

    private fun listAnswer(label: String, events: List<AgendaEvent>): String {
        if (events.isEmpty()) return "$label você não tem nenhum compromisso."
        val body = events.take(6).joinToString("; ") { eventSummary(it) }
        val extra = if (events.size > 6) " e mais ${events.size - 6}" else ""
        return "$label: $body$extra."
    }

    fun dateForPrompt(now: ZonedDateTime = ZonedDateTime.now(zone)): String =
        "${prettyDate(now.toLocalDate())}, ${now.format(timeFormat)}"
}

package com.glia.devicecontrol

import com.glia.ai.tools.Sensitivity
import com.glia.ai.tools.ToolSpec

/**
 * Catálogo único de ferramentas expostas ao modelo e ao roteador local.
 * Schemas são JSON Schema reduzido e validados antes da execução.
 */
object ToolCatalog {

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "time_now",
            description = "Hora, data e dia da semana atuais. Use também para contas de calendário.",
            schema = """{"type":"object","properties":{"question":{"type":"string"}},"required":[]}""",
        ),
        ToolSpec(
            name = "agenda_list",
            description = "Lista compromissos da agenda do aparelho.",
            schema = """{"type":"object","properties":{"period":{"type":"string","enum":["hoje","amanha","semana","proximo"]}},"required":["period"]}""",
        ),
        ToolSpec(
            name = "agenda_create",
            description = "Cria um evento na agenda. start no formato 2026-05-20T14:30.",
            schema = """{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string"},"duration_min":{"type":"integer","minimum":5,"maximum":1440},"location":{"type":"string"},"reminder_min":{"type":"integer","minimum":0,"maximum":1440}},"required":["title","start"]}""",
            sensitivity = Sensitivity.SENSITIVE,
        ),
        ToolSpec(
            name = "device_status",
            description = "Estado real do aparelho: battery, storage, ram, network, volume, brightness, notifications, apps.",
            schema = """{"type":"object","properties":{"topic":{"type":"string","enum":["battery","storage","ram","network","volume","brightness","notifications","apps"]}},"required":["topic"]}""",
        ),
        ToolSpec(
            name = "app_open",
            description = "Abre um aplicativo instalado pelo nome.",
            schema = """{"type":"object","properties":{"app":{"type":"string"}},"required":["app"]}""",
        ),
        ToolSpec(
            name = "volume_set",
            description = "Define o volume de mídia em porcentagem.",
            schema = """{"type":"object","properties":{"percent":{"type":"integer","minimum":0,"maximum":100}},"required":["percent"]}""",
        ),
        ToolSpec(
            name = "brightness_set",
            description = "Define o brilho da tela em porcentagem (5 a 100).",
            schema = """{"type":"object","properties":{"percent":{"type":"integer","minimum":5,"maximum":100}},"required":["percent"]}""",
        ),
        ToolSpec(
            name = "flashlight_set",
            description = "Liga ou desliga a lanterna.",
            schema = """{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"]}""",
        ),
        ToolSpec(
            name = "wifi_panel",
            description = "Abre o painel de Wi-Fi do sistema (apps comuns não podem alternar Wi-Fi).",
            schema = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = "bluetooth_panel",
            description = "Abre as configurações de Bluetooth.",
            schema = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = "dnd_set",
            description = "Ativa ou desativa o modo Não Perturbe.",
            schema = """{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"]}""",
        ),
        ToolSpec(
            name = "alarm_set",
            description = "Cria um alarme no relógio do sistema.",
            schema = """{"type":"object","properties":{"hour":{"type":"integer","minimum":0,"maximum":23},"minute":{"type":"integer","minimum":0,"maximum":59},"label":{"type":"string"}},"required":["hour","minute"]}""",
        ),
        ToolSpec(
            name = "timer_set",
            description = "Inicia um timer.",
            schema = """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1,"maximum":86400}},"required":["seconds"]}""",
        ),
        ToolSpec(
            name = "music_play",
            description = "Toca música pelo app padrão, opcionalmente com busca.",
            schema = """{"type":"object","properties":{"query":{"type":"string"}},"required":[]}""",
        ),
        ToolSpec(
            name = "phone_call",
            description = "Liga para um contato ou número.",
            schema = """{"type":"object","properties":{"target":{"type":"string"}},"required":["target"]}""",
            sensitivity = Sensitivity.SENSITIVE,
        ),
        ToolSpec(
            name = "message_send",
            description = "Prepara e envia mensagem SMS para um contato.",
            schema = """{"type":"object","properties":{"target":{"type":"string"},"message":{"type":"string"}},"required":["target","message"]}""",
            sensitivity = Sensitivity.SENSITIVE,
        ),
        ToolSpec(
            name = "screen_read",
            description = "Lê o texto da tela do app em primeiro plano.",
            schema = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = "screen_tap",
            description = "Toca no elemento da tela cujo texto contém o valor informado.",
            schema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
            sensitivity = Sensitivity.SENSITIVE,
        ),
        ToolSpec(
            name = "screen_type",
            description = "Digita texto no campo de texto em foco.",
            schema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
            sensitivity = Sensitivity.SENSITIVE,
        ),
        ToolSpec(
            name = "screen_scroll",
            description = "Rola a tela para baixo (forward=true) ou para cima.",
            schema = """{"type":"object","properties":{"forward":{"type":"boolean"}},"required":["forward"]}""",
        ),
        ToolSpec(
            name = "screen_back",
            description = "Executa o botão voltar do sistema.",
            schema = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = "screen_home",
            description = "Vai para a tela inicial do Android.",
            schema = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = "notification_list",
            description = "Lista as notificações ativas do aparelho.",
            schema = """{"type":"object","properties":{},"required":[]}""",
        ),
    )

    fun spec(name: String): ToolSpec? = specs.firstOrNull { it.name == name }
}

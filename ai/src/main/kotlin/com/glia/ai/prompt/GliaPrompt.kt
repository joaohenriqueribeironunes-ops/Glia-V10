package com.glia.ai.prompt

import com.glia.ai.ChatTurn
import com.glia.ai.Role
import com.glia.ai.tools.ToolSpec

/**
 * Monta o prompt no formato ChatML (usado por boa parte dos GGUFs).
 * Se o seu GGUF usar outro template, ajuste `render` — nada mais depende dele.
 */
object GliaPrompt {

    fun system(deviceSummary: String, tools: List<ToolSpec>): String = buildString {
        append("Você é Glia, assistente pessoal offline em português do Brasil. ")
        append("Fale de forma curta, calorosa e direta (no máximo 3 frases), sem markdown. ")
        append("Você roda inteiramente no aparelho, sem internet. ")
        append("Quando o pedido exigir dados do aparelho ou uma ação, chame UMA ferramenta ")
        append("respondendo somente com JSON no formato {\"tool\":\"nome\",\"args\":{...}}. ")
        append("Se nenhuma ferramenta resolver, responda em texto normal.\n")
        if (deviceSummary.isNotBlank()) append("Contexto do aparelho: ").append(deviceSummary).append('\n')
        if (tools.isNotEmpty()) {
            append("Ferramentas disponíveis:\n")
            for (tool in tools) {
                append("- ").append(tool.name).append(": ").append(tool.description)
                append(" | args: ").append(tool.schema).append('\n')
            }
        }
        append("Nunca invente resultados de ferramentas.")
    }

    fun render(systemPrompt: String, turns: List<ChatTurn>): String = buildString {
        append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
        for (turn in turns) {
            if (turn.role == Role.SYSTEM) continue
            val role = when (turn.role) {
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
                Role.TOOL -> "tool"
                Role.SYSTEM -> "system"
            }
            append("<|im_start|>").append(role).append('\n')
            append(turn.content.trim()).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }
}

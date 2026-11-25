package com.glia.ai.tools

import org.json.JSONObject

/**
 * Nível de risco da ferramenta.
 * SAFE: executa direto (ler estado, abrir app, ajustar volume).
 * SENSITIVE: exige confirmação por voz antes de executar (ligar, enviar, apagar).
 */
enum class Sensitivity { SAFE, SENSITIVE }

data class ToolSpec(
    val name: String,
    val description: String,
    val schema: String,
    val sensitivity: Sensitivity = Sensitivity.SAFE,
)

data class ToolCall(val name: String, val args: JSONObject)

data class ToolOutcome(
    val ok: Boolean,
    val summary: String,
    val spoken: String = summary,
)

package com.glia.ai

/** Parâmetros de amostragem do modelo local. */
data class SamplingParams(
    val temperature: Float = 0.55f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val repeatPenalty: Float = 1.12f,
    val maxTokens: Int = 220,
)

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatTurn(val role: Role, val content: String)

/**
 * Contrato de qualquer motor de linguagem do Glia (nativo ou fallback).
 * `generate` é bloqueante: deve ser chamado fora da main thread.
 * O retorno de `onToken` indica se a geração continua.
 */
interface LlmEngine : AutoCloseable {
    val label: String
    val isReady: Boolean
    val contextSize: Int
    fun generate(prompt: String, params: SamplingParams, onToken: (String) -> Boolean): String
    override fun close()
}

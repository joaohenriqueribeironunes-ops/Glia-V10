package com.glia.ai.engine

import com.glia.ai.LlmEngine
import com.glia.ai.SamplingParams

/**
 * Motor de emergência, usado apenas quando nenhum GGUF está instalado.
 * Não finge ser o modelo: responde o essencial e explica como instalar o Bonsai.
 * As consultas de dispositivo continuam funcionando pelo roteador local do app.
 */
class OfflinePersonaEngine : LlmEngine {

    override val label: String = "Modo essencial (modelo não instalado)"
    override val isReady: Boolean = false
    override val contextSize: Int = 0

    override fun generate(
        prompt: String,
        params: SamplingParams,
        onToken: (String) -> Boolean,
    ): String {
        val lastUser = prompt.substringAfterLast("<|im_start|>user", "")
            .substringBefore("<|im_end|>")
            .trim()
            .lowercase()
        val answer = when {
            lastUser.contains("quem é você") || lastUser.contains("seu nome") ->
                "Sou a Glia, sua assistente offline. Ainda estou sem meu modelo de linguagem local instalado, mas já consigo consultar a hora, a agenda e o estado do aparelho."

            lastUser.contains("modelo") || lastUser.contains("instalar") ->
                "Para eu pensar com o Bonsai 2B local, envie o arquivo GGUF ternário para a pasta de modelos do app. O caminho aparece em Ajustes, seção Modelo."

            lastUser.contains("oi") || lastUser.contains("olá") || lastUser.contains("bom dia") ->
                "Olá. Estou ouvindo. Posso ver sua agenda, o estado do aparelho e abrir apps — e assim que o modelo GGUF estiver instalado, converso sobre qualquer assunto."

            else ->
                "Meu modelo local ainda não está instalado, então não consigo raciocinar sobre isso. Consigo, porém, responder hora, data, agenda, bateria, armazenamento e executar comandos do aparelho."
        }
        for (chunk in answer.chunked(18)) {
            if (!onToken(chunk)) break
            Thread.sleep(12)
        }
        return answer
    }

    override fun close() = Unit
}

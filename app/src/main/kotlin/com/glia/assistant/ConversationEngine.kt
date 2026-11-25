package com.glia.assistant

import com.glia.ai.ChatTurn
import com.glia.ai.Role
import com.glia.ai.SamplingParams
import com.glia.ai.prompt.GliaPrompt
import com.glia.ai.tools.Sensitivity
import com.glia.ai.tools.ToolCall
import com.glia.ai.tools.ToolCallParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Núcleo da conversa:
 *
 * 1. roteador determinístico (hora, agenda, estado, ações óbvias);
 * 2. modelo local com tool calling em JSON validado por schema;
 * 3. confirmação obrigatória para ferramentas SENSITIVE.
 *
 * A execução de ferramentas é sempre feita pelo ToolExecutor — o modelo
 * nunca tem acesso direto ao sistema.
 */
class ConversationEngine(private val graph: AppGraph) {

    interface Listener {
        fun onStatus(text: String)
        fun onDelta(text: String)
        fun onConfirmation(question: String, tool: String)
        fun onConfirmationCleared()
    }

    private var confirmation: CompletableDeferred<Boolean>? = null

    val awaitingConfirmation: Boolean get() = confirmation != null

    fun resolveConfirmation(accepted: Boolean) {
        confirmation?.complete(accepted)
    }

    suspend fun handle(
        text: String,
        history: List<ChatTurn>,
        listener: Listener,
    ): String {
        listener.onStatus("analisando pedido")
        when (val route = graph.router.route(text)) {
            is AssistantRouter.Route.Answer -> return route.text
            is AssistantRouter.Route.Action -> return executeCall(
                call = graph.router.toCall(route),
                sensitive = route.sensitive,
                question = route.confirmation,
                listener = listener,
            )

            AssistantRouter.Route.None -> Unit
        }

        listener.onStatus("carregando modelo local")
        val engine = graph.ensureEngine()
        listener.onStatus(if (engine.isReady) "pensando localmente" else "modo essencial")

        val system = GliaPrompt.system(graph.reader.summary(), graph.tools.specs)
        val turns = history.takeLast(8) + ChatTurn(Role.USER, text)
        val prompt = GliaPrompt.render(system, turns)

        val reply = withContext(Dispatchers.Default) {
            engine.generate(
                prompt = prompt,
                params = SamplingParams(maxTokens = if (engine.isReady) 220 else 80),
            ) { delta ->
                listener.onDelta(delta)
                true
            }
        }

        val call = ToolCallParser.parse(reply)
        if (call != null) {
            val spec = graph.tools.spec(call.name)
            if (spec != null) {
                listener.onStatus("executando ${call.name}")
                return executeCall(
                    call = call,
                    sensitive = spec.sensitivity == Sensitivity.SENSITIVE,
                    question = null,
                    listener = listener,
                )
            }
        }
        return reply.trim().ifBlank { "Não consegui formular uma resposta agora." }
    }

    private suspend fun executeCall(
        call: ToolCall,
        sensitive: Boolean,
        question: String?,
        listener: Listener,
    ): String {
        if (sensitive) {
            val prompt = question ?: "Posso executar ${humanName(call.name)}?"
            val accepted = askConfirmation(prompt, call.name, listener)
            if (!accepted) return "Tudo bem, não fiz nada."
        }
        val outcome = graph.tools.execute(call)
        return if (outcome.ok) outcome.spoken else outcome.summary
    }

    private suspend fun askConfirmation(
        question: String,
        tool: String,
        listener: Listener,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmation = deferred
        listener.onConfirmation(question, tool)
        val answer = withTimeoutOrNull(20_000) { deferred.await() }
        listener.onConfirmationCleared()
        confirmation = null
        return answer == true
    }

    private fun humanName(tool: String): String = when (tool) {
        "phone_call" -> "essa ligação"
        "message_send" -> "esse envio de mensagem"
        "agenda_create" -> "esse evento na agenda"
        "screen_tap" -> "esse toque na tela"
        "screen_type" -> "essa digitação"
        else -> "essa ação"
    }
}

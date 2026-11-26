package com.glia.assistant

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.glia.ai.SamplingParams
import com.glia.voice.VoiceBus
import com.glia.voice.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Activity TEMPORÁRIA de validação em aparelho (não vai na build final).
 * Escreve o resultado em `Android/data/com.glia.assistant/files/selftest.txt`
 * porque o logcat é inundado pelos avisos do próprio runtime.
 */
class SelfTestActivity : ComponentActivity() {

    private val report = StringBuilder()

    private fun out(line: String) {
        Log.i("SelfTest", line)
        report.append(line).append('\n')
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val graph = (application as GliaApplication).graph
            try {
                val voskDir = File(filesDir, "vosk-model")
                out("VOSK top=${voskDir.list()?.joinToString() ?: "ausente"}")
                out("VOSK conf=${File(voskDir, "conf").exists()} am=${File(voskDir, "am").exists()}")

                val t0 = System.currentTimeMillis()
                val engine = graph.ensureEngine()
                out("ENGINE ready=${engine.isReady} ctx=${engine.contextSize} load=${System.currentTimeMillis() - t0}ms")
                if (engine.isReady) {
                    val prefix = "<|im_start|>system\nVocê é a Glia, uma assistente concisa.<|im_end|>\n" +
                        "<|im_start|>user\nExplique em uma frase o que é a fotossíntese.<|im_end|>\n" +
                        "<|im_start|>assistant\n"
                    var chars = 0
                    val t1 = System.currentTimeMillis()
                    val reply = withContext(Dispatchers.Default) {
                        engine.generate("$prefix</think>\n", SamplingParams(maxTokens = 40)) { token ->
                            chars += token.length
                            true
                        }
                    }
                    val ms = System.currentTimeMillis() - t1
                    out("LLM nothink ms=$ms chars=$chars cps=${if (ms > 0) chars * 1000 / ms else 0} reply=${reply.replace("\r", "").replace("\n", " ").take(160)}")
                }

                val t2 = System.currentTimeMillis()
                graph.tts.initialize()
                out("TTS init=${System.currentTimeMillis() - t2}ms label=${graph.tts.engineLabel}")
                graph.tts.speak("Teste da voz do sistema da Glia. Se você está ouvindo, funcionou.")
                out("TTS fim label=${graph.tts.engineLabel} neural=${graph.tts.neuralReady}")

                graph.voice.ensureModel()
                out("VOSK instalado=${graph.voice.isModelInstalled()} mic=${graph.voice.hasMicPermission()}")

                if (graph.voice.hasMicPermission()) {
                    VoiceBus.wakeEnabled = true
                    WakeWordService.start(this@SelfTestActivity)
                    delay(4_000)
                    out("MIC estado=${VoiceBus.state.value} parcial='${VoiceBus.partial.value}'")
                } else {
                    out("MIC sem permissão")
                }

                graph.tts.stop()
            } catch (t: Throwable) {
                out("ERRO ${t::class.java.simpleName}: ${t.message}")
            } finally {
                val target = File(getExternalFilesDir(null) ?: filesDir, "selftest.txt")
                runCatching { target.writeText(report.toString()) }
                Log.i("SelfTest", "relatório em ${target.absolutePath}")
                finish()
            }
        }
    }
}

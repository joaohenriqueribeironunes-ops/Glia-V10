package com.glia.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serviço em primeiro plano que escuta "Glia" com gramática restrita.
 *
 * Ciclo: escuta wake word → pausa microfone → reconhece o comando →
 * publica em VoiceBus.commands → espera o TTS terminar → volta a escutar.
 * O microfone fica desligado durante o processamento e a fala.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "glia_wake"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.glia.voice.START"
        const val ACTION_LISTEN_NOW = "com.glia.voice.LISTEN_NOW"
        const val ACTION_PAUSE = "com.glia.voice.PAUSE"
        const val ACTION_STOP = "com.glia.voice.STOP"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
        }

        fun listenNow(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_LISTEN_NOW)
            runCatching { context.startForegroundService(intent) }
        }

        fun pause(context: Context) {
            runCatching {
                context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE))
            }
        }

        fun stop(context: Context) {
            VoiceBus.wakeEnabled = false
            runCatching {
                context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "glia-wake-audio").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private lateinit var engine: VoskEngine
    private var capturing = false
    private var paused = false
    private var listeningWake = false
    @Volatile
    private var lastFailureAt = 0L

    override fun onCreate() {
        super.onCreate()
        engine = VoskEngine(this)
        createChannel()
        startForegroundCompat()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_START -> {
                paused = false
                VoiceBus.wakeEnabled = true
                scope.launch { startWakeLoop() }
            }
            ACTION_LISTEN_NOW -> {
                paused = false
                VoiceBus.wakeEnabled = true
                scope.launch {
                    stopWake()
                    captureCommand()
                }
            }
            ACTION_PAUSE -> {
                paused = true
                scope.launch { stopWake() }
                VoiceBus.setState(VoiceState.IDLE)
            }
            ACTION_STOP -> {
                paused = true
                VoiceBus.wakeEnabled = false
                scope.launch {
                    stopWake()
                    ServiceCompat.stopForeground(this@WakeWordService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun observeState() {
        scope.launch {
            VoiceBus.state.collect { state ->
                if (!VoiceBus.wakeEnabled || paused || capturing) return@collect
                // Sem modelo de voz, não martela o microfone: tenta a cada 15 s.
                if (System.currentTimeMillis() - lastFailureAt < 15_000) return@collect
                if (state == VoiceState.IDLE && !listeningWake) {
                    delay(700)
                    startWakeLoop()
                }
            }
        }
    }

    private suspend fun startWakeLoop() {
        if (listeningWake || capturing || paused) return
        val started = withContext(audioDispatcher) {
            engine.start(
                grammar = VoskEngine.WAKE_GRAMMAR,
                onPartial = { partial ->
                    if (partial.lowercase().contains("glia")) VoiceBus.setPartial("Glia?")
                },
                onResult = { text ->
                    listeningWake = false
                    if (text.lowercase().contains("glia")) {
                        VoiceBus.emitWake()
                    }
                    scope.launch {
                        stopWake()
                        captureCommand()
                    }
                },
                onError = {
                    listeningWake = false
                    lastFailureAt = System.currentTimeMillis()
                    VoiceBus.setState(VoiceState.IDLE)
                },
            )
        }
        if (!started) lastFailureAt = System.currentTimeMillis()
        if (started) {
            listeningWake = true
            VoiceBus.setState(VoiceState.LISTENING_WAKE)
        }
    }

    private suspend fun stopWake() {
        withContext(audioDispatcher) { engine.stop() }
        listeningWake = false
    }

    private suspend fun captureCommand() {
        if (capturing) return
        capturing = true
        VoiceBus.setState(VoiceState.LISTENING_COMMAND)
        VoiceBus.setPartial("")
        var emitted = false
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        withContext(audioDispatcher) {
            engine.start(
                grammar = null,
                onPartial = { VoiceBus.setPartial(it) },
                onResult = { text ->
                    emitted = true
                    VoiceBus.emitCommand(text)
                    done.complete(Unit)
                },
                onError = {
                    lastFailureAt = System.currentTimeMillis()
                    VoiceBus.setState(VoiceState.IDLE)
                    done.complete(Unit)
                },
            )
        }
        // Espera o resultado (ou o timeout do reconhecedor) e desliga o microfone.
        kotlinx.coroutines.withTimeoutOrNull(10_000) { done.await() }
        withContext(audioDispatcher) { engine.stop() }
        capturing = false
        if (!emitted) VoiceBus.setPartial("")
        // IDLE faz o observador religar a escuta da palavra-chave (com pausa).
        VoiceBus.setState(VoiceState.IDLE)
    }

    private fun startForegroundCompat() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glia_mic)
            .setContentTitle("Glia está por perto")
            .setContentText("Diga \"Glia\" quando precisar.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Escuta da palavra Glia",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantém o microfone ativo apenas para ouvir a palavra \"Glia\"."
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        runCatching { audioDispatcher.close() }
        runCatching { engine.close() }
        VoiceBus.setState(VoiceState.IDLE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

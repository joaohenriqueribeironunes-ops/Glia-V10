package com.glia.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.glia.voice.VoiceBus

/**
 * Tile "Glia": abre o painel de voz já escutando, inclusive sobre a tela
 * bloqueada. A escuta é iniciada pela activity visível (a restrição de
 * foreground service impede abrir o microfone direto do tile no Android 14+).
 */
class GliaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Glia"
            state = if (VoiceBus.wakeEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (VoiceBus.wakeEnabled) "escutando" else "toque e fale"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, VoiceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }
}

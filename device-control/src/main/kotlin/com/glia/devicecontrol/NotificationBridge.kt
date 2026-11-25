package com.glia.devicecontrol

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class GliaNotification(val app: String, val title: String, val text: String)

/**
 * Snapshot das notificações ativas. Só funciona quando o usuário concede
 * acesso a notificações em Ajustes → Acesso a notificações → Glia.
 */
object NotificationBridge {

    @Volatile
    private var items: List<GliaNotification> = emptyList()

    fun snapshot(): List<GliaNotification> = items

    internal fun update(list: List<GliaNotification>) {
        items = list
    }
}

class GliaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() = refresh()

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onListenerDisconnected() {
        NotificationBridge.update(emptyList())
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val appNames = HashMap<String, String>()
        val mapped = active.mapNotNull { sbn ->
            val extras = sbn.notification?.extras ?: return@mapNotNull null
            val pkg = sbn.packageName ?: return@mapNotNull null
            val app = appNames.getOrPut(pkg) {
                runCatching {
                    val pm = packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) null
            else GliaNotification(app, title.ifBlank { text }, text)
        }
        NotificationBridge.update(mapped)
    }
}

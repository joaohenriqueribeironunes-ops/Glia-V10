package com.glia.devicecontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/** Lista e abre apps instalados. */
class AppsDirectory(private val context: Context) {

    data class AppInfo(val label: String, val packageName: String)

    private fun launcherIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    fun installed(): List<AppInfo> {
        val pm = context.packageManager
        val resolve: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent(), 0)
        return resolve.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm).toString()
            AppInfo(label, pkg)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    fun installedCount(): Int = installed().size

    fun find(query: String): AppInfo? {
        val normalized = normalize(query)
        if (normalized.isBlank()) return null
        val apps = installed()
        apps.firstOrNull { normalize(it.label) == normalized }?.let { return it }
        apps.firstOrNull { normalize(it.label).startsWith(normalized) }?.let { return it }
        apps.firstOrNull { normalize(it.label).contains(normalized) }?.let { return it }
        apps.firstOrNull { normalize(it.packageName).contains(normalized) }?.let { return it }
        return null
    }

    fun launch(app: AppInfo): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("á", "a").replace("à", "a").replace("â", "a").replace("ã", "a")
        .replace("é", "e").replace("ê", "e").replace("í", "i")
        .replace("ó", "o").replace("ô", "o").replace("õ", "o")
        .replace("ú", "u").replace("ç", "c")
        .trim()
}

package com.glia.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ações de tela via AccessibilityService. Requer que o usuário habilite
 * "Glia — controle de tela" nas configurações de acessibilidade.
 */
object AccessibilityBridge {

    @Volatile
    private var service: GliaAccessibilityService? = null

    @Volatile
    var lastPackage: String = ""
        internal set

    val connected: Boolean get() = service != null

    internal fun attach(value: GliaAccessibilityService) { service = value }
    internal fun detach(value: GliaAccessibilityService) { if (service === value) service = null }

    fun readScreen(): String {
        val root = service?.rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        collect(root, builder, 0)
        val text = builder.toString().trim()
        // Limita o tamanho: telas longas não devem estourar o contexto do modelo.
        return if (text.length <= 3_000) text else text.take(3_000) + "…"
    }

    private fun collect(node: AccessibilityNodeInfo?, builder: StringBuilder, depth: Int) {
        if (node == null || depth > 14) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val label = when {
            text.isNotBlank() -> text
            description.isNotBlank() -> description
            else -> ""
        }
        if (label.isNotBlank()) {
            if (node.isClickable) builder.append("[botão] ")
            builder.append(label).append('\n')
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), builder, depth + 1)
        }
    }

    fun tapByText(text: String): Boolean {
        val root = service?.rootInActiveWindow ?: return false
        val target = findByText(root, text) ?: return false
        return clickableAncestor(target)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val root = service?.rootInActiveWindow ?: return false
        val field = findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun scroll(forward: Boolean): Boolean {
        val svc = service ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val root = svc.rootInActiveWindow
        if (root?.performAction(action) == true) return true
        // Fallback: gesto de arrastar no centro da tela.
        val metrics = svc.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val fromY = metrics.heightPixels * if (forward) 0.72f else 0.30f
        val toY = metrics.heightPixels * if (forward) 0.32f else 0.70f
        val path = Path().apply {
            moveTo(x, fromY)
            lineTo(x, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun back(): Boolean = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false

    fun home(): Boolean = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    fun recents(): Boolean =
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false

    fun notificationsPanel(): Boolean =
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ?: false

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                .lowercase()
            if (label.contains(needle)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var guard = 0
        while (current != null && guard < 8) {
            if (current.isClickable) return current
            current = current.parent
            guard++
        }
        return node
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isFocused) return node
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }
}

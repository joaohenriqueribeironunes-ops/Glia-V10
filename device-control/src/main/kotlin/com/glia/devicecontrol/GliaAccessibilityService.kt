package com.glia.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço declarado no manifesto do módulo. Não faz nada sozinho: apenas
 * expõe a árvore de acessibilidade para a Glia quando o usuário pede.
 */
class GliaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) AccessibilityBridge.lastPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }
}

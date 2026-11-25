package com.glia.ai.engine

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observa a pressão de memória do sistema e recomenda o tamanho de contexto.
 * O Redmi Note 11S tem 6 GB, mas o HyperOS mata apps de fundo com agressividade:
 * mantemos o contexto curto e liberamos o modelo quando o app fica ocioso.
 */
object MemoryGovernor {

    enum class Pressure { NORMAL, MODERATE, CRITICAL }

    private val _pressure = MutableStateFlow(Pressure.NORMAL)
    val pressure: StateFlow<Pressure> = _pressure

    fun onTrimMemory(level: Int) {
        _pressure.value = when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> Pressure.CRITICAL
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> Pressure.CRITICAL
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> Pressure.MODERATE
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> Pressure.MODERATE
            else -> Pressure.NORMAL
        }
    }

    fun recommendContext(defaultContext: Int): Int = when (_pressure.value) {
        Pressure.CRITICAL -> minOf(256, defaultContext)
        Pressure.MODERATE -> minOf(512, defaultContext)
        Pressure.NORMAL -> defaultContext
    }

    fun availableRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    fun isLowMemory(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }
}

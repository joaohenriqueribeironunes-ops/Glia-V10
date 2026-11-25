package com.glia.assistant

import android.app.Application
import com.glia.ai.engine.MemoryGovernor

class GliaApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryGovernor.onTrimMemory(level)
        // Pressão crítica: desanexa o mmap do modelo; o próximo uso recarrega.
        if (level >= TRIM_MEMORY_COMPLETE || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            graph.releaseEngine()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        graph.releaseEngine()
    }
}

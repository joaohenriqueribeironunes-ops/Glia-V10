package com.glia.voice

import android.content.Context
import java.io.File

/**
 * Modelo Vosk pequeno de português (Apache 2.0) dentro do APK, em
 * `assets/vosk-model.zip`: extraído uma única vez para `filesDir/vosk-model`
 * no primeiro uso, sem nenhuma etapa manual.
 *
 * Também aceita um modelo já instalado em `filesDir/vosk-model` (adb push).
 */
object VoskModelLocator {

    private const val DIR_NAME = "vosk-model"
    private const val ASSET_ZIP = "vosk-model.zip"

    fun modelDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun isInstalled(context: Context): Boolean = isInstalledAt(modelDir(context))

    private fun isInstalledAt(dir: File): Boolean =
        File(dir, "conf").exists() || File(dir, "am").exists()

    /** Extrai o zip de assets, se existir. Retorna true se o modelo ficou disponível. */
    fun ensureFromAssets(context: Context): Boolean {
        if (isInstalled(context)) return true
        val target = modelDir(context)
        return runCatching {
            val input = context.assets.open(ASSET_ZIP)
            val temp = File(context.cacheDir, "vosk-model.zip")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            java.util.zip.ZipInputStream(temp.inputStream()).use { zip ->
                var entry = zip.nextEntry
                target.mkdirs()
                while (entry != null) {
                    val out = File(target, entry.name)
                    if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                        throw SecurityException("entrada de zip insegura")
                    }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            temp.delete()
            flattenSingleDir(target)
            isInstalledAt(target)
        }.getOrDefault(false)
    }

    /** O zip oficial traz a pasta `vosk-model-small-pt-0.3/` como raiz. */
    private fun flattenSingleDir(target: java.io.File) {
        if (isInstalledAt(target)) return
        val children = target.listFiles().orEmpty()
        if (children.size != 1 || !children[0].isDirectory) return
        val inner = children[0]
        inner.listFiles().orEmpty().forEach { file ->
            val dest = java.io.File(target, file.name)
            if (dest.exists()) dest.deleteRecursively()
            file.renameTo(dest)
        }
        inner.delete()
    }
}

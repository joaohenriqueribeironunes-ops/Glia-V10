package com.glia.voice

import android.content.Context
import java.io.File

/**
 * O modelo Vosk pequeno de português (~50 MB, Apache 2.0) não é distribuído
 * neste repositório. Instale com:
 *
 *   adb push vosk-model-small-pt-0.3 <app>/files/vosk-model
 *
 * ou coloque o zip em assets/vosk-model.zip (extraído no primeiro uso).
 */
object VoskModelLocator {

    private const val DIR_NAME = "vosk-model"
    private const val ASSET_ZIP = "vosk-model.zip"

    fun modelDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, "conf").exists() || File(dir, "am").exists()
    }

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
            true
        }.getOrDefault(false)
    }
}

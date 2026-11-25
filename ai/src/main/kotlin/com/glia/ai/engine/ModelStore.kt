package com.glia.ai.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.File

/**
 * Localiza o GGUF do modelo local.
 *
 * Ordem de busca:
 * 1. `filesDir/models` (modelo avulso, ex.: `adb push`);
 * 2. `getExternalFilesDir("models")`;
 * 3. asset `model/glia.gguf` embutido no APK — aberto como
 *    `/proc/self/fd/<fd>` para o llama.cpp mapear sem copiar ~600 MB.
 *
 * Nenhum peso é distribuído no código-fonte: o GGUF fica fora do git e entra
 * apenas no build do APK (`app/src/main/assets/model/glia.gguf`).
 */
object ModelStore {

    const val ASSET_MODEL = "model/glia.gguf"
    private const val PREF_ACTIVE = "glia_model_active"

    @Volatile
    private var bundled: AssetFileDescriptor? = null

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun listModels(context: Context): List<File> {
        val dirs = listOfNotNull(modelsDir(context), context.getExternalFilesDir("models"))
        return dirs.flatMap { dir ->
            dir.listFiles { file -> file.isFile && file.name.endsWith(".gguf", true) }
                ?.toList().orEmpty()
        }.sortedByDescending { it.length() }
    }

    fun activeModel(context: Context): File? {
        val prefs = context.getSharedPreferences("glia_engine", Context.MODE_PRIVATE)
        val preferred = prefs.getString(PREF_ACTIVE, null)
        val all = listModels(context)
        if (preferred != null) {
            all.firstOrNull { it.absolutePath == preferred }?.let { return it }
        }
        return all.firstOrNull()
    }

    fun setActive(context: Context, file: File) {
        context.getSharedPreferences("glia_engine", Context.MODE_PRIVATE)
            .edit().putString(PREF_ACTIVE, file.absolutePath).apply()
    }

    /** Tamanho do GGUF empacotado em assets (0 se não houver). */
    fun bundledSize(context: Context): Long = runCatching {
        context.assets.openFd(ASSET_MODEL).use { it.length }
    }.getOrDefault(0L)

    fun hasBundledModel(context: Context): Boolean = bundledSize(context) > 0

    /**
     * Caminho utilizável pelo llama.cpp para o modelo embutido.
     *
     * O asset é gravado sem compressão (`noCompress += "gguf"`), então o próprio
     * APK serve de mmap: basta um descritor aberto e o caminho `/proc/self/fd/N`.
     * Se o asset estiver comprimido (APK antigo), cai para cópia em `filesDir`.
     */
    @Synchronized
    fun bundledModel(context: Context): File? {
        bundled?.let { afd ->
            if (afd.parcelFileDescriptor.fileDescriptor.valid()) {
                return File("/proc/self/fd/${afd.parcelFileDescriptor.fd}")
            }
        }
        val afd = runCatching { context.assets.openFd(ASSET_MODEL) }.getOrNull()
        if (afd != null) {
            bundled = afd
            return File("/proc/self/fd/${afd.parcelFileDescriptor.fd}")
        }
        return ensureAssetModel(context)
    }

    /** Plano B: copia o asset comprimido para o armazenamento interno. */
    fun ensureAssetModel(context: Context): File? {
        val target = File(modelsDir(context), "glia-model.gguf")
        if (target.exists() && target.length() > 0) return target
        return try {
            context.assets.open(ASSET_MODEL).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            target
        } catch (t: Throwable) {
            target.delete()
            null
        }
    }
}

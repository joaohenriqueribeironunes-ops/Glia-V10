package com.glia.ai.engine

import android.content.Context
import java.io.File

/**
 * Localiza o GGUF do modelo local.
 *
 * Ordem de busca:
 * 1. `filesDir/models` (recomendado: `adb push` do arquivo GGUF);
 * 2. `getExternalFilesDir("models")` (transferência via USB sem root);
 * 3. asset `model/glia.gguf` (opcional; aumenta o APK em ~400 MB).
 *
 * Nenhum binário de modelo é distribuído com o código: os pesos têm licença própria.
 */
object ModelStore {

    const val ASSET_MODEL = "model/glia.gguf"
    private const val PREF_ACTIVE = "glia_model_active"

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

    /** Copia o modelo empacotado em assets, se existir. */
    fun ensureAssetModel(context: Context): File? {
        val target = File(modelsDir(context), "glia-model.gguf")
        if (target.exists()) return target
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

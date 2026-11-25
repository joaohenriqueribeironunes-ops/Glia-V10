package com.glia.ai.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extrai e valida chamadas de ferramenta emitidas pelo modelo.
 *
 * O modelo é instruído a responder apenas com:
 * {"tool":"nome","args":{...}}
 * Aceitamos também blocos ```json ... ``` e raciocínio antes do JSON.
 */
object ToolCallParser {

    private const val FENCE = "```"

    fun parse(raw: String): ToolCall? {
        val text = stripFences(raw)
        for (candidate in balancedObjects(text)) {
            val call = runCatching { parseObject(JSONObject(candidate)) }.getOrNull()
            if (call != null) return call
        }
        return null
    }

    private fun parseObject(obj: JSONObject): ToolCall? {
        val name = obj.optString("tool").ifBlank { obj.optString("name") }
        if (name.isBlank()) return null
        val args = obj.optJSONObject("args")
            ?: obj.optJSONObject("arguments")
            ?: obj.optJSONObject("parameters")
            ?: JSONObject()
        return ToolCall(name.trim(), args)
    }

    fun stripFences(raw: String): String {
        var text = raw.trim()
        if (!text.contains(FENCE)) return text
        val blocks = StringBuilder()
        var index = 0
        while (true) {
            val start = text.indexOf(FENCE, index)
            if (start < 0) break
            val afterStart = start + FENCE.length
            val end = text.indexOf(FENCE, afterStart)
            if (end < 0) break
            var block = text.substring(afterStart, end)
            if (block.startsWith("json")) block = block.removePrefix("json")
            blocks.append(block).append('\n')
            index = end + FENCE.length
        }
        return if (blocks.isBlank()) text.replace(FENCE, " ") else blocks.toString()
    }

    /** Objetos JSON de nível superior, respeitando strings e escapes. */
    fun balancedObjects(text: String): List<String> {
        val results = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            results.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return results
    }
}

/**
 * Validador de schema (subconjunto de JSON Schema) — suficiente para garantir
 * que o modelo não invente parâmetros nem valores fora do permitido.
 */
object ToolSchemaValidator {

    fun validate(schemaJson: String, args: JSONObject): List<String> {
        val schema = runCatching { JSONObject(schemaJson) }.getOrNull()
            ?: return listOf("schema inválido")
        return validateObject(schema, args, "")
    }

    private fun validateObject(schema: JSONObject, value: JSONObject, path: String): List<String> {
        val errors = ArrayList<String>()
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val key = required.optString(i)
            if (!value.has(key)) errors.add("$path$key é obrigatório")
        }
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val prop = properties.optJSONObject(key)
            if (prop == null) {
                errors.add("$path$key não é permitido")
                continue
            }
            errors.addAll(validateValue(prop, value.opt(key), "$path$key."))
        }
        return errors
    }

    private fun validateValue(schema: JSONObject, value: Any?, path: String): List<String> {
        val errors = ArrayList<String>()
        val type = schema.optString("type")
        val label = path.trimEnd('.')
        when (type) {
            "string" -> if (value !is String) errors.add("$label deve ser texto")
            "integer" -> {
                val number = value as? Number
                if (number == null || number.toDouble() % 1.0 != 0.0) {
                    errors.add("$label deve ser inteiro")
                }
            }
            "number" -> if (value !is Number) errors.add("$label deve ser número")
            "boolean" -> if (value !is Boolean) errors.add("$label deve ser booleano")
            "array" -> if (value !is JSONArray) errors.add("$label deve ser lista")
            "object" -> if (value !is JSONObject) errors.add("$label deve ser objeto")
        }
        if (schema.has("enum") && value != null) {
            val options = schema.optJSONArray("enum")
            var match = false
            if (options != null) {
                for (i in 0 until options.length()) {
                    if (options.opt(i).toString() == value.toString()) match = true
                }
            }
            if (!match) errors.add("$label fora dos valores permitidos")
        }
        if (value is Number) {
            if (schema.has("minimum") && value.toDouble() < schema.optDouble("minimum")) {
                errors.add("$label abaixo do mínimo")
            }
            if (schema.has("maximum") && value.toDouble() > schema.optDouble("maximum")) {
                errors.add("$label acima do máximo")
            }
        }
        return errors
    }
}

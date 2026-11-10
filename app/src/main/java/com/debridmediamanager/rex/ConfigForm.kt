package com.debridmediamanager.rex

import kotlinx.serialization.json.*

/** What a settings form shows first for each field. A saved secret shows blank: leaving it blank keeps it. */
fun initialForm(settings: AddonSettings): Map<String, String> = settings.fields.associate { field ->
    val value = settings.values[field.key] ?: field.default
    field.key to when {
        field.type == "secret" -> ""
        value is JsonArray -> value.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        value is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> ""
    }
}

/** A secret the host already holds comes back masked ("********"), never empty. */
fun secretSaved(settings: AddonSettings, key: String) =
    (settings.values[key] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true

class FormResult(val body: JsonObject, val errors: Map<String, String>)

/**
 * Checks what was typed against the manifest's fields and builds the body `PUT .../config` sends.
 * A blank secret that is already saved is left out, so the host keeps it.
 */
fun checkForm(settings: AddonSettings, input: Map<String, String>): FormResult {
    val errors = mutableMapOf<String, String>()
    val body = buildJsonObject {
        for (field in settings.fields) {
            val text = input[field.key].orEmpty().trim()
            when (field.type) {
                "bool" -> put(field.key, text == "true")
                "number" -> when {
                    // Blank puts back the addon's default.
                    text.isEmpty() -> if (field.required) errors[field.key] = "Enter a number" else put(field.key, JsonNull)
                    text.toLongOrNull() != null -> put(field.key, text.toLong())
                    text.toDoubleOrNull()?.isFinite() == true -> put(field.key, text.toDouble())
                    else -> errors[field.key] = "Enter a number"
                }
                "list" -> {
                    val items = text.split('\n', ',').map(String::trim).filter(String::isNotEmpty)
                    if (items.isEmpty() && field.required) errors[field.key] = "Add at least one"
                    else put(field.key, JsonArray(items.map(::JsonPrimitive)))
                }
                "secret" -> when {
                    text.isNotEmpty() -> put(field.key, text)
                    field.required && !secretSaved(settings, field.key) -> errors[field.key] = "Required"
                }
                // text, and any type this app does not know yet
                else -> if (text.isEmpty() && field.required) errors[field.key] = "Required" else put(field.key, text)
            }
        }
    }
    return FormResult(body, errors)
}

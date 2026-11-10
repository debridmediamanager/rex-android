package com.debridmediamanager.rex

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConfigFormTest {
    private val settings = AddonSettings("Archive", listOf(
        ConfigField("collection", "Collection", "text", required = true),
        ConfigField("key", "API key", "secret", required = true),
        ConfigField("hd", "Only HD", "bool", default = JsonPrimitive(true)),
        ConfigField("limit", "Limit", "number", default = JsonPrimitive(50)),
        ConfigField("languages", "Languages", "list"),
        ConfigField("mood", "Mood", "colour"),
    ), mapOf("collection" to JsonPrimitive("feature_films"), "key" to JsonPrimitive("••••"), "languages" to JsonArray(listOf(JsonPrimitive("en"), JsonPrimitive("de")))))

    @Test fun theFormStartsFromWhatIsSetThenTheDefaults() {
        assertEquals(mapOf("collection" to "feature_films", "key" to "", "hd" to "true", "limit" to "50",
            "languages" to "en\nde", "mood" to ""), initialForm(settings))
    }

    @Test fun aValidFormBecomesTypedJson() {
        val result = checkForm(settings, initialForm(settings) + mapOf("limit" to "2.5", "languages" to "en, fr\nja\n"))
        assertEquals(emptyMap<String, String>(), result.errors)
        assertEquals(buildJsonObject {
            put("collection", "feature_films"); put("hd", true); put("limit", 2.5)
            put("languages", JsonArray(listOf(JsonPrimitive("en"), JsonPrimitive("fr"), JsonPrimitive("ja"))))
            put("mood", "")
        }, result.body)
        assertFalse("a saved secret left blank is kept, so it is not sent", "key" in result.body)
    }

    @Test fun aBlankOptionalNumberPutsBackTheDefault() {
        val result = checkForm(settings, initialForm(settings) + ("limit" to ""))
        assertEquals(JsonNull, result.body["limit"])
        assertTrue(result.errors.isEmpty())
    }

    @Test fun aNewSecretIsSent() {
        val result = checkForm(settings, initialForm(settings) + ("key" to " new-key "))
        assertEquals(JsonPrimitive("new-key"), result.body["key"])
        assertEquals(JsonPrimitive(50L), result.body["limit"])
    }

    @Test fun missingAndMalformedValuesAreReported() {
        val unset = settings.copy(values = emptyMap())
        val result = checkForm(unset, mapOf("collection" to " ", "limit" to "lots"))
        assertEquals(mapOf("collection" to "Required", "key" to "Required", "limit" to "Enter a number"), result.errors)
    }

    @Test fun aRequiredListNeedsAnEntry() {
        val form = AddonSettings("x", listOf(ConfigField("ids", type = "list", required = true)), emptyMap())
        assertEquals(mapOf("ids" to "Add at least one"), checkForm(form, mapOf("ids" to " , \n")).errors)
    }
}

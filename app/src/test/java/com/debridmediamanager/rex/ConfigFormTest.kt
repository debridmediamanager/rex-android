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

    // Host 0.6's select: the person picks a label, the host gets its value; blank puts back the default.
    @Test fun aSelectSendsItsValue() {
        val quality = ConfigField("quality", "Highest quality", "select", default = JsonPrimitive("1080"),
            options = listOf(SelectOption("1080", "1080p"), SelectOption("720", "720p")))
        val form = AddonSettings("x", listOf(quality, ConfigField("server", "Server", "host", required = true)), emptyMap())
        assertEquals(mapOf("quality" to "1080", "server" to ""), initialForm(form))
        val picked = checkForm(form, mapOf("quality" to "720", "server" to " 192.168.1.20:8096 "))
        assertEquals(buildJsonObject { put("quality", "720"); put("server", "192.168.1.20:8096") }, picked.body)
        assertEquals(JsonNull, checkForm(form, mapOf("quality" to "", "server" to "nas")).body["quality"])
        assertEquals(mapOf("server" to "Required"), checkForm(form, mapOf("quality" to "720")).errors)
        val noDefault = form.copy(fields = listOf(quality.copy(default = null, required = true)))
        assertEquals(mapOf("quality" to "Pick one"), checkForm(noDefault, emptyMap()).errors)
    }

    // The host refuses a value as "<label>: <why>"; the form shows the why next to that field.
    @Test fun aRefusedValueIsShownAtItsField() {
        val form = AddonSettings("x", listOf(ConfigField("server", "Server", "host"), ConfigField("backup", "Server backup", "host")), emptyMap())
        assertEquals("server" to "That is this device, which addons never reach",
            fieldError(form, "Server: that is this device, which addons never reach"))
        assertEquals("backup" to "Give an http:// or https:// address", fieldError(form, "Server backup: give an http:// or https:// address"))
        assertNull(fieldError(form, "the addon host is busy"))
    }
}

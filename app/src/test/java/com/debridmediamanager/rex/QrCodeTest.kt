package com.debridmediamanager.rex

import org.junit.Assert.*
import org.junit.Test

/**
 * The encoder against matrices from other encoders (resources/qr; each file names how it was made).
 * Which mask an encoder picks is its own choice, so matrices are compared mask by mask; the mask
 * this one picks follows Project Nayuki's penalty count, so it should pick the same one.
 */
class QrCodeTest {
    private class Reference(val text: String, val version: Int, val auto: Int, val masks: Map<Int, List<String>>)

    private fun reference(name: String): Reference {
        val lines = javaClass.getResource("/qr/$name.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val masks = linkedMapOf<Int, MutableList<String>>()
        lines.drop(3).forEach { line ->
            if (line.startsWith("mask ")) masks[line.removePrefix("mask ").toInt()] = mutableListOf() else masks.values.last() += line
        }
        return Reference(lines[0].removePrefix("text "), lines[1].removePrefix("version ").toInt(), lines[2].removePrefix("auto ").toInt(), masks)
    }

    private fun rows(code: QrCode) = List(code.size) { y -> (0 until code.size).joinToString("") { x -> if (code[x, y]) "1" else "0" } }

    private fun check(name: String) {
        val reference = reference(name)
        reference.masks.forEach { (mask, matrix) ->
            val code = QrCode.encode(reference.text, mask)!!
            assertEquals(reference.version, code.version)
            assertEquals("$name mask $mask", matrix.joinToString("\n"), rows(code).joinToString("\n"))
        }
        val chosen = QrCode.encode(reference.text)!!
        assertEquals("$name picks the mask", reference.auto, chosen.mask)
        assertEquals(reference.masks.getValue(reference.auto), rows(chosen))
    }

    @Test fun everyMaskOfASmallCodeMatches() = check("v2-all-masks")
    @Test fun aSignInLinkMatches() = check("v4-link")
    // Version 7 and up carry version information and split the data into blocks of two lengths;
    // UTF-8 text goes in as its bytes.
    @Test fun aLargerCodeMatches() = check("v8-utf8")
    // Version 10 counts the bytes in 16 bits instead of 8.
    @Test fun versionTenMatches() = check("v10-long")

    @Test fun tooMuchTextHasNoCode() {
        assertNotNull(QrCode.encode("x".repeat(213)))
        assertNull(QrCode.encode("x".repeat(214)))
        assertEquals(21, QrCode.encode("")!!.size)
    }
}

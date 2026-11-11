package com.debridmediamanager.rex

import kotlin.math.abs
import kotlin.math.max

/**
 * A QR code (ISO/IEC 18004) of text in byte mode at error correction level M, versions 1 to 10:
 * room for 213 bytes, plenty for a sign-in link. Draw it with a light border four modules wide.
 */
class QrCode private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size get() = modules.size
    /** Whether the module in column [x], row [y] is dark. */
    operator fun get(x: Int, y: Int) = modules[y][x]

    companion object {
        // Level M by version (index 0 unused): error correction codewords per block, and blocks.
        private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        /** Null when [text] needs more than version 10. [mask] forces a mask; by default the lowest penalty wins. */
        fun encode(text: String, mask: Int? = null): QrCode? {
            require(mask == null || mask in 0..7)
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..10).firstOrNull { 4 + countBits(it) + bytes.size * 8 <= dataCodewords(it) * 8 } ?: return null
            return build(version, interleave(version, dataCodewords(version, bytes)), mask)
        }

        private fun countBits(version: Int) = if (version < 10) 8 else 16

        /** The modules left for codewords once the function patterns and format and version areas are drawn. */
        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int) = rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

        private fun dataCodewords(version: Int, bytes: ByteArray): IntArray {
            val capacity = dataCodewords(version) * 8
            val bits = ArrayList<Boolean>(capacity)
            fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
            put(0b0100, 4)
            put(bytes.size, countBits(version))
            bytes.forEach { put(it.toInt() and 0xFF, 8) }
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return IntArray(capacity / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 } }
        }

        /** Splits the data into blocks, adds each one's Reed-Solomon codewords and interleaves them. */
        private fun interleave(version: Int, data: IntArray): IntArray {
            val blocks = BLOCKS[version]
            val ecc = ECC_PER_BLOCK[version]
            val raw = rawModules(version) / 8
            val short = blocks - raw % blocks
            val shortData = raw / blocks - ecc
            var at = 0
            val dataBlocks = List(blocks) { i -> data.copyOfRange(at, at + shortData + if (i < short) 0 else 1).also { at += it.size } }
            val divisor = reedSolomonDivisor(ecc)
            val eccBlocks = dataBlocks.map { reedSolomonRemainder(it, divisor) }
            val out = ArrayList<Int>(raw)
            for (i in 0..shortData) dataBlocks.forEach { if (i < it.size) out += it[i] }
            for (i in 0 until ecc) eccBlocks.forEach { out += it[i] }
            return out.toIntArray()
        }

        private fun reedSolomonDivisor(degree: Int): IntArray {
            val result = IntArray(degree).also { it[degree - 1] = 1 }
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                result.copyInto(result, 0, 1)
                result[result.lastIndex] = 0
                for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
            return result
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun build(version: Int, codewords: IntArray, forced: Int?): QrCode {
            val size = version * 4 + 17
            val dark = Array(size) { BooleanArray(size) }
            val fixed = Array(size) { BooleanArray(size) }
            fun set(x: Int, y: Int, on: Boolean) { dark[y][x] = on; fixed[y][x] = true }

            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) max(abs(dx), abs(dy)).let { set(x, y, it != 2 && it != 4) }
            }
            if (version > 1) {
                val count = version / 7 + 2
                val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
                val at = IntArray(count) { if (it == 0) 6 else size - 7 - (count - 1 - it) * step }
                for (i in at.indices) for (j in at.indices) {
                    if (i == 0 && j == 0 || i == 0 && j == count - 1 || i == count - 1 && j == 0) continue
                    for (dy in -2..2) for (dx in -2..2) set(at[i] + dx, at[j] + dy, max(abs(dx), abs(dy)) != 1)
                }
            }
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val on = (bits ushr i) and 1 == 1
                    set(size - 11 + i % 3, i / 3, on); set(i / 3, size - 11 + i % 3, on)
                }
            }
            fun format(mask: Int) {
                val data = mask // level M is 00
                var rem = data
                repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
                val bits = ((data shl 10) or rem) xor 0x5412
                fun bit(i: Int) = (bits ushr i) and 1 == 1
                for (i in 0..5) set(8, i, bit(i))
                set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
                for (i in 9..14) set(14 - i, 8, bit(i))
                for (i in 0..7) set(size - 1 - i, 8, bit(i))
                for (i in 8..14) set(8, size - 15 + i, bit(i))
                set(8, size - 8, true)
            }
            format(0)

            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                val upward = (right + 1) and 2 == 0
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val y = if (upward) size - 1 - vert else vert
                    if (!fixed[y][x] && i < codewords.size * 8) {
                        dark[y][x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
                right -= 2
            }

            // Masking twice undoes it.
            fun flip(mask: Int) {
                for (y in 0 until size) for (x in 0 until size) if (!fixed[y][x] && when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }) dark[y][x] = !dark[y][x]
            }
            val mask = forced ?: (0..7).minBy { m -> flip(m); format(m); penalty(dark).also { flip(m) } }
            flip(mask)
            format(mask)
            return QrCode(version, mask, dark)
        }

        /** The standard's penalty score (rules N1 to N4), counted the way Project Nayuki's reference does. */
        private fun penalty(dark: Array<BooleanArray>): Int {
            val size = dark.size
            var result = 0
            for (line in 0 until size * 2) {
                val at = { k: Int -> if (line < size) dark[line][k] else dark[k][line - size] }
                var color = false
                var run = 0
                val history = IntArray(7)
                fun record(length: Int) {
                    history.copyInto(history, 1, 0, 6)
                    // The line's first run continues into the light border.
                    history[0] = if (history[1] == 0) length + size else length
                }
                fun finders(): Int {
                    val n = history[1]
                    val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
                    return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
                        (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
                }
                for (k in 0 until size) {
                    if (at(k) == color) {
                        run++
                        if (run == 5) result += 3 else if (run > 5) result++
                    } else {
                        record(run)
                        if (!color) result += finders() * 40
                        color = at(k)
                        run = 1
                    }
                }
                if (color) { record(run); run = 0 }
                record(run + size)
                result += finders() * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = dark[y][x]
                if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) result += 3
            }
            val total = size * size
            val darkCount = dark.sumOf { row -> row.count { it } }
            result += ((abs(darkCount * 20 - total * 10) + total - 1) / total - 1) * 10
            return result
        }
    }
}

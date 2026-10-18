package com.danielealbano.androidremotecontrolmcp.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

@DisplayName("BenchmarkArgs")
class BenchmarkArgsTest {
    @Test
    fun `defaults when no args`() {
        val args = parseArgs(emptyArray())

        assertEquals(listOf("a", "b", "c"), args.corpora)
        assertEquals(listOf(Layer.DETERMINISTIC, Layer.MODEL, Layer.FULL), args.layers)
        assertEquals(0, args.sample)
        assertEquals(20260803L, args.seed)
        assertEquals(File("privacy-benchmark/.cache"), args.cacheDir)
        assertEquals(File("privacy-benchmark/build/reports/privacy-benchmark"), args.outDir)
    }

    @Test
    fun `parses overrides`() {
        val args =
            parseArgs(
                arrayOf("--corpora=c", "--layers=full", "--sample=100", "--seed=7", "--cache-dir=/x", "--out=/y"),
            )

        assertEquals(listOf("c"), args.corpora)
        assertEquals(listOf(Layer.FULL), args.layers)
        assertEquals(100, args.sample)
        assertEquals(7L, args.seed)
        assertEquals(File("/x"), args.cacheDir)
        assertEquals(File("/y"), args.outDir)
    }

    @Test
    fun `rejects unknown key`() {
        assertThrows(IllegalArgumentException::class.java) { parseArgs(arrayOf("--nope=1")) }
    }

    @Test
    fun `rejects unknown corpus and malformed arg`() {
        assertThrows(IllegalArgumentException::class.java) { parseArgs(arrayOf("--corpora=x")) }
        assertThrows(IllegalArgumentException::class.java) { parseArgs(arrayOf("--sample")) }
    }
}

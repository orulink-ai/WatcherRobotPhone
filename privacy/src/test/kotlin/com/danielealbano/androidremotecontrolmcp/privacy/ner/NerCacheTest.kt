package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NerCache")
class NerCacheTest {
    private val cache = NerCache()
    private val sample = listOf(PiiDetection(PiiCategory.NAMES, 0, 4, PiiDetection.Source.MODEL))

    @Test
    fun `hit and miss`() {
        val key = cache.keyFor("ctx", "text")
        assertNull(cache.get(key))

        cache.put(key, sample)
        assertEquals(sample, cache.get(key))
    }

    @Test
    fun `evicts eldest past capacity`() {
        for (i in 0..NerCache.MAX_ENTRIES) {
            cache.put("key$i", sample)
        }

        assertNull(cache.get("key0"), "eldest entry should be evicted")
        assertNotNull(cache.get("key${NerCache.MAX_ENTRIES}"))
    }

    @Test
    fun `access order keeps recently used`() {
        for (i in 0 until NerCache.MAX_ENTRIES) {
            cache.put("key$i", sample)
        }
        cache.get("key0") // mark key0 most-recently-used
        cache.put("keyNew", sample) // evicts the now-eldest (key1)

        assertNotNull(cache.get("key0"))
        assertNull(cache.get("key1"))
    }

    @Test
    fun `clear removes all`() {
        val key = cache.keyFor("ctx", "text")
        cache.put(key, sample)

        cache.clear()

        assertNull(cache.get(key))
    }
}

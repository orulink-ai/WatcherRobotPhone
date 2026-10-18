package com.danielealbano.androidremotecontrolmcp.services.accessibility

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScreenStateSnapshotCacheImpl")
class ScreenStateSnapshotCacheImplTest {
    private val cache = ScreenStateSnapshotCacheImpl()

    private val screenInfo =
        ScreenInfo(width = 1080, height = 2400, densityDpi = 420, orientation = "portrait")

    private fun snapshot(id: String): ScreenStateSnapshot =
        ScreenStateSnapshot(
            id = id,
            result = MultiWindowResult(windows = emptyList()),
            screenInfo = screenInfo,
            totalKeptNodes = 0,
            totalPages = 1,
        )

    @Test
    @DisplayName("get returns stored snapshot on id match")
    fun getReturnsStoredSnapshotOnIdMatch() {
        val snap = snapshot("abc")
        cache.store(snap)
        assertSame(snap, cache.get("abc"))
    }

    @Test
    @DisplayName("get returns null on id mismatch")
    fun getReturnsNullOnIdMismatch() {
        cache.store(snapshot("abc"))
        assertNull(cache.get("other"))
    }

    @Test
    @DisplayName("store replaces previous snapshot")
    fun storeReplacesPreviousSnapshot() {
        cache.store(snapshot("first"))
        val second = snapshot("second")
        cache.store(second)
        assertNull(cache.get("first"))
        assertSame(second, cache.get("second"))
    }

    @Test
    @DisplayName("clear removes snapshot")
    fun clearRemovesSnapshot() {
        cache.store(snapshot("abc"))
        cache.clear()
        assertNull(cache.get("abc"))
    }
}

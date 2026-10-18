package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ServerLogRepositoryImpl")
class ServerLogRepositoryImplTest {
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun repo(dispatcher: CoroutineDispatcher): ServerLogRepositoryImpl =
        ServerLogRepositoryImpl(ServerLogSegmentedStore(File(tempDir, "logs")), dispatcher)

    @Test
    fun `log persists entry and bumps revision`() =
        runTest {
            val repo = repo(StandardTestDispatcher(testScheduler))
            repo.log(ServerLogEntry.Type.SERVER, "started")
            advanceUntilIdle()

            assertEquals(1L, repo.revision.value)
            val index = repo.readIndex()
            assertEquals(1, index.size)
            assertEquals("started", repo.readEntry(index.first()).message)
        }

    @Test
    fun `log sanitizes uuid tokens in message and toolName`() =
        runTest {
            val repo = repo(StandardTestDispatcher(testScheduler))
            val uuid = "12345678-1234-1234-1234-123456789012"
            repo.log(ServerLogEntry.Type.SERVER, "token is $uuid", toolName = uuid)
            advanceUntilIdle()

            val entry = repo.readEntry(repo.readIndex().first())
            assertTrue(entry.message.contains("[REDACTED]"))
            assertEquals("[REDACTED]", entry.toolName)
        }

    @Test
    fun `recent returns newest first capped at count`() =
        runTest {
            val repo = repo(StandardTestDispatcher(testScheduler))
            repeat(8) { i -> repo.log(ServerLogEntry.Type.SERVER, "m$i") }
            advanceUntilIdle()

            val recent = repo.recent(5)
            assertEquals(listOf("m7", "m6", "m5", "m4", "m3"), recent.map { it.message })
        }

    @Test
    fun `clear empties log and bumps revision`() =
        runTest {
            val repo = repo(StandardTestDispatcher(testScheduler))
            repo.log(ServerLogEntry.Type.SERVER, "a")
            advanceUntilIdle()
            val revisionBefore = repo.revision.value

            repo.clear()
            advanceUntilIdle()

            assertTrue(repo.readIndex().isEmpty())
            assertTrue(repo.revision.value > revisionBefore)
        }

    @Test
    fun `writes are ordered`() =
        runTest {
            val repo = repo(StandardTestDispatcher(testScheduler))
            repeat(50) { i -> repo.log(ServerLogEntry.Type.SERVER, "m$i") }
            advanceUntilIdle()

            val messages = repo.readIndex().map { repo.readEntry(it).message }
            assertEquals((0 until 50).map { "m$it" }, messages)
        }

    @Test
    fun `readIndex is served from the in-memory cache`() =
        runTest {
            val repo = repo(StandardTestDispatcher(testScheduler))
            repo.log(ServerLogEntry.Type.SERVER, "a")
            repo.log(ServerLogEntry.Type.SERVER, "b")
            advanceUntilIdle()
            assertEquals(2, repo.readIndex().size)

            File(tempDir, "logs").listFiles { f -> f.name.endsWith(".idx") }?.forEach { it.delete() }

            assertEquals(2, repo.readIndex().size)
        }

    @Test
    fun `index cache stays consistent across rotation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = ServerLogRepositoryImpl(ServerLogSegmentedStore(File(tempDir, "logs"), 3, 2), dispatcher)
            repeat(7) { i -> repo.log(ServerLogEntry.Type.SERVER, "m$i") }
            advanceUntilIdle()

            val index = repo.readIndex()
            assertEquals(4, index.size)
            assertEquals(
                listOf("m3", "m4", "m5", "m6"),
                index.map { repo.readEntry(it).message },
            )
        }

    @Test
    fun `writer survives an IOException and keeps draining`() =
        runTest {
            val store = mockk<ServerLogSegmentedStore>()
            val validResult =
                AppendResult(
                    entry = ServerLogIndexEntry(1, 0, 0L, ServerLogEntry.Type.SERVER, null, 0, 0, 0),
                    removedSegmentSeqs = emptyList(),
                )
            val diskFull = IOException("disk full")
            coEvery { store.append(any(), any(), any(), any(), any()) } throws diskFull andThen validResult

            val repo = ServerLogRepositoryImpl(store, StandardTestDispatcher(testScheduler))
            repo.log(ServerLogEntry.Type.SERVER, "fails")
            repo.log(ServerLogEntry.Type.SERVER, "ok1")
            repo.log(ServerLogEntry.Type.SERVER, "ok2")
            advanceUntilIdle()

            assertEquals(2L, repo.revision.value)
        }
}

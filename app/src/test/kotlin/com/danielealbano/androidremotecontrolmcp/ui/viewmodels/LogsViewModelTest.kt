package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("LogsViewModel")
class LogsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: RecordingServerLogRepository
    private lateinit var viewModel: LogsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = spyk(RecordingServerLogRepository())
        viewModel = LogsViewModel(repo, dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `recentServerLogs exposes 5 newest entries`() =
        runTest(dispatcher) {
            repeat(7) { repo.log(ServerLogEntry.Type.SERVER, "m$it") }

            viewModel.recentServerLogs.test {
                advanceUntilIdle()
                assertEquals(
                    listOf("m6", "m5", "m4", "m3", "m2"),
                    expectMostRecentItem().map { it.message },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `filteredIndex newest first with all types default`() =
        runTest(dispatcher) {
            repo.log(ServerLogEntry.Type.SERVER, "s")
            repo.log(ServerLogEntry.Type.TOOL_CALL, "", toolName = "tap")
            repo.log(ServerLogEntry.Type.SETTINGS, "cfg")

            viewModel.filteredIndex.test {
                advanceUntilIdle()
                val refs = expectMostRecentItem()
                assertEquals(
                    listOf(
                        ServerLogEntry.Type.SETTINGS,
                        ServerLogEntry.Type.TOOL_CALL,
                        ServerLogEntry.Type.SERVER,
                    ),
                    refs.map { it.type },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `toggleType filters entries`() =
        runTest(dispatcher) {
            repo.log(ServerLogEntry.Type.SERVER, "s")
            repo.log(ServerLogEntry.Type.SETTINGS, "cfg")

            viewModel.filteredIndex.test {
                advanceUntilIdle()
                assertEquals(2, expectMostRecentItem().size)

                viewModel.toggleType(ServerLogEntry.Type.SETTINGS)
                advanceUntilIdle()
                val afterDeselect = expectMostRecentItem()
                assertEquals(1, afterDeselect.size)
                assertTrue(afterDeselect.none { it.type == ServerLogEntry.Type.SETTINGS })

                viewModel.toggleType(ServerLogEntry.Type.SETTINGS)
                advanceUntilIdle()
                assertEquals(2, expectMostRecentItem().size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clearLogs empties index`() =
        runTest(dispatcher) {
            repo.log(ServerLogEntry.Type.SERVER, "s")

            viewModel.filteredIndex.test {
                advanceUntilIdle()
                assertEquals(1, expectMostRecentItem().size)

                viewModel.clearLogs()
                advanceUntilIdle()
                assertTrue(expectMostRecentItem().isEmpty())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `entryAt caches loaded entries`() =
        runTest(dispatcher) {
            repo.log(ServerLogEntry.Type.SERVER, "s")
            val ref = repo.readIndex().first()

            viewModel.entryAt(ref)
            viewModel.entryAt(ref)

            coVerify(exactly = 1) { repo.readEntry(ref) }
        }
}

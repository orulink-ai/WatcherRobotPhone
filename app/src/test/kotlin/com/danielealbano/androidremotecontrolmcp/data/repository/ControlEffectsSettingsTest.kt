package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEdgeColorMode
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ControlEffectsSettingsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `empty preferences expose the default multicolor appearance`() =
        runTest {
            val store = createStore(backgroundScope)
            assertEquals(ControlEffectsConfig(), settings(store).controlEffectsConfig.first())
        }

    @Test
    fun `the unified settings API persists normalized values without touching server settings`() =
        runTest {
            val store = createStore(backgroundScope)
            val logger = logger()
            val repository: SettingsRepository = SettingsRepositoryImpl(store, logger, EventChannelSettingsImpl(store, logger))
            val unrelated = stringPreferencesKey("unrelated_server_setting")
            store.edit { it[unrelated] = "keep me" }
            val config =
                ControlEffectsConfig(
                    colorMode = ControlEdgeColorMode.CUSTOM,
                    customColorArgb = 0x0033AAEE,
                    glowWidthDp = 999f,
                    intensity = 0.75f,
                    motionRange = 0f,
                    speed = 1.8f,
                )
            repository.updateControlEffectsConfig(config)

            assertEquals(config.normalized(), repository.controlEffectsConfig.first())
            assertEquals(config.normalized(), settings(store).controlEffectsConfig.first())
            assertEquals("keep me", store.data.first()[unrelated])
        }

    @Test
    fun `malformed persisted input recovers and can be replaced`() =
        runTest {
            val store = createStore(backgroundScope)
            store.edit { it[stringPreferencesKey("control_effects_config")] = "{broken json" }
            val settings = settings(store)
            assertEquals(ControlEffectsConfig(), settings.controlEffectsConfig.first())

            val repaired = ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE, glowWidthDp = 32f)
            settings.updateControlEffectsConfig(repaired)
            assertEquals(repaired, settings.controlEffectsConfig.first())
        }

    @Test
    fun `observers receive live changes and ignore unrelated settings writes`() =
        runTest {
            val store = createStore(backgroundScope)
            val settings = settings(store)
            val observed = mutableListOf<ControlEffectsConfig>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                settings.controlEffectsConfig.collect { observed += it }
            }
            runCurrent()
            val blue = ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE)
            val custom = ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM, glowWidthDp = 80f)
            settings.updateControlEffectsConfig(blue)
            runCurrent()
            settings.updateControlEffectsConfig(custom)
            runCurrent()
            store.edit { it[stringPreferencesKey("unrelated")] = "changed" }
            runCurrent()

            assertEquals(listOf(ControlEffectsConfig(), blue, custom), observed)
        }

    @Test
    fun `saved choices survive closing and reopening the datastore`() =
        runTest {
            val expected = ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM, customColorArgb = 0xFFFC7A45.toInt())
            val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                settings(createStore(firstScope)).updateControlEffectsConfig(expected)
            } finally {
                firstScope.coroutineContext.job.cancelAndJoin()
            }

            val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                assertEquals(expected, settings(createStore(secondScope)).controlEffectsConfig.first())
            } finally {
                secondScope.coroutineContext.job.cancelAndJoin()
            }
        }

    private fun createStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "control_effects.preferences_pb") }

    private fun TestScope.logger(): SettingsChangeLogger =
        SettingsChangeLogger(RecordingServerLogRepository(), StandardTestDispatcher(testScheduler), 0L)

    private fun TestScope.settings(store: DataStore<Preferences>): ControlEffectsSettings = ControlEffectsSettingsImpl(store, logger())
}

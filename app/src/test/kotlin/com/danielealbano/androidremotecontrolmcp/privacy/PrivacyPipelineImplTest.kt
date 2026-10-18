package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PrivacyPipelineImpl")
class PrivacyPipelineImplTest {
    private val manager = mockk<PrivacyModeManager>()
    private val engine = mockk<RedactionEngine>()
    private val pipeline = PrivacyPipelineImpl(manager, engine)

    private fun configure(
        config: PrivacyModeConfig,
        status: PrivacyModeStatus,
    ) {
        coEvery { manager.currentConfig() } returns config
        every { manager.status } returns MutableStateFlow(status)
    }

    private fun tree(): MultiWindowResult {
        val root = AccessibilityNodeData(id = "root", text = "a@b.com", bounds = BoundsData(0, 0, 10, 10))
        return MultiWindowResult(listOf(WindowData(windowId = 1, windowType = "APPLICATION", tree = root)))
    }

    @Test
    fun `processTexts passthrough when disabled`() =
        runTest {
            configure(PrivacyModeConfig(enabled = false), PrivacyModeStatus.Disabled)

            val result = pipeline.processTexts(listOf(TextItem("a@b.com", DetectionContext.EMPTY)))

            assertEquals(listOf("a@b.com"), result)
            coVerify(exactly = 0) { engine.redactTexts(any(), any()) }
        }

    @Test
    fun `processTexts throws PrivacyModeUnavailable when model required and not ready`() =
        runTest {
            configure(PrivacyModeConfig(enabled = true), PrivacyModeStatus.Unavailable("no model"))

            var thrown: Throwable? = null
            try {
                pipeline.processTexts(listOf(TextItem("Sarah", DetectionContext.EMPTY)))
            } catch (e: McpToolException.PrivacyModeUnavailable) {
                thrown = e
            }
            assertTrue(thrown is McpToolException.PrivacyModeUnavailable)
            coVerify(exactly = 0) { engine.redactTexts(any(), any()) }
        }

    @Test
    fun `processTexts delegates to engine when ready`() =
        runTest {
            val config = PrivacyModeConfig(enabled = true)
            configure(config, PrivacyModeStatus.Ready)
            coEvery { engine.redactTexts(any(), config) } returns listOf("redacted")

            val result = pipeline.processTexts(listOf(TextItem("Sarah", DetectionContext.EMPTY)))

            assertEquals(listOf("redacted"), result)
            // The gate reads the config exactly once and passes that same instance to the engine.
            coVerify(exactly = 1) { manager.currentConfig() }
        }

    @Test
    fun `PrivacyModelException maps to PrivacyModeUnavailable`() =
        runTest {
            configure(PrivacyModeConfig(enabled = true), PrivacyModeStatus.Ready)
            coEvery { engine.redactTexts(any(), any()) } throws PrivacyModelException("boom")

            var thrown: Throwable? = null
            try {
                pipeline.processTexts(listOf(TextItem("Sarah", DetectionContext.EMPTY)))
            } catch (e: McpToolException.PrivacyModeUnavailable) {
                thrown = e
            }
            assertTrue(thrown is McpToolException.PrivacyModeUnavailable)
        }

    @Test
    fun `processTree passthrough when disabled`() =
        runTest {
            configure(PrivacyModeConfig(enabled = false), PrivacyModeStatus.Disabled)
            val input = tree()

            val processed = pipeline.processTree(input)

            assertEquals(input, processed.result)
            assertTrue(processed.flaggedBounds.isEmpty())
            coVerify(exactly = 0) { engine.redactTree(any(), any()) }
        }
}

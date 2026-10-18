package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [PrivacyPipeline]: gates on the Privacy Mode config + model readiness and delegates the
 * mechanical work to [RedactionEngine]. Fails closed (throws
 * [McpToolException.PrivacyModeUnavailable]) when the model is required but unavailable or inference
 * fails; identity passthrough when Privacy Mode is disabled.
 */
@Singleton
class PrivacyPipelineImpl
    @Inject
    constructor(
        private val manager: PrivacyModeManager,
        private val engine: RedactionEngine,
    ) : PrivacyPipeline {
        override suspend fun processText(
            text: String,
            context: DetectionContext,
        ): String = processTexts(listOf(TextItem(text, context))).first()

        override suspend fun processTexts(items: List<TextItem>): List<String> {
            val config = gatedConfigOrNull() ?: return items.map { it.text }
            return failClosed { engine.redactTexts(items, config) }
        }

        override suspend fun processTree(result: MultiWindowResult): ProcessedTree {
            val config = gatedConfigOrNull() ?: return ProcessedTree(result, emptyList())
            return failClosed { engine.redactTree(result, config) }
        }

        /** Null when Privacy Mode is disabled. Throws when a model-backed category is on but not Ready. */
        private suspend fun gatedConfigOrNull(): PrivacyModeConfig? {
            val config = manager.currentConfig()
            if (!config.enabled) return null
            if (config.modelRequired() && manager.status.value !is PrivacyModeStatus.Ready) {
                throw McpToolException.PrivacyModeUnavailable(
                    "Privacy mode is enabled but the on-device detection model is not available",
                )
            }
            return config
        }

        private suspend fun <T> failClosed(block: suspend () -> T): T =
            try {
                block()
            } catch (e: PrivacyModelException) {
                throw McpToolException.PrivacyModeUnavailable(e.message ?: "model inference failed", e)
            }
    }

package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ContextExtractor")
class ContextExtractorTest {
    private val extractor = ContextExtractor()

    @Test
    fun `splitResourceId strips package and splits snake camel`() {
        assertEquals(listOf("card", "number", "field"), extractor.splitResourceId("com.app:id/card_number_field"))
        assertEquals(listOf("card", "number", "field"), extractor.splitResourceId("com.app:id/cardNumberField"))
        assertEquals(listOf("some", "field"), extractor.splitResourceId("android:id/some-field"))
        assertEquals(emptyList<String>(), extractor.splitResourceId(null))
        assertEquals(emptyList<String>(), extractor.splitResourceId(""))
    }

    @Test
    fun `extract prefers labeledBy over nearest label`() {
        val node =
            AccessibilityNodeData(
                id = "n",
                bounds = BoundsData(0, 0, 10, 10),
                editable = true,
                labeledByText = "Card number",
            )

        val context = extractor.extract(node, nearestLabel = "Nearest fallback")

        assertEquals("Card number", context.labelText)
    }

    @Test
    fun `extract uses nearest label when labeledBy absent`() {
        val node = AccessibilityNodeData(id = "n", bounds = BoundsData(0, 0, 10, 10), editable = true)

        val context = extractor.extract(node, nearestLabel = "Email address")

        assertEquals("Email address", context.labelText)
    }

    @Test
    fun `computeNearestLabels picks label above within threshold and ignores editable candidates`() {
        val label =
            AccessibilityNodeData(id = "label", text = "Email", bounds = BoundsData(0, 0, 100, 20), editable = false)
        // Editable candidate is geometrically closer but MUST be ignored as a label source.
        val editableCandidate =
            AccessibilityNodeData(id = "other", text = "typed", bounds = BoundsData(0, 26, 100, 28), editable = true)
        val field = AccessibilityNodeData(id = "field", bounds = BoundsData(0, 40, 200, 70), editable = true)
        val root =
            AccessibilityNodeData(
                id = "root",
                bounds = BoundsData(0, 0, 300, 300),
                editable = false,
                children = listOf(label, editableCandidate, field),
            )

        val result = extractor.computeNearestLabels(root)

        assertEquals("Email", result["field"])
    }

    @Test
    fun `computeNearestLabels ignores labels beyond threshold`() {
        val label =
            AccessibilityNodeData(id = "label", text = "Email", bounds = BoundsData(0, 0, 100, 20), editable = false)
        val field = AccessibilityNodeData(id = "field", bounds = BoundsData(0, 500, 200, 540), editable = true)
        val root =
            AccessibilityNodeData(
                id = "root",
                bounds = BoundsData(0, 0, 600, 600),
                editable = false,
                children = listOf(label, field),
            )

        val result = extractor.computeNearestLabels(root)

        assertEquals(null, result["field"])
    }
}

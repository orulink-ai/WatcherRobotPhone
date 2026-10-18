package com.danielealbano.androidremotecontrolmcp.privacy

/**
 * A single detected PII span within a piece of text: the [category], the half-open character
 * range [[start], [end]), and which layer produced it ([source]).
 */
data class PiiDetection(
    val category: PiiCategory,
    val start: Int,
    val end: Int,
    val source: Source,
) {
    enum class Source { STRUCTURAL, DETERMINISTIC, MODEL }
}

/**
 * Contextual signals used to boost/suppress detection and to build the constructed model input.
 * [contextText] flattens the free-form signals into a single lowercase string for keyword matching.
 */
data class DetectionContext(
    val resourceIdWords: List<String> = emptyList(),
    val hintText: String? = null,
    val labelText: String? = null,
    val contentDescription: String? = null,
    val isPassword: Boolean = false,
    val isEditable: Boolean = false,
    val fieldName: String? = null,
) {
    fun contextText(): String =
        listOfNotNull(
            resourceIdWords.joinToString(" ").takeIf { it.isNotEmpty() },
            hintText,
            labelText,
            contentDescription,
            fieldName,
        ).joinToString(" ").lowercase()

    companion object {
        val EMPTY = DetectionContext()

        fun forField(fieldName: String): DetectionContext = DetectionContext(fieldName = fieldName)
    }
}

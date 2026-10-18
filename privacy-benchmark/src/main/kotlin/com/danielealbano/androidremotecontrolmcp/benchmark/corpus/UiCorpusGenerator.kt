package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.ContextExtractor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import kotlin.random.Random

/**
 * Seeded UI-shaped corpus: builds real [AccessibilityNodeData] screens (label/value rows across five
 * context styles) and derives each sample's context through the REAL [ContextExtractor], so context
 * construction — including geometric nearest-label — is part of what the benchmark measures.
 */
class UiCorpusGenerator(
    private val seed: Long = DEFAULT_SEED,
) {
    private val contextExtractor = ContextExtractor()
    private val values = UiValueFactory()

    fun generate(): LoadedCorpus {
        val rng = Random(seed)
        val samples = mutableListOf<BenchmarkSample>()
        for (language in LANGUAGES) {
            repeat(SCREENS_PER_LANGUAGE) { screen ->
                samples += generateScreen(language, screen, rng)
            }
        }
        return LoadedCorpus("ui-synthetic", samples, droppedRows = 0, unknownLabels = emptyMap())
    }

    private fun generateScreen(
        language: String,
        screen: Int,
        rng: Random,
    ): List<BenchmarkSample> {
        val kinds =
            (POSITIVE_KINDS.shuffled(rng).take(POSITIVE_ROWS) + NEGATIVE_KINDS.shuffled(rng).take(NEGATIVE_ROWS))
                .shuffled(rng)
        val rows =
            kinds.mapIndexed { index, kind ->
                val style = ContextStyle.entries[index % ContextStyle.entries.size]
                buildRow(RowSpec(language, screen, index, kind, style), rng)
            }
        val root =
            AccessibilityNodeData(
                id = "s$screen-root",
                bounds = BoundsData(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT),
                visible = true,
                children = rows.flatMap { it.nodes },
            )
        val goldByNode = rows.flatMap { it.goldByNode.entries }.associate { it.key to it.value }
        val nearest = contextExtractor.computeNearestLabels(root)
        val samples = mutableListOf<BenchmarkSample>()

        fun visit(node: AccessibilityNodeData) {
            val text = node.text
            if (!text.isNullOrBlank()) {
                samples +=
                    BenchmarkSample(
                        id = "b-$language-${node.id}",
                        text = text,
                        context = contextExtractor.extract(node, nearest[node.id]),
                        gold = goldByNode[node.id].orEmpty(),
                        language = language,
                    )
            }
            node.children.forEach(::visit)
        }
        root.children.forEach(::visit)
        return samples
    }

    private fun buildRow(
        spec: RowSpec,
        rng: Random,
    ): Row {
        val y = TOP_MARGIN + spec.index * ROW_SPACING
        val idBase = "s${spec.screen}-r${spec.index}"
        val label = labelFor(spec.kind, spec.language)
        val (value, gold) = values.valueFor(spec.kind, spec.language, rng)
        val nodes = mutableListOf<AccessibilityNodeData>()
        if (spec.style == ContextStyle.GEOMETRIC || spec.style == ContextStyle.LABELED_BY) {
            nodes +=
                AccessibilityNodeData(
                    id = "$idBase-label",
                    text = label,
                    bounds = BoundsData(LEFT, y, NODE_RIGHT, y + LABEL_HEIGHT),
                    visible = true,
                )
        }
        nodes +=
            AccessibilityNodeData(
                id = "$idBase-value",
                text = value,
                bounds = BoundsData(LEFT, y + VALUE_TOP_OFFSET, NODE_RIGHT, y + VALUE_BOTTOM_OFFSET),
                editable = spec.kind.editableField && spec.style != ContextStyle.NONE,
                isPassword = spec.kind == FieldKind.PASSWORD,
                labeledByText = label.takeIf { spec.style == ContextStyle.LABELED_BY },
                hintText = label.takeIf { spec.style == ContextStyle.HINT },
                resourceId =
                    ("com.example.app:id/" + spec.kind.resourceWords)
                        .takeIf { spec.style == ContextStyle.RESOURCE_ID },
                visible = true,
            )
        return Row(nodes, mapOf("$idBase-value" to gold))
    }

    private fun labelFor(
        kind: FieldKind,
        language: String,
    ): String {
        val labels = LABELS.getValue(kind)
        return labels[language] ?: labels.getValue("en")
    }

    private data class RowSpec(
        val language: String,
        val screen: Int,
        val index: Int,
        val kind: FieldKind,
        val style: ContextStyle,
    )

    private data class Row(
        val nodes: List<AccessibilityNodeData>,
        val goldByNode: Map<String, List<GoldSpan>>,
    )

    companion object {
        const val DEFAULT_SEED = 20260803L
        const val SCREENS_PER_LANGUAGE = 40
        val LANGUAGES = listOf("en", "fr", "de", "es", "it", "nl", "hi", "te")

        private const val POSITIVE_ROWS = 6
        private const val NEGATIVE_ROWS = 4
        private const val TOP_MARGIN = 100
        private const val ROW_SPACING = 320
        private const val SCREEN_WIDTH = 1080
        private const val SCREEN_HEIGHT = 3500
        private const val LEFT = 40
        private const val NODE_RIGHT = 400
        private const val LABEL_HEIGHT = 40
        private const val VALUE_TOP_OFFSET = 50
        private const val VALUE_BOTTOM_OFFSET = 130

        private val LABELS: Map<FieldKind, Map<String, String>> =
            mapOf(
                FieldKind.NAME to
                    mapOf(
                        "en" to "Full name",
                        "fr" to "Nom complet",
                        "de" to "Vollständiger Name",
                        "es" to "Nombre completo",
                        "it" to "Nome completo",
                        "nl" to "Volledige naam",
                    ),
                FieldKind.EMAIL to
                    mapOf(
                        "en" to "Email",
                        "fr" to "E-mail",
                        "de" to "E-Mail",
                        "es" to "Correo electrónico",
                        "it" to "Email",
                        "nl" to "E-mail",
                    ),
                FieldKind.PHONE to
                    mapOf(
                        "en" to "Phone number",
                        "fr" to "Téléphone",
                        "de" to "Telefonnummer",
                        "es" to "Teléfono",
                        "it" to "Telefono",
                        "nl" to "Telefoonnummer",
                    ),
                FieldKind.CARD to
                    mapOf(
                        "en" to "Card number",
                        "fr" to "Numéro de carte",
                        "de" to "Kartennummer",
                        "es" to "Número de tarjeta",
                        "it" to "Numero carta",
                        "nl" to "Kaartnummer",
                    ),
                FieldKind.IBAN to mapOf("en" to "IBAN"),
                FieldKind.NATIONAL_ID to
                    mapOf(
                        "en" to "Social security number",
                        "fr" to "Numéro de sécurité sociale",
                        "de" to "Steuernummer",
                        "es" to "DNI",
                        "it" to "Codice fiscale",
                        "nl" to "BSN",
                        "hi" to "Tax ID",
                        "te" to "Tax ID",
                    ),
                FieldKind.PASSWORD to
                    mapOf(
                        "en" to "Password",
                        "fr" to "Mot de passe",
                        "de" to "Passwort",
                        "es" to "Contraseña",
                        "it" to "Password",
                        "nl" to "Wachtwoord",
                    ),
                FieldKind.API_KEY to mapOf("en" to "Access token"),
                FieldKind.ADDRESS to
                    mapOf(
                        "en" to "Address",
                        "fr" to "Adresse",
                        "de" to "Adresse",
                        "es" to "Dirección",
                        "it" to "Indirizzo",
                        "nl" to "Adres",
                    ),
                FieldKind.SENTENCE_NAME to mapOf("en" to "Message"),
                FieldKind.ORDER to mapOf("en" to "Order number"),
                FieldKind.TRACKING to mapOf("en" to "Tracking number"),
                FieldKind.REFERENCE to mapOf("en" to "Reference"),
                FieldKind.INVOICE to mapOf("en" to "Invoice number"),
                FieldKind.PLAIN to mapOf("en" to "Status"),
            )
        private val POSITIVE_KINDS = FieldKind.entries.filter { it.category != null }
        private val NEGATIVE_KINDS = FieldKind.entries.filter { it.category == null }
    }
}

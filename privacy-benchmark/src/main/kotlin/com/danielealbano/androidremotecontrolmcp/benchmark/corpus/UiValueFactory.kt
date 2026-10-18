package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import kotlin.random.Random

/** One (value, gold spans) pair per [FieldKind]; checksum values delegate to [IdValueGenerators]. */
class UiValueFactory {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    fun valueFor(
        kind: FieldKind,
        language: String,
        rng: Random,
    ): Pair<String, List<GoldSpan>> =
        if (kind == FieldKind.SENTENCE_NAME) {
            sentenceWithName(language, rng)
        } else if (kind.category != null) {
            positiveValue(kind, language, rng)
        } else {
            negativeValue(kind, rng) to emptyList()
        }

    private fun positiveValue(
        kind: FieldKind,
        language: String,
        rng: Random,
    ): Pair<String, List<GoldSpan>> {
        val value =
            when (kind) {
                FieldKind.NAME -> {
                    fullName(language, rng)
                }

                FieldKind.EMAIL -> {
                    email(rng)
                }

                FieldKind.PHONE -> {
                    phone(language, rng)
                }

                FieldKind.CARD -> {
                    IdValueGenerators.card(rng)
                }

                FieldKind.IBAN -> {
                    IdValueGenerators.iban(language, rng)
                }

                FieldKind.NATIONAL_ID -> {
                    IdValueGenerators.nationalId(language, rng)
                }

                FieldKind.PASSWORD -> {
                    PASSWORD_POOL[rng.nextInt(PASSWORD_POOL.size)] + RandomText.digits(rng, PASSWORD_SUFFIX)
                }

                FieldKind.API_KEY -> {
                    apiKey(rng)
                }

                FieldKind.ADDRESS -> {
                    address(language, rng)
                }

                else -> {
                    error("not a positive field kind: $kind")
                }
            }
        return value to listOf(GoldSpan(0, value.length, requireNotNull(kind.category)))
    }

    private fun negativeValue(
        kind: FieldKind,
        rng: Random,
    ): String =
        when (kind) {
            FieldKind.ORDER -> {
                "ORD-" + RandomText.digits(rng, ORDER_DIGITS)
            }

            FieldKind.TRACKING -> {
                "1Z999AA1" + RandomText.digits(rng, TRACKING_DIGITS)
            }

            FieldKind.REFERENCE -> {
                IdValueGenerators.uuidLike(rng)
            }

            FieldKind.INVOICE -> {
                "INV-" + RandomText.digits(rng, INVOICE_GROUP_1) + "-" + RandomText.digits(rng, INVOICE_GROUP_2)
            }

            FieldKind.PLAIN -> {
                PLAIN_SENTENCES[rng.nextInt(PLAIN_SENTENCES.size)]
            }

            else -> {
                error("not a negative field kind: $kind")
            }
        }

    private fun sentenceWithName(
        language: String,
        rng: Random,
    ): Pair<String, List<GoldSpan>> {
        val template = SENTENCE_TEMPLATES.getValue(language)
        val name = fullName(language, rng)
        val start = template.indexOf(NAME_PLACEHOLDER)
        val text = template.replace(NAME_PLACEHOLDER, name)
        return text to listOf(GoldSpan(start, start + name.length, PiiCategory.NAMES))
    }

    private fun fullName(
        language: String,
        rng: Random,
    ): String {
        val pool = NAME_POOLS.getValue(language)
        return "${pool.given[rng.nextInt(pool.given.size)]} ${pool.surnames[rng.nextInt(pool.surnames.size)]}"
    }

    private fun email(rng: Random): String {
        val given = ASCII_GIVEN[rng.nextInt(ASCII_GIVEN.size)].lowercase(Locale.ROOT)
        val surname = ASCII_SURNAMES[rng.nextInt(ASCII_SURNAMES.size)].lowercase(Locale.ROOT)
        return "$given.$surname@${DOMAINS[rng.nextInt(DOMAINS.size)]}"
    }

    private fun phone(
        language: String,
        rng: Random,
    ): String {
        val regions = PHONE_REGIONS.getValue(language)
        val region = regions[rng.nextInt(regions.size)]
        val number =
            phoneUtil.getExampleNumberForType(region, PhoneNumberUtil.PhoneNumberType.MOBILE)
                ?: checkNotNull(phoneUtil.getExampleNumber(region)) { "no example number for $region" }
        return phoneUtil.format(number, PHONE_FORMATS[rng.nextInt(PHONE_FORMATS.size)])
    }

    private fun apiKey(rng: Random): String =
        when (rng.nextInt(API_KEY_SHAPES)) {
            0 -> "sk-live-" + RandomText.fromAlphabet(rng, RandomText.ALNUM_CHARS, SK_KEY_LENGTH)
            1 -> "ghp_" + RandomText.fromAlphabet(rng, RandomText.ALNUM_CHARS, GHP_KEY_LENGTH)
            else -> "AKIA" + RandomText.fromAlphabet(rng, RandomText.UPPER_ALNUM_CHARS, AKIA_KEY_LENGTH)
        }

    private fun address(
        language: String,
        rng: Random,
    ): String {
        val pool = ADDRESS_POOLS.getValue(language)
        val street = pool.streets[rng.nextInt(pool.streets.size)]
        val city = pool.cities[rng.nextInt(pool.cities.size)]
        return "${1 + rng.nextInt(MAX_BUILDING_NUM)} $street, ${zip(language, rng)} $city"
    }

    private fun zip(
        language: String,
        rng: Random,
    ): String =
        when (language) {
            "nl" -> RandomText.digits(rng, NL_ZIP_DIGITS) + " " + RandomText.fromPattern(rng, "LL")
            "hi", "te" -> RandomText.digits(rng, IN_ZIP_DIGITS)
            else -> RandomText.digits(rng, DEFAULT_ZIP_DIGITS)
        }

    private data class NamePool(
        val given: List<String>,
        val surnames: List<String>,
    )

    private data class AddressPool(
        val streets: List<String>,
        val cities: List<String>,
    )

    companion object {
        private const val API_KEY_SHAPES = 3
        private const val SK_KEY_LENGTH = 24
        private const val GHP_KEY_LENGTH = 36
        private const val AKIA_KEY_LENGTH = 16
        private const val PASSWORD_SUFFIX = 2
        private const val ORDER_DIGITS = 9
        private const val TRACKING_DIGITS = 8
        private const val INVOICE_GROUP_1 = 4
        private const val INVOICE_GROUP_2 = 5
        private const val NL_ZIP_DIGITS = 4
        private const val IN_ZIP_DIGITS = 6
        private const val DEFAULT_ZIP_DIGITS = 5
        private const val MAX_BUILDING_NUM = 200
        private const val NAME_PLACEHOLDER = "%NAME%"

        private val PHONE_FORMATS =
            listOf(
                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL,
                PhoneNumberUtil.PhoneNumberFormat.NATIONAL,
                PhoneNumberUtil.PhoneNumberFormat.E164,
            )
        private val PHONE_REGIONS =
            mapOf(
                "en" to listOf("US", "GB"),
                "fr" to listOf("FR"),
                "de" to listOf("DE"),
                "es" to listOf("ES"),
                "it" to listOf("IT"),
                "nl" to listOf("NL"),
                "hi" to listOf("IN"),
                "te" to listOf("IN"),
            )
        private val DOMAINS = listOf("example.com", "mail.example.org", "corp.example.net")
        private val ASCII_GIVEN = listOf("James", "Emma", "Lucas", "Sofia", "Arjun", "Priya", "Marco", "Nina")
        private val ASCII_SURNAMES =
            listOf("Miller", "Rossi", "Silva", "Novak", "Sharma", "Reddy", "Weber", "Janssen")
        private val PASSWORD_POOL = listOf("Tr0ub4dor&", "S3cure!pass", "correct-horse-B1", "Xk9#mQpL")
        private val PLAIN_SENTENCES =
            listOf(
                "Settings saved successfully",
                "Your download has finished",
                "Sync completed without errors",
                "Update available for two apps",
            )
        private val SENTENCE_TEMPLATES =
            mapOf(
                "en" to "Please call %NAME% when the meeting ends",
                "fr" to "Merci d'appeler %NAME% après la réunion",
                "de" to "Bitte rufen Sie %NAME% nach dem Termin an",
                "es" to "Por favor llama a %NAME% después de la reunión",
                "it" to "Chiama %NAME% dopo la riunione",
                "nl" to "Bel %NAME% na de vergadering",
                "hi" to "कृपया बैठक के बाद %NAME% को फोन करें",
                "te" to "సమావేశం తర్వాత %NAME% కి కాల్ చేయండి",
            )
        private val NAME_POOLS =
            mapOf(
                "en" to
                    NamePool(
                        listOf("Oliver", "Amelia", "Henry", "Isla", "George", "Freya"),
                        listOf("Walker", "Hughes", "Bennett", "Foster", "Dawson", "Pearce"),
                    ),
                "fr" to
                    NamePool(
                        listOf("Léa", "Hugo", "Chloé", "Louis", "Manon", "Jules"),
                        listOf("Moreau", "Lefèvre", "Garnier", "Chevalier", "Perrot", "Blanchard"),
                    ),
                "de" to
                    NamePool(
                        listOf("Lena", "Finn", "Marie", "Jonas", "Clara", "Felix"),
                        listOf("Schneider", "Hoffmann", "Wagner", "Becker", "Krüger", "Vogel"),
                    ),
                "es" to
                    NamePool(
                        listOf("Lucía", "Mateo", "Valeria", "Diego", "Carmen", "Álvaro"),
                        listOf("García", "Fernández", "Navarro", "Iglesias", "Molina", "Serrano"),
                    ),
                "it" to
                    NamePool(
                        listOf("Giulia", "Lorenzo", "Aurora", "Matteo", "Elisa", "Davide"),
                        listOf("Ricci", "Marino", "Greco", "Gallo", "Ferrara", "Rinaldi"),
                    ),
                "nl" to
                    NamePool(
                        listOf("Sanne", "Daan", "Fleur", "Bram", "Lotte", "Thijs"),
                        listOf("de Vries", "van Dijk", "Bakker", "Visser", "Smit", "Mulder"),
                    ),
                "hi" to
                    NamePool(
                        listOf("आरव", "अनन्या", "विहान", "दिया", "कबीर", "मीरा"),
                        listOf("शर्मा", "वर्मा", "गुप्ता", "सिंह", "मेहता", "जोशी"),
                    ),
                "te" to
                    NamePool(
                        listOf("ఆరవ్", "సాన్వి", "విహాన్", "ఆద్య", "రేయాన్", "ఇషా"),
                        listOf("రెడ్డి", "రావు", "నాయుడు", "శర్మ", "చౌదరి", "వర్మ"),
                    ),
            )
        private val ADDRESS_POOLS =
            mapOf(
                "en" to
                    AddressPool(
                        listOf("Maple Avenue", "Church Lane", "High Street"),
                        listOf("Springfield", "Riverton", "Oakdale"),
                    ),
                "fr" to
                    AddressPool(
                        listOf("Rue de la Paix", "Avenue Victor Hugo", "Boulevard Saint-Michel"),
                        listOf("Lyon", "Nantes", "Lille"),
                    ),
                "de" to
                    AddressPool(
                        listOf("Hauptstraße", "Gartenweg", "Bahnhofstraße"),
                        listOf("Freiburg", "Kassel", "Augsburg"),
                    ),
                "es" to
                    AddressPool(
                        listOf("Calle Mayor", "Avenida del Sol", "Paseo de Gracia"),
                        listOf("Sevilla", "Valencia", "Zaragoza"),
                    ),
                "it" to
                    AddressPool(
                        listOf("Via Roma", "Corso Italia", "Via Garibaldi"),
                        listOf("Torino", "Bologna", "Verona"),
                    ),
                "nl" to
                    AddressPool(
                        listOf("Kerkstraat", "Dorpsstraat", "Molenweg"),
                        listOf("Utrecht", "Haarlem", "Leiden"),
                    ),
                "hi" to
                    AddressPool(
                        listOf("MG Road", "Nehru Street", "Station Road"),
                        listOf("Pune", "Jaipur", "Lucknow"),
                    ),
                "te" to
                    AddressPool(
                        listOf("Tank Bund Road", "Jubilee Hills Road", "NTR Marg"),
                        listOf("Hyderabad", "Vijayawada", "Warangal"),
                    ),
            )
    }
}

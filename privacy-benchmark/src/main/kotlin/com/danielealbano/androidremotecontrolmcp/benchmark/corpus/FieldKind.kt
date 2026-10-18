package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory

enum class ContextStyle { LABELED_BY, GEOMETRIC, RESOURCE_ID, HINT, NONE }

enum class FieldKind(
    val category: PiiCategory?,
    val editableField: Boolean,
    val resourceWords: String,
) {
    NAME(PiiCategory.NAMES, true, "full_name"),
    EMAIL(PiiCategory.EMAILS, true, "email_address"),
    PHONE(PiiCategory.PHONE_NUMBERS, true, "phone_number"),
    CARD(PiiCategory.CARDS_AND_IBAN, true, "card_number"),
    IBAN(PiiCategory.CARDS_AND_IBAN, true, "iban"),
    NATIONAL_ID(PiiCategory.NATIONAL_IDS, true, "national_id_number"),
    PASSWORD(PiiCategory.CREDENTIALS, true, "password"),
    API_KEY(PiiCategory.CREDENTIALS, true, "access_token"),
    ADDRESS(PiiCategory.ADDRESSES, true, "home_address"),
    SENTENCE_NAME(PiiCategory.NAMES, false, "message_body"),
    ORDER(null, false, "order_number"),
    TRACKING(null, false, "tracking_number"),
    REFERENCE(null, false, "reference_id"),
    INVOICE(null, false, "invoice_number"),
    PLAIN(null, false, "status_text"),
}

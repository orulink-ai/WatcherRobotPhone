package com.danielealbano.androidremotecontrolmcp.privacy

enum class PiiCategory(
    val placeholderToken: String,
    val requiresModel: Boolean,
) {
    CREDENTIALS("CREDENTIAL", requiresModel = false),
    CARDS_AND_IBAN("CARD", requiresModel = false),
    EMAILS("EMAIL", requiresModel = false),
    PHONE_NUMBERS("PHONE", requiresModel = false),
    NAMES("NAME", requiresModel = true),
    ADDRESSES("ADDRESS", requiresModel = true),
    NATIONAL_IDS("ID", requiresModel = true),
}

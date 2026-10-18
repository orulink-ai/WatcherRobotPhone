package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext

/**
 * Lowercase keyword sets matched as substrings against [DetectionContext.contextText] to boost or
 * suppress deterministic detections.
 */
object ContextKeywords {
    val CREDENTIAL =
        setOf(
            "password",
            "passwd",
            "pwd",
            "passcode",
            "pin",
            "otp",
            "2fa",
            "mfa",
            "verification code",
            "security code",
            "secret",
            "token",
            "credential",
            "cvv",
            "cvc",
            "contraseña",
            "passwort",
            "mot de passe",
            "senha",
            "wachtwoord",
        )

    val CARD_POSITIVE =
        setOf(
            "card",
            "credit",
            "debit",
            "visa",
            "mastercard",
            "amex",
            "pan",
            "kaart",
            "carte",
            "tarjeta",
            "carta",
            "karte",
        )

    val CARD_NEGATIVE =
        setOf("tracking", "order", "imei", "serial", "invoice", "ticket", "reference")

    val NATIONAL_ID =
        setOf(
            "ssn",
            "social security",
            "national id",
            "tax id",
            "taxpayer",
            "vat",
            "passport",
            "driver licen",
            "driving licen",
            "id card",
            "identity",
            "codice fiscale",
            "steuernummer",
            "nif",
            "nie",
            "dni",
            "bsn",
            "cpf",
            "insurance number",
        )

    fun matches(
        context: DetectionContext,
        keywords: Set<String>,
    ): Boolean {
        val text = context.contextText()
        if (text.isEmpty()) return false
        return keywords.any { text.contains(it) }
    }
}

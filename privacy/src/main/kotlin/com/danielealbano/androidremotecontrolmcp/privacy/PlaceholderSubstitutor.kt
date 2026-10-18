package com.danielealbano.androidremotecontrolmcp.privacy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reverses pseudonymization on tool arguments: replaces every placeholder token resolvable in the
 * [PseudonymStore] with its original value, so an agent can act on `EMAIL#a1b2c` and the tool operates
 * on the real address. Unresolvable placeholder-shaped tokens (evicted, or merely coincidental) are
 * left untouched.
 */
@Singleton
class PlaceholderSubstitutor
    @Inject
    constructor(
        private val pseudonymStore: PseudonymStore,
    ) {
        fun substitute(text: String): String =
            PseudonymStore.PLACEHOLDER_PATTERN.replace(text) { match ->
                pseudonymStore.resolve(match.value) ?: match.value
            }
    }

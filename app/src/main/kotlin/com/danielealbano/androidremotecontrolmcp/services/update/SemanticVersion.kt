package com.danielealbano.androidremotecontrolmcp.services.update

/**
 * A Semantic Versioning value used to compare the installed app version against the latest published
 * GitHub release. The `major.minor.patch` core plus the pre-release segment participate in ordering;
 * build metadata (the `+…` suffix, e.g. the git-describe commit hash) is parsed but ignored, per the
 * SemVer spec.
 *
 * Ordering follows SemVer precedence: cores compare numerically; a version WITH a pre-release sorts
 * below the matching release (`1.2.0-beta < 1.2.0`); two pre-releases of the same core compare by
 * their dot-separated identifiers (numeric identifiers numerically and below alphanumerics, a larger
 * set of identifiers above a smaller one when all preceding identifiers are equal).
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String = "",
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    /** The canonical `major.minor.patch` string, without a leading `v` or any pre-release/build suffix. */
    fun toCoreString(): String = "$major.$minor.$patch"

    companion object {
        // Optional leading `v`, then the numeric core, then an optional `-pre-release` segment and an
        // optional `+build` metadata segment (either/both may be absent).
        private val PATTERN =
            Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$""")

        /** Parses a version string (e.g. `v1.11.0`, `1.11.0-dev.7+abc1234`), or returns null if malformed. */
        fun parse(raw: String): SemanticVersion? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            // Groups 1-3 are `\d+`, so toIntOrNull can only fail on overflow; treat that as malformed.
            val major = match.groupValues[1].toIntOrNull()
            val minor = match.groupValues[2].toIntOrNull()
            val patch = match.groupValues[3].toIntOrNull()
            return if (major != null && minor != null && patch != null) {
                SemanticVersion(major, minor, patch, preRelease = match.groupValues[4])
            } else {
                null
            }
        }
    }
}

// A release (empty pre-release) outranks a matching pre-release; otherwise compare identifier lists.
private fun comparePreRelease(
    a: String,
    b: String,
): Int =
    when {
        a == b -> 0
        a.isEmpty() -> 1
        b.isEmpty() -> -1
        else -> compareIdentifierLists(a.split('.'), b.split('.'))
    }

private fun compareIdentifierLists(
    aParts: List<String>,
    bParts: List<String>,
): Int {
    for (i in 0 until minOf(aParts.size, bParts.size)) {
        val cmp = compareIdentifier(aParts[i], bParts[i])
        if (cmp != 0) return cmp
    }
    return aParts.size.compareTo(bParts.size)
}

private fun compareIdentifier(
    a: String,
    b: String,
): Int {
    val aNum = a.toIntOrNull()
    val bNum = b.toIntOrNull()
    return when {
        aNum != null && bNum != null -> aNum.compareTo(bNum)

        // A numeric identifier always has lower precedence than an alphanumeric one.
        aNum != null -> -1

        bNum != null -> 1

        else -> a.compareTo(b)
    }
}

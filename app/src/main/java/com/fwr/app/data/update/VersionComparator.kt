package com.fwr.app.data.update

/**
 * Minimal semantic-version comparison used by the in-app updater.
 *
 * Accepts tags such as `v0.5.0`, `0.5`, `0.5.0-beta1` or `1.2.3+42`. Pre-release / build
 * suffixes are ignored for ordering (GitHub's `/releases/latest` already excludes pre-releases).
 */
object VersionComparator {

    fun parse(version: String): List<Int> {
        val core = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .substringBefore(' ')
        if (core.isEmpty()) return emptyList()
        return core.split('.').map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }
    }

    /** Returns a negative number if [a] < [b], zero if equal, positive if [a] > [b]. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean {
        if (parse(candidate).isEmpty()) return false
        return compare(candidate, current) > 0
    }

    /** Normalised display form without a leading `v`. */
    fun clean(version: String): String = version.trim().removePrefix("v").removePrefix("V")
}

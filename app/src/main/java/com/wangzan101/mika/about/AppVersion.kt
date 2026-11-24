package com.wangzan101.mika.about

/**
 * Version comparison following the "major.minor.patch[-prerelease][+build]" convention: a leading v is stripped, the numeric
 * segments are compared one by one (missing ones count as 0),
 * when the numbers are equal a final release is newer than one with a prerelease suffix, and when both have suffixes the
 * dot-separated segments are compared (numbers numerically, text lexicographically, numeric segments before text segments).
 * The build suffix (after +) takes no part in the comparison.
 */
object AppVersion {
    fun normalize(v: String): String = v.trim().removePrefix("v").removePrefix("V").trim()

    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            preA == null && preB == null -> 0
            preA == null -> 1
            preB == null -> -1
            else -> comparePre(preA, preB)
        }
    }

    /** [candidate] is newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun split(v: String): Pair<List<Int>, String?> {
        val s = normalize(v).substringBefore('+')
        val core = s.substringBefore('-')
        val pre = if ('-' in s) s.substringAfter('-').takeIf { it.isNotEmpty() } else null
        val parts = core.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return parts to pre
    }

    private fun comparePre(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until minOf(pa.size, pb.size)) {
            val na = pa[i].toIntOrNull()
            val nb = pb[i].toIntOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> pa[i].compareTo(pb[i])
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}

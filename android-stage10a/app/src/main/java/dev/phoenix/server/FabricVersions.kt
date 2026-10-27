package dev.phoenix.server

/**
 * Fabric Loader's version rules, enough to check a mod's `depends` and `breaks` before the server tries to start it.
 * Versions are `1.2.3[-pre][+build]` with any number of numeric parts; predicates are space-separated terms that must
 * all match (`>=1.2 <2`, `~26.2`, `^0.19`, `26.2.x`, `*`), and a list of predicates matches when any one does.
 * Anything Fabric would read differently returns null ("can't tell"), which callers show as a warning, never a block.
 */
object FabricVersions {
    class Version(val parts: List<Int>, val pre: List<String>) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            for (i in 0 until maxOf(parts.size, other.parts.size)) {
                val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            // A pre-release sorts before its release: 26.2-rc1 < 26.2.
            if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
            for (i in 0 until minOf(pre.size, other.pre.size)) {
                val a = pre[i]; val b = other.pre[i]
                val an = a.toIntOrNull(); val bn = b.toIntOrNull()
                val c = when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
                if (c != 0) return c
            }
            return pre.size.compareTo(other.pre.size)
        }
    }

    /** Null when [text] isn't a semantic version; Fabric then only compares it as text. */
    fun parse(text: String): Version? {
        val core = text.trim().substringBefore('+')
        if (core.isEmpty()) return null
        val main = core.substringBefore('-')
        val pre = if ('-' in core) core.substringAfter('-').split('.') else emptyList()
        val parts = main.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        if (pre.any { it.isEmpty() }) return null
        return Version(parts, pre)
    }

    /** True when [version] satisfies any of [predicates]; null when that can't be decided. */
    fun matchesAny(predicates: List<String>, version: String): Boolean? {
        if (predicates.isEmpty()) return true
        var unknown = false
        for (predicate in predicates) when (matches(predicate, version)) {
            true -> return true
            null -> unknown = true
            false -> Unit
        }
        return if (unknown) null else false
    }

    fun matches(predicate: String, version: String): Boolean? {
        val terms = predicate.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty() || terms == listOf("*")) return true
        var unknown = false
        for (term in terms) when (term(term, version)) {
            false -> return false
            null -> unknown = true
            true -> Unit
        }
        return if (unknown) null else true
    }

    private fun term(term: String, version: String): Boolean? {
        if (term == "*") return true
        val op = listOf(">=", "<=", ">", "<", "=", "~", "^").firstOrNull { term.startsWith(it) } ?: ""
        val text = term.removePrefix(op)
        val wildcard = text.split('.').indexOfFirst { it == "x" || it == "X" || it == "*" }
        val have = parse(version)
        if (wildcard >= 0) {
            // 26.2.x: the parts before the wildcard must be equal; only meaningful as an equality.
            if (op != "" && op != "=") return null
            val prefix = text.split('.').take(wildcard).map { it.toIntOrNull() ?: return null }
            have ?: return null
            return prefix.indices.all { have.parts.getOrElse(it) { 0 } == prefix[it] }
        }
        val want = parse(text)
        if (want == null || have == null) return if (op == "" || op == "=") version.trim() == text else null
        val c = have.compareTo(want)
        return when (op) {
            "", "=" -> c == 0
            ">=" -> c >= 0
            "<=" -> c <= 0
            ">" -> c > 0
            "<" -> c < 0
            // ~1.2.3: same major and minor; ~1: same major.
            "~" -> c >= 0 && have.compareTo(Version(bump(want.parts, if (want.parts.size >= 2) 1 else 0), listOf("0"))) < 0
            // ^1.2.3: same major.
            "^" -> c >= 0 && have.compareTo(Version(bump(want.parts, 0), listOf("0"))) < 0
            else -> null
        }
    }

    /** The smallest version above every version that shares [parts] up to [index] (as a lowest pre-release). */
    private fun bump(parts: List<Int>, index: Int): List<Int> = parts.take(index) + (parts[index] + 1)
}

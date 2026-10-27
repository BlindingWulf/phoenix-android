package dev.phoenix.server

/** Parses the first-party HTML bootstrap without executing scripts or decoding raw script text. */
internal object MinecraftHowInertia {
    data class Bootstrap(val version: String, val props: Map<*, *>)

    private val scripts = Regex("""<script\b([^>]*)>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tags = Regex("""<(?:div|main)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val entities = Regex("""&(?:quot|apos|amp|lt|gt|#\d+|#x[0-9a-fA-F]+);""")

    fun parseHtmlBootstrap(html: String): Bootstrap? = runCatching {
        require(html.length <= 2 * 1024 * 1024)
        val candidates = mutableListOf<String>()
        for (script in scripts.findAll(html)) {
            val attrs = attrs(script.groupValues[1])
            if (attrs["data-page"] == "app" && attrs["type"].equals("application/json", true)) {
                candidates += script.groupValues[2] // Inertia v3 uses raw JSON, including literal HTML entities.
            }
        }
        for (tag in tags.findAll(html)) {
            attrs(tag.groupValues[1])["data-page"]?.takeIf { it.startsWith("{") || it.startsWith("&") }
                ?.let { candidates += decodeAttribute(it) }
        }
        require(candidates.size == 1)
        val page = Json.parse(candidates.single()) as? Map<*, *> ?: return null
        val version = page["version"] as? String ?: return null
        require(version.isNotBlank() && version.length <= 256 && version.none { it.isISOControl() })
        val props = page["props"] as? Map<*, *> ?: return null
        Bootstrap(version, props)
    }.getOrNull()

    private fun attrs(source: String): Map<String, String> = attributes.findAll(source).associate {
        it.groupValues[1].lowercase(java.util.Locale.ROOT) to (it.groups[2]?.value ?: it.groupValues[3])
    }

    private fun decodeAttribute(source: String): String = entities.replace(source) { match ->
        when (val name = match.value.substring(1, match.value.length - 1)) {
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            else -> {
                val number = if (name.startsWith("#x")) name.substring(2).toInt(16) else name.substring(1).toInt()
                require(Character.isValidCodePoint(number) && number !in 0xD800..0xDFFF)
                String(Character.toChars(number))
            }
        }
    }
}

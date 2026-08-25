package accountsx.build

import java.io.File

/**
 * Minimal TOML reader covering exactly the subset used by `gradle/adapters.toml`:
 * array-of-table headers (`[[mc]]`), quoted-string values and bare booleans.
 *
 * Why hand-rolled: `settings.gradle.kts` needs the same data before `buildSrc`
 * exists, so the format must stay trivial enough to re-read there in a few lines
 * (see the comment in `settings.gradle.kts`). Pulling in a real TOML library
 * would put a dependency on the build-logic classpath while still not helping
 * the settings script.
 *
 * Anything outside the supported subset fails loudly instead of being ignored —
 * silently dropping a matrix entry would mean silently dropping an adapter.
 */
object Toml {

    /** Parses [file] into `table name -> list of rows`, preserving declaration order. */
    fun parseArrayTables(file: File): Map<String, List<Map<String, Any>>> {
        require(file.isFile) { "TOML file missing: ${file.absolutePath}" }

        val tables = LinkedHashMap<String, MutableList<Map<String, Any>>>()
        var current: MutableMap<String, Any>? = null

        file.readLines().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            fun fail(reason: String): Nothing =
                throw IllegalArgumentException("${file.name}:${index + 1}: $reason -> $raw")

            when {
                line.startsWith("[[") -> {
                    if (!line.endsWith("]]")) fail("unterminated array-table header")
                    val name = line.removeSurrounding("[[", "]]").trim()
                    if (name.isEmpty()) fail("empty array-table name")
                    current = LinkedHashMap<String, Any>().also {
                        tables.getOrPut(name) { mutableListOf() }.add(it)
                    }
                }

                line.startsWith("[") -> fail("plain tables are not supported, use [[name]]")

                else -> {
                    val table = current ?: fail("key-value pair before any [[table]] header")
                    val separator = line.indexOf('=')
                    if (separator < 0) fail("not a key-value pair")
                    val key = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim()
                    if (key.isEmpty()) fail("empty key")
                    table[key] = when {
                        value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
                            value.removeSurrounding("\"")

                        value == "true" -> true
                        value == "false" -> false
                        else -> fail("only quoted strings and booleans are supported")
                    }
                }
            }
        }

        return tables
    }
}

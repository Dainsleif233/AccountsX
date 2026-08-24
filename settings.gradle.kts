pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        maven("https://maven.aliyun.com/repository/public/")
    }
    val loomVersion: String = providers.gradleProperty("loomVersion").get()
    plugins {
        id("net.fabricmc.fabric-loom-remap") version loomVersion
        id("net.fabricmc.fabric-loom") version loomVersion
    }
}

rootProject.name = "AccountsX"

// Adapter subprojects come from gradle/adapters.toml — the single source of
// truth for the matrix (P0.2). Settings scripts run before buildSrc is built, so
// accountsx.build.Toml is not available here; only the `version` key of each
// [[table]] is needed to build the include paths, so it is re-read with a few
// lines. `:validateAdapterMatrix` (P0.5) asserts the toml matches the on-disk
// directories, which is what would otherwise silently drift.
include(
    *buildList {
        var prefix: String? = null
        rootDir.resolve("gradle/adapters.toml").readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("[[") -> prefix = when (val table = line.removeSurrounding("[[", "]]").trim()) {
                    "mc", "authlib", "modmenu" -> "adapters:$table:"
                    else -> throw GradleException("gradle/adapters.toml: unknown table [[$table]]")
                }

                line.startsWith("version") && prefix != null ->
                    add(prefix + line.substringAfter('=').trim().removeSurrounding("\""))
            }
        }
        check(isNotEmpty()) { "gradle/adapters.toml declares no adapters" }
    }.toTypedArray()
)

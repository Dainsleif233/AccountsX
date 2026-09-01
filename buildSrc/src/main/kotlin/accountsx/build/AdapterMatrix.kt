package accountsx.build

import java.io.File

/**
 * One Minecraft adapter declared in `gradle/adapters.toml`.
 *
 * @param version      Minecraft version; equals the `adapters/mc/<version>/` directory name
 * @param authlib      authlib adapter this MC version binds to
 * @param fabricApi    Fabric API version used for the resource-loader module
 * @param obfuscated   false for MC 26.1+ (no ProGuard): plain `fabric-loom` + `jar`,
 *                     no `officialMojangMappings()`, no `mod*` configurations
 * @param loader       Fabric Loader version (defaults to the version catalog)
 */
data class McAdapter(
    val version: String,
    val authlib: String,
    val fabricApi: String,
    val modmenu: String,
    val obfuscated: Boolean,
    val loader: String?,
    val java: String
) {
    val projectPath: String get() = "adapters:mc:$version"

    /** Loom only registers `remapJar` for obfuscated versions. */
    val jarTask: String get() = if (obfuscated) "remapJar" else "jar"

    /** Loom plugin id the leaf build script must apply. */
    val loomPluginId: String
        get() = if (obfuscated) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom"
}

/** One authlib bridge adapter (`adapters/authlib/<version>/`). */
data class AuthlibAdapter(val version: String) {
    val projectPath: String get() = "adapters:authlib:$version"
    val jarTask: String get() = "jar"
}

/**
 * The parsed adapter matrix — the single source of truth for which adapters
 * exist and which versions they pin.
 *
 * Read by `settings.gradle.kts` (indirectly, via its own tiny parser), the three
 * buildSrc adapter plugins, the root `universal` packaging task and
 * `:validateAdapterMatrix`. Nothing may re-derive this from directory listings
 * or by pattern-matching build script text.
 */
class AdapterMatrix(
    val mc: List<McAdapter>,
    val authlib: List<AuthlibAdapter>
) {
    fun mc(version: String): McAdapter = mc.firstOrNull { it.version == version }
        ?: error("Minecraft adapter '$version' is not declared in gradle/adapters.toml")

    fun authlib(version: String): AuthlibAdapter = authlib.firstOrNull { it.version == version }
        ?: error("authlib adapter '$version' is not declared in gradle/adapters.toml")

    companion object {
        const val RELATIVE_PATH: String = "gradle/adapters.toml"

        fun load(rootDir: File): AdapterMatrix {
            val tables = Toml.parseArrayTables(rootDir.resolve(RELATIVE_PATH))

            val mc = tables["mc"].orEmpty().map { row ->
                McAdapter(
                    version = row.string("mc", "version"),
                    authlib = row.string("mc", "authlib"),
                    fabricApi = row.string("mc", "fabricApi"),
                    modmenu = row.string("mc", "modmenu"),
                    java = (row["java"] as? String) ?: "17",
                    obfuscated = row.boolean("mc", "obfuscated"),
                    loader = row["loader"] as String?
                )
            }
            val authlib = tables["authlib"].orEmpty().map { row ->
                AuthlibAdapter(row.string("authlib", "version"))
            }

            require(mc.isNotEmpty()) { "$RELATIVE_PATH declares no [[mc]] adapters" }
            require(authlib.isNotEmpty()) { "$RELATIVE_PATH declares no [[authlib]] adapters" }

            val matrix = AdapterMatrix(mc, authlib)
            // Fail at configuration time rather than with an unresolvable
            // project dependency deep inside a Loom task.
            mc.forEach { matrix.authlib(it.authlib) }
            return matrix
        }

        private fun Map<String, Any>.string(table: String, key: String): String =
            this[key] as? String ?: error("[[$table]] entry $this is missing string key '$key'")

        private fun Map<String, Any>.boolean(table: String, key: String): Boolean =
            this[key] as? Boolean ?: error("[[$table]] entry $this is missing boolean key '$key'")
    }
}

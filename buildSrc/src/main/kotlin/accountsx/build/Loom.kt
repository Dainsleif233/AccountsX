package accountsx.build

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.support.serviceOf
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Reflective access to Fabric Loom internals, shared by the MC and Mod Menu
 * adapter plugins (P0.2 — previously duplicated in both).
 *
 * Reflection is unavoidable here: Gradle isolates each plugin's classpath, so
 * Loom's classes are not visible from these precompiled script plugins even
 * though Loom is applied to the same project. There is no public Loom API for
 * either operation below.
 */
object Loom {

    /**
     * The `officialMojangMappings()` dependency for this project's Minecraft version.
     *
     * Only valid for obfuscated Minecraft versions. On 26.1+ the game jar already
     * carries Mojang names and Loom throws `UnsupportedOperationException`, so the
     * caller must decide from `adapters.toml`'s `obfuscated` flag instead of
     * probing (probing was the old approach and hid real failures).
     */
    fun officialMojangMappings(project: Project): Any {
        val loom = project.extensions.getByName("loom")
        return loom.javaClass.methods.first {
            it.name == "officialMojangMappings" && it.parameterCount == 0
        }.invoke(loom)
    }

    /**
     * Resolves one Fabric API module (e.g. `fabric-resource-loader-v0`) as a
     * dependency notation, via Loom's internal `FabricApiVersions`.
     *
     * MUST be called lazily from inside a dependency provider on a
     * Loom-resolved configuration (`modRuntimeOnly` / `modImplementation`):
     * the `StackWalker` below locates Loom's ClassLoader by finding a
     * `net.fabricmc.loom.*` frame on the current call stack. Called eagerly at
     * configuration time there is no such frame and this throws.
     *
     * @param moduleVersion full module version, i.e. `<fabricApi>+<minecraft>`
     */
    fun fabricApiModule(project: Project, moduleName: String, moduleVersion: String): Dependency {
        val loomClassLoader = StackWalker.getInstance(setOf(StackWalker.Option.RETAIN_CLASS_REFERENCE))
            .walk { frames -> frames.filter { it.className.startsWith("net.fabricmc.loom.") }.findFirst() }
            .orElseThrow {
                IllegalStateException(
                    "No Loom frame on the call stack — Loom.fabricApiModule must be called from " +
                        "inside a dependency provider on a Loom-resolved configuration."
                )
            }
            .declaringClass.classLoader

        val versions = Class.forName(
            "net.fabricmc.loom.configuration.fabricapi.FabricApiVersions", true, loomClassLoader
        )

        return MethodHandles.lookup().findVirtual(
            versions, "module",
            MethodType.methodType(Dependency::class.java, String::class.java, String::class.java)
        ).invokeWithArguments(
            project.serviceOf<ObjectFactory>().newInstance(versions), moduleName, moduleVersion
        ) as Dependency
    }
}

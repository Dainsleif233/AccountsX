package accountsx.build

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.support.serviceOf
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.io.File
import java.lang.reflect.Proxy

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
     * Call lazily from a dependency provider: resolving the module version
     * downloads the fabric-api POM. Loom's ClassLoader is located from a
     * `net.fabricmc.loom.*` stack frame when resolving remapped (`mod*`)
     * configurations; non-obfuscated projects resolve plain configurations with
     * no such frame, so fall back to the loom extension's own classloader.
     *
     * @param moduleVersion full module version, i.e. `<fabricApi>+<minecraft>`
     */
    fun fabricApiModule(project: Project, moduleName: String, moduleVersion: String): Dependency {
        val loomClassLoader = StackWalker.getInstance(setOf(StackWalker.Option.RETAIN_CLASS_REFERENCE))
            .walk { frames -> frames.filter { it.className.startsWith("net.fabricmc.loom.") }.findFirst() }
            .map { it.declaringClass.classLoader }
            .orElseGet { project.extensions.getByName("loom").javaClass.classLoader }

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

    /**
     * Wires Fabric client game tests for one adapter project:
     * `fabricApi.configureTests` with a dedicated `gametest` source set, then isolates
     * the `clientGameTest` run configuration from the player's real config and home.
     *
     * Two settings are load-bearing:
     * - `enableGameTests(false)`: the default `true` makes `check` depend on the SERVER
     *   gametest run, which this client-only mod can never satisfy.
     * - ACCOUNTSX_CONFIG + user.home both point inside `build/run/clientGameTest`, the
     *   directory `clearRunDirectory` deletes; anywhere else would leak state across runs
     *   (JsonConfigStore also reads user.home/.accountsx and writes avatar caches there).
     */
    fun configureClientGameTests(project: Project) {
        val fabricApi = project.extensions.getByName("fabricApi")
        // The decorated extension also exposes a Groovy Closure overload; only the
        // Action one can be served with a dynamic proxy.
        val configureTests = fabricApi.javaClass.methods.first {
            it.name == "configureTests" && it.parameterCount == 1 &&
                it.parameterTypes[0].name == "org.gradle.api.Action"
        }

        // The proxy must implement the Action class as seen by Loom's classloader,
        // not buildSrc's view of it, or Method.invoke rejects the argument.
        val actionType = configureTests.parameterTypes[0]
        val settingsAction = Proxy.newProxyInstance(
            actionType.classLoader,
            arrayOf(actionType)
        ) { proxy, method, args ->
            when (method.name) {
                "execute" -> {
                    val settings = args!![0]
                    setProperty(settings, "getCreateSourceSet", true)
                    setProperty(settings, "getModId", "accountsx-gametest")
                    setProperty(settings, "getEnableGameTests", false)
                    setProperty(settings, "getEnableClientGameTests", true)
                    setProperty(settings, "getEula", true)
                    setProperty(settings, "getClearRunDirectory", true)
                    setProperty(settings, "getUsername", "Player0")
                    null
                }
                "toString" -> "accountsx client gametest settings"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args!![0] === proxy
                else -> null
            }
        }

        configureTests.invoke(fabricApi, settingsAction)

        val loom = project.extensions.getByName("loom")
        val runConfigs = loom.javaClass.methods.first { it.name == "getRunConfigs" && it.parameterCount == 0 }
            .invoke(loom)
        val clientGameTest = runConfigs.javaClass.methods.first {
            it.name == "getByName" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }.invoke(runConfigs, "clientGameTest")

        val runDir = project.layout.buildDirectory.dir("run/clientGameTest").get().asFile
        val env = clientGameTest.javaClass.methods.first { it.name == "getEnvironmentVars" }
            .invoke(clientGameTest)
        env.javaClass.methods.first { it.name == "put" && it.parameterCount == 2 && it.parameterTypes[1].name != "org.gradle.api.provider.Provider" }
            .invoke(env, "ACCOUNTSX_CONFIG", File(runDir, "config/accountsx.json").absolutePath)

        val sysProps = clientGameTest.javaClass.methods.first { it.name == "getSystemProperties" }
            .invoke(clientGameTest)
        sysProps.javaClass.methods.first { it.name == "put" && it.parameterCount == 2 && it.parameterTypes[1].name != "org.gradle.api.provider.Provider" }
            .invoke(sysProps, "user.home", File(runDir, "home").absolutePath)
    }

    private fun setProperty(settings: Any, getter: String, value: Any) {
        val property = settings.javaClass.methods.first { it.name == getter && it.parameterCount == 0 }
            .invoke(settings)
        // Property has both set(T) and set(Provider); only the value overload applies.
        property.javaClass.methods.first {
            it.name == "set" && it.parameterCount == 1 &&
                it.parameterTypes[0].name != "org.gradle.api.provider.Provider"
        }.invoke(property, value)
    }
}

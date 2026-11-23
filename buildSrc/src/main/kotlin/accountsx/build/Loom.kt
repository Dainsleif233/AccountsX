package accountsx.build

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.Delete
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


    /**
     * Adds a second client gametest run configuration that boots the game with
     * authlib-injector attached as a -javaagent (acceptance point 6). It inherits
     * clientGameTest, then redirects authlib's URL constants at a fixed local API
     * root and supplies prefetched root metadata so the agent does no network fetch
     * at startup. The matching test binds that same fixed port.
     *
     * The run directory is isolated and cleared like clientGameTest's; the marker
     * property accountsx.gametest.injector lets the shared test class tell this run
     * apart from the plain one (it skips when the marker is absent).
     *
     * @param agentJar      the authlib-injector agent jar (downloaded by the caller)
     * @param apiRoot       fixed local API root the agent redirects to, e.g. http://127.0.0.1:25599
     * @param prefetchedMeta root metadata JSON for -Dauthlibinjector.yggdrasil.prefetched
     */
    fun configureAuthlibInjectorGameTest(project: Project, agentJar: File, apiRoot: String, prefetchedMeta: String) {
        val loom = project.extensions.getByName("loom")
        val runConfigs = loom.javaClass.methods.first { it.name == "getRunConfigs" && it.parameterCount == 0 }
            .invoke(loom)
        val clientGameTest = runConfigs.javaClass.methods.first {
            it.name == "getByName" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }.invoke(runConfigs, "clientGameTest")

        val runDir = project.file("build/run/clientAuthlibInjectorGameTest")
        val create = runConfigs.javaClass.methods.first {
            it.name == "create" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1].name == "org.gradle.api.Action"
        }
        val actionType = create.parameterTypes[1]
        val configAction = Proxy.newProxyInstance(
            actionType.classLoader, arrayOf(actionType)
        ) { proxy, method, args ->
            when (method.name) {
                "execute" -> {
                    val run = args!![0]
                    fun call(name: String, vararg a: Any) {
                        run.javaClass.methods.first { m ->
                            m.name == name && m.parameterCount == a.size &&
                                a.indices.all { i -> m.parameterTypes[i].isAssignableFrom(a[i].javaClass) || primitiveMatch(m.parameterTypes[i], a[i]) }
                        }.invoke(run, *a)
                    }
                    call("inherit", clientGameTest)
                    call("runDir", "build/run/clientAuthlibInjectorGameTest")
                    call("environmentVariable", "ACCOUNTSX_CONFIG", File(runDir, "config/accountsx.json").absolutePath)
                    call("property", "user.home", File(runDir, "home").absolutePath)
                    call("property", "accountsx.gametest.injector", "true")
                    call("property", "authlibinjector.yggdrasil.prefetched", prefetchedMeta)
                    call("vmArg", "-javaagent:" + agentJar.absolutePath + "=" + apiRoot)
                    null
                }
                "toString" -> "accountsx authlib-injector gametest run"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args!![0] === proxy
                else -> null
            }
        }
        create.invoke(runConfigs, "clientAuthlibInjectorGameTest", configAction)

        val deleteRunDir = project.tasks.register("deleteAuthlibInjectorGameTestRunDir", Delete::class.java) {
            group = "fabric"
            delete(runDir)
        }
        project.tasks.named("runClientAuthlibInjectorGameTest") {
            dependsOn(deleteRunDir)
        }
    }

    /**
     * Turns off Loom's automatic xvfb-run wrapping for the given run tasks.
     *
     * Loom wraps with bare `xvfb-run --auto-servernum` (default screen depth), which
     * gives GLFW no usable GLX visual and SDL no sRGB framebuffer. CI wraps the Gradle
     * invocation itself with `xvfb-run -s "-screen 0 1280x720x24"` plus the mesa/EGL
     * environment; if Loom also wrapped, its inner default-depth server would shadow
     * that outer 24-bit one. So the task must not wrap a second time.
     */
    fun disableAutoXvfb(project: Project, vararg taskNames: String) {
        for (name in taskNames) {
            project.tasks.named(name) {
                val useXvfb = javaClass.methods.first { it.name == "getUseXvfb" && it.parameterCount == 0 }
                    .invoke(this)
                useXvfb.javaClass.methods.first {
                    it.name == "set" && it.parameterCount == 1 &&
                        it.parameterTypes[0].name != "org.gradle.api.provider.Provider"
                }.invoke(useXvfb, false)
            }
        }
    }

    private fun primitiveMatch(paramType: Class<*>, value: Any): Boolean =
        (paramType == java.lang.Boolean.TYPE && value is Boolean) ||
            (paramType == Integer.TYPE && value is Int)

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

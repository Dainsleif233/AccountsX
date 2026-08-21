import org.gradle.kotlin.dsl.support.serviceOf
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

plugins {
    java
}

version = rootProject.version

interface MCAdapterExtension {
    var minecraft: String

    var loader: String
    var api: String
    var authlib: String
}

val adapter = extensions.create("adapter", MCAdapterExtension::class.java)

// ── Detect non-obfuscated MC versions (26.1+) ──────────────────────────
// Minecraft 26.1+ ships without ProGuard obfuscation — the game jar already
// carries Mojang names, so officialMojangMappings() throws
// UnsupportedOperationException.  Loom also skips creating the mod*
// configurations (modImplementation / modRuntimeOnly / …) in this mode.
val nonObfuscated: Boolean = try {
    extensions.getByName("loom").let { loom ->
        loom.javaClass.methods.first {
            it.name == "officialMojangMappings" && it.parameterCount == 0
        }.invoke(loom)
        false // call succeeded → mappings needed → obfuscated env
    }
} catch (e: Exception) {
    generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is UnsupportedOperationException } != null
}

// Mojang official mappings (null for non-obfuscated versions).
val mojangMappings: Any? = if (!nonObfuscated) {
    extensions.getByName("loom").let { loom ->
        loom.javaClass.methods.first {
            it.name == "officialMojangMappings" && it.parameterCount == 0
        }.invoke(loom)
    }
} else null

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.aliyun.com/repository/gradle-plugin/")
}

dependencies {
    if (mojangMappings != null) {
        add("mappings", mojangMappings)
    }

    add("implementation", project(":"))

    if (!nonObfuscated) {
        // ── Classic path (pre-26.1) — Loom handles remapping via mod* configs ──
        add("modRuntimeOnly", "io.github.llamalad7:mixinextras-fabric:0.3.5")
        addProvider("modImplementation", provider { "net.fabricmc:fabric-loader:${adapter.loader}" })

        // Fabric API resource-loader module (resolved by Loom's FabricApiVersions).
        addProvider("modRuntimeOnly", provider {
            val clazz = Class.forName(
                "net.fabricmc.loom.configuration.fabricapi.FabricApiVersions", true,
                StackWalker.getInstance(setOf(StackWalker.Option.RETAIN_CLASS_REFERENCE)).walk { stream ->
                    stream.filter { frame -> frame.className.startsWith("net.fabricmc.loom.") }.findFirst()
                }.orElseThrow().declaringClass.classLoader
            )
            MethodHandles.lookup().findVirtual(
                clazz, "module", MethodType.methodType(
                    Dependency::class.java, Class.forName("java.lang.String"), Class.forName("java.lang.String")
                )
            ).invokeWithArguments(
                serviceOf<ObjectFactory>().newInstance(clazz),
                "fabric-resource-loader-v0",
                "${adapter.api}+${adapter.minecraft.split(Regex("-"), 2)[0]}"
            )
        })
    } else {
        // ── Non-obfuscated path (26.1+) — mod* configs don't exist ──
        // Same deps as above, just through implementation/compileOnly since
        // Loom skips creating modImplementation / modRuntimeOnly.
        // sponge-mixin & mixinextras are bundled inside fabric-loader's JAR
        // (not in its Maven POM), so we add them explicitly.
        add("implementation", "io.github.llamalad7:mixinextras-fabric:0.5.4")
        add("implementation", "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
        addProvider("implementation", provider { "net.fabricmc:fabric-loader:${adapter.loader}" })
    }

    addProvider("minecraft", provider { "com.mojang:minecraft:${adapter.minecraft}" })
    addProvider("implementation", provider { project(":adapters:authlib:${adapter.authlib}") })
}

java.withSourcesJar()

tasks.withType<ProcessResources> {
    inputs.property("version", project.version)

    // `version` is assigned by this plugin itself above, so capture it here at
    // configuration time. (Reading `project` inside the execution-time copy
    // action below would be deprecated in Gradle 10.) The per-adapter
    // `adapter { }` values are only assigned by the leaf project after this
    // plugin applies, so they are still read lazily inside filesMatching's
    // execution-time closure.
    val modVersion = project.version

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion,
                "loader" to adapter.loader,
                "minecraft" to adapter.minecraft,
                "authlib" to adapter.authlib
            )
        )
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

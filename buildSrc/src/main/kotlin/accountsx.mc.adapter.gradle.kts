import org.gradle.kotlin.dsl.support.serviceOf
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

plugins {
    java
}

version = rootProject.version

interface MCAdapterExtension {
    var minecraft: String
    var yarn: Int

    var loader: String
    var api: String
    var authlib: String
}

val adapter = extensions.create("adapter", MCAdapterExtension::class.java)

repositories {
    gradlePluginPortal()
    maven(url = "https://maven.fabricmc.net/")
}

dependencies {
    "modRuntimeOnly"("io.github.llamalad7:mixinextras-fabric:0.3.5")
    "implementation"(rootProject)

    mapOf<String, () -> Any>(
        "minecraft" to { "com.mojang:minecraft:${adapter.minecraft}" },
        "mappings" to { "net.fabricmc:yarn:${adapter.minecraft}+build.${adapter.yarn}:v2" },
        "modImplementation" to { "net.fabricmc:fabric-loader:${adapter.loader}" },
        "modRuntimeOnly" to {
            // Gradle restricts access Loom classes from other plugins.
            // As configuration 'modRuntimeOnly' is resolved by Loom, StackWalker can access a Loom class instance (and its class loader).

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
        },
        "implementation" to { project(":adapters:authlib:${adapter.authlib}") }
    ).forEach { (key, p) ->
        addProvider(key, provider(p))
    }
}

java.withSourcesJar()

tasks.withType<ProcessResources> {
    inputs.properties(
        mapOf(
            "version" to project.version
        )
    )

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to project.version,
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
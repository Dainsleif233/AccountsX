import org.gradle.kotlin.dsl.support.serviceOf
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

plugins {
    java
}

version = rootProject.version

interface ModmenuAdapterExtension {
    var minecraft: String

    var loader: String
    var api: String
    var modmenu: String
}

val adapter = extensions.create("adapter", ModmenuAdapterExtension::class.java)

// Mojang official mappings for this adapter's Minecraft version. The `loom`
// extension is not on this precompiled plugin's classpath (Gradle isolates Loom
// classes from other plugins), so it is reached reflectively. The returned
// dependency resolves the Minecraft version lazily from the `minecraft` config.
val mojangMappings = extensions.getByName("loom").let { loom ->
    loom.javaClass.methods.first {
        it.name == "officialMojangMappings" && it.parameterCount == 0
    }.invoke(loom)
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")
    maven("https://maven.aliyun.com/repository/gradle-plugin/")
}

dependencies {
    add("mappings", mojangMappings)

    "modRuntimeOnly"("io.github.llamalad7:mixinextras-fabric:0.3.5")
    "implementation"(project(":"))

    addProvider("minecraft", provider { "com.mojang:minecraft:${adapter.minecraft}" })
    addProvider("modImplementation", provider { "net.fabricmc:fabric-loader:${adapter.loader}" })
    addProvider("modImplementation", provider { "com.terraformersmc:modmenu:${adapter.modmenu}" })
    addProvider("implementation", provider { project(":adapters:mc:${adapter.minecraft}") })
    addProvider("modRuntimeOnly", provider {
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
            "${adapter.api}+${adapter.minecraft}"
        )
    })
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
                "modmenu" to adapter.modmenu
            )
        )
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

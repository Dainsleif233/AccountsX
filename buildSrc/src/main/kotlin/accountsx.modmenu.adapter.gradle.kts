import accountsx.build.AdapterMatrix
import accountsx.build.Catalog
import accountsx.build.Loom

plugins {
    java
}

version = rootProject.version

// Pinned versions come from `gradle/adapters.toml`, keyed by the project
// directory name (= the Mod Menu version). The matrix also decides which MC
// adapter this Mod Menu build binds to (P0.2).
val matrix = AdapterMatrix.load(rootDir)
val adapter = matrix.modmenu(project.name)
val mc = matrix.mc(adapter.minecraft)

require(mc.obfuscated) {
    "${project.path}: Mod Menu adapter is pinned to MC ${mc.version}, which adapters.toml marks as " +
        "non-obfuscated — this plugin only supports the fabric-loom-remap path."
}

val loaderVersion = mc.loader ?: Catalog.version(project, "fabric-loader")

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")
    maven("https://maven.aliyun.com/repository/gradle-plugin/")
}

dependencies {
    add("mappings", Loom.officialMojangMappings(project))

    add("modRuntimeOnly", Catalog.notation(project, "mixinextras-fabric"))
    add("implementation", project(":"))

    add("minecraft", "com.mojang:minecraft:${mc.version}")
    add("modImplementation", Catalog.notation(project, "fabric-loader", loaderVersion))
    add("modImplementation", "com.terraformersmc:modmenu:${adapter.version}")
    add("implementation", project(":adapters:mc:${mc.version}"))

    // Resolved lazily on purpose: the reflection inside Loom.fabricApiModule
    // needs a Loom frame on the call stack.
    addProvider("modRuntimeOnly", provider {
        Loom.fabricApiModule(project, "fabric-resource-loader-v0", "${mc.fabricApi}+${mc.version}")
    })
}

java.withSourcesJar()

tasks.withType<ProcessResources> {
    val placeholders = mapOf(
        // `version` is assigned by this plugin above, so capture it at
        // configuration time — reading `project` inside the execution-time copy
        // action would be deprecated in Gradle 10.
        "version" to project.version.toString(),
        "loader" to loaderVersion,
        "minecraft" to mc.version,
        "modmenu" to adapter.version
    )

    inputs.properties(placeholders)

    filesMatching("fabric.mod.json") {
        expand(placeholders)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

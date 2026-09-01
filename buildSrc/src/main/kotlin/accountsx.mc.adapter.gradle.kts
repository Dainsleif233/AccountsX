import accountsx.build.AdapterMatrix
import accountsx.build.Catalog
import accountsx.build.Loom
import accountsx.build.MixinDeps

plugins {
    java
}

version = rootProject.version

// Every version this adapter pins comes from `gradle/adapters.toml`, keyed by the
// project directory name (= the Minecraft version). The leaf build script only
// applies the right Loom plugin; there is deliberately no `adapter { }` block
// anymore, so the matrix cannot drift from the build scripts (P0.2).
val adapter = AdapterMatrix.load(rootDir).mc(project.name)

// Guard the one thing the leaf script still chooses for itself: MC 26.1+ is
// unobfuscated, has no `remapJar`, no `officialMojangMappings()` and no `mod*`
// configurations, so it must use plain `fabric-loom`.
require(pluginManager.hasPlugin("net.fabricmc.fabric-loom-remap") == adapter.obfuscated) {
    "${project.path}: adapters.toml says obfuscated=${adapter.obfuscated}, so this project must " +
        "apply ${adapter.loomPluginId}"
}

val loaderVersion = adapter.loader ?: Catalog.version(project, "fabric-loader")

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")
    maven("https://maven.aliyun.com/repository/gradle-plugin/")
}

dependencies {
    if (adapter.obfuscated) {
        add("mappings", Loom.officialMojangMappings(project))
    }

    add("implementation", project(":"))

    if (adapter.obfuscated) {
        // ── Classic path (pre-26.1) — Loom handles remapping via mod* configs ──
        add("modRuntimeOnly", MixinDeps.MIXINEXTRAS_FABRIC)
        add("modImplementation", Catalog.notation(project, "fabric-loader", loaderVersion))
        add("modImplementation", "com.terraformersmc:modmenu:${adapter.modmenu}")

        // Fabric API resource-loader module. Resolved lazily on purpose: the
        // reflection inside Loom.fabricApiModule needs a Loom frame on the stack.
        addProvider("modRuntimeOnly", provider {
            Loom.fabricApiModule(
                project,
                "fabric-resource-loader-v0",
                "${adapter.fabricApi}+${adapter.version.split(Regex("-"), 2)[0]}"
            )
        })
    } else {
        // ── Non-obfuscated path (26.1+) — mod* configs don't exist ──
        // Same deps as above, just through implementation since Loom skips
        // creating modImplementation / modRuntimeOnly. sponge-mixin &
        // mixinextras are bundled inside fabric-loader's JAR (not in its Maven
        // POM), so we add them explicitly.
        add("implementation", MixinDeps.MIXINEXTRAS_FABRIC_PLAIN)
        add("implementation", MixinDeps.SPONGE_MIXIN)
        add("implementation", Catalog.notation(project, "fabric-loader", loaderVersion))
        add("implementation", "com.terraformersmc:modmenu:${adapter.modmenu}")
    }

    add("minecraft", "com.mojang:minecraft:${adapter.version}")
    add("implementation", project(":adapters:authlib:${adapter.authlib}"))
}

java.withSourcesJar()

tasks.withType<ProcessResources> {
    val placeholders = mapOf(
        // `version` is assigned by this plugin above, so capture it at
        // configuration time — reading `project` inside the execution-time copy
        // action would be deprecated in Gradle 10.
        "version" to project.version.toString(),
        "loader" to loaderVersion,
        "minecraft" to adapter.version,
        "authlib" to adapter.authlib
    )

    inputs.properties(placeholders)

    filesMatching("fabric.mod.json") {
        expand(placeholders)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = adapter.java
    targetCompatibility = adapter.java
}

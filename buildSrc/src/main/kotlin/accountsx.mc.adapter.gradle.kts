import accountsx.build.AdapterMatrix
import accountsx.build.AdapterSourceResolver
import accountsx.build.Catalog
import accountsx.build.Loom
import accountsx.build.MixinDeps

plugins {
    java
}

version = rootProject.version

val adapter = AdapterMatrix.load(rootDir).mc(project.name)

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
        add("modRuntimeOnly", MixinDeps.MIXINEXTRAS_FABRIC)
        add("modImplementation", Catalog.notation(project, "fabric-loader", loaderVersion))
        add("modImplementation", "com.terraformersmc:modmenu:${adapter.modmenu}")

        // Full fabric-api, not just resource-loader: modmenu <= 17 publishes an empty
        // POM, so its fabric.mod.json dependencies (screen/key-binding/lifecycle) are
        // only satisfied when the whole API is present — same as a real installation.
        add("modRuntimeOnly", "net.fabricmc.fabric-api:fabric-api:${adapter.fabricApi}+${adapter.version.split(Regex("-"), 2)[0]}")
    } else {
        add("implementation", MixinDeps.MIXINEXTRAS_FABRIC_PLAIN)
        add("implementation", MixinDeps.SPONGE_MIXIN)
        add("implementation", Catalog.notation(project, "fabric-loader", loaderVersion))
        add("implementation", "com.terraformersmc:modmenu:${adapter.modmenu}")
    }

    add("minecraft", "com.mojang:minecraft:${adapter.version}")
    add("implementation", project(":adapters:authlib:${adapter.authlib}"))
}

// Client game tests (REFACTORING/10-testing-strategy.md §5). Only versions whose
// fabric-api ships fabric-client-gametest-api-v1 participate; the shared test sources
// in src/client-gametest are compiled once per participating version against that
// version's own Minecraft, so they must stick to the cross-version API intersection.
if (adapter.clientGametest) {
    Loom.configureClientGameTests(project)

    val gametestImplementation = if (adapter.obfuscated) "modGametestImplementation" else "gametestImplementation"
    val fabricApiVersion = "${adapter.fabricApi}+${adapter.version.split(Regex("-"), 2)[0]}"

    dependencies.addProvider(gametestImplementation, provider {
        Loom.fabricApiModule(project, "fabric-client-gametest-api-v1", fabricApiVersion)
    })

    sourceSets.named("gametest") {
        java.srcDir(rootProject.file("src/client-gametest/java"))
        resources.srcDir(rootProject.file("src/client-gametest/resources"))
    }
}

java.withSourcesJar()

val generatedSourcesDir = layout.buildDirectory.dir("generated/adapter-sources/main/java")
val provenanceReportDir = layout.buildDirectory.dir("reports/adapter-sources")

val prepareAdapterSources = tasks.register("prepareAdapterSources") {
    val rootDirFile: File = rootDir
    val adapterVersion: String = adapter.version

    notCompatibleWithConfigurationCache("prepareAdapterSources captures script object references")

    group = "build"
    description = "Resolve effective Java sources from the version chain"

    val matrix = AdapterMatrix.load(rootDirFile)
    val lineage = AdapterSourceResolver.lineageOf(matrix, adapterVersion)

    inputs.property("targetVersion", adapter.version)
    inputs.property("lineage", lineage)
    for (v in lineage) {
        val dir = rootDirFile.resolve("adapters/mc/$v/src/main/java")
        if (dir.isDirectory) {
            inputs.dir(dir)
        }
        val removals = rootDirFile.resolve("adapters/mc/$v/source-removals.txt")
        if (removals.isFile) {
            inputs.file(removals)
        }
    }

    outputs.dir(generatedSourcesDir)
    outputs.dir(provenanceReportDir)

    doLast {
        val matrix = AdapterMatrix.load(rootDirFile)
        val resolved = AdapterSourceResolver.resolve(matrix, rootDirFile, adapterVersion)

        AdapterSourceResolver.syncTo(generatedSourcesDir.get().asFile, resolved)

        val reportDir = provenanceReportDir.get().asFile
        reportDir.mkdirs()
        val reportFile = File(reportDir, "${adapter.version}-sources.txt")
        reportFile.writeText(buildString {
            appendLine("Adapter: ${adapter.version}")
            appendLine("Lineage: ${resolved.lineage.joinToString(" -> ")}")
            appendLine("Effective files: ${resolved.effectiveFiles.size}")
            appendLine("Deletions: ${resolved.deletions.size}")
            appendLine()
            appendLine("## Effective sources")
            for ((rel, owner) in resolved.owners) {
                appendLine("$rel <- $owner")
            }
            if (resolved.deletions.isNotEmpty()) {
                appendLine()
                appendLine("## Deletions")
                for (d in resolved.deletions) {
                    appendLine(d)
                }
            }
        })

        println("prepareAdapterSources: ${resolved.effectiveFiles.size} files from ${resolved.lineage.size} versions")
    }
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf(generatedSourcesDir.get().asFile))
        }
    }
}

tasks.named("compileJava") {
    dependsOn(prepareAdapterSources)
}

tasks.named("sourcesJar") {
    dependsOn(prepareAdapterSources)
}

tasks.withType<ProcessResources> {
    val placeholders = mapOf(
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

tasks.register("explainAdapterSources") {
    group = "help"
    description = "Print effective source provenance for this adapter"
    dependsOn(prepareAdapterSources)
    doLast {
        val report = provenanceReportDir.get().asFile.resolve("${adapter.version}-sources.txt")
        if (report.isFile) {
            println(report.readText())
        } else {
            println("No provenance report found. Run prepareAdapterSources first.")
        }
    }
}

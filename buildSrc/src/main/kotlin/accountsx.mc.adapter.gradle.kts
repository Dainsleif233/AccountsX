import accountsx.build.AdapterMatrix
import accountsx.build.AdapterSourceResolver
import accountsx.build.Catalog
import accountsx.build.Loom
import accountsx.build.MixinDeps
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.zip.ZipFile
import org.gradle.api.GradleException

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

    // Acceptance 6: a second run configuration boots the game with authlib-injector
    // attached as a -javaagent. The agent jar is pinned and downloaded once; prefetched
    // root metadata (generated here in memory, a throwaway test key) keeps the agent off
    // the network at startup. The matching test binds injectorPort on the loopback.
    val injectorVersion = "1.2.8"
    val injectorJar = project.file("build/authlib-injector/authlib-injector-" + injectorVersion + ".jar")
    val downloadInjector = project.tasks.register("downloadAuthlibInjector") {
        group = "fabric"
        outputs.file(injectorJar)
        // jar 已在就判 UP-TO-DATE：否则无输入的任务永远 out-of-date，Gradle 会先清掉声明的输出
        // （把已缓存/手工放置的 jar 删了）再进 doLast，「存在即跳过」失效，离线环境必撞下载。
        outputs.upToDateWhen { injectorJar.exists() }
        doLast {
            if (!injectorJar.exists()) {
                injectorJar.parentFile.mkdirs()
                val url = URI(
                    "https://github.com/yushijinhun/authlib-injector/releases/download/v" +
                        injectorVersion + "/authlib-injector-" + injectorVersion + ".jar"
                ).toURL()
                // 先写临时文件、校验是完整 zip 再原子替换：直写最终 jar 时下载中断会留下半包，
                // 而「存在即跳过」会让后续运行一直把残缺 jar 当 -javaagent，启动即崩且不自愈。
                val tmp = Files.createTempFile(injectorJar.parentFile.toPath(), "authlib-injector-", ".part")
                try {
                    url.openStream().use { input ->
                        Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                    }
                    ZipFile(tmp.toFile()).use { zip ->
                        if (zip.getEntry("moe/yushi/authlibinjector/AuthlibInjector.class") == null) {
                            throw GradleException("下载的 authlib-injector jar 不完整（缺 agent 主类）: " + url)
                        }
                    }
                    Files.move(tmp, injectorJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }
        }
    }
    val injectorPort = 25599
    val injectorApiRoot = "http://127.0.0.1:" + injectorPort
    val prefetchedMeta = run {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----"
        val json = com.google.gson.Gson().toJson(mapOf(
            "signaturePublickey" to pem,
            "skinDomains" to listOf("127.0.0.1"),
            "meta" to mapOf("serverName" to "GtYggdrasil")
        ))
        // The agent Base64-decodes this property; encoding also keeps spaces out of
        // the JVM command line (the PEM header would otherwise split into stray args).
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
    Loom.configureAuthlibInjectorGameTest(project, injectorJar, injectorApiRoot, prefetchedMeta)
    project.tasks.named("runClientAuthlibInjectorGameTest") { dependsOn(downloadInjector) }

    // CI wraps these runs in xvfb-run with an explicit 24-bit screen and the mesa/EGL
    // environment (see ci.yml). Loom's own bare xvfb-run wrapping would nest a
    // default-depth server inside that and shadow it, so turn it off here.
    Loom.disableAutoXvfb(project, "runClientGameTest", "runClientAuthlibInjectorGameTest")
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

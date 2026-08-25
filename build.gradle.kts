import accountsx.build.AdapterMatrix
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.copyTo

plugins {
    java
}

// Gson (used below to rewrite the nested fabric.mod.json) reaches this script
// through buildSrc's runtime classpath, so its version lives in
// gradle/libs.versions.toml like every other dependency — there is deliberately
// no `buildscript { classpath(...) }` block here anymore.

rootProject.version = providers.gradleProperty("version").get()

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.aliyun.com/repository/public/")
}

dependencies {
    compileOnly(libs.fabric.loader)
    compileOnly(libs.gson)
    compileOnly(libs.guava)
    compileOnly(libs.slf4j.api)
    compileOnly(libs.asm)
}

tasks.processResources {
    val version = project.version

    inputs.property("version", version)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to version
        )
    }
}

/**
 * One nested adapter jar shipped inside the universal artifact.
 *
 * @param project Gradle project path without leading colon (e.g. `adapters:mc:1.21.4`)
 * @param builder Task that produces the remapped/plain jar
 * @param prefix Nested-jar filename prefix (`mc` / `authlib` / `modmenu`)
 */
data class Adapter(val project: String, val builder: String, val prefix: String)

// The adapter list and each adapter's jar task come from gradle/adapters.toml
// (P0.2). The `obfuscated` flag there is what decides `remapJar` vs `jar`,
// replacing the old approach of reading each adapter's build script text and
// string-matching "fabric-loom-remap".
val adapters: List<Adapter> = AdapterMatrix.load(rootDir).let { matrix ->
    matrix.authlib.map { Adapter(it.projectPath, it.jarTask, "authlib") } +
        matrix.mc.map { Adapter(it.projectPath, it.jarTask, "mc") } +
        matrix.modmenu.map { Adapter(it.projectPath, it.jarTask, "modmenu") }
}

fun packageUniversalJar() {
    // The universal fat jar is the deliverable and ships under the plain
    // `<name>-<version>.jar` name, replacing the core jar produced by `:jar`.
    // We package into a temporary copy and atomically move it over the output
    // so we never open the Gradle-tracked output file in place (which would hit
    // an exclusive file lock).
    val outputFile = project.layout.buildDirectory.file("libs/${project.name}-${project.version}.jar").get().asFile
    val coreJar = project.layout.buildDirectory.file("libs/${project.name}-core-${project.version}.jar").get().asFile
    require(coreJar.isFile) {
        "Core jar missing: ${coreJar.absolutePath}. Run `:jar` first."
    }

    adapters.forEach { adapter ->
        val p = project(adapter.project)
        val fileName = "${p.name}-${p.version}.jar"
        val adapterJar = p.layout.buildDirectory.file("libs/$fileName").get().asFile
        require(adapterJar.isFile) {
            "Adapter jar missing for ${adapter.project}: ${adapterJar.absolutePath}. Build `:${adapter.project}:${adapter.builder}` first."
        }
    }

    val tmp = File(outputFile.parentFile, "universal-${System.nanoTime()}.jar")
    coreJar.copyTo(tmp)

    try {
        FileSystems.newFileSystem(
            URI.create("jar:" + tmp.toURI()), emptyMap<String, Any>()
        ).use { fs ->
            Files.createDirectories(fs.getPath("/META-INF/jars"))

            val e = fs.getPath("/fabric.mod.json").bufferedReader().use {
                Gson().fromJson(it, JsonElement::class.java)
            } as JsonObject

            (e.get("depends") as JsonObject).addProperty("fabric-api", "*")

            val jars = JsonArray().also {
                e.add("jars", it)
            }

            adapters.forEach { adapter ->
                val p = project(adapter.project)
                val fileName = "${p.name}-${p.version}.jar"
                p.layout.buildDirectory.file("libs/$fileName").get().asFile.toPath().copyTo(
                    fs.getPath("/META-INF/jars/adapter-${adapter.prefix}-$fileName")
                )

                JsonObject().also {
                    it.addProperty("file", "META-INF/jars/adapter-${adapter.prefix}-$fileName")
                    jars.add(it)
                }
            }

            fs.getPath("/fabric.mod.json").bufferedWriter().use {
                GsonBuilder().setPrettyPrinting().create().toJson(e, it)
            }
        }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }

    outputFile.parentFile.mkdirs()
    Files.move(tmp.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Build every adapter, then nest them into the universal fat jar.
 * Local / single-machine full builds use this.
 */
val universal = tasks.register("universal") {
    group = "build"
    description = "Build all adapters and package core + nested adapters into a universal fat jar"

    adapters.forEach { adapter -> dependsOn(":${adapter.project}:${adapter.builder}") }
    dependsOn(tasks.named("jar"))

    outputs.upToDateWhen {
        adapters.all { adapter -> project(adapter.project).tasks.getByName(adapter.builder).state.upToDate } &&
            tasks.named("jar").get().state.upToDate
    }

    val output = project.layout.buildDirectory.file("libs/${project.name}-${project.version}.jar")
    outputs.file(output)

    doLast {
        packageUniversalJar()
    }
}

/**
 * Package universal jar from already-built core/adapter jars without recompiling adapters.
 *
 * Used by CI matrix builds (P0.3): each adapter is built in a separate job, artifacts
 * are downloaded and restored with [restoreAdapterArtifacts], then this task re-packs
 * the universal jar without rebuilding any adapter.
 */
val packageUniversal = tasks.register("packageUniversal") {
    group = "build"
    description = "Package core + nested adapter jars into a universal fat jar (no adapter rebuild)"

    dependsOn(tasks.named("jar"))

    val output = project.layout.buildDirectory.file("libs/${project.name}-${project.version}.jar")
    outputs.file(output)

    // Inputs are external artifacts restored by CI; always re-pack when invoked.
    outputs.upToDateWhen { false }

    doLast {
        packageUniversalJar()
    }
}

/**
 * Restore adapter jar artifacts downloaded by CI into each adapter's build/libs.
 *
 * CI builds each adapter in a separate job and uploads the jar as an artifact.
 * This task copies them back to the expected locations so [packageUniversalJar]
 * can find them.
 *
 * Usage: `./gradlew restoreAdapterArtifacts -PartifactsDir=artifacts`
 *
 * Expected artifact directory layout (produced by actions/download-artifact):
 * ```
 * artifacts/
 *   adapter-mc-1.20/          ← artifact name "adapter-mc-1.20"
 *     accountsx-adapter-mc-<ver>.jar
 *   adapter-mc-1.20.2/
 *     ...
 *   adapter-authlib/          ← artifact name "adapter-authlib"
 *     accountsx-adapter-authlib-<ver>.jar
 *     ...
 *   adapter-modmenu-7.0.0/    ← artifact name "adapter-modmenu-7.0.0"
 *     accountsx-modmenu-<ver>.jar
 *     ...
 * ```
 */
val restoreAdapterArtifacts = tasks.register("restoreAdapterArtifacts") {
    group = "build"
    description = "Restore adapter jar artifacts into each adapter's build/libs (for CI packaging)"
    val artifactsDir = providers.gradleProperty("artifactsDir").map { rootProject.file(it) }

    doLast {
        val baseDir = artifactsDir.get()
        require(baseDir.isDirectory) { "Artifacts directory not found: ${baseDir.absolutePath}" }

        var restored = 0
        val matrix = AdapterMatrix.load(rootDir)

        // Restore MC adapters: artifact name "adapter-mc-<version>" → adapters/mc/<version>/build/libs/
        for (mc in matrix.mc) {
            val artifactDir = baseDir.resolve("adapter-mc-${mc.version}")
            if (artifactDir.isDirectory) {
                val targetDir = rootDir.resolve("adapters/mc/${mc.version}/build/libs")
                targetDir.mkdirs()
                artifactDir.listFiles { f -> f.isFile && f.extension == "jar" }?.forEach { jar ->
                    jar.copyTo(targetDir.resolve(jar.name), overwrite = true)
                    restored++
                }
            }
        }

        // Restore authlib adapters: artifact name "adapter-authlib" → adapters/authlib/<version>/build/libs/
        // Downloaded artifact layout: adapter-authlib/<jar-filename>.jar
        val authlibArtifactDir = baseDir.resolve("adapter-authlib")
        if (authlibArtifactDir.isDirectory) {
            for (authlib in matrix.authlib) {
                val targetDir = rootDir.resolve("adapters/authlib/${authlib.version}/build/libs")
                targetDir.mkdirs()
                authlibArtifactDir.listFiles { f -> f.isFile && f.extension == "jar" }?.forEach { jar ->
                    // Match by version prefix: jar name is "<version>-<rootVersion>.jar"
                    if (jar.name.startsWith(authlib.version)) {
                        jar.copyTo(targetDir.resolve(jar.name), overwrite = true)
                        restored++
                    }
                }
            }
        }

        // Restore Mod Menu adapters: artifact name "adapter-modmenu-<version>" → adapters/modmenu/<version>/build/libs/
        for (modmenu in matrix.modmenu) {
            val artifactDir = baseDir.resolve("adapter-modmenu-${modmenu.version}")
            if (artifactDir.isDirectory) {
                val targetDir = rootDir.resolve("adapters/modmenu/${modmenu.version}/build/libs")
                targetDir.mkdirs()
                artifactDir.listFiles { f -> f.isFile && f.extension == "jar" }?.forEach { jar ->
                    jar.copyTo(targetDir.resolve(jar.name), overwrite = true)
                    restored++
                }
            }
        }

        logger.lifecycle("Restored $restored adapter artifacts into build/libs directories")
        require(restored > 0) { "No adapter artifacts found in ${baseDir.absolutePath}" }
    }
}

/**
 * Verify the universal jar's nested structure is correct.
 *
 * Checks:
 * 1. Every nested jar listed in fabric.mod.json's `jars` array exists in the zip
 * 2. Nested jar count matches the expected adapter count from the matrix
 * 3. Root fabric.mod.json `depends` contains `fabric-api`
 * 4. Each nested jar's fabric.mod.json has a valid `custom.accountsx:adapter.*.class`
 *    entry pointing to a .class file that actually exists inside that nested jar
 */
val verifyUniversalJar = tasks.register("verifyUniversalJar") {
    group = "build"
    description = "Verify universal jar: nested jar count, fabric.mod.json consistency, class existence"
    dependsOn(tasks.named("packageUniversal"))

    doLast {
        val universalJar = file("build/libs/${project.name}-${project.version}.jar")
        require(universalJar.isFile) { "Universal jar not found: ${universalJar.absolutePath}" }

        val expectedAdapterCount = adapters.size
        logger.lifecycle("Verifying universal jar: ${universalJar.name} (expected $expectedAdapterCount nested adapters)")

        ZipFile(universalJar).use { zip ->
            // 1. Read and parse root fabric.mod.json
            val rootModEntry = zip.getEntry("fabric.mod.json")
                ?: error("Universal jar missing fabric.mod.json")

            val rootMod = zip.getInputStream(rootModEntry).bufferedReader().use { reader ->
                Gson().fromJson(reader, JsonObject::class.java)
            }

            // 2. Check depends contains fabric-api
            val depends = rootMod.getAsJsonObject("depends")
            require(depends != null && depends.has("fabric-api")) {
                "Root fabric.mod.json missing 'depends.fabric-api'"
            }

            // 3. Check jars array
            val jarsArray = rootMod.getAsJsonArray("jars")
            require(jarsArray != null) { "Root fabric.mod.json missing 'jars' array" }
            require(jarsArray.size() == expectedAdapterCount) {
                "Nested jar count mismatch: fabric.mod.json declares ${jarsArray.size()}, " +
                    "but adapter matrix defines $expectedAdapterCount"
            }

            // 4. Verify each nested jar exists and has valid class references
            for (i in 0 until jarsArray.size()) {
                val jarEntry = jarsArray[i].asJsonObject
                val filePath = jarEntry.get("file")?.asString
                    ?: error("jars[$i] missing 'file' field")

                require(zip.getEntry(filePath) != null) {
                    "Nested jar '$filePath' listed in fabric.mod.json not found in zip"
                }

                // Extract the nested jar to a temp file and inspect it
                val nestedEntry = zip.getEntry(filePath)
                val tempDir = Files.createTempDirectory("ax-verify-")
                val tempJar = tempDir.resolve(filePath.substringAfterLast('/'))
                try {
                    zip.getInputStream(nestedEntry).use { input ->
                        Files.copy(input, tempJar)
                    }

                    // Parse the nested fabric.mod.json
                    ZipFile(tempJar.toFile()).use { nestedZip ->
                        val nestedModEntry = nestedZip.getEntry("fabric.mod.json")
                        require(nestedModEntry != null) {
                            "Nested jar '$filePath' missing fabric.mod.json"
                        }

                        val nestedMod = nestedZip.getInputStream(nestedModEntry).bufferedReader().use {
                            Gson().fromJson(it, JsonObject::class.java)
                        }

                        // Check custom adapter class reference
                        val custom = nestedMod.getAsJsonObject("custom")
                        require(custom != null) {
                            "Nested jar '$filePath' missing 'custom' in fabric.mod.json"
                        }

                        // Find accountsx:adapter.mc or accountsx:adapter.authlib
                        // (modmenu adapter has custom.modmenu instead — no class check needed)
                        val adapterKeys = custom.keySet().filter { it.startsWith("accountsx:adapter.") }
                        for (key in adapterKeys) {
                            val adapterObj = custom.getAsJsonObject(key)
                            val className = adapterObj?.get("class")?.asString
                            require(className != null) {
                                "Nested jar '$filePath': '$key' missing 'class' field"
                            }

                            // Convert class name to path
                            val classPath = className.replace('.', '/') + ".class"
                            require(nestedZip.getEntry(classPath) != null) {
                                "Nested jar '$filePath': class '$className' ($classPath) not found in jar"
                            }
                        }
                    }
                } finally {
                    // Clean up temp files
                    tempJar.toFile().delete()
                    tempDir.toFile().delete()
                }

                logger.lifecycle("  ✓ $filePath")
            }
        }

        logger.lifecycle("Universal jar verification passed: $expectedAdapterCount nested adapters, all classes present")
    }
}

tasks.build {
    dependsOn(universal)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

// Core module jar ships as `AccountsX-core-<version>.jar`; the universal task
// repackages it (nested adapters) into the plain-named final deliverable.
tasks.jar {
    archiveAppendix = "core"
}

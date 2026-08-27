import accountsx.build.AdapterMatrix
import accountsx.build.McAdapter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.FileFilter
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
    // 测试依赖（P0.4）
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.gson) // 测试需要 Gson 运行时
    testRuntimeOnly(libs.slf4j.api) // core 大量使用 slf4j；单测运行时需要它（无 binding 时 slf4j 退化为 NOP）
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
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

// 配置期捕获的根项目引用，供下方任务 action（执行期）使用。
// 在 doLast 内调用 project.* 会触发 `Task.project` 弃用（Gradle 9.7 起，Gradle 10 将报错，
// 且破坏配置缓存），因此这里在配置期一次性解析，执行期只引用这些已捕获的值。
val rootName = project.name
val rootVersion = project.version
val rootBuildDir = project.layout.buildDirectory
val rootDirPath = rootDir

// 配置期解析每个适配器产物 jar 的绝对路径。适配器 version 恒等于 rootVersion，
// 故文件名用 rootVersion 而非 p.version，避免跨项目配置期读取子项目 version。
data class AdapterJarSpec(val prefix: String, val jarFile: File)
val adapterJarSpecs: List<AdapterJarSpec> = adapters.map { adapter ->
    val p = project(adapter.project)
    AdapterJarSpec(adapter.prefix, p.layout.buildDirectory.file("libs/${p.name}-${rootVersion}.jar").get().asFile)
}

fun packageUniversalJar() {
    // The universal fat jar is the deliverable and ships under the plain
    // `<name>-<version>.jar` name, replacing the core jar produced by `:jar`.
    // We package into a temporary copy and atomically move it over the output
    // so we never open the Gradle-tracked output file in place (which would hit
    // an exclusive file lock).
    val outputFile = rootBuildDir.file("libs/${rootName}-${rootVersion}.jar").get().asFile
    val coreJar = rootBuildDir.file("libs/${rootName}-core-${rootVersion}.jar").get().asFile
    require(coreJar.isFile) {
        "Core jar missing: ${coreJar.absolutePath}. Run `:jar` first."
    }

    adapterJarSpecs.forEach { spec ->
        require(spec.jarFile.isFile) {
            "Adapter jar missing for ${spec.prefix}: ${spec.jarFile.absolutePath}. Build the corresponding adapter first."
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

            adapterJarSpecs.forEach { spec ->
                val fileName = spec.jarFile.name
                spec.jarFile.toPath().copyTo(
                    fs.getPath("/META-INF/jars/adapter-${spec.prefix}-$fileName")
                )

                JsonObject().also {
                    it.addProperty("file", "META-INF/jars/adapter-${spec.prefix}-$fileName")
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
 * Nothing in CI calls this today (CI runs the full `build`, which goes through
 * `universal`): a per-adapter job matrix that would have needed this measured
 * slower than one full build. Kept because re-packing without rebuilding is the
 * only way to assemble the fat jar from externally supplied adapter jars.
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
 * Restore adapter jar artifacts (e.g. downloaded from a CI run) into each
 * adapter's build/libs, so [packageUniversal] can find them without rebuilding.
 *
 * Not used by ci.yml, which runs the full `build` instead — kept as the entry
 * point for assembling a fat jar from externally supplied adapter jars.
 *
 * Usage: `./gradlew restoreAdapterArtifacts -PartifactsDir=artifacts`
 *
 * Expected layout (as produced by actions/download-artifact for the uploads
 * ci.yml used to do, i.e. the adapter's own `build/libs` path is preserved):
 * ```
 * artifacts/
 *   adapter-mc-1.20/<version>-<rootVersion>.jar
 *   adapter-authlib/<version>/build/libs/<version>-<rootVersion>.jar
 *   adapter-modmenu-7.0.0/<version>-<rootVersion>.jar
 * ```
 */
val restoreAdapterArtifacts = tasks.register("restoreAdapterArtifacts") {
    group = "build"
    description = "Restore adapter jar artifacts into each adapter's build/libs (for CI packaging)"
    val artifactsDir = providers.gradleProperty("artifactsDir").map { rootDirPath.resolve(it) }

    doLast {
        val baseDir = artifactsDir.get()
        require(baseDir.isDirectory) { "Artifacts directory not found: ${baseDir.absolutePath}" }

        var restored = 0
        val matrix = AdapterMatrix.load(rootDirPath)
        val jarFilter = FileFilter { it.isFile && it.name.endsWith(".jar") }

        // Restore MC adapters: artifact name "adapter-mc-<version>" → adapters/mc/<version>/build/libs/
        for (mc in matrix.mc) {
            val artifactDir = baseDir.resolve("adapter-mc-${mc.version}")
            if (artifactDir.isDirectory) {
                val targetDir = rootDirPath.resolve("adapters/mc/${mc.version}/build/libs")
                targetDir.mkdirs()
                artifactDir.listFiles(jarFilter)?.forEach { jar ->
                    jar.copyTo(targetDir.resolve(jar.name), overwrite = true)
                    restored++
                }
            }
        }

        // Restore authlib adapters: artifact name "adapter-authlib" → adapters/authlib/<version>/build/libs/
        // Downloaded artifact layout: adapter-authlib/<version>/build/libs/<version>-<rootVersion>.jar
        // (upload-artifact@v4 with merge-multiple:false nests the original build/libs structure)
        val authlibArtifactDir = baseDir.resolve("adapter-authlib")
        if (authlibArtifactDir.isDirectory) {
            for (authlib in matrix.authlib) {
                val targetDir = rootDirPath.resolve("adapters/authlib/${authlib.version}/build/libs")
                targetDir.mkdirs()
                val nestedLibs = authlibArtifactDir.resolve("${authlib.version}/build/libs")
                if (nestedLibs.isDirectory) {
                    nestedLibs.listFiles(jarFilter)?.forEach { jar ->
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
                val targetDir = rootDirPath.resolve("adapters/modmenu/${modmenu.version}/build/libs")
                targetDir.mkdirs()
                artifactDir.listFiles(jarFilter)?.forEach { jar ->
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
 *    (the Mod Menu adapter has no such entry and is skipped)
 *
 * Deliberately declares no `dependsOn`, only ordering: it verifies whatever
 * produced the jar, whether that was `universal` (full build, what CI does) or
 * `packageUniversal` (re-pack of restored artifacts). Depending on one of them
 * would make the other path pack the jar twice.
 */
val verifyUniversalJar = tasks.register("verifyUniversalJar") {
    group = "build"
    description = "Verify universal jar: nested jar count, fabric.mod.json consistency, class existence"
    mustRunAfter(universal, packageUniversal)

    doLast {
        val universalJar = rootBuildDir.file("libs/${rootName}-${rootVersion}.jar").get().asFile
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

/**
 * Scan the compiled core classes and verify that none reference Minecraft or
 * authlib internals, preserving the platform-independent boundary between core
 * and adapters (P0.5).
 *
 * Forbidden references:
 * - `net/minecraft/` — Minecraft client code (must come from adapters at runtime)
 * - `com/mojang/authlib/` — authlib internals (bridged by authlib adapters)
 * - `java/awt/` — AWT (currently warn-only; enforce after P1.4 migrates avatars out of core)
 * - `javax/imageio/` — ImageIO (same status as java/awt)
 * - `net/fabricmc/` — Fabric internals (whitelisted: `net/fabricmc/loader/api/` for mod entrypoint)
 *
 * Depends on `compileJava` so classes exist when this runs.
 */
val checkArchitecture = tasks.register("checkArchitecture") {
    group = "verification"
    description = "Scan core compiled classes for forbidden platform references (MC / authlib / AWT)"
    dependsOn(tasks.named("compileJava"))
    dependsOn(tasks.named("processResources"))

    doLast {
        val classesDir = rootBuildDir.dir("classes/java/main").get().asFile
        require(classesDir.isDirectory) {
            "Compiled classes not found: ${classesDir.absolutePath}. Run `compileJava` first."
        }

        val forbidden = listOf(
            "net/minecraft/",
            "com/mojang/authlib/",
            "net/fabricmc/"
        )

        val warnOnly = listOf(
            "java/awt/",
            "javax/imageio/"
        )

        val whitelist = listOf(
            "net/fabricmc/api/",      // Fabric API entrypoints (ClientModInitializer)
            "net/fabricmc/loader/api/" // Fabric Loader API
        )

        // Scan a .class file's constant pool (UTF-8 entries) for forbidden references.
        // Uses raw byte parsing instead of ASM to avoid needing the full class visitor pipeline.
        fun scanClassFile(bytes: ByteArray): List<String> {
            if (bytes.size < 8) return emptyList()
            val magic = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            if (magic != 0xCAFEBABE.toInt()) return emptyList()

            val cpSize = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
            var offset = 10
            val hits = mutableListOf<String>()

            var i = 1
            while (i < cpSize && offset < bytes.size) {
                val tag = bytes[offset].toInt() and 0xFF
                offset++
                when (tag) {
                    1 -> { // CONSTANT_Utf8
                        if (offset + 2 > bytes.size) break
                        val len = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                        offset += 2
                        if (offset + len > bytes.size) break
                        val str = String(bytes, offset, len, Charsets.UTF_8)
                        if (whitelist.any { str.startsWith(it) }) {
                            // whitelisted — skip
                        } else if (forbidden.any { str.startsWith(it) }) {
                            hits.add(str)
                        } else if (warnOnly.any { str.startsWith(it) }) {
                            hits.add("WARN:$str")
                        }
                        offset += len
                    }
                    7, 8, 16, 19, 20 -> { offset += 2 } // Class, String, MethodType, Module, Package
                    3, 4, 9, 10, 11, 12, 17, 18 -> { offset += 4 } // Integer, Float, Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
                    5, 6 -> { offset += 8; i++ } // Long, Double (occupy 2 entries)
                    15 -> { offset += 3 } // InvokeDynamic
                    else -> break // Unknown tag — stop scanning
                }
                i++
            }
            return hits
        }

        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var scanned = 0

        Files.walk(classesDir.toPath())
            .filter { it.toString().endsWith(".class") }
            .forEach { classFile ->
                scanned++
                val bytes = Files.readAllBytes(classFile)
                val hits = scanClassFile(bytes)
                val rel = classesDir.toPath().relativize(classFile).toString()
                for (hit in hits) {
                    if (hit.startsWith("WARN:")) {
                        warnings.add("$rel references warn-only: ${hit.removePrefix("WARN:")}")
                    } else {
                        violations.add("$rel references forbidden: $hit")
                    }
                }
            }

        logger.lifecycle("checkArchitecture: scanned $scanned class files")
        warnings.forEach { logger.warn("  WARN: $it") }
        if (warnings.isNotEmpty()) {
            logger.warn("  (${warnings.size} warnings — will become errors after P1.4 avatar migration)")
        }

        require(violations.isEmpty()) {
            "checkArchitecture FAILED — core contains ${violations.size} forbidden reference(s):\n" +
                violations.joinToString("\n") { "  $it" }
        }
        logger.lifecycle("checkArchitecture: PASSED")
    }
}

/**
 * Validate that `gradle/adapters.toml` matches disk and that Fabric Loader's
 * adapter selection would be deterministic (P0.5).
 *
 * Checks:
 * 1. toml ↔ disk directory correspondence (authlib + MC adapters)
 * 2. No two MC adapters claim the same MC version
 * 3. Simulates Loader's `>=` candidate filtering + version sorting to assert
 *    each MC version selects the expected adapter
 */
val validateAdapterMatrix = tasks.register("validateAdapterMatrix") {
    group = "verification"
    description = "Validate adapters.toml ↔ disk consistency and simulate Fabric Loader selection"

    doLast {
        val matrix = AdapterMatrix.load(rootDirPath)
        var errors = 0

        fun fail(msg: String) {
            logger.error("  FAIL: $msg")
            errors++
        }

        fun pass(msg: String) {
            logger.lifecycle("  OK: $msg")
        }

        // --- Check 1: toml ↔ disk ---
        logger.lifecycle("Checking authlib adapter directories...")
        for (authlib in matrix.authlib) {
            val dir = rootDirPath.resolve("adapters/authlib/${authlib.version}")
            if (dir.isDirectory) pass("authlib ${authlib.version}") else fail("authlib ${authlib.version}: directory not found at ${dir.path}")
        }

        logger.lifecycle("Checking MC adapter directories...")
        for (mc in matrix.mc) {
            val dir = rootDirPath.resolve("adapters/mc/${mc.version}")
            if (dir.isDirectory) pass("MC ${mc.version}") else fail("MC ${mc.version}: directory not found at ${dir.path}")

            // Verify authlib adapter referenced by this MC entry exists
            val hasAuthlib = matrix.authlib.any { it.version == mc.authlib }
            if (hasAuthlib) pass("MC ${mc.version} → authlib ${mc.authlib}") else fail("MC ${mc.version}: references missing authlib ${mc.authlib}")
        }

        // --- Check 2: no duplicate MC versions ---
        logger.lifecycle("Checking for duplicate MC version declarations...")
        val versions = matrix.mc.map { it.version }
        val dupes = versions.groupingBy { it }.eachCount().filter { it.value > 1 }
        if (dupes.isEmpty()) pass("No duplicate MC versions") else dupes.forEach { (v, c) -> fail("MC version $v declared $c times") }

        // --- Check 3: simulate Loader selection ---
        logger.lifecycle("Simulating Fabric Loader adapter selection...")
        fun parseVersion(v: String): List<Int> = v.split(".").map { it.toInt() }

        fun versionCompare(a: String, b: String): Int {
            val pa = parseVersion(a)
            val pb = parseVersion(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val ai = if (i < pa.size) pa[i] else 0
                val bi = if (i < pb.size) pb[i] else 0
                if (ai != bi) return ai.compareTo(bi)
            }
            return 0
        }

        fun findWinner(mcVersion: String): McAdapter? {
            val candidates = matrix.mc.filter { versionCompare(mcVersion, it.version) >= 0 }
            return candidates.ifEmpty { return null }
                .maxWith(Comparator { a, b -> versionCompare(a.version, b.version) })
        }

        for (mc in matrix.mc) {
            val winner = findWinner(mc.version)
            if (winner == null) {
                fail("MC ${mc.version}: no adapter covers this version")
            } else if (winner.version != mc.version) {
                fail("MC ${mc.version}: expected adapter ${mc.version}, but Loader would select ${winner.version}")
            } else {
                pass("MC ${mc.version}: selects adapter ${mc.version}")
            }
        }

        logger.lifecycle("validateAdapterMatrix: ${if (errors == 0) "PASSED" else "FAILED ($errors errors)"}")
        require(errors == 0) { "validateAdapterMatrix failed with $errors error(s)" }
    }
}

tasks.build {
    dependsOn(universal)
    dependsOn(checkArchitecture)
    dependsOn(validateAdapterMatrix)
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

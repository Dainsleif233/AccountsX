import com.google.gson.*
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.copyTo

plugins {
    java
}

buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
    }
    dependencies {
        classpath("com.google.code.gson:gson:2.14.0")
    }
}

rootProject.version = providers.gradleProperty("version").get()

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.aliyun.com/repository/public/")
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:0.19.3")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly("com.google.guava:guava:33.6.0-jre")
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("org.ow2.asm:asm:9.6")
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

fun discoverAdapters(): List<Adapter> {
    val adaptersDir = projectDir.resolve("adapters")
    return listOf("authlib", "mc", "modmenu").flatMap { type ->
        val typeDir = adaptersDir.resolve(type)
        if (!typeDir.isDirectory) {
            return@flatMap emptyList()
        }
        typeDir.list()!!.asIterable()
            .filter { name -> typeDir.resolve(name).isDirectory && !name.startsWith(".") }
            .sorted()
            .map { version ->
                when (type) {
                    "authlib" -> Adapter("adapters:$type:$version", "jar", "authlib")
                    "mc" -> Adapter("adapters:$type:$version", "remapJar", "mc")
                    "modmenu" -> Adapter("adapters:$type:$version", "remapJar", "modmenu")
                    else -> throw IllegalArgumentException("Unknown type: $type")
                }
            }
    }
}

val adapters: List<Adapter> = discoverAdapters()

fun packageUniversalJar() {
    val output = project.layout.buildDirectory.file("libs/${project.name}-${project.version}-universal.jar")
    val outputFile = output.get().asFile

    val coreJar = project.layout.buildDirectory.file("libs/${project.name}-${project.version}.jar").get().asFile
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

    outputFile.parentFile.mkdirs()
    outputFile.delete()
    coreJar.copyTo(outputFile)

    FileSystems.newFileSystem(
        URI.create("jar:" + outputFile.toURI()), emptyMap<String, Any>()
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

    val output = project.layout.buildDirectory.file("libs/${project.name}-${project.version}-universal.jar")
    outputs.file(output)

    doLast {
        packageUniversalJar()
    }
}

/**
 * Package universal jar from already-built core/adapter jars without recompiling adapters.
 * Used by CI assemble after a parallel matrix has produced the jars.
 */
val packageUniversal = tasks.register("packageUniversal") {
    group = "build"
    description = "Package core + nested adapter jars into a universal fat jar (no adapter rebuild)"

    dependsOn(tasks.named("jar"))

    val output = project.layout.buildDirectory.file("libs/${project.name}-${project.version}-universal.jar")
    outputs.file(output)

    // Inputs are external artifacts restored by CI; always re-pack when invoked.
    outputs.upToDateWhen { false }

    doLast {
        packageUniversalJar()
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

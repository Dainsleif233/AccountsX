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
        classpath("com.google.code.gson:gson:2.10.1")
    }
}

rootProject.version = properties["version"]!!

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.aliyun.com/repository/public/")
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:0.16.10")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("com.google.guava:guava:31.1-jre")
    compileOnly("org.apache.logging.log4j:log4j-api:2.22.1")
    compileOnly("org.ow2.asm:asm:9.6")
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version
        )
    }
}

data class Adapter(val project: String, val builder: String, val prefix: String)

val universal = tasks.register("universal") {
    group = "build"

    val adapters = projectDir.resolve("adapters").let { adapters ->
        listOf("authlib", "mc", "modmenu").flatMap { type ->
            adapters.resolve(type).list()!!.asIterable().map { version ->
                when (type) {
                    "authlib" -> Adapter("adapters:$type:$version", "jar", "authlib")
                    "mc" -> Adapter("adapters:$type:$version", "remapJar", "mc")
                    "modmenu" -> Adapter("adapters:$type:$version", "remapJar", "modmenu")
                    else -> throw IllegalArgumentException("Unknown type: $type")
                }
            }
        }
    }

    adapters.forEach { adapter -> dependsOn(":${adapter.project}:${adapter.builder}") }

    outputs.upToDateWhen {
        adapters.all { adapter -> project(adapter.project).tasks.getByName(adapter.builder).state.upToDate }
    }

    val output = project.layout.buildDirectory.file("libs/${project.name}-${project.version}-universal.jar")
    outputs.file(output)

    doLast {
        val outputFile = output.get().asFile

        outputFile.delete()
        project.layout.buildDirectory.file("libs/${project.name}-${project.version}.jar")
            .get().asFile.copyTo(outputFile)

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
}

tasks.build {
    dependsOn(universal)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

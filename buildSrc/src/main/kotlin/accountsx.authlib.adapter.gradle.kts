import org.gradle.kotlin.dsl.*

plugins {
    java
}

version = rootProject.version

interface AuthlibAdapterExtension {
    var authlib: String
}

val adapter = extensions.create("adapter", AuthlibAdapterExtension::class.java)

repositories {
    maven("https://maven.aliyun.com/repository/public/")
    maven("https://maven.aliyun.com/repository/gradle-plugin/")
    maven("https://libraries.minecraft.net/") {
        metadataSources {
            artifact()
        }
    }
}

dependencies {
    addProvider("implementation", provider {
        "com.mojang:authlib:${adapter.authlib}"
    })

    "implementation"("com.google.guava:guava:31.1-jre")
    "implementation"("com.google.code.gson:gson:2.10.1")
    "implementation"("org.slf4j:slf4j-api:2.0.1")
    "implementation"(rootProject)
}

tasks.processResources {
    mapOf(
        "version" to project.version,
        "authlib" to adapter.authlib
    ).let { map ->
        inputs.properties(map)

        filesMatching("fabric.mod.json") {
            expand(map)
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
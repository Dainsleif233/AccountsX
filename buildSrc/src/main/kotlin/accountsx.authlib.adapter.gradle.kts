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
    maven("https://bmclapi2.bangbang93.com/maven/") {
        metadataSources {
            artifact()
        }
    }
}

dependencies {
    addProvider("implementation", provider {
        "com.mojang:authlib:${adapter.authlib}"
    })

    "implementation"("com.google.guava:guava:33.6.0-jre")
    "implementation"("com.google.code.gson:gson:2.14.0")
    "compileOnly"("org.slf4j:slf4j-api:2.0.18")
    "implementation"(project(":"))
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

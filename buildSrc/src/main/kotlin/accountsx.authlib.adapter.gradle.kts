import accountsx.build.AdapterMatrix
import accountsx.build.Catalog

plugins {
    java
}

version = rootProject.version

// The authlib version is the project directory name; `gradle/adapters.toml`
// declares which ones exist, so an undeclared directory fails here instead of
// silently building an adapter nothing references (P0.2).
val adapter = AdapterMatrix.load(rootDir).authlib(project.name)

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
    "implementation"("com.mojang:authlib:${adapter.version}")

    "implementation"(Catalog.notation(project, "guava"))
    "implementation"(Catalog.notation(project, "gson"))
    "compileOnly"(Catalog.notation(project, "slf4j-api"))
    "implementation"(project(":"))
}

tasks.processResources {
    mapOf(
        "version" to project.version.toString(),
        "authlib" to adapter.version
    ).let { placeholders ->
        inputs.properties(placeholders)

        filesMatching("fabric.mod.json") {
            expand(placeholders)
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

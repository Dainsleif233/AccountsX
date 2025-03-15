plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    maven(url = "https://maven.fabricmc.net/")
}

dependencies {
    compileOnly("fabric-loom:fabric-loom.gradle.plugin:1.10-SNAPSHOT")
}
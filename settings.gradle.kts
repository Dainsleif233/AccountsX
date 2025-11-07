pluginManagement {
    repositories {
        maven(url = "https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
    val loomVersion: String = providers.gradleProperty("loomVersion").get()
    plugins {
        id("fabric-loom") version loomVersion
    }
}

rootProject.name = "AccountsX"

include(rootProject.projectDir.resolve("adapters").let { adapters ->
    listOf("authlib", "mc").flatMap { type ->
        adapters.resolve(type).list()!!.asIterable().map { version -> "adapters:$type:$version" }
    }
})
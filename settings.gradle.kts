pluginManagement {
    repositories {
        maven(url = "https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "AccountsX"

include(rootProject.projectDir.resolve("adapters").let { adapters ->
    listOf("authlib", "mc", "modmenu").flatMap { type ->
        adapters.resolve(type).list()!!.asIterable().map { version -> "adapters:$type:$version" }
    }
})
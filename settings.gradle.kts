pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
    }
    val loomVersion: String = providers.gradleProperty("loomVersion").get()
    plugins {
        id("net.fabricmc.fabric-loom-remap") version loomVersion
    }
}

rootProject.name = "AccountsX"

include(rootProject.projectDir.resolve("adapters").let { adapters ->
    listOf("authlib", "mc", "modmenu").flatMap { type ->
        adapters.resolve(type).list()!!.asIterable().map { version -> "adapters:$type:$version" }
    }
})

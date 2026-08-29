// core-image: a JDK-only library module holding the AWT / ImageIO avatar
// rendering logic (decision D4 / P1.4). It is NOT an adapter and is not
// declared in gradle/adapters.toml; it is included explicitly from
// settings.gradle.kts and nested into the universal jar as a library.
plugins {
    `java-library`
}

version = rootProject.version

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.processResources {
    val placeholders = mapOf(
        "version" to project.version.toString()
    )
    inputs.properties(placeholders)
    filesMatching("fabric.mod.json") {
        expand(placeholders)
    }
}

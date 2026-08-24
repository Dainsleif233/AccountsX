// buildSrc is a separate build, so it does not inherit the main build's
// auto-detected version catalog. Declaring it here lets build-logic code
// (e.g. the gson used by the universal packager) share the same versions as
// the rest of the project instead of hardcoding literals.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

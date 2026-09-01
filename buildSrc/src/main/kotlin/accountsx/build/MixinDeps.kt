package accountsx.build

/**
 * Mixin-family dependencies consumed only by the adapter plugins
 * (`accountsx.mc.adapter`).
 *
 * These are intentionally kept OUT of `gradle/libs.versions.toml`. That catalog
 * is the single source of truth for dependencies reached through the type-safe
 * `libs.*` accessors (root + subproject build scripts) or the dynamic [Catalog]
 * lookup. But Gradle's "unused alias" validation only counts static `libs.*`
 * accessor usages — it cannot see catalog entries read through the dynamic
 * `VersionCatalogsExtension.findLibrary` call inside a precompiled script
 * plugin, so the three mixin aliases would otherwise be flagged as unused even
 * though the plugins do use them.
 *
 * Centralising the coordinates here (instead of as literals in the two plugins)
 * keeps the versions out of the leaf build scripts while also satisfying
 * Gradle's static analysis.
 *
 * `MIXINEXTRAS_FABRIC` and `MIXINEXTRAS_FABRIC_PLAIN` share the same module but
 * keep separate slots on purpose: the plain variant is for the non-obfuscated
 * path (26.1+), which has no `remapJar` and must also pull in `SPONGE_MIXIN`
 * explicitly because sponge-mixin is bundled inside fabric-loader's JAR (not in
 * its Maven POM).
 */
object MixinDeps {
    const val MIXINEXTRAS_FABRIC = "io.github.llamalad7:mixinextras-fabric:0.5.4"
    const val MIXINEXTRAS_FABRIC_PLAIN = "io.github.llamalad7:mixinextras-fabric:0.5.4"
    const val SPONGE_MIXIN = "net.fabricmc:sponge-mixin:0.17.4+"
}

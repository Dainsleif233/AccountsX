package accountsx.build

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Version-catalog access for precompiled script plugins.
 *
 * Gradle does not generate the type-safe `libs.*` accessors inside buildSrc
 * precompiled script plugins, so the catalog declared in
 * `gradle/libs.versions.toml` is reached through its API instead. Keeping this
 * in one place stops dependency versions from leaking back into the plugins as
 * string literals.
 */
object Catalog {

    /** `group:name:version` notation for the library [alias]. */
    fun notation(project: Project, alias: String): String = library(project, alias).let { library ->
        "${library.module.group}:${library.module.name}:${library.versionConstraint.requiredVersion}"
    }

    /**
     * `group:name:version` for [alias], but with [version] substituted.
     * Used where the matrix may pin a per-adapter version (Fabric Loader).
     */
    fun notation(project: Project, alias: String, version: String): String =
        library(project, alias).module.let { "${it.group}:${it.name}:$version" }

    fun version(project: Project, alias: String): String =
        catalog(project).findVersion(alias)
            .orElseThrow { IllegalStateException("Version '$alias' missing from gradle/libs.versions.toml") }
            .requiredVersion

    private fun library(project: Project, alias: String): MinimalExternalModuleDependency =
        catalog(project).findLibrary(alias)
            .orElseThrow { IllegalStateException("Library '$alias' missing from gradle/libs.versions.toml") }
            .get()

    private fun catalog(project: Project) =
        project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
}

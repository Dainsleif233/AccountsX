plugins {
    `kotlin-dsl`
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.aliyun.com/repository/gradle-plugin/")
    maven("https://maven.aliyun.com/repository/public/")
}

dependencies {
    // buildSrc's runtime classpath is also the build-script classpath of the
    // main build, so declaring gson here replaces the root project's
    // `buildscript { classpath(...) }` block — the universal packager can
    // import com.google.gson.* directly and the version stays in one place.
    implementation(libs.gson)
}

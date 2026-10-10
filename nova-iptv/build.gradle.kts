// AGP is put on the classpath only when Android modules are configured, so the
// pure JVM modules can be built and tested without access to Google's Maven repo.
buildscript {
    val jvmOnly = providers.gradleProperty("jvmOnly").orNull == "true"
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
    }
    dependencies {
        if (!jvmOnly) {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

pluginManagement {
    val jvmOnly = providers.gradleProperty("jvmOnly").orNull == "true"
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("jvmOnly").orNull != "true") google()
        mavenCentral()
    }
}

rootProject.name = "nova-iptv"

// Pure Kotlin/JVM modules: buildable and testable without the Android SDK.
include(":core:model")
include(":core:common")
include(":core:domain")
include(":data:playlist")
include(":data:xtream")
include(":data:epg")

// Android modules: require the Android SDK (ANDROID_HOME or local.properties).
// Set -PjvmOnly=true to configure only the JVM modules (CI without an SDK).
if (providers.gradleProperty("jvmOnly").orNull != "true") {
    include(":core:data")
    include(":core:player")
    include(":core:designsystem")
    include(":app")
}

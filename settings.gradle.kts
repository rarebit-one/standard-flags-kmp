rootProject.name = "standard-flags-kmp"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":standard-flags-core")
include(":standard-flags-ofrep")
include(":standard-flags-openfeature")
include(":standard-flags-status")
include(":standard-flags-gradle-plugin")

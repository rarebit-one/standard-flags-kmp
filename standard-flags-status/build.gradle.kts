plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    pom {
        name.set("standard-flags-status")
        description.set("Client upgrade status for standard_flags: /client/status as a StateFlow, and 426 detection.")
    }
}

kotlin {
    explicitApi()

    jvm()
    android {
        namespace = "one.rarebit.flags.status"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.standardFlagsOfrep)
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    pom {
        name.set("standard-flags-ofrep")
        description.set("Ktor OFREP client for standard_flags: X-Client-* headers, ETag caching, backoff, apply-at-launch with live kill switches.")
    }
}

kotlin {
    explicitApi()

    jvm()
    android {
        namespace = "one.rarebit.flags.ofrep"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.standardFlagsCore)
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

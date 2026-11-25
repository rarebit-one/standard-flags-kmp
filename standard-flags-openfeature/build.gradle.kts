plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    pom {
        name.set("standard-flags-openfeature")
        description.set("OpenFeature kotlin-sdk provider over the standard_flags OFREP client.")
    }
}

kotlin {
    explicitApi()

    jvm()
    android {
        namespace = "one.rarebit.flags.openfeature"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.standardFlagsOfrep)
            api(libs.openfeature.kotlin.sdk)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

plugins {
    base
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.mavenPublish) apply false
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(11)
    }
}

// The iOS simulator tests of every module, for the macOS CI job.
tasks.register("iosSimulatorTests") {
    group = "verification"
    description = "Runs the iosSimulatorArm64 tests of every module."
    dependsOn(subprojects.map { project -> project.tasks.matching { it.name == "iosSimulatorArm64Test" } })
}

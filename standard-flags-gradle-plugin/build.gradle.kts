plugins {
    `java-gradle-plugin`
    alias(libs.plugins.mavenPublish)
}

// Written in Java so the plugin puts no Kotlin stdlib on the build classpath
// of the apps that apply it. JSON is read with the Groovy bundled in Gradle.

mavenPublishing {
    pom {
        name.set("standard-flags-gradle-plugin")
        description.set("Gradle contract check for standard_flags clients: fails the build when the app reads a wire its audience isn't exposed, or a wire's type changed.")
    }
}

gradlePlugin {
    plugins {
        create("flagsContract") {
            id = "one.rarebit.flags.contract"
            implementationClass = "one.rarebit.flags.gradle.FlagsContractPlugin"
            displayName = "standard_flags contract check"
            description = "Checks the wires an app reads against the server's committed flags manifest."
        }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The shared fixtures (wire/manifest_check.json) the contract check runs.
    val conformance = rootProject.layout.projectDirectory.dir("conformance")
    inputs.dir(conformance).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("conformance")
    systemProperty("standardFlags.conformance", conformance.asFile.path)
}

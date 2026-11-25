plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    pom {
        name.set("standard-flags-core")
        description.set("Wire model, tri-state reads, SemVer ranges, payload validation and the last-good store for standard_flags clients.")
    }
}

// The vendored wire fixtures (../conformance), embedded as Kotlin string
// constants so the conformance runner reads them on every target, native
// included, without file access.
val generateConformanceFixtures = tasks.register<GenerateConformanceFixtures>("generateConformanceFixtures") {
    fixtures.from(
        rootProject.fileTree("conformance") {
            include("**/*.json")
            exclude("schema/**", "PIN.json")
        },
    )
    outputDir.set(layout.buildDirectory.dir("generated/conformance/kotlin"))
}

kotlin {
    explicitApi()

    jvm()
    android {
        namespace = "one.rarebit.flags.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest {
            kotlin.srcDir(generateConformanceFixtures.map { it.outputDir })
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

@CacheableTask
abstract class GenerateConformanceFixtures : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val files = mutableMapOf<String, String>()
        fixtures.asFileTree.visit {
            if (!isDirectory) files[relativePath.pathString] = file.readText()
        }
        val out = StringBuilder()
        out.append("// Generated from conformance/ by generateConformanceFixtures. Do not edit.\n")
        out.append("package one.rarebit.flags.core.conformance\n\n")
        out.append("internal object ConformanceFixtures {\n")
        out.append("    val files: Map<String, String> by lazy {\n        mapOf(\n")
        for ((path, text) in files.toSortedMap()) {
            out.append("            ").append(literal(path)).append(" to listOf(\n")
            // JVM class files cap a string constant at 65535 bytes.
            text.chunked(8000).forEach { out.append("                ").append(literal(it)).append(",\n") }
            out.append("            ).joinToString(\"\"),\n")
        }
        out.append("        )\n    }\n}\n")
        val dir = outputDir.get().asFile.resolve("one/rarebit/flags/core/conformance")
        dir.mkdirs()
        dir.resolve("ConformanceFixtures.kt").writeText(out.toString())
    }

    private fun literal(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '$' -> sb.append("\\$")
                c.code in 0x20..0x7e -> sb.append(c)
                else -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
        return sb.append('"').toString()
    }
}

package one.rarebit.flags.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagsContractPluginFunctionalTest {
    @TempDir
    Path project;

    private void write(String name, String text) throws IOException {
        Files.write(project.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(args);
    }

    @Test
    void checkRunsTheContractAndFailsOnADriftedWire() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"app\"\n");
        write("flags.manifest.json", ManifestContractTest.V1);
        write("flags.wires.json", "{\"homeHero\": \"string\"}");
        write("build.gradle.kts", "plugins {\n"
            + "    base\n"
            + "    id(\"one.rarebit.flags.contract\")\n"
            + "}\n"
            + "standardFlags {\n"
            + "    manifest.set(file(\"flags.manifest.json\"))\n"
            + "    audience.set(\"mobile\")\n"
            + "    wire(\"chat_enabled\", \"boolean\")\n"
            + "    wiresFile.set(file(\"flags.wires.json\"))\n"
            + "}\n");

        BuildResult ok = runner("check").build();
        assertEquals(TaskOutcome.SUCCESS, ok.task(":checkFlagsManifest").getOutcome());
        assertTrue(Files.readString(project.resolve("build/reports/standard-flags/contract.txt")).contains("ok: 2 wires"));

        write("flags.wires.json", "{\"homeHero\": \"string\", \"tester_feedback\": \"boolean\"}");
        BuildResult failed = runner("checkFlagsManifest").buildAndFail();
        assertTrue(failed.getOutput().contains("wire \"tester_feedback\" is not exposed to audience \"mobile\""), failed.getOutput());

        write("flags.wires.json", "{\"homeHero\": {\"type\": \"string\", \"values\": [\"control\"]}}");
        BuildResult drift = runner("checkFlagsManifest").buildAndFail();
        assertTrue(drift.getOutput().contains("can carry [savings], which the app doesn't declare"), drift.getOutput());

        // A baseline: the manifest shipped builds were made against.
        String v2 = ManifestContractTest.V2;
        write("baseline.manifest.json", v2);
        write("flags.manifest.json", v2);
        write("flags.wires.json", "{\"insights_enabled\": \"boolean\"}");
        write("build.gradle.kts", "plugins {\n    base\n    id(\"one.rarebit.flags.contract\")\n}\n"
            + "standardFlags {\n    manifest.set(file(\"flags.manifest.json\"))\n    baselineManifest.set(file(\"baseline.manifest.json\"))\n"
            + "    audience.set(\"mobile\")\n    wiresFile.set(file(\"flags.wires.json\"))\n}\n");
        assertEquals(TaskOutcome.SUCCESS, runner("checkFlagsManifest").build().task(":checkFlagsManifest").getOutcome());
        write("flags.manifest.json", v2.replace("[\"chat_enabled\", \"insights_enabled\"]", "[\"insights_enabled\"]"));
        BuildResult breaking = runner("checkFlagsManifest").buildAndFail();
        assertTrue(breaking.getOutput().contains("removed wire mobile.chat_enabled"), breaking.getOutput());

        write("flags.wires.json", "{\"homeHero\": ");
        BuildResult unreadable = runner("checkFlagsManifest").buildAndFail();
        assertTrue(unreadable.getOutput().contains("standardFlags: can't read"), unreadable.getOutput());
    }
}

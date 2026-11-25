package one.rarebit.flags.gradle;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared wire/manifest_check.json (standard_flags 3d) through
 * {@link ManifestCheck}, so the Gradle check and the server's
 * {@code standard_flags:manifest:check} agree on what breaks a shipped build.
 * The server-scope case (a version 1 committed manifest) runs too: the plugin
 * reads a version 1 baseline as the server does.
 */
class ManifestCheckConformanceTest {
    @Test
    void runsEveryWireCase() throws IOException {
        File fixture = new File(System.getProperty("standardFlags.conformance"), "wire/manifest_check.json");
        assertTrue(fixture.isFile(), "wire/manifest_check.json isn't vendored: " + fixture);
        Map<?, ?> document = (Map<?, ?>) new JsonSlurper().parseText(new String(Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8));
        LocalDate fileToday = LocalDate.parse(String.valueOf(document.get("today")));
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (Object item : (List<?>) document.get("cases")) {
            Map<?, ?> c = (Map<?, ?>) item;
            ran++;
            String name = String.valueOf(c.get("name"));
            LocalDate today = c.get("today") != null ? LocalDate.parse(String.valueOf(c.get("today"))) : fileToday;
            ManifestCheck.Result result = ManifestCheck.compare(JsonOutput.toJson(c.get("committed")), JsonOutput.toJson(c.get("current")), today);
            Map<?, ?> expected = (Map<?, ?>) c.get("expected");
            String status = String.valueOf(expected.get("status"));
            if (!status.equals(result.status.name().toLowerCase(java.util.Locale.ROOT))) {
                failures.add(name + ": status " + result.status + " (" + result.reason + ")");
                continue;
            }
            if ("invalid".equals(status)) {
                if (!String.valueOf(expected.get("invalid")).equals(result.invalid)) failures.add(name + ": invalid " + result.invalid);
                continue;
            }
            if (!expected.get("stale").equals(result.stale)) failures.add(name + ": stale " + result.stale);
            List<String> removed = new ArrayList<>();
            for (Object w : (List<?>) expected.get("removed_wires")) removed.add(wire((Map<?, ?>) w, false));
            List<String> actualRemoved = new ArrayList<>();
            for (ManifestCheck.Wire w : result.removedWires) actualRemoved.add(w.audience + "." + w.wire);
            if (!removed.equals(actualRemoved)) failures.add(name + ": removed " + actualRemoved + ", expected " + removed);
            List<String> changes = new ArrayList<>();
            for (Object w : (List<?>) expected.get("type_changes")) changes.add(wire((Map<?, ?>) w, true));
            List<String> actualChanges = new ArrayList<>();
            for (ManifestCheck.Wire w : result.typeChanges) actualChanges.add(w.audience + "." + w.wire + ":" + w.from + ">" + w.to);
            if (!changes.equals(actualChanges)) failures.add(name + ": type changes " + actualChanges + ", expected " + changes);
            List<String> madePrivate = new ArrayList<>();
            for (Object a : (List<?>) expected.get("audiences_made_private")) madePrivate.add(String.valueOf(((Map<?, ?>) a).get("audience")));
            if (!madePrivate.equals(result.audiencesMadePrivate)) failures.add(name + ": made private " + result.audiencesMadePrivate + ", expected " + madePrivate);
        }
        assertTrue(ran > 0, "no cases");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static String wire(Map<?, ?> w, boolean typed) {
        String base = w.get("audience") + "." + w.get("wire");
        return typed ? base + ":" + w.get("from") + ">" + w.get("to") : base;
    }
}

package one.rarebit.flags.gradle;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestContractTest {
    // The shape the gem's flags:manifest writes today (version 1).
    static final String V1 = "{\n"
        + "  \"version\": 1,\n"
        + "  \"flags\": [\n"
        + "    {\"key\": \"ai\", \"kind\": \"kill_switch\", \"type\": \"boolean\", \"values\": [], \"default\": true,\n"
        + "     \"audiences\": {\"mobile\": \"chat_enabled\", \"web\": \"chat\"},\n"
        + "     \"wires\": {\"mobile\": [\"chat_enabled\", \"insights_enabled\"], \"web\": [\"chat\"]}},\n"
        + "    {\"key\": \"home_hero\", \"kind\": \"experiment\", \"type\": \"string\", \"values\": [\"control\", \"savings\"],\n"
        + "     \"default\": \"control\", \"audiences\": {\"mobile\": \"homeHero\"}, \"permanent\": false},\n"
        + "    {\"key\": \"internal_only\", \"kind\": \"flag\", \"type\": \"boolean\", \"values\": [], \"default\": false, \"audiences\": {}}\n"
        + "  ],\n"
        + "  \"tombstones\": [{\"key\": \"old_flag\", \"removed\": \"2026-10-01\"}]\n"
        + "}\n";

    // The planned version 2: per-audience wire lists and deprecated wires on tombstones.
    static final String V2 = "{\n"
        + "  \"version\": 2,\n"
        + "  \"audiences\": {\"mobile\": {\"public\": false}, \"device\": {\"public\": true}},\n"
        + "  \"flags\": [\n"
        + "    {\"key\": \"ai\", \"type\": \"boolean\", \"audiences\": {\"mobile\": [\"chat_enabled\", \"insights_enabled\"]}}\n"
        + "  ],\n"
        + "  \"tombstones\": [\n"
        + "    {\"key\": \"banner\", \"removed\": \"2026-11-01\", \"until\": \"2027-01-31\",\n"
        + "     \"serve\": {\"mobile\": {\"bannerOn\": false, \"bannerText\": \"hello\"}}}\n"
        + "  ]\n"
        + "}\n";

    private static Map<String, ManifestContract.Type> reads(Object... pairs) {
        Map<String, ManifestContract.Type> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (ManifestContract.Type) pairs[i + 1]);
        return out;
    }

    @Test
    void v1PassesWiresTheAudienceIsExposed() {
        ManifestContract contract = ManifestContract.parse(V1);
        assertEquals(1, contract.version());
        ManifestContract.Result result = contract.check("mobile", reads(
            "chat_enabled", ManifestContract.Type.BOOLEAN,
            "insights_enabled", ManifestContract.Type.BOOLEAN,
            "homeHero", ManifestContract.Type.STRING));
        assertTrue(result.ok(), result.errors.toString());
        assertTrue(result.warnings.isEmpty());
    }

    @Test
    void failsAWireTheAudienceIsNotExposed() {
        ManifestContract.Result result = ManifestContract.parse(V1).check("mobile", reads(
            "chat", ManifestContract.Type.BOOLEAN,
            "tester_feedback", ManifestContract.Type.BOOLEAN));
        assertEquals(2, result.errors.size(), result.errors.toString());
        assertTrue(result.errors.get(0).contains("\"chat\" is not exposed to audience \"mobile\" (only to [web])"));
        assertTrue(result.errors.get(1).contains("\"tester_feedback\" is not exposed"));
    }

    @Test
    void failsATypeChange() {
        ManifestContract.Result result = ManifestContract.parse(V1).check("mobile", reads("homeHero", ManifestContract.Type.BOOLEAN));
        assertEquals("wire \"homeHero\" is a string on the server, but the app reads a boolean", result.errors.get(0));
    }

    @Test
    void failsAnUnknownAudience() {
        ManifestContract.Result result = ManifestContract.parse(V1).check("watch", reads("chat", ManifestContract.Type.BOOLEAN));
        assertTrue(result.errors.get(0).startsWith("the manifest has no audience \"watch\""));
    }

    @Test
    void v2ReadsWireListsAndWarnsOnDeprecatedWires() {
        ManifestContract contract = ManifestContract.parse(V2);
        assertTrue(contract.audiences().contains("device"), "declared audiences are known even with no wires");
        ManifestContract.Result result = contract.check("mobile", reads(
            "insights_enabled", ManifestContract.Type.BOOLEAN,
            "bannerOn", ManifestContract.Type.BOOLEAN,
            "bannerText", ManifestContract.Type.STRING));
        assertTrue(result.ok(), result.errors.toString());
        assertEquals(2, result.warnings.size());
        assertTrue(result.warnings.get(0).contains("until 2027-01-31"));
        assertEquals(1, contract.check("mobile", reads("bannerText", ManifestContract.Type.BOOLEAN)).errors.size());
    }

    @Test
    void failsAStringValueTheAppDoesNotDeclare() {
        Map<String, java.util.Set<String>> values = new LinkedHashMap<>();
        values.put("homeHero", new java.util.LinkedHashSet<>(java.util.Arrays.asList("control", "legacy")));
        ManifestContract.Result result = ManifestContract.parse(V1).check("mobile", reads("homeHero", ManifestContract.Type.STRING), values);
        assertEquals("wire \"homeHero\" can carry [savings], which the app doesn't declare (it would read its code default)", result.errors.get(0));
        assertEquals("wire \"homeHero\": the server never sends [legacy], which the app declares", result.warnings.get(0));

        values.put("homeHero", new java.util.LinkedHashSet<>(java.util.Arrays.asList("control", "savings")));
        assertTrue(ManifestContract.parse(V1).check("mobile", reads("homeHero", ManifestContract.Type.STRING), values).ok());
    }

    @Test
    void refusesAMalformedManifest() {
        IllegalArgumentException fraction = assertThrows(IllegalArgumentException.class,
            () -> ManifestContract.parse("{\"version\": 2.5, \"flags\": []}"));
        assertTrue(fraction.getMessage().contains("not a whole number"));
        IllegalArgumentException keyless = assertThrows(IllegalArgumentException.class,
            () -> ManifestContract.parse("{\"version\": 1, \"flags\": [{\"type\": \"boolean\", \"audiences\": {}}]}"));
        assertTrue(keyless.getMessage().contains("has no key"));
    }

    @Test
    void refusesAnUnknownManifestVersion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ManifestContract.parse("{\"version\": 3, \"flags\": []}"));
        assertTrue(error.getMessage().contains("newer than this check understands"));
        assertThrows(IllegalArgumentException.class, () -> ManifestContract.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> ManifestContract.parse("{\"version\": 1, \"flags\": [{\"key\": \"x\", \"type\": \"number\"}]}"));
    }
}

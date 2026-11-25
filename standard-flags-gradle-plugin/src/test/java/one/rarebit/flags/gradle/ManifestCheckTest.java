package one.rarebit.flags.gradle;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ManifestCheck's rules without the shared fixture (which ManifestCheckConformanceTest runs). */
class ManifestCheckTest {
    private static final LocalDate TODAY = LocalDate.parse("2027-01-15");
    private static final String BASE = ManifestContractTest.V2;

    @Test
    void identicalIsOkAndReorderedMembersAreNotStale() {
        assertEquals(ManifestCheck.Status.OK, ManifestCheck.compare(BASE, BASE, TODAY).status);
        String reordered = BASE.replace("\"version\": 2,\n", "").replace("\"tombstones\"", "\"version\": 2, \"tombstones\"");
        assertEquals(ManifestCheck.Status.OK, ManifestCheck.compare(BASE, reordered, TODAY).status);
        assertEquals("current", ManifestCheck.compare(BASE, BASE.replace("\"version\": 2", "\"version\": 2.0"), TODAY).invalid, "only the integer 2");
    }

    @Test
    void removedAndRetypedWiresBreak() {
        String dropped = BASE.replace("[\"chat_enabled\", \"insights_enabled\"]", "[\"insights_enabled\"]");
        ManifestCheck.Result removed = ManifestCheck.compare(BASE, dropped, TODAY);
        assertEquals(ManifestCheck.Status.BREAKING, removed.status);
        assertEquals("mobile.chat_enabled", removed.removedWires.get(0).toString());

        String retyped = BASE.replace("\"bannerOn\": false", "\"bannerOn\": \"off\"");
        ManifestCheck.Result changed = ManifestCheck.compare(BASE, retyped, TODAY);
        assertEquals("boolean", changed.typeChanges.get(0).from);
        assertEquals("string", changed.typeChanges.get(0).to);
    }

    @Test
    void aDeprecatedWireMayGoTheDayAfterUntil() {
        String gone = BASE.replace("\"bannerOn\": false, ", "");
        assertEquals(ManifestCheck.Status.BREAKING, ManifestCheck.compare(BASE, gone, LocalDate.parse("2027-01-31")).status);
        assertEquals(ManifestCheck.Status.STALE, ManifestCheck.compare(BASE, gone, LocalDate.parse("2027-02-01")).status);
    }

    @Test
    void aPublicAudienceMadePrivateWhileServingBreaks() {
        String base = BASE.replace("{\"mobile\": [\"chat_enabled\", \"insights_enabled\"]}",
            "{\"mobile\": [\"chat_enabled\", \"insights_enabled\"], \"device\": [\"ai\"]}");
        String closed = base.replace("\"device\": {\"public\": true}", "\"device\": {\"public\": false}");
        assertEquals("device", ManifestCheck.compare(base, closed, TODAY).audiencesMadePrivate.get(0));
    }

    @Test
    void refusesInvalidManifests() {
        assertEquals("committed", ManifestCheck.compare(BASE.replace("\"version\": 2", "\"version\": 3"), BASE, TODAY).invalid);
        assertEquals("current", ManifestCheck.compare(BASE, BASE.replace("2027-01-31", "2027-02-30"), TODAY).invalid);
        assertEquals("current", ManifestCheck.compare(BASE, BASE.replace("\"until\": \"2027-01-31\",", ""), TODAY).invalid);
        ManifestCheck.Result twice = ManifestCheck.compare(BASE, BASE.replace("\"bannerOn\": false", "\"chat_enabled\": false"), TODAY);
        assertEquals(ManifestCheck.Status.INVALID, twice.status);
        assertTrue(twice.reason.contains("listed twice"));
    }

    /** What the server's Manifest.read refuses, one change to a valid manifest at a time (the gem's manifest_v2_spec). */
    @Test
    void refusesWhatTheServersReaderRefuses() {
        Map<String, String[]> refused = new LinkedHashMap<>();
        refused.put("only a version", new String[] {"{\"version\": 2}", "needs audiences, flags, tombstones, clients"});
        refused.put("no clients", new String[] {BASE.replace(",\n  \"clients\": {}", ""), "needs clients"});
        refused.put("a version written as 2.0", new String[] {BASE.replace("\"version\": 2", "\"version\": 2.0"), "integer 1 or 2"});
        refused.put("audiences not an object", new String[] {BASE.replace("\"audiences\": {\"mobile\": {\"public\": false}, \"device\": {\"public\": true}}", "\"audiences\": []"), "audiences is not an object"});
        refused.put("audience without public", new String[] {BASE.replace("\"mobile\": {\"public\": false}", "\"mobile\": {}"), "audiences.mobile needs \"public\""});
        refused.put("public that isn't a boolean", new String[] {BASE.replace("{\"public\": true}", "{\"public\": \"yes\"}"), "audiences.device needs \"public\""});
        refused.put("flags not an array", new String[] {BASE.replace("\"flags\": [", "\"flags\": {\"x\": [").replace("\n  ],\n  \"tombstones\"", "\n  ]},\n  \"tombstones\""), "flags is not an array"});
        refused.put("a flag without a key", new String[] {BASE.replace("\"key\": \"ai\", ", ""), "a flag has no key"});
        refused.put("an unknown type", new String[] {BASE.replace("\"type\": \"boolean\"", "\"type\": \"number\""), "unknown type number"});
        refused.put("values of the wrong type", new String[] {BASE.replace("[true, false]", "[\"on\", \"off\"]"), "two unique boolean values"});
        refused.put("one value", new String[] {BASE.replace("[true, false]", "[true]"), "two unique boolean values"});
        refused.put("duplicate values", new String[] {BASE.replace("[true, false]", "[true, true]"), "two unique boolean values"});
        refused.put("a default of the wrong type", new String[] {BASE.replace("\"default\": true", "\"default\": {\"*\": \"off\"}"), "default is not a boolean"});
        refused.put("a final of the wrong type", new String[] {BASE.replace("\"default\": true", "\"default\": true, \"final\": \"on\""), "final is not a boolean"});
        refused.put("a remove_by that isn't a calendar date", new String[] {BASE.replace("\"default\": true", "\"default\": true, \"remove_by\": \"2027-02-30\""), "remove_by 2027-02-30"});
        refused.put("an empty wire list", new String[] {BASE.replace("[\"chat_enabled\", \"insights_enabled\"]", "[]"), "not a non-empty array"});
        refused.put("a bare-string wire list", new String[] {BASE.replace("[\"chat_enabled\", \"insights_enabled\"]", "\"chat_enabled\""), "not a non-empty array"});
        refused.put("a wire that isn't a string", new String[] {BASE.replace("[\"chat_enabled\", \"insights_enabled\"]", "[\"chat_enabled\", 1]"), "not a string"});
        refused.put("a wire listed twice in one list", new String[] {BASE.replace("[\"chat_enabled\", \"insights_enabled\"]", "[\"chat_enabled\", \"chat_enabled\"]"), "listed twice (flag ai)"});
        refused.put("a tombstone without removed", new String[] {BASE.replace("\"removed\": \"2026-11-01\", ", ""), "removed null"});
        refused.put("until without serve", new String[] {BASE.replace(",\n     \"serve\": {\"mobile\": {\"bannerOn\": false, \"bannerText\": \"hello\"}}", ""), "until or until_clients without serve"});
        refused.put("an empty serve", new String[] {BASE.replace("\"serve\": {\"mobile\": {\"bannerOn\": false, \"bannerText\": \"hello\"}}", "\"serve\": {}"), "not a non-empty object"});
        refused.put("serve without until_clients", new String[] {BASE.replace("\"until_clients\": {},", ""), "needs until_clients"});
        refused.put("a static number", new String[] {BASE.replace("\"bannerOn\": false", "\"bannerOn\": 1"), "serves bannerOn as 1"});
        refused.put("clients not an object", new String[] {BASE.replace("\"clients\": {}", "\"clients\": []"), "clients is not an object"});
        refused.put("a client missing a field", new String[] {BASE.replace("\"clients\": {}", "\"clients\": {\"a\": " + CLIENT.replace("\"grace\": 7, ", "") + "}"), "clients.a needs exactly"});
        refused.put("a client without a platform", new String[] {BASE.replace("\"clients\": {}", "\"clients\": {\"a\": " + CLIENT.replace("\"android\"", "\"\"") + "}"), "platform is not a string"});
        refused.put("a client version that isn't a string", new String[] {BASE.replace("\"clients\": {}", "\"clients\": {\"a\": " + CLIENT.replace("\"0.4.0\"", "4") + "}"), "min_version is not a string or null"});
        refused.put("a client since that isn't a calendar date", new String[] {BASE.replace("\"clients\": {}", "\"clients\": {\"a\": " + CLIENT.replace("2026-12-01", "2026-02-30") + "}"), "since 2026-02-30"});
        refused.put("a client grace that isn't whole days", new String[] {BASE.replace("\"clients\": {}", "\"clients\": {\"a\": " + CLIENT.replace("\"grace\": 7", "\"grace\": 1.5") + "}"), "grace is not a whole number"});
        refused.put("a client store_lookup that isn't a boolean", new String[] {BASE.replace("\"clients\": {}", "\"clients\": {\"a\": " + CLIENT.replace("\"store_lookup\": false", "\"store_lookup\": {}") + "}"), "store_lookup is not true or false"});
        for (Map.Entry<String, String[]> c : refused.entrySet()) {
            ManifestCheck.Result result = ManifestCheck.compare(c.getValue()[0], BASE, TODAY);
            assertEquals(ManifestCheck.Status.INVALID, result.status, c.getKey());
            assertEquals("committed", result.invalid, c.getKey());
            assertTrue(result.reason.contains(c.getValue()[1]), c.getKey() + ": " + result.reason);
        }
        String withClient = BASE.replace("\"clients\": {}", "\"clients\": {\"a\": " + CLIENT + "}");
        assertEquals(ManifestCheck.Status.STALE, ManifestCheck.compare(withClient, BASE, TODAY).status, "a full client entry reads");
    }

    @Test
    void aVersionOneBaselineIsReadAsTheServerReadsIt() {
        // Version 1 lists the first wire under audiences and every alias under wires; type may be left out.
        String v1 = "{\"version\": 1, \"flags\": ["
            + "{\"key\": \"ai\", \"values\": [true, false], \"audiences\": {\"mobile\": \"chat_enabled\"},"
            + " \"wires\": {\"mobile\": [\"chat_enabled\", \"insights_enabled\", \"legacyChat\"]}}],"
            + " \"tombstones\": [{\"key\": \"old\", \"removed\": \"2026-10-01\"}]}";
        ManifestCheck.Result result = ManifestCheck.compare(v1, BASE, TODAY);
        assertEquals(ManifestCheck.Status.BREAKING, result.status);
        assertTrue(result.stale);
        assertEquals("[mobile.legacyChat]", result.removedWires.toString());
        assertEquals(ManifestCheck.Status.INVALID, ManifestCheck.compare(v1.replace("\"values\": [true, false], ", "\"type\": \"number\", "), BASE, TODAY).status);
    }

    @Test
    void refusesTextTheServersJsonParserRefuses() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("a repeated member name", BASE.replace("\"clients\": {}", "\"clients\": {}, \"clients\": {}"));
        refused.put("a repeated member name spelled with an escape", BASE.replace("\"clients\": {}", "\"clients\": {}, \"\\u0063lients\": {}"));
        refused.put("a repeated name in a nested object", BASE.replace("{\"public\": false}", "{\"public\": false, \"public\": false}"));
        refused.put("a trailing comma in an object", BASE.replace("\"clients\": {}", "\"clients\": {},"));
        refused.put("a trailing comma in an array", BASE.replace("[true, false]", "[true, false,]"));
        refused.put("a comment", BASE.replace("\"clients\": {}", "\"clients\": {} /* none */"));
        refused.put("an unquoted literal", BASE.replace("\"default\": true", "\"default\": True"));
        refused.put("a leading zero", BASE.replace("\"version\": 2", "\"version\": 02"));
        refused.put("trailing text", BASE + "{}");
        refused.put("101 nested containers", BASE.replace("\"clients\": {}", "\"clients\": {}, \"x\": " + "[".repeat(100) + "]".repeat(100)));
        for (Map.Entry<String, String> c : refused.entrySet()) {
            ManifestCheck.Result result = ManifestCheck.compare(c.getValue(), BASE, TODAY);
            assertEquals(ManifestCheck.Status.INVALID, result.status, c.getKey());
            assertTrue(result.reason.startsWith("not strict JSON"), c.getKey() + ": " + result.reason);
        }
        String deep = BASE.replace("\"clients\": {}", "\"clients\": {}, \"x\": " + "[".repeat(99) + "]".repeat(99));
        assertEquals(ManifestCheck.Status.STALE, ManifestCheck.compare(deep, BASE, TODAY).status, "100 nested containers are read");
        String escapes = BASE.replace("\"hello\"", "\"h\\u00e9llo \\\" \\n \\/\"");
        assertEquals(ManifestCheck.Status.STALE, ManifestCheck.compare(escapes, BASE, TODAY).status);
    }

    @Test
    void aVersionOneWireListReadsAsRubysArrayDoes() {
        String v1 = "{\"version\": 1, \"flags\": [{\"key\": \"ai\", \"type\": \"boolean\", \"wires\": {\"mobile\": WIRES}}]}";
        assertEquals(ManifestCheck.Status.OK, ManifestCheck.compare(v1.replace("WIRES", "{}"), v1.replace("WIRES", "{}"), TODAY).status,
            "an empty object is no wires");
        assertEquals(ManifestCheck.Status.STALE, ManifestCheck.compare(v1.replace("WIRES", "\"chat\""), v1.replace("WIRES", "[\"chat\"]"), TODAY).status,
            "a bare string is one wire");
        assertEquals(ManifestCheck.Status.INVALID, ManifestCheck.compare(v1.replace("WIRES", "{\"a\": \"b\"}"), BASE, TODAY).status);
    }

    @Test
    void warnsAsTheServerDoes() {
        String earlier = BASE.replace("2027-01-31", "2027-01-20");
        assertEquals(ManifestCheck.Status.STALE, ManifestCheck.compare(BASE, earlier, TODAY).status, "an earlier until isn't a break");
        assertEquals("[tombstone banner now serves mobile.bannerOn through 2027-01-20, earlier than the 2027-01-31 shipped clients were promised,"
            + " tombstone banner now serves mobile.bannerText through 2027-01-20, earlier than the 2027-01-31 shipped clients were promised]",
            ManifestCheck.compare(BASE, earlier, TODAY).warnings.toString());

        String clash = BASE.replace("[\"chat_enabled\", \"insights_enabled\"]", "[\"chat_enabled\", \"insights_enabled\", \"chatEnabled\"]");
        List<String> warnings = ManifestCheck.compare(BASE, clash, TODAY).warnings;
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("new wire mobile.chatEnabled and existing wire mobile.chat_enabled are both CHAT_ENABLED"), warnings.get(0));
    }

    @Test
    void kotlinConstantsMatchTheServer() {
        assertEquals("NEW_CHECKOUT", ManifestCheck.kotlinConstant("newCheckout"));
        assertEquals("NEW_CHECKOUT", ManifestCheck.kotlinConstant("new_checkout"));
        assertEquals("HTTP_SERVER", ManifestCheck.kotlinConstant("HTTPServer"));
        assertEquals("CHAT_V1", ManifestCheck.kotlinConstant("chat_v1"));
        assertEquals("A_B", ManifestCheck.kotlinConstant("a__b_"));
    }

    private static final String CLIENT = "{\"platform\": \"android\", \"group\": null, \"min_version\": \"0.4.0\", \"since\": \"2026-12-01\", "
        + "\"grace\": 7, \"recommended\": null, \"available\": null, \"store_url\": null, \"store_lookup\": false}";
}

package one.rarebit.flags.gradle;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ManifestCheck's rules without the shared fixture (which ManifestCheckConformanceTest runs once vendored). */
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
        String base = BASE.replace("{\"key\": \"ai\", \"type\": \"boolean\", \"audiences\": {\"mobile\": [\"chat_enabled\", \"insights_enabled\"]}}",
            "{\"key\": \"ai\", \"type\": \"boolean\", \"audiences\": {\"mobile\": [\"chat_enabled\", \"insights_enabled\"], \"device\": [\"ai\"]}}");
        String closed = base.replace("\"device\": {\"public\": true}", "\"device\": {\"public\": false}");
        assertEquals("device", ManifestCheck.compare(base, closed, TODAY).audiencesMadePrivate.get(0));
    }

    @Test
    void refusesInvalidManifests() {
        assertEquals("committed", ManifestCheck.compare(BASE.replace("\"version\": 2", "\"version\": 1"), BASE, TODAY).invalid);
        assertEquals("current", ManifestCheck.compare(BASE, BASE.replace("2027-01-31", "2027-02-30"), TODAY).invalid);
        assertEquals("current", ManifestCheck.compare(BASE, BASE.replace("\"until\": \"2027-01-31\",", ""), TODAY).invalid);
        ManifestCheck.Result twice = ManifestCheck.compare(BASE, BASE.replace("\"bannerOn\": false", "\"chat_enabled\": false"), TODAY);
        assertEquals(ManifestCheck.Status.INVALID, twice.status);
        assertTrue(twice.reason.contains("listed twice"));
    }
}

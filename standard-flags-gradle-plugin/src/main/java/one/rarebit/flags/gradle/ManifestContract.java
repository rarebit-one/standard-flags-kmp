package one.rarebit.flags.gradle;

import groovy.json.JsonSlurper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The client contract check: the wires an app reads, against what the
 * server's committed flags manifest exposes to the app's audience.
 *
 * <p>It fails when the app reads a wire its audience isn't exposed (a typo, a
 * flag that was never exposed, or one removed without a deprecated wire) or a
 * wire whose type differs from the server's. A wire served only by a tombstone
 * (a deprecated static value) passes with a warning that names its end date.
 *
 * <p>Reads manifest version 1 (the gem's current format) and version 2 (per
 * audience wire lists and tombstone {@code serve} blocks). A later format fails
 * the check rather than passing silently.
 *
 * <p>TODO(standard_flags 3d): version 2 is read as the gem's plan documents it.
 * When the gem ships manifest v2 and {@code wire/manifest_check.json}, vendor
 * them (scripts/sync-conformance) and run the fixture's cases here, so this
 * check and the server's {@code flags:manifest:check} agree on removed wires and
 * type changes.
 */
public final class ManifestContract {
    /** A wire's value type on the wire. */
    public enum Type {
        BOOLEAN, STRING;

        static Type parse(Object value, String where) {
            if ("boolean".equals(value)) return BOOLEAN;
            if ("string".equals(value)) return STRING;
            throw new IllegalArgumentException(where + ": unknown type " + value);
        }

        @Override
        public String toString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What the manifest exposes on one wire. */
    public static final class Exposure {
        public final Type type;
        /** The flag key, or the tombstone key for a deprecated wire. */
        public final String source;
        /** For a deprecated wire: the date it stops being served. Null for a live flag. */
        public final String until;

        Exposure(Type type, String source, String until) {
            this.type = type;
            this.source = source;
            this.until = until;
        }

        public boolean deprecated() {
            return until != null;
        }
    }

    /** The outcome of a check. */
    public static final class Result {
        public final List<String> errors;
        public final List<String> warnings;

        Result(List<String> errors, List<String> warnings) {
            this.errors = Collections.unmodifiableList(errors);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final int version;
    /** audience → wire → exposure. */
    private final Map<String, Map<String, Exposure>> audiences;

    private ManifestContract(int version, Map<String, Map<String, Exposure>> audiences) {
        this.version = version;
        this.audiences = audiences;
    }

    public int version() {
        return version;
    }

    /** The audiences the manifest names. */
    public java.util.Set<String> audiences() {
        return Collections.unmodifiableSet(audiences.keySet());
    }

    /** What the manifest exposes to {@code audience}, by wire. */
    public Map<String, Exposure> exposed(String audience) {
        Map<String, Exposure> wires = audiences.get(audience);
        return wires == null ? Collections.emptyMap() : Collections.unmodifiableMap(wires);
    }

    /** Parses a manifest's JSON text. */
    public static ManifestContract parse(String json) {
        Object document = new JsonSlurper().parseText(json);
        if (!(document instanceof Map)) throw new IllegalArgumentException("the manifest is not a JSON object");
        Map<?, ?> manifest = (Map<?, ?>) document;
        Object version = manifest.get("version");
        if (!(version instanceof Number)) throw new IllegalArgumentException("the manifest has no version");
        int v = ((Number) version).intValue();
        if (v != 1 && v != 2) {
            throw new IllegalArgumentException("manifest version " + v + " is newer than this check understands; upgrade the plugin");
        }

        Map<String, Map<String, Exposure>> audiences = new TreeMap<>();
        // Version 2 declares its audiences, so one with no wires is still known.
        if (v == 2 && manifest.get("audiences") instanceof Map) {
            for (Object name : ((Map<?, ?>) manifest.get("audiences")).keySet()) {
                audiences.computeIfAbsent(String.valueOf(name), k -> new TreeMap<>());
            }
        }
        for (Map<?, ?> flag : objects(manifest.get("flags"), "flags")) {
            String key = String.valueOf(flag.get("key"));
            Type type = Type.parse(flag.get("type"), "flag " + key);
            for (Map.Entry<String, List<String>> exposure : wires(flag, v).entrySet()) {
                Map<String, Exposure> wires = audiences.computeIfAbsent(exposure.getKey(), k -> new TreeMap<>());
                for (String wire : exposure.getValue()) wires.put(wire, new Exposure(type, key, null));
            }
        }
        for (Map<?, ?> tombstone : objects(manifest.get("tombstones"), "tombstones")) {
            Object serve = tombstone.get("serve");
            if (!(serve instanceof Map)) continue;
            String key = String.valueOf(tombstone.get("key"));
            String until = tombstone.get("until") == null ? "an unknown date" : String.valueOf(tombstone.get("until"));
            for (Map.Entry<?, ?> audience : ((Map<?, ?>) serve).entrySet()) {
                if (!(audience.getValue() instanceof Map)) continue;
                Map<String, Exposure> wires = audiences.computeIfAbsent(String.valueOf(audience.getKey()), k -> new TreeMap<>());
                for (Map.Entry<?, ?> wire : ((Map<?, ?>) audience.getValue()).entrySet()) {
                    Type type = wire.getValue() instanceof Boolean ? Type.BOOLEAN : Type.STRING;
                    wires.putIfAbsent(String.valueOf(wire.getKey()), new Exposure(type, key, until));
                }
            }
        }
        return new ManifestContract(v, audiences);
    }

    /** Checks the wires an app reads ({@code wire → type}) against {@code audience}. */
    public Result check(String audience, Map<String, Type> reads) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!audiences.containsKey(audience)) {
            errors.add("the manifest has no audience \"" + audience + "\" (it has " + new TreeSet<>(audiences.keySet()) + ")");
            return new Result(errors, warnings);
        }
        Map<String, Exposure> exposed = audiences.get(audience);
        for (Map.Entry<String, Type> read : new TreeMap<>(reads).entrySet()) {
            String wire = read.getKey();
            Exposure exposure = exposed.get(wire);
            if (exposure == null) {
                List<String> elsewhere = new ArrayList<>();
                for (Map.Entry<String, Map<String, Exposure>> other : audiences.entrySet()) {
                    if (other.getValue().containsKey(wire)) elsewhere.add(other.getKey());
                }
                errors.add("wire \"" + wire + "\" is not exposed to audience \"" + audience + "\""
                    + (elsewhere.isEmpty() ? "" : " (only to " + elsewhere + ")"));
            } else if (exposure.type != read.getValue()) {
                errors.add("wire \"" + wire + "\" is a " + exposure.type + " on the server, but the app reads a " + read.getValue());
            } else if (exposure.deprecated()) {
                warnings.add("wire \"" + wire + "\" is deprecated (tombstone " + exposure.source
                    + "): the server serves a static value until " + exposure.until + ", then stops");
            }
        }
        return new Result(errors, warnings);
    }

    private static List<Map<?, ?>> objects(Object value, String where) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new IllegalArgumentException("the manifest's " + where + " is not a list");
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("an entry of the manifest's " + where + " is not an object");
            out.add((Map<?, ?>) item);
        }
        return out;
    }

    /** audience → wires for one flag. v1: {@code audiences} maps to the first wire, {@code wires} to all. v2: lists. */
    private static Map<String, List<String>> wires(Map<?, ?> flag, int version) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (flag.get("audiences") instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) flag.get("audiences")).entrySet()) {
                out.put(String.valueOf(entry.getKey()), names(entry.getValue()));
            }
        }
        if (version == 1 && flag.get("wires") instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) flag.get("wires")).entrySet()) {
                out.put(String.valueOf(entry.getKey()), names(entry.getValue()));
            }
        }
        return out;
    }

    private static List<String> names(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) if (item != null) out.add(String.valueOf(item));
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return out;
    }
}

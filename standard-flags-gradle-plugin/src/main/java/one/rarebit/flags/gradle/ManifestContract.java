package one.rarebit.flags.gradle;

import groovy.json.JsonSlurper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * (a deprecated static value) passes with a warning that names its end date
 * while it is still served ({@code today <= until}); after that the server no
 * longer sends it, so reading it fails, as the server's deprecation lifecycle
 * and {@code wire/manifest_check.json} have it.
 *
 * <p>Reads manifest version 1 (standard_flags 0.3.0 and earlier) and version 2
 * (per audience wire lists and tombstone {@code serve} blocks). A later format
 * fails the check rather than passing silently. {@link ManifestCheck} compares
 * a baseline with the current manifest as the server does.
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
        /** The values a string wire can carry, as the manifest lists them; empty when it lists none. */
        public final List<String> values;

        Exposure(Type type, String source, String until, List<String> values) {
            this.type = type;
            this.source = source;
            this.until = until;
            this.values = Collections.unmodifiableList(values);
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
        double raw = ((Number) version).doubleValue();
        if (raw != Math.rint(raw)) throw new IllegalArgumentException("manifest version " + version + " is not a whole number");
        int v = (int) raw;
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
            String key = key(flag, "flag");
            Type type = Type.parse(flag.get("type"), "flag " + key);
            List<String> values = type == Type.STRING ? names(flag.get("values")) : Collections.emptyList();
            for (Map.Entry<String, List<String>> exposure : wires(flag, v).entrySet()) {
                Map<String, Exposure> wires = audiences.computeIfAbsent(exposure.getKey(), k -> new TreeMap<>());
                for (String wire : exposure.getValue()) wires.put(wire, new Exposure(type, key, null, values));
            }
        }
        for (Map<?, ?> tombstone : objects(manifest.get("tombstones"), "tombstones")) {
            Object serve = tombstone.get("serve");
            if (!(serve instanceof Map)) continue;
            String key = key(tombstone, "tombstone");
            String until = tombstone.get("until") == null ? "an unknown date" : String.valueOf(tombstone.get("until"));
            for (Map.Entry<?, ?> audience : ((Map<?, ?>) serve).entrySet()) {
                if (!(audience.getValue() instanceof Map)) continue;
                Map<String, Exposure> wires = audiences.computeIfAbsent(String.valueOf(audience.getKey()), k -> new TreeMap<>());
                for (Map.Entry<?, ?> wire : ((Map<?, ?>) audience.getValue()).entrySet()) {
                    Type type = wire.getValue() instanceof Boolean ? Type.BOOLEAN : Type.STRING;
                    List<String> values = type == Type.STRING
                        ? Collections.singletonList(String.valueOf(wire.getValue()))
                        : Collections.<String>emptyList();
                    wires.putIfAbsent(String.valueOf(wire.getKey()), new Exposure(type, key, until, values));
                }
            }
        }
        return new ManifestContract(v, audiences);
    }

    /** Checks the wires an app reads ({@code wire → type}) against {@code audience} on {@code today} (UTC). */
    public Result check(String audience, Map<String, Type> reads, LocalDate today) {
        return check(audience, reads, Collections.<String, java.util.Set<String>>emptyMap(), today);
    }

    /**
     * Checks the wires an app reads against {@code audience} on {@code today}
     * (UTC). {@code values} holds, for string wires, the values the app
     * understands: a value the server can send that isn't among them fails (the
     * app would read its code default), and one the server never sends is a
     * warning.
     */
    public Result check(String audience, Map<String, Type> reads, Map<String, java.util.Set<String>> values, LocalDate today) {
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
            } else if (exposure.deprecated() && !servedOn(exposure.until, today)) {
                errors.add("wire \"" + wire + "\" is no longer served: tombstone " + exposure.source + " served it until "
                    + exposure.until + ", so the app reads its code default");
            } else if (exposure.type != read.getValue()) {
                errors.add("wire \"" + wire + "\" is a " + exposure.type + " on the server, but the app reads a " + read.getValue());
            } else {
                java.util.Set<String> understood = values.get(wire);
                if (understood != null && exposure.type == Type.STRING && !exposure.values.isEmpty()) {
                    List<String> unknown = new ArrayList<>();
                    for (String value : exposure.values) if (!understood.contains(value)) unknown.add(value);
                    if (!unknown.isEmpty()) {
                        errors.add("wire \"" + wire + "\" can carry " + unknown + ", which the app doesn't declare"
                            + " (it would read its code default)");
                    }
                    java.util.Set<String> unused = new TreeSet<>(understood);
                    unused.removeAll(exposure.values);
                    if (!unused.isEmpty()) {
                        warnings.add("wire \"" + wire + "\": the server never sends " + unused + ", which the app declares");
                    }
                }
                if (exposure.deprecated()) {
                    warnings.add("wire \"" + wire + "\" is deprecated (tombstone " + exposure.source
                        + "): the server serves a static value until " + exposure.until + ", then stops");
                }
            }
        }
        return new Result(errors, warnings);
    }

    /** Whether a static wire is still served: {@code today <= until}. An until that isn't a date never is. */
    private static boolean servedOn(String until, LocalDate today) {
        if (!until.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return false;
        try {
            return !today.isAfter(LocalDate.parse(until));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String key(Map<?, ?> entry, String what) {
        Object key = entry.get("key");
        if (!(key instanceof String) || ((String) key).isEmpty()) {
            throw new IllegalArgumentException("a " + what + " in the manifest has no key");
        }
        return (String) key;
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

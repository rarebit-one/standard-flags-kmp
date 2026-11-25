package one.rarebit.flags.gradle;

import groovy.json.JsonSlurper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * When a manifest change breaks shipped clients: the committed manifest (the
 * baseline a client was built against) against the current one, on a given
 * date. These are the semantics of the server's {@code standard_flags:manifest:check},
 * pinned by the shared fixture {@code wire/manifest_check.json}.
 *
 * <ul>
 *   <li>{@code stale}: the manifests differ as JSON values (member order ignored, array order kept).</li>
 *   <li>{@code removedWires}: wires the committed manifest tells an audience (a static wire only
 *       while still served) that the current one neither exposes from a flag nor serves from a
 *       tombstone still serving.</li>
 *   <li>{@code typeChanges}: wires both tell the same audience with different types.</li>
 *   <li>{@code audiencesMadePrivate}: public audiences with a wire still served that became private.</li>
 * </ul>
 *
 * Both manifests are read as the server reads them (version 2, or version 1
 * from standard_flags 0.3.0 and earlier); anything the server's reader refuses
 * makes the result {@code invalid}, naming which manifest.
 */
public final class ManifestCheck {
    public enum Status { OK, STALE, BREAKING, INVALID }

    /** One (audience, wire) pair, with types for a type change. */
    public static final class Wire implements Comparable<Wire> {
        public final String audience;
        public final String wire;
        public final String from;
        public final String to;

        Wire(String audience, String wire, String from, String to) {
            this.audience = audience;
            this.wire = wire;
            this.from = from;
            this.to = to;
        }

        @Override
        public int compareTo(Wire other) {
            int order = audience.compareTo(other.audience);
            return order != 0 ? order : wire.compareTo(other.wire);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Wire)) return false;
            Wire that = (Wire) other;
            return audience.equals(that.audience) && wire.equals(that.wire)
                && Objects.equals(from, that.from) && Objects.equals(to, that.to);
        }

        @Override
        public int hashCode() {
            return Objects.hash(audience, wire, from, to);
        }

        @Override
        public String toString() {
            return audience + "." + wire + (from == null ? "" : " (" + from + " → " + to + ")");
        }
    }

    /** The outcome of a comparison. */
    public static final class Result {
        public final Status status;
        public final boolean stale;
        public final List<Wire> removedWires;
        public final List<Wire> typeChanges;
        public final List<String> audiencesMadePrivate;
        /** For {@link Status#INVALID}: "committed" or "current". */
        public final String invalid;
        /** For {@link Status#INVALID}: why. */
        public final String reason;
        /** What the server's task warns about but doesn't fail on (an earlier until, a Kotlin name collision). */
        public final List<String> warnings;

        Result(Status status, boolean stale, List<Wire> removed, List<Wire> changes, List<String> madePrivate, String invalid, String reason,
               List<String> warnings) {
            this.status = status;
            this.stale = stale;
            this.removedWires = Collections.unmodifiableList(removed);
            this.typeChanges = Collections.unmodifiableList(changes);
            this.audiencesMadePrivate = Collections.unmodifiableList(madePrivate);
            this.invalid = invalid;
            this.reason = reason;
            this.warnings = Collections.unmodifiableList(warnings);
        }

        static Result invalid(String which, String reason) {
            return new Result(Status.INVALID, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), which, reason,
                Collections.emptyList());
        }
    }

    /** A wire as one manifest tells it: its type, the flag or tombstone key, and for a static (tombstone) wire the date it stops. */
    private static final class Told {
        final String type;
        final LocalDate until;
        final String key;

        Told(String type, LocalDate until, String key) {
            this.type = type;
            this.until = until;
            this.key = key;
        }

        boolean servedOn(LocalDate today) {
            return until == null || !today.isAfter(until);
        }
    }

    private static final class Parsed {
        final Object document;
        final Map<String, Map<String, Told>> told = new TreeMap<>();
        final Map<String, Boolean> publicAudiences = new TreeMap<>();

        Parsed(Object document) {
            this.document = document;
        }
    }

    private ManifestCheck() {}

    /** Compares two manifests' JSON texts on {@code today} (UTC). */
    public static Result compare(String committed, String current, LocalDate today) {
        Parsed before;
        Parsed after;
        try {
            before = parse(committed);
        } catch (IllegalArgumentException | groovy.json.JsonException e) {
            return Result.invalid("committed", e.getMessage());
        }
        try {
            after = parse(current);
        } catch (IllegalArgumentException | groovy.json.JsonException e) {
            return Result.invalid("current", e.getMessage());
        }
        boolean stale = !before.document.equals(after.document);

        List<Wire> removed = new ArrayList<>();
        List<Wire> changes = new ArrayList<>();
        for (Map.Entry<String, Map<String, Told>> audience : before.told.entrySet()) {
            Map<String, Told> now = after.told.getOrDefault(audience.getKey(), Collections.emptyMap());
            for (Map.Entry<String, Told> wire : audience.getValue().entrySet()) {
                Told then = wire.getValue();
                Told still = now.get(wire.getKey());
                if (then.servedOn(today) && (still == null || !still.servedOn(today))) {
                    removed.add(new Wire(audience.getKey(), wire.getKey(), null, null));
                }
                if (still != null && !still.type.equals(then.type)) {
                    changes.add(new Wire(audience.getKey(), wire.getKey(), then.type, still.type));
                }
            }
        }
        Collections.sort(removed);
        Collections.sort(changes);

        List<String> madePrivate = new ArrayList<>();
        for (Map.Entry<String, Boolean> audience : before.publicAudiences.entrySet()) {
            if (!audience.getValue()) continue;
            boolean serving = before.told.getOrDefault(audience.getKey(), Collections.emptyMap()).values().stream()
                .anyMatch(told -> told.servedOn(today));
            if (serving && Boolean.FALSE.equals(after.publicAudiences.get(audience.getKey()))) {
                madePrivate.add(audience.getKey());
            }
        }

        Status status = !removed.isEmpty() || !changes.isEmpty() || !madePrivate.isEmpty()
            ? Status.BREAKING : stale ? Status.STALE : Status.OK;
        return new Result(status, stale, removed, changes, madePrivate, null, null, warnings(before, after, today));
    }

    private static final List<String> TYPES = Arrays.asList("boolean", "string");
    private static final List<String> CLIENT_FIELDS = Arrays.asList(
        "available", "grace", "group", "min_version", "platform", "recommended", "since", "store_lookup", "store_url");

    /**
     * Reads a manifest as the server's {@code Manifest.read} does, refusing what
     * it refuses: everything {@code schema/manifest.schema.json} rejects that
     * the server checks, plus a non-integer version, dates that aren't calendar
     * dates, and a wire listed twice for one audience. Version 1 (written by
     * standard_flags 0.3.0 and earlier) is read too, so a shipped version 1
     * baseline is still checked across the upgrade.
     */
    private static Parsed parse(String json) {
        StrictJsonText.check(json);
        Object document = new JsonSlurper().parseText(json);
        if (!(document instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        Map<?, ?> manifest = (Map<?, ?>) document;
        Object raw = manifest.get("version");
        if (!isInteger(raw) || (((Number) raw).longValue() != 1 && ((Number) raw).longValue() != 2)) {
            throw new IllegalArgumentException("version must be the integer 1 or 2, not " + raw);
        }
        int version = ((Number) raw).intValue();
        if (version == 2) {
            List<String> missing = new ArrayList<>();
            for (String field : Arrays.asList("audiences", "flags", "tombstones", "clients")) {
                if (!manifest.containsKey(field)) missing.add(field);
            }
            if (!missing.isEmpty()) throw new IllegalArgumentException("a version 2 manifest needs " + String.join(", ", missing));
        }
        Parsed parsed = new Parsed(normalize(document));

        if (version == 2) {
            Object audiences = manifest.get("audiences");
            if (!(audiences instanceof Map)) throw new IllegalArgumentException("audiences is not an object");
            for (Map.Entry<?, ?> audience : ((Map<?, ?>) audiences).entrySet()) {
                Object meta = audience.getValue();
                Object isPublic = meta instanceof Map ? ((Map<?, ?>) meta).get("public") : null;
                if (!(isPublic instanceof Boolean)) {
                    throw new IllegalArgumentException("audiences." + audience.getKey() + " needs \"public\": true or false");
                }
                parsed.publicAudiences.put(String.valueOf(audience.getKey()), (Boolean) isPublic);
            }
            clients(manifest.get("clients"));
        }

        for (Map<?, ?> flag : list(manifest, "flags")) {
            String key = text(flag, "key", "a flag");
            if (flag.containsKey("remove_by")) date(flag.get("remove_by"), "flag " + key + " remove_by");
            String type = flagType(version, flag, key);
            for (Map.Entry<String, List<String>> audience : flagWires(version, flag, key).entrySet()) {
                for (String wire : audience.getValue()) tell(parsed, audience.getKey(), wire, new Told(type, null, key), "flag " + key);
            }
        }

        for (Map<?, ?> tombstone : list(manifest, "tombstones")) {
            String key = text(tombstone, "key", "a tombstone");
            if (version == 2 || tombstone.containsKey("removed")) date(tombstone.get("removed"), "tombstone " + key + " removed");
            if (!tombstone.containsKey("serve")) {
                if (tombstone.containsKey("until") || tombstone.containsKey("until_clients")) {
                    throw new IllegalArgumentException("tombstone " + key + " has until or until_clients without serve");
                }
                continue;
            }
            Object serve = tombstone.get("serve");
            if (!(serve instanceof Map) || ((Map<?, ?>) serve).isEmpty()) {
                throw new IllegalArgumentException("tombstone " + key + " serve is not a non-empty object");
            }
            if (!tombstone.containsKey("until")) throw new IllegalArgumentException("tombstone " + key + " serve needs until");
            if (version == 2 && !(tombstone.get("until_clients") instanceof Map)) {
                throw new IllegalArgumentException("tombstone " + key + " serve needs until_clients (an object)");
            }
            LocalDate until = date(tombstone.get("until"), "tombstone " + key + " until");
            for (Map.Entry<?, ?> audience : ((Map<?, ?>) serve).entrySet()) {
                if (!(audience.getValue() instanceof Map)) {
                    throw new IllegalArgumentException("tombstone " + key + " serve." + audience.getKey() + " is not an object");
                }
                for (Map.Entry<?, ?> wire : ((Map<?, ?>) audience.getValue()).entrySet()) {
                    Object value = wire.getValue();
                    String type = value instanceof Boolean ? "boolean" : value instanceof String ? "string" : null;
                    if (type == null) throw new IllegalArgumentException("tombstone " + key + " serves " + wire.getKey() + " as " + value);
                    tell(parsed, String.valueOf(audience.getKey()), String.valueOf(wire.getKey()), new Told(type, until, key), "tombstone " + key);
                }
            }
        }
        return parsed;
    }

    /**
     * A flag's type. Version 2 requires it, with values (and default and final)
     * of that type. A version 1 file written before {@code type} existed reads
     * as boolean when every value is one.
     */
    private static String flagType(int version, Map<?, ?> flag, String key) {
        Object type = flag.get("type");
        if (version == 2) {
            if (!TYPES.contains(type)) throw new IllegalArgumentException("flag " + key + " has an unknown type " + type);
            checkValues(flag, (String) type, key);
            return (String) type;
        }
        if (TYPES.contains(type)) return (String) type;
        if (type != null) throw new IllegalArgumentException("flag " + key + " has an unknown type " + type);
        List<?> values = asList(flag.get("values"));
        return !values.isEmpty() && values.stream().allMatch(v -> v instanceof Boolean) ? "boolean" : "string";
    }

    private static void checkValues(Map<?, ?> flag, String type, String key) {
        Class<?> typed = "boolean".equals(type) ? Boolean.class : String.class;
        Object values = flag.get("values");
        if (!(values instanceof List) || ((List<?>) values).size() < 2 || !((List<?>) values).stream().allMatch(typed::isInstance)
            || new java.util.HashSet<>((List<?>) values).size() != ((List<?>) values).size()) {
            throw new IllegalArgumentException("flag " + key + " values must list at least two unique " + type + " values");
        }
        Object fallback = flag.get("default");
        java.util.Collection<?> defaults = fallback instanceof Map ? ((Map<?, ?>) fallback).values() : Collections.singletonList(fallback);
        if (!defaults.stream().allMatch(typed::isInstance)) throw new IllegalArgumentException("flag " + key + " default is not a " + type);
        if (flag.containsKey("final") && !typed.isInstance(flag.get("final"))) {
            throw new IllegalArgumentException("flag " + key + " final is not a " + type);
        }
    }

    /**
     * audience → wire names for one flag. Version 1 wrote {@code audiences} as
     * {@code {audience: first wire}} and listed aliases under {@code wires};
     * version 2 writes {@code {audience: [wires]}}.
     */
    private static Map<String, List<String>> flagWires(int version, Map<?, ?> flag, String key) {
        Object audiences = version == 1 && flag.containsKey("wires") ? flag.get("wires")
            : flag.containsKey("audiences") ? flag.get("audiences") : Collections.emptyMap();
        if (!(audiences instanceof Map)) throw new IllegalArgumentException("flag " + key + " audiences is not an object");
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> audience : ((Map<?, ?>) audiences).entrySet()) {
            Object names = audience.getValue();
            if (version == 2 && !(names instanceof List && !((List<?>) names).isEmpty())) {
                throw new IllegalArgumentException("flag " + key + " audiences." + audience.getKey() + " is not a non-empty array of wire names");
            }
            List<String> wires = new ArrayList<>();
            for (Object name : asList(names)) {
                if (!(name instanceof String)) {
                    throw new IllegalArgumentException("flag " + key + " lists a wire for " + audience.getKey() + " that is not a string");
                }
                wires.add((String) name);
            }
            out.put(String.valueOf(audience.getKey()), wires);
        }
        return out;
    }

    /** A version 2 manifest's {@code clients}: client name → exactly the registry's fields, typed as the server writes them. */
    private static void clients(Object clients) {
        if (!(clients instanceof Map)) throw new IllegalArgumentException("clients is not an object");
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) clients).entrySet()) {
            String label = "clients." + entry.getKey();
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException(label + " is not an object");
            Map<?, ?> client = (Map<?, ?>) entry.getValue();
            List<String> fields = new ArrayList<>();
            for (Object field : client.keySet()) fields.add(String.valueOf(field));
            Collections.sort(fields);
            if (!fields.equals(CLIENT_FIELDS)) throw new IllegalArgumentException(label + " needs exactly " + String.join(", ", CLIENT_FIELDS));
            Object platform = client.get("platform");
            if (!(platform instanceof String) || ((String) platform).isEmpty()) throw new IllegalArgumentException(label + " platform is not a string");
            for (String field : Arrays.asList("group", "min_version", "recommended", "available", "store_url")) {
                Object value = client.get(field);
                if (value != null && !(value instanceof String)) throw new IllegalArgumentException(label + " " + field + " is not a string or null");
            }
            if (client.get("since") != null) date(client.get("since"), label + " since");
            Object grace = client.get("grace");
            if (!isInteger(grace) || new java.math.BigDecimal(grace.toString()).signum() < 0) {
                throw new IllegalArgumentException(label + " grace is not a whole number of days");
            }
            if (!(client.get("store_lookup") instanceof Boolean)) throw new IllegalArgumentException(label + " store_lookup is not true or false");
        }
    }

    /**
     * Warnings the server's task also prints (not pinned by the fixture): a
     * deprecated wire's end date moved earlier, and a new wire whose generated
     * Kotlin constant would collide with an existing wire's.
     */
    private static List<String> warnings(Parsed before, Parsed after, LocalDate today) {
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Map<String, Told>> audience : before.told.entrySet()) {
            Map<String, Told> now = after.told.getOrDefault(audience.getKey(), Collections.emptyMap());
            for (Map.Entry<String, Told> wire : audience.getValue().entrySet()) {
                Told then = wire.getValue();
                Told still = now.get(wire.getKey());
                if (then.until != null && then.servedOn(today) && still != null && still.until != null && still.until.isBefore(then.until)) {
                    warnings.add("tombstone " + still.key + " now serves " + audience.getKey() + "." + wire.getKey() + " through " + still.until
                        + ", earlier than the " + then.until + " shipped clients were promised");
                }
            }
        }
        for (Map.Entry<String, Map<String, Told>> audience : after.told.entrySet()) {
            Map<String, Told> base = before.told.getOrDefault(audience.getKey(), Collections.emptyMap());
            for (String wire : audience.getValue().keySet()) {
                if (base.containsKey(wire)) continue;
                String name = kotlinConstant(wire);
                for (String existing : base.keySet()) {
                    if (kotlinConstant(existing).equals(name)) {
                        warnings.add("new wire " + audience.getKey() + "." + wire + " and existing wire " + audience.getKey() + "." + existing
                            + " are both " + name + " in generated Kotlin; check which wire client code means");
                        break;
                    }
                }
            }
        }
        return warnings;
    }

    /** The server's Kotlin constant name for a wire: {@code newCheckout} and {@code new_checkout} are both NEW_CHECKOUT. */
    static String kotlinConstant(String wire) {
        String name = wire.replaceAll("[^A-Za-z0-9]+", "_")
            .replaceAll("([A-Z\\d]+)([A-Z][a-z])", "$1_$2")
            .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
            .toUpperCase(java.util.Locale.ROOT)
            .replaceAll("_+", "_");
        if (name.startsWith("_")) name = name.substring(1);
        if (name.endsWith("_")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = "V_" + name;
            if (name.endsWith("_")) name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    /** Plain values for structural comparison: maps by key, lists in order, numbers by value. */
    private static Object normalize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) out.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) out.add(normalize(item));
            return out;
        }
        if (value instanceof Number) {
            java.math.BigDecimal number = new java.math.BigDecimal(value.toString()).stripTrailingZeros();
            return number.signum() == 0 ? java.math.BigDecimal.ZERO : number;
        }
        return value;
    }

    private static void tell(Parsed parsed, String audience, String wire, Told told, String owner) {
        Map<String, Told> wires = parsed.told.computeIfAbsent(audience, k -> new TreeMap<>());
        if (wires.put(wire, told) != null) {
            throw new IllegalArgumentException("wire " + audience + "." + wire + " is listed twice (" + owner + ")");
        }
    }

    /** A YYYY-MM-DD calendar date (2027-02-30 is refused); anything else, null included, is refused. */
    private static LocalDate date(Object value, String where) {
        if (!(value instanceof String) || !((String) value).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException(where + " " + value + " is not a YYYY-MM-DD calendar date");
        }
        try {
            return LocalDate.parse((String) value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + " " + value + " is not a YYYY-MM-DD calendar date");
        }
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger;
    }

    /**
     * Ruby's {@code Array(value)}: a list as is, null as empty, an object as its
     * members (as pairs, so an empty object is empty and any other isn't a list
     * of strings), anything else as a one-item list.
     */
    private static List<?> asList(Object value) {
        if (value instanceof List) return (List<?>) value;
        if (value instanceof Map) return new ArrayList<>(((Map<?, ?>) value).entrySet());
        return value == null ? Collections.emptyList() : Collections.singletonList(value);
    }

    private static String text(Map<?, ?> entry, String field, String what) {
        Object value = entry.get(field);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new IllegalArgumentException(what + " has no " + field);
        return (String) value;
    }

    /** A top-level array of objects; absent reads as empty (version 1 may leave it out). */
    private static List<Map<?, ?>> list(Map<?, ?> manifest, String field) {
        if (!manifest.containsKey(field)) return Collections.emptyList();
        Object value = manifest.get(field);
        if (!(value instanceof List)) throw new IllegalArgumentException(field + " is not an array");
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) throw new IllegalArgumentException(field + " holds an entry that is not an object");
            out.add((Map<?, ?>) item);
        }
        return out;
    }
}

package one.rarebit.flags.gradle;

import groovy.json.JsonSlurper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
 * Both manifests must be version 2; anything a reader must refuse makes the
 * result {@code invalid}, naming which manifest.
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

        Result(Status status, boolean stale, List<Wire> removed, List<Wire> changes, List<String> madePrivate, String invalid, String reason) {
            this.status = status;
            this.stale = stale;
            this.removedWires = Collections.unmodifiableList(removed);
            this.typeChanges = Collections.unmodifiableList(changes);
            this.audiencesMadePrivate = Collections.unmodifiableList(madePrivate);
            this.invalid = invalid;
            this.reason = reason;
        }

        static Result invalid(String which, String reason) {
            return new Result(Status.INVALID, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), which, reason);
        }
    }

    /** A wire as one manifest tells it: its type, and for a static (tombstone) wire the date it stops. */
    private static final class Told {
        final String type;
        final LocalDate until;

        Told(String type, LocalDate until) {
            this.type = type;
            this.until = until;
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
        return new Result(status, stale, removed, changes, madePrivate, null, null);
    }

    private static Parsed parse(String json) {
        Object document = new JsonSlurper().parseText(json);
        if (!(document instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        Map<?, ?> manifest = (Map<?, ?>) document;
        Object version = manifest.get("version");
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() != 2) {
            throw new IllegalArgumentException("version must be the integer 2, not " + version);
        }
        Parsed parsed = new Parsed(normalize(document));

        Object audiences = manifest.get("audiences");
        if (audiences != null) {
            if (!(audiences instanceof Map)) throw new IllegalArgumentException("audiences is not an object");
            for (Map.Entry<?, ?> audience : ((Map<?, ?>) audiences).entrySet()) {
                Object meta = audience.getValue();
                boolean isPublic = meta instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) meta).get("public"));
                parsed.publicAudiences.put(String.valueOf(audience.getKey()), isPublic);
            }
        }

        for (Map<?, ?> flag : list(manifest.get("flags"), "flags")) {
            String key = text(flag, "key", "a flag");
            Object type = flag.get("type");
            if (!"boolean".equals(type) && !"string".equals(type)) {
                throw new IllegalArgumentException("flag " + key + " has type " + type);
            }
            date(flag.get("remove_by"), "flag " + key + " remove_by");
            Object exposure = flag.get("audiences");
            if (exposure == null) continue;
            if (!(exposure instanceof Map)) throw new IllegalArgumentException("flag " + key + " audiences is not an object");
            for (Map.Entry<?, ?> audience : ((Map<?, ?>) exposure).entrySet()) {
                if (!(audience.getValue() instanceof List)) {
                    throw new IllegalArgumentException("flag " + key + " lists " + audience.getKey() + " wires as " + audience.getValue());
                }
                for (Object wire : (List<?>) audience.getValue()) {
                    tell(parsed, String.valueOf(audience.getKey()), String.valueOf(wire), new Told((String) type, null));
                }
            }
        }

        for (Map<?, ?> tombstone : list(manifest.get("tombstones"), "tombstones")) {
            String key = text(tombstone, "key", "a tombstone");
            date(tombstone.get("removed"), "tombstone " + key + " removed");
            LocalDate until = date(tombstone.get("until"), "tombstone " + key + " until");
            Object serve = tombstone.get("serve");
            if (serve == null) continue;
            if (!(serve instanceof Map)) throw new IllegalArgumentException("tombstone " + key + " serve is not an object");
            if (until == null) throw new IllegalArgumentException("tombstone " + key + " serves wires without an until");
            for (Map.Entry<?, ?> audience : ((Map<?, ?>) serve).entrySet()) {
                if (!(audience.getValue() instanceof Map)) {
                    throw new IllegalArgumentException("tombstone " + key + " serve " + audience.getKey() + " is not an object");
                }
                for (Map.Entry<?, ?> wire : ((Map<?, ?>) audience.getValue()).entrySet()) {
                    Object value = wire.getValue();
                    String type = value instanceof Boolean ? "boolean" : value instanceof String ? "string" : null;
                    if (type == null) throw new IllegalArgumentException("tombstone " + key + " serves " + wire.getKey() + " as " + value);
                    tell(parsed, String.valueOf(audience.getKey()), String.valueOf(wire.getKey()), new Told(type, until));
                }
            }
        }
        return parsed;
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

    private static void tell(Parsed parsed, String audience, String wire, Told told) {
        Map<String, Told> wires = parsed.told.computeIfAbsent(audience, k -> new TreeMap<>());
        if (wires.put(wire, told) != null) {
            throw new IllegalArgumentException("wire " + audience + "." + wire + " is listed twice");
        }
    }

    private static LocalDate date(Object value, String where) {
        if (value == null) return null;
        if (!(value instanceof String) || !((String) value).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException(where + " is not a date: " + value);
        }
        try {
            return LocalDate.parse((String) value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + " is not a calendar date: " + value);
        }
    }

    private static String text(Map<?, ?> entry, String field, String what) {
        Object value = entry.get(field);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new IllegalArgumentException(what + " has no " + field);
        return (String) value;
    }

    private static List<Map<?, ?>> list(Object value, String where) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new IllegalArgumentException(where + " is not a list");
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("an entry of " + where + " is not an object");
            out.add((Map<?, ?>) item);
        }
        return out;
    }
}

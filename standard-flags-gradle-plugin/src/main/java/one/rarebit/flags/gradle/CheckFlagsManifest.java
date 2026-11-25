package one.rarebit.flags.gradle;

import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Checks the wires the app reads against the server's committed manifest. */
@CacheableTask
public abstract class CheckFlagsManifest extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifest();

    @Input
    public abstract Property<String> getAudience();

    @Input
    public abstract MapProperty<String, String> getWires();

    @Input
    public abstract MapProperty<String, List<String>> getValues();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getWiresFile();

    @Input
    public abstract Property<Boolean> getFailOnDeprecated();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBaselineManifest();

    /**
     * Today's date (UTC), which decides whether a deprecated wire is still
     * served. The plugin supplies it from {@link UtcToday}, computed when the
     * task runs.
     */
    @Input
    public abstract Property<String> getToday();

    /** A report of the last check, so the task is up to date until an input changes. */
    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void check() throws IOException {
        java.time.LocalDate today = today();
        File manifestFile = getManifest().get().getAsFile();
        ManifestContract contract;
        try {
            contract = ManifestContract.parse(read(manifestFile));
        } catch (IllegalArgumentException | groovy.json.JsonException e) {
            throw new GradleException("standardFlags: can't read " + manifestFile + ": " + e.getMessage(), e);
        }

        Map<String, ManifestContract.Type> reads = new TreeMap<>();
        Map<String, Set<String>> values = new TreeMap<>();
        for (Map.Entry<String, String> wire : getWires().get().entrySet()) {
            reads.put(wire.getKey(), type(wire.getValue(), wire.getKey()));
        }
        for (Map.Entry<String, List<String>> wire : getValues().get().entrySet()) {
            values.put(wire.getKey(), new LinkedHashSet<>(wire.getValue()));
        }
        if (getWiresFile().isPresent()) {
            File file = getWiresFile().get().getAsFile();
            Object document;
            try {
                document = new JsonSlurper().parseText(read(file));
            } catch (groovy.json.JsonException e) {
                throw new GradleException("standardFlags: can't read " + file + ": " + e.getMessage(), e);
            }
            if (!(document instanceof Map)) throw new GradleException("standardFlags: " + file + " is not a JSON object");
            for (Map.Entry<?, ?> wire : ((Map<?, ?>) document).entrySet()) {
                String name = String.valueOf(wire.getKey());
                Object declaration = wire.getValue();
                if (declaration instanceof Map) {
                    Map<?, ?> fields = (Map<?, ?>) declaration;
                    reads.put(name, type(fields.get("type"), name));
                    if (fields.get("values") instanceof List) {
                        Set<String> understood = new LinkedHashSet<>();
                        for (Object value : (List<?>) fields.get("values")) understood.add(String.valueOf(value));
                        values.put(name, understood);
                    }
                } else {
                    reads.put(name, type(declaration, name));
                }
            }
        }
        if (reads.isEmpty()) throw new GradleException("standardFlags: no wires declared; add wire(...) or wiresFile");

        StringBuilder baselineReport = new StringBuilder();
        if (getBaselineManifest().isPresent()) {
            File baseline = getBaselineManifest().get().getAsFile();
            ManifestCheck.Result check = ManifestCheck.compare(read(baseline), read(manifestFile), today);
            switch (check.status) {
                case INVALID:
                    File bad = "committed".equals(check.invalid) ? baseline : manifestFile;
                    throw new GradleException("standardFlags: " + bad + " is not a manifest the server can read: " + check.reason);
                case BREAKING:
                    StringBuilder message = new StringBuilder("standardFlags: ").append(manifestFile.getName())
                        .append(" breaks builds made against ").append(baseline.getName()).append(':');
                    for (ManifestCheck.Wire wire : check.removedWires) message.append("\n  - removed wire ").append(wire);
                    for (ManifestCheck.Wire wire : check.typeChanges) message.append("\n  - type changed: ").append(wire);
                    for (String audience : check.audiencesMadePrivate) message.append("\n  - audience made private: ").append(audience);
                    throw new GradleException(message.toString());
                default:
                    baselineReport.append("baseline: ").append(check.status.name().toLowerCase(java.util.Locale.ROOT)).append('\n');
            }
            for (String warning : check.warnings) {
                getLogger().warn("standardFlags: baseline: " + warning);
                baselineReport.append("warning: baseline: ").append(warning).append('\n');
            }
        }

        ManifestContract.Result result = contract.check(getAudience().get(), reads, values, today);
        boolean failDeprecated = getFailOnDeprecated().get();
        StringBuilder report = new StringBuilder();
        for (String warning : result.warnings) {
            getLogger().warn("standardFlags: " + warning);
            report.append("warning: ").append(warning).append('\n');
        }
        for (String error : result.errors) report.append("error: ").append(error).append('\n');
        if (!result.ok() || (failDeprecated && !result.warnings.isEmpty())) {
            StringBuilder message = new StringBuilder("standardFlags: the app's wires don't match ")
                .append(manifestFile.getName()).append(" (audience \"").append(getAudience().get()).append("\"):");
            for (String error : result.errors) message.append("\n  - ").append(error);
            if (failDeprecated) for (String warning : result.warnings) message.append("\n  - ").append(warning);
            throw new GradleException(message.toString());
        }
        report.append(baselineReport);
        report.append("ok: ").append(reads.size()).append(" wires checked against manifest version ")
            .append(contract.version()).append('\n');
        File out = getReport().get().getAsFile();
        Files.createDirectories(out.getParentFile().toPath());
        Files.write(out.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private java.time.LocalDate today() {
        String value = getToday().get();
        try {
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            // reported below
        }
        throw new GradleException("standardFlags: the check's date " + value + " is not a YYYY-MM-DD calendar date (" + FlagsContractPlugin.TODAY_PROPERTY + ")");
    }

    private static ManifestContract.Type type(Object value, String wire) {
        try {
            return ManifestContract.Type.parse(value, "wire " + wire);
        } catch (IllegalArgumentException e) {
            throw new GradleException("standardFlags: " + e.getMessage() + " (use \"boolean\" or \"string\")");
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

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
import java.util.Map;
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

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getWiresFile();

    @Input
    public abstract Property<Boolean> getFailOnDeprecated();

    /** A report of the last check, so the task is up to date until an input changes. */
    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void check() throws IOException {
        File manifestFile = getManifest().get().getAsFile();
        ManifestContract contract;
        try {
            contract = ManifestContract.parse(read(manifestFile));
        } catch (IllegalArgumentException | groovy.json.JsonException e) {
            throw new GradleException("standardFlags: can't read " + manifestFile + ": " + e.getMessage(), e);
        }

        Map<String, ManifestContract.Type> reads = new TreeMap<>();
        for (Map.Entry<String, String> wire : getWires().get().entrySet()) {
            reads.put(wire.getKey(), type(wire.getValue(), wire.getKey()));
        }
        if (getWiresFile().isPresent()) {
            File file = getWiresFile().get().getAsFile();
            Object document = new JsonSlurper().parseText(read(file));
            if (!(document instanceof Map)) throw new GradleException("standardFlags: " + file + " is not a JSON object");
            for (Map.Entry<?, ?> wire : ((Map<?, ?>) document).entrySet()) {
                reads.put(String.valueOf(wire.getKey()), type(wire.getValue(), String.valueOf(wire.getKey())));
            }
        }
        if (reads.isEmpty()) throw new GradleException("standardFlags: no wires declared; add wire(...) or wiresFile");

        ManifestContract.Result result = contract.check(getAudience().get(), reads);
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
        report.append("ok: ").append(reads.size()).append(" wires checked against manifest version ")
            .append(contract.version()).append('\n');
        File out = getReport().get().getAsFile();
        Files.createDirectories(out.getParentFile().toPath());
        Files.write(out.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
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

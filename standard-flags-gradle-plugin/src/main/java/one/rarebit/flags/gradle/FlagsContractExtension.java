package one.rarebit.flags.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code standardFlags} block:
 *
 * <pre>
 * standardFlags {
 *     manifest = rootProject.file("contracts/flags.manifest.json")
 *     audience = "mobile"
 *     wire("chat_enabled", "boolean")
 *     wire("homeHero", "string")
 *     // or keep them in a file: {"chat_enabled": "boolean", "homeHero": "string"}
 *     wiresFile = file("flags.wires.json")
 * }
 * </pre>
 */
public abstract class FlagsContractExtension {
    /** The server's committed flags manifest. */
    public abstract RegularFileProperty getManifest();

    /** The audience this app is. */
    public abstract Property<String> getAudience();

    /** The wires the app reads, by name, with their type ("boolean" or "string"). */
    public abstract MapProperty<String, String> getWires();

    /** A JSON object of more wires: {@code {"wire": "boolean" | "string"}}. */
    public abstract RegularFileProperty getWiresFile();

    /** Whether a deprecated wire fails the check instead of warning. Default false. */
    public abstract Property<Boolean> getFailOnDeprecated();

    /** Declares a wire the app reads. */
    public void wire(String name, String type) {
        getWires().put(name, type);
    }
}

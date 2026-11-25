package one.rarebit.flags.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Today's date in UTC, as {@code YYYY-MM-DD}. A value source wired into a task
 * input is computed when the task runs, so a configuration cache entry made on
 * an earlier day never freezes the date a deprecated wire's {@code until} is
 * compared with.
 */
public abstract class UtcToday implements ValueSource<String, UtcToday.Parameters> {
    /** The value source's parameters. */
    public interface Parameters extends ValueSourceParameters {
        /** A date to use instead of today (the {@code standardFlags.today} Gradle property), to reproduce a check. */
        Property<String> getOverride();
    }

    @Override
    public String obtain() {
        String override = getParameters().getOverride().getOrNull();
        return override != null ? override : LocalDate.now(ZoneOffset.UTC).toString();
    }
}

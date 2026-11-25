package one.rarebit.flags.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * {@code id("one.rarebit.flags.contract")}: adds {@code checkFlagsManifest},
 * which {@code check} runs, configured by the {@code standardFlags} block.
 */
public class FlagsContractPlugin implements Plugin<Project> {
    public static final String TASK_NAME = "checkFlagsManifest";

    @Override
    public void apply(Project project) {
        FlagsContractExtension extension = project.getExtensions().create("standardFlags", FlagsContractExtension.class);
        extension.getFailOnDeprecated().convention(false);

        TaskProvider<CheckFlagsManifest> task = project.getTasks().register(TASK_NAME, CheckFlagsManifest.class, t -> {
            t.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            t.setDescription("Checks the flag wires this app reads against the server's committed manifest.");
            t.getManifest().set(extension.getManifest());
            t.getAudience().set(extension.getAudience());
            t.getWires().set(extension.getWires());
            t.getWiresFile().set(extension.getWiresFile());
            t.getFailOnDeprecated().set(extension.getFailOnDeprecated());
            t.getReport().set(project.getLayout().getBuildDirectory().file("reports/standard-flags/contract.txt"));
        });

        project.getPlugins().withType(LifecycleBasePlugin.class, base ->
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(check -> check.dependsOn(task)));
    }
}

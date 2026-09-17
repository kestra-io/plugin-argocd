package io.kestra.plugin.argocd.apps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete an ArgoCD application",
    description = "Runs `argocd app delete` without prompting. By default the deletion is cascaded, so every resource managed by the application is deleted from the cluster too."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete an application and its resources",
            full = true,
            code = """
                id: argocd_delete
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.argocd.apps.Delete
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                """
        ),
        @Example(
            title = "Delete the application only and keep its resources on the cluster",
            full = true,
            code = """
                id: argocd_delete_orphan
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.argocd.apps.Delete
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                    cascade: false
                """
        ),
        @Example(
            title = "Delete an application and wait for the resources to be gone",
            full = true,
            code = """
                id: argocd_delete_wait
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.argocd.apps.Delete
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                    propagationPolicy: BACKGROUND
                    wait: true
                """
        )
    }
)
public class Delete extends AbstractArgoCD implements RunnableTask<Delete.Output> {

    public enum PropagationPolicy {
        FOREGROUND,
        BACKGROUND
    }

    @Schema(
        title = "Cascade deletion",
        description = "When true, delete every resource managed by the application; when false, only the application object is removed and its resources are orphaned."
    )
    @Builder.Default
    Property<Boolean> cascade = Property.ofValue(true);

    @Schema(
        title = "Propagation policy",
        description = "How the cascaded deletion propagates: `FOREGROUND` waits for the resources to be deleted before removing the application, `BACKGROUND` removes the application first."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<PropagationPolicy> propagationPolicy = Property.ofValue(PropagationPolicy.FOREGROUND);

    @Schema(
        title = "Wait for completion",
        description = "Block until the deletion of the application completes."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Boolean> wait = Property.ofValue(false);

    @Schema(
        title = "Application namespace",
        description = "Namespace the application is deleted from (`--app-namespace`)."
    )
    @PluginProperty(group = "advanced")
    Property<String> appNamespace;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rApplication = runContext.render(this.application).as(String.class).orElseThrow();
        boolean rCascade = runContext.render(this.cascade).as(Boolean.class).orElse(true);

        StringBuilder deleteCmd = new StringBuilder();
        deleteCmd.append("argocd app delete ").append(shellQuote(rApplication));
        deleteCmd.append(getServerArgs(runContext));

        // A task never has a terminal to confirm on, so the prompt is always turned off.
        deleteCmd.append(" --yes");
        deleteCmd.append(" --cascade=").append(rCascade);

        if (rCascade) {
            PropagationPolicy rPropagationPolicy = runContext.render(this.propagationPolicy)
                .as(PropagationPolicy.class)
                .orElse(PropagationPolicy.FOREGROUND);

            deleteCmd.append(" --propagation-policy ").append(rPropagationPolicy.name().toLowerCase(Locale.ROOT));
        }

        if (runContext.render(this.wait).as(Boolean.class).orElse(false)) {
            deleteCmd.append(" --wait");
        }

        String rAppNamespace = runContext.render(this.appNamespace).as(String.class).orElse(null);
        if (rAppNamespace != null) {
            deleteCmd.append(" --app-namespace ").append(shellQuote(rAppNamespace));
        }

        List<String> commands = new ArrayList<>();
        commands.add(deleteCmd.toString());

        StringBuilder stdOutBuilder = new StringBuilder();
        AbstractLogConsumer logConsumer = buildStdoutConsumer(stdOutBuilder, runContext);

        ScriptOutput scriptOutput = executeCommands(runContext, commands, logConsumer);

        runContext.logger().info("ArgoCD application {} deleted", rApplication);

        return Output.builder()
            .exitCode(scriptOutput.getExitCode())
            .stdOutLineCount(scriptOutput.getStdOutLineCount())
            .stdErrLineCount(scriptOutput.getStdErrLineCount())
            .vars(scriptOutput.getVars())
            .rawOutput(stdOutBuilder.toString().trim())
            .build();
    }

    @SuperBuilder
    @Getter
    public static class Output extends ScriptOutput {

        @Schema(
            title = "Raw output",
            description = "Unparsed CLI output for debugging."
        )
        private final String rawOutput;
    }
}

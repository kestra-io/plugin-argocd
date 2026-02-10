package io.kestra.plugin.argocd.app;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.RunnerType;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.kestra.plugin.scripts.runner.docker.Docker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractArgoCD extends Task {

    private static final String DEFAULT_IMAGE = "curlimages/curl:latest";

    @Schema(
        title = "ArgoCD API server URL.",
        description = "The URL of the ArgoCD API server (e.g., https://argocd.example.com)."
    )
    @NotNull
    Property<String> server;

    @Schema(
        title = "ArgoCD authentication token.",
        description = "The authentication token for ArgoCD API access."
    )
    @NotNull
    Property<String> token;

    @Schema(
        title = "ArgoCD application name",
        description = "The name of the ArgoCD application to operate on."
    )
    @NotNull
    Property<String> application;

    @Schema(
        title = "Skip TLS verification",
        description = "Whether to to skip TLS certificate verification when connecting to the ArgoCD server."
    )
    @Builder.Default
    Property<Boolean> insecure = Property.ofValue(true);

    @Schema(
        title = "Task runner to use.",
        description = "The task runner to use for executing the ArgoCD commands. By default, uses Docker with the curlimages/curl image."
    )
    @Builder.Default
    @PluginProperty
    @Valid
    protected TaskRunner<?> taskRunner = Docker.builder()
        .type(Docker.class.getName())
        .build();

    @Schema(
        title = "Container image.",
        description = "The container image to use for the task. Defaults to curlimages/curl:latest."
    )
    @Builder.Default
    protected Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @Schema(
        title = "Additional environment variables.",
        description = "Additional environment variables to pass to the container."
    )
    @PluginProperty(dynamic = true)
    protected Map<String, String> env;

    protected List<String> getInstallCommands(RunContext runContext) throws IllegalVariableEvaluationException {
        List<String> commands = new ArrayList<>();

        commands.add("curl -sSL -o /tmp/argocd https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-amd64");
        commands.add("chmod +x /tmp/argocd");

        return commands;
    }

    protected String getLoginCommand(RunContext runContext) throws IllegalVariableEvaluationException {
        String rServer = runContext.render(this.server).as(String.class).orElseThrow();
        String rToken = runContext.render(this.token).as(String.class).orElseThrow();

        StringBuilder cmd = new StringBuilder();
        cmd.append("/tmp/argocd login ").append(rServer);
        cmd.append(" --auth-token ").append(rToken);

        if (Boolean.TRUE.equals(this.insecure)) {
            cmd.append(" --insecure");
        }

        return cmd.toString();
    }

    protected Map<String, String> getEnvironmentVariables(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> envVars = new HashMap<>();

        if (this.env != null) {
            for (Map.Entry<String, String> entry : this.env.entrySet()) {
                envVars.put(entry.getKey(), runContext.render(entry.getValue()));
            }
        }

        return envVars;
    }

    protected ScriptOutput executeCommands(RunContext runContext, List<String> commands) throws Exception {
        return executeCommands(runContext, commands, null);
    }

    protected ScriptOutput executeCommands(RunContext runContext, List<String> commands, List<String> outputFiles) throws Exception {
        List<String> allCommands = new ArrayList<>();

        allCommands.addAll(getInstallCommands(runContext));

        allCommands.add(getLoginCommand(runContext));

        allCommands.addAll(commands);

        String renderedContainerImage = runContext.render(this.containerImage).as(String.class).orElseThrow();

        CommandsWrapper commandsWrapper = new CommandsWrapper(runContext)
            .withRunnerType(RunnerType.DOCKER)
            .withTaskRunner(this.taskRunner)
            .withContainerImage(renderedContainerImage)
            .withEnv(this.getEnvironmentVariables(runContext))
            .withCommands(
                (Property<List<String>>) ScriptService.scriptCommands(
                    List.of("/bin/sh", "-c"),
                    null,
                    allCommands
                )
            );

        if (outputFiles != null && !outputFiles.isEmpty()) {
            commandsWrapper = commandsWrapper.withOutputFiles(outputFiles);
        }

        return commandsWrapper.run();
    }
}

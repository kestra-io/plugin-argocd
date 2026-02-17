package io.kestra.plugin.argocd.app;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
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
        title = "ArgoCD API server URL",
        description = "The URL of the ArgoCD API server (e.g., `https://argocd.example.com`)."
    )
    @NotNull
    Property<String> server;

    @Schema(
        title = "ArgoCD authentication token",
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
        title = "Task runner.",
        description = "The task runner used to execute the ArgoCD CLI commands inside a container."
    )
    @Builder.Default
    @PluginProperty
    @Valid
    protected TaskRunner<?> taskRunner = Docker.builder()
        .type(Docker.class.getName())
        .entryPoint(new ArrayList<>())
        .build();

    @Schema(
        title = "Container image.",
        description = "The container image to use. Defaults to curlimages/curl:latest; the ArgoCD CLI is installed at runtime."
    )
    @Builder.Default
    protected Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @Schema(
        title = "Additional environment variables.",
        description = "Additional environment variables to pass to the container."
    )
    @PluginProperty(dynamic = true)
    protected Map<String, String> env;

    protected List<String> getInstallCommands() {
        return List.of(
            "curl -sSL -o /tmp/argocd https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-amd64",
            "chmod +x /tmp/argocd",
            "export PATH=$PATH:/tmp"
        );
    }

    /**
     * Returns common ArgoCD CLI flags for server connection, authentication, and TLS.
     */
    protected String getServerArgs(RunContext runContext) throws IllegalVariableEvaluationException {
        String rServer = runContext.render(this.server).as(String.class).orElseThrow();
        String rToken = runContext.render(this.token).as(String.class).orElseThrow();

        if (rServer.startsWith("https://")) {
            rServer = rServer.substring("https://".length());
        } else if (rServer.startsWith("http://")) {
            rServer = rServer.substring("http://".length());
        }

        StringBuilder args = new StringBuilder();
        args.append(" --server ").append(rServer);
        args.append(" --auth-token ").append(rToken);

        boolean rInsecure = runContext.render(this.insecure).as(Boolean.class).orElse(true);
        if (rInsecure) {
            args.append(" --insecure");
        }

        return args.toString();
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

    protected ScriptOutput executeCommands(RunContext runContext, List<String> commands, AbstractLogConsumer logConsumer) throws Exception {
        List<String> allCommands = new ArrayList<>();

        allCommands.addAll(getInstallCommands());
        allCommands.addAll(commands);

        String renderedContainerImage = runContext.render(this.containerImage).as(String.class).orElseThrow();

        CommandsWrapper commandsWrapper = new CommandsWrapper(runContext)
            .withTaskRunner(this.taskRunner)
            .withContainerImage(renderedContainerImage)
            .withEnv(this.getEnvironmentVariables(runContext))
            .withEnableOutputDirectory(true)
            .withLogConsumer(logConsumer)
            .withCommands(
                Property.ofValue(ScriptService.scriptCommands(
                    List.of("/bin/sh", "-c"),
                    null,
                    allCommands
                ))
            );

        return commandsWrapper.run();
    }
}

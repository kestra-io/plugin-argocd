package io.kestra.plugin.argocd.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.kestra.plugin.scripts.runner.docker.Docker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
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
    protected static final ObjectMapper OBJECT_MAPPER = JacksonMapper.ofJson();

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

    @Schema(
        title = "Server TLS certificate",
        description = "PEM-encoded certificate of the ArgoCD server. Use this when the server uses a self-signed or custom CA certificate. The certificate is written to a temporary file inside the container and passed via `--server-crt`."
    )
    Property<String> serverCert;

    @Schema(
        title = "Disable TLS",
        description = "Connect to the ArgoCD server over plain HTTP instead of HTTPS. Use this only when the server is not configured with TLS."
    )
    @Builder.Default
    Property<Boolean> plaintext = Property.ofValue(false);

    @Schema(
        title = "Enable gRPC-web",
        description = "Use the gRPC-web protocol instead of gRPC. Useful when the ArgoCD server is behind a proxy that does not support HTTP/2."
    )
    @Builder.Default
    Property<Boolean> grpcWeb = Property.ofValue(false);

    @Schema(
        title = "ArgoCD CLI version",
        description = "The version of the ArgoCD CLI to download (e.g., `2.10.0`). Defaults to the latest release if not specified."
    )
    Property<String> argoCDVersion;

    protected List<String> getInstallCommands(RunContext runContext) throws IllegalVariableEvaluationException {
        String rVersion = runContext.render(this.argoCDVersion).as(String.class).orElse(null);
        String arch = "$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')";
        String downloadUrl = rVersion != null
            ? "https://github.com/argoproj/argo-cd/releases/download/v" + rVersion + "/argocd-linux-" + arch
            : "https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-" + arch;

        return List.of(
            "curl -sSL -o /tmp/argocd " + downloadUrl,
            "chmod +x /tmp/argocd",
            "export PATH=$PATH:/tmp"
        );
    }

    protected String getServerArgs(RunContext runContext) throws IllegalVariableEvaluationException {
        String rServer = runContext.render(this.server).as(String.class).orElseThrow();
        String rToken = runContext.render(this.token).as(String.class).orElseThrow();
        String rServerCert = runContext.render(this.serverCert).as(String.class).orElse(null);
        Boolean rPlaintext = runContext.render(this.plaintext).as(Boolean.class).orElse(false);
        Boolean rGrpcWeb  = runContext.render(this.grpcWeb).as(Boolean.class).orElse(false);

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
        if (rPlaintext) {
            args.append(" --plaintext");
        }
        if (rGrpcWeb) {
            args.append(" --grpc-web");
        }
        if (rServerCert != null) {
            args.append(" --server-crt /tmp/argocd-server.crt");
        }

        return args.toString();
    }

    protected List<String> getCertCommands() {
        if (this.serverCert == null) return List.of();
        return List.of("printf '%s' \"$ARGOCD_SERVER_CERT\" > /tmp/argocd-server.crt");
    }

    protected Map<String, String> getEnvironmentVariables(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> envVars = new HashMap<>();

        if (this.env != null) {
            for (Map.Entry<String, String> entry : this.env.entrySet()) {
                envVars.put(entry.getKey(), runContext.render(entry.getValue()));
            }
        }

        runContext.render(this.serverCert).as(String.class).ifPresent(rServerCert -> envVars.put("ARGOCD_SERVER_CERT", rServerCert));

        return envVars;
    }

    protected AbstractLogConsumer buildStdoutConsumer(StringBuilder builder, RunContext runContext) {
        return new AbstractLogConsumer() {
            @Override
            public void accept(String line, Boolean isStdErr) {
                if (Boolean.FALSE.equals(isStdErr)) {
                    builder.append(line).append("\n");
                } else {
                    runContext.logger().warn(line);
                }
            }

            @Override
            public void accept(String line, Boolean isStdErr, Instant timestamp) {
                accept(line, isStdErr);
            }
        };
    }

    @SuppressWarnings("unchecked")
    protected String parseSyncStatus(Map<String, Object> statusMap) {
        if (!statusMap.containsKey("sync")) return null;
        Map<String, Object> sync = (Map<String, Object>) statusMap.get("sync");
        return (String) sync.get("status");
    }

    @SuppressWarnings("unchecked")
    protected String parseHealthStatus(Map<String, Object> statusMap) {
        if (!statusMap.containsKey("health")) return null;
        Map<String, Object> health = (Map<String, Object>) statusMap.get("health");
        return (String) health.get("status");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> parseResources(Map<String, Object> statusMap) {
        if (!statusMap.containsKey("resources")) return null;
        return (List<Map<String, Object>>) statusMap.get("resources");
    }

    protected ScriptOutput executeCommands(RunContext runContext, List<String> commands, AbstractLogConsumer logConsumer) throws Exception {
        List<String> allCommands = new ArrayList<>();

        allCommands.addAll(getInstallCommands(runContext));
        allCommands.addAll(getCertCommands());
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

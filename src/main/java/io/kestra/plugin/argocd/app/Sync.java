package io.kestra.plugin.argocd.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class Sync extends AbstractArgoCD implements RunnableTask<Sync.Output> {

    private static final ObjectMapper OBJECT_MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "Git revision",
        description = "Optional Git revision to sync to."
    )
    private Property<String> revision;

    @Schema(
        title = "Prune resources",
        description = "Whether to prune (delete) resources that are no longer defined in Git."
    )
    @Builder.Default
    private Property<Boolean> prune = Property.ofValue(false);

    @Schema(
        title = "Dry run mode",
        description = "Perform a dry-run sync to preview changes without actually applying them."
    )
    @Builder.Default
    private Property<Boolean> dryRun = Property.ofValue(false);

    @Schema(
        title = "Force sync",
        description = "Force the sync operation, which may cause resource recreation."
    )
    @Builder.Default
    private Property<Boolean> force = Property.ofValue(false);

    @Schema(
        title = "Sync timeout.",
        description = "Maximum duration to wait for the sync operation to complete."
    )
    private Property<Duration> timeout;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rApplication = runContext.render(this.application).as(String.class).orElseThrow();

        StringBuilder syncCmd = new StringBuilder();
        syncCmd.append("/tmp/argocd app sync ").append(rApplication);

        if (this.revision != null) {
            String rRevision = runContext.render(this.revision).as(String.class).orElse(null);
            syncCmd.append(" --revision ").append(rRevision);
        }

        if (Boolean.TRUE.equals(this.prune)) {
            syncCmd.append(" --prune");
        }

        if (Boolean.TRUE.equals(this.dryRun)) {
            syncCmd.append(" --dry-run");
        }

        if (Boolean.TRUE.equals(this.force)) {
            syncCmd.append(" --force");
        }

        if (this.timeout != null) {
            syncCmd.append(" --timeout ").append(this.timeout);
        }

        syncCmd.append(" --output json");

        List<String> commands = new ArrayList<>();
        commands.add(syncCmd.toString() + " > sync_output.json 2>&1 || true");

        ScriptOutput scriptOutput = executeCommands(runContext, commands, List.of("sync_output.json"));

        String rawOutput = "";
        Map<String, URI> outputFiles = scriptOutput.getOutputFiles();
        if (outputFiles != null && outputFiles.containsKey("sync_output.json")) {
            try (InputStream is = runContext.storage().getFile(outputFiles.get("sync_output.json"))) {
                rawOutput = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        String syncStatus = null;
        String healthStatus = null;
        String outputRevision = null;
        List<Map<String, Object>> resources = null;

        try {
            String jsonOutput = extractJson(rawOutput);
            if (jsonOutput != null && !jsonOutput.isEmpty()) {
                Map<String, Object> result = OBJECT_MAPPER.readValue(jsonOutput, new TypeReference<Map<String, Object>>() {});

                // Extract sync status
                if (result.containsKey("status")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> status = (Map<String, Object>) result.get("status");

                    if (status.containsKey("sync")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sync = (Map<String, Object>) status.get("sync");
                        syncStatus = (String) sync.get("status");
                        outputRevision = (String) sync.get("revision");
                    }

                    if (status.containsKey("health")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> health = (Map<String, Object>) status.get("health");
                        healthStatus = (String) health.get("status");
                    }

                    if (status.containsKey("resources")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> resourceList = (List<Map<String, Object>>) status.get("resources");
                        resources = resourceList;
                    }
                }
            }
        } catch (Exception e) {
            runContext.logger().warn("Failed to parse ArgoCD output as JSON: {}", e.getMessage());
        }

        runContext.logger().info("ArgoCD sync completed - Status: {}, Health: {}", syncStatus, healthStatus);

        return Output.builder()
            .syncStatus(syncStatus)
            .healthStatus(healthStatus)
            .revision(outputRevision)
            .resources(resources)
            .rawOutput(rawOutput)
            .exitCode(scriptOutput.getExitCode())
            .build();
    }

    private String extractJson(String output) {
        if (output == null || output.isEmpty()) {
            return  null;
        }

        int startIndex  = output.indexOf("{");
        int endIndex = output.lastIndexOf("}");

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return output.substring(startIndex, endIndex + 1);
        }
        return null;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Sync status.",
            description = "The synchronization status of the application (e.g., 'Synced', 'OutOfSync')."
        )
        private final String syncStatus;

        @Schema(
            title = "Health status.",
            description = "The health status of the application (e.g., 'Healthy', 'Progressing', 'Degraded')."
        )
        private final String healthStatus;

        @Schema(
            title = "Revision.",
            description = "The Git revision (commit SHA) that the application is synced to."
        )
        private final String revision;

        @Schema(
            title = "Resources.",
            description = "The status of managed Kubernetes resources."
        )
        private final List<Map<String, Object>> resources;

        @Schema(
            title = "Raw output.",
            description = "The raw CLI output for debugging and observability."
        )
        private final String rawOutput;

        @Schema(
            title = "Exit code.",
            description = "The exit code from the ArgoCD CLI command."
        )
        private final Integer exitCode;
    }
}

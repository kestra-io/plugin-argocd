package io.kestra.plugin.argocd.app;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a synchronization of an ArgoCD Application.",
    description = "Deploys the desired state from Git to Kubernetes by syncing an ArgoCD application."
)
@Plugin(
    examples = {
        @Example(
            title = "Sync an ArgoCD application",
            full = true,
            code = """
                id: argocd_sync
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.argocd.app.Sync
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                """
        )
    }
)
public class Sync extends AbstractArgoCD implements RunnableTask<Sync.Output> {

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
        title = "Sync timeout",
        description = "Maximum duration to wait for the sync operation to complete."
    )
    private Property<Duration> timeout;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rApplication = runContext.render(this.application).as(String.class).orElseThrow();

        StringBuilder syncCmd = new StringBuilder();
        syncCmd.append("argocd app sync ").append(rApplication);
        syncCmd.append(getServerArgs(runContext));

        if (this.revision != null) {
            String rRevision = runContext.render(this.revision).as(String.class).orElse(null);
            if (rRevision != null) {
                syncCmd.append(" --revision ").append(rRevision);
            }
        }

        if (runContext.render(this.prune).as(Boolean.class).orElse(false)) {
            syncCmd.append(" --prune");
        }

        if (runContext.render(this.dryRun).as(Boolean.class).orElse(false)) {
            syncCmd.append(" --dry-run");
        }

        if (runContext.render(this.force).as(Boolean.class).orElse(false)) {
            syncCmd.append(" --force");
        }

        if (this.timeout != null) {
            Duration rTimeout = runContext.render(this.timeout).as(Duration.class).orElse(null);
            if (rTimeout != null) {
                syncCmd.append(" --timeout ").append(rTimeout.getSeconds());
            }
        }

        syncCmd.append(" --output json");

        List<String> commands = new ArrayList<>();
        commands.add(syncCmd.toString());

        StringBuilder stdOutBuilder = new StringBuilder();
        AbstractLogConsumer logConsumer = buildStdoutConsumer(stdOutBuilder, runContext);

        ScriptOutput scriptOutput = executeCommands(runContext, commands, logConsumer);

        String rawOutput = stdOutBuilder.toString().trim();
        String syncStatus = null;
        String healthStatus = null;
        String outputRevision = null;
        List<Map<String, Object>> resources = null;

        try {
            if (!rawOutput.isEmpty()) {
                Map<String, Object> result = OBJECT_MAPPER.readValue(rawOutput, new TypeReference<Map<String, Object>>() {});

                if (result.containsKey("status")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> status = (Map<String, Object>) result.get("status");

                    syncStatus = parseSyncStatus(status);
                    healthStatus = parseHealthStatus(status);
                    resources = parseResources(status);

                    if (status.containsKey("sync")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sync = (Map<String, Object>) status.get("sync");
                        outputRevision = (String) sync.get("revision");
                    }
                }
            }
        } catch (Exception e) {
            runContext.logger().warn("Failed to parse ArgoCD output as JSON: {}", e.getMessage());
        }

        runContext.logger().info("ArgoCD sync completed - Status: {}, Health: {}", syncStatus, healthStatus);

        return Output.builder()
            .exitCode(scriptOutput.getExitCode())
            .stdOutLineCount(scriptOutput.getStdOutLineCount())
            .stdErrLineCount(scriptOutput.getStdErrLineCount())
            .vars(scriptOutput.getVars())
            .syncStatus(syncStatus)
            .healthStatus(healthStatus)
            .revision(outputRevision)
            .resources(resources)
            .rawOutput(rawOutput)
            .build();
    }

    @SuperBuilder
    @Getter
    public static class Output extends ScriptOutput {

        @Schema(
            title = "Sync status",
            description = "Synchronization status reported by ArgoCD (e.g., `Synced`, `OutOfSync`)."
        )
        private final String syncStatus;

        @Schema(
            title = "Health status.",
            description = "Application health from ArgoCD (e.g., Healthy, Progressing, Degraded)."
        )
        private final String healthStatus;

        @Schema(
            title = "Revision.",
            description = "Git revision (commit SHA) the application ended up syncing to."
        )
        private final String revision;

        @Schema(
            title = "Resources",
            description = "Statuses of managed Kubernetes resources returned by ArgoCD."
        )
        private final List<Map<String, Object>> resources;

        @Schema(
            title = "Raw output",
            description = "Unparsed CLI JSON/text for debugging when parsing fails or for additional fields."
        )
        private final String rawOutput;
    }
}

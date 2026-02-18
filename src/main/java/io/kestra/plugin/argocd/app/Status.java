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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch ArgoCD application status",
    description = "Reads the application's sync and health state plus conditions/resources; optional refresh forces a live cluster check and raw CLI output is kept for troubleshooting."
)
@Plugin(
    examples = {
        @Example(
            title = "Get the status of an ArgoCD application",
            full = true,
            code = """
                id: argocd_status
                namespace: company.team

                tasks:
                  - id: status
                    type: io.kestra.plugin.argocd.app.Status
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                """
        ),
        @Example(
            title = "Get status with forced refresh",
            full = true,
            code = """
                id: argocd_status_refresh
                namespace: company.team

                tasks:
                  - id: status
                    type: io.kestra.plugin.argocd.app.Status
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                    refresh: true
                """
        )
    }
)
public class Status extends AbstractArgoCD implements RunnableTask<Status.Output> {

    @Schema(
        title = "Force refresh",
        description = "When true, calls `argocd app get --refresh` to bypass cache and re-query the cluster (adds an extra API call)."
    )
    @Builder.Default
    private Property<Boolean> refresh = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rApplication = runContext.render(this.application).as(String.class).orElseThrow();

        StringBuilder getCmd = new StringBuilder();
        getCmd.append("argocd app get ").append(rApplication);
        getCmd.append(getServerArgs(runContext));

        boolean rRefresh = runContext.render(this.refresh).as(Boolean.class).orElse(false);
        if (rRefresh) {
            getCmd.append(" --refresh");
        }

        getCmd.append(" --output json");

        List<String> commands = new ArrayList<>();
        commands.add(getCmd.toString());

        StringBuilder stdOutBuilder = new StringBuilder();
        AbstractLogConsumer logConsumer = buildStdoutConsumer(stdOutBuilder, runContext);

        ScriptOutput scriptOutput = executeCommands(runContext, commands, logConsumer);

        String rawOutput = stdOutBuilder.toString().trim();
        String syncStatus = null;
        String healthStatus = null;
        List<Map<String, Object>> conditions = null;
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

                    if (status.containsKey("conditions")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> conditionList = (List<Map<String, Object>>) status.get("conditions");
                        conditions = conditionList;
                    }
                }
            }
        } catch (Exception e) {
            runContext.logger().warn("Failed to parse ArgoCD output as JSON: {}", e.getMessage());
        }

        runContext.logger().info("ArgoCD status retrieved - Sync: {}, Health: {}", syncStatus, healthStatus);

        return Output.builder()
            .exitCode(scriptOutput.getExitCode())
            .stdOutLineCount(scriptOutput.getStdOutLineCount())
            .stdErrLineCount(scriptOutput.getStdErrLineCount())
            .vars(scriptOutput.getVars())
            .syncStatus(syncStatus)
            .healthStatus(healthStatus)
            .conditions(conditions)
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
            title = "Health status",
            description = "Application health from ArgoCD (e.g., Healthy, Progressing, Degraded)."
        )
        private final String healthStatus;

        @Schema(
            title = "Conditions",
            description = "Application conditions returned by ArgoCD (warnings, errors, etc.)."
        )
        private final List<Map<String, Object>> conditions;

        @Schema(
            title = "Resources",
            description = "Statuses of managed Kubernetes resources from the ArgoCD response."
        )
        private final List<Map<String, Object>> resources;

        @Schema(
            title = "Raw output",
            description = "Unparsed CLI JSON/text for debugging when parsing fails or for additional fields."
        )
        private final String rawOutput;
    }
}

package io.kestra.plugin.argocd.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Retrieve the current status of an ArgoCD Application.",
    description = "Enables orchestration logic to inspect the synchronization and health state of an application."
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

    private static final ObjectMapper OBJECT_MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "Force refresh.",
        description = "Force a status refresh from the cluster before retrieving data."
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
        AbstractLogConsumer logConsumer = new AbstractLogConsumer() {
            @Override
            public void accept(String line, Boolean isStdErr) {
                if (Boolean.FALSE.equals(isStdErr)) {
                    stdOutBuilder.append(line).append("\n");
                }
            }

            @Override
            public void accept(String line, Boolean isStdErr, Instant timestamp) {
                accept(line, isStdErr);
            }
        };

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

                    if (status.containsKey("sync")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sync = (Map<String, Object>) status.get("sync");
                        syncStatus = (String) sync.get("status");
                    }

                    if (status.containsKey("health")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> health = (Map<String, Object>) status.get("health");
                        healthStatus = (String) health.get("status");
                    }

                    if (status.containsKey("conditions")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> conditionList = (List<Map<String, Object>>) status.get("conditions");
                        conditions = conditionList;
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
            title = "Conditions.",
            description = "The ArgoCD application conditions (warnings, errors, etc.)."
        )
        private final List<Map<String, Object>> conditions;

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
    }
}

package io.kestra.plugin.argocd.apps;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Patch an ArgoCD application",
    description = "Runs `argocd app patch` to update the application spec in place with a JSON patch or a merge patch. The CLI returns the patched application as YAML, which is parsed into the task outputs."
)
@Plugin(
    examples = {
        @Example(
            title = "Change the target revision of an application with a merge patch",
            full = true,
            code = """
                id: argocd_patch
                namespace: company.team

                tasks:
                  - id: patch
                    type: io.kestra.plugin.argocd.apps.Patch
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                    patchType: MERGE
                    patch: |
                      {"spec": {"source": {"targetRevision": "master"}}}
                """
        ),
        @Example(
            title = "Change the source path of an application with a JSON patch",
            full = true,
            code = """
                id: argocd_patch_json
                namespace: company.team

                tasks:
                  - id: patch
                    type: io.kestra.plugin.argocd.apps.Patch
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                    patch: |
                      [{"op": "replace", "path": "/spec/source/path", "value": "newPath"}]
                """
        ),
        @Example(
            title = "Apply a patch stored as a namespace file",
            full = true,
            code = """
                id: argocd_patch_file
                namespace: company.team

                tasks:
                  - id: patch
                    type: io.kestra.plugin.argocd.apps.Patch
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: my-application
                    patchType: MERGE
                    patch: nsfile:///patches/target-revision.json
                """
        )
    }
)
public class Patch extends AbstractArgoCD implements RunnableTask<Patch.Output> {

    private static final ObjectMapper YAML_MAPPER = JacksonMapper.ofYaml();

    public enum PatchType {
        JSON,
        MERGE
    }

    @Schema(
        title = "Patch body",
        description = "Inline JSON, or a `kestra://`, `file://` or `nsfile://` URI pointing to the patch file. A JSON patch is a list of operations, a merge patch is an object with the fields to override."
    )
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    Property<String> patch;

    @Schema(
        title = "Patch type",
        description = "Format of the patch body: `JSON` for a RFC 6902 JSON patch, `MERGE` for a RFC 7386 merge patch."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    Property<PatchType> patchType = Property.ofValue(PatchType.JSON);

    @Schema(
        title = "Application namespace",
        description = "Restrict the patch to an application living in this namespace (`--app-namespace`)."
    )
    @PluginProperty(group = "advanced")
    private Property<String> appNamespace;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApplication = runContext.render(this.application)
            .as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Missing required property 'application'"));
        var rType = runContext.render(this.patchType).as(PatchType.class).orElse(PatchType.JSON);

        var patchCmd = new StringBuilder();
        patchCmd.append("argocd app patch ").append(shellQuote(rApplication));
        patchCmd.append(getServerArgs(runContext));

        // The patch body goes through an environment variable so quotes and braces survive the shell.
        patchCmd.append(" --patch \"$ARGOCD_PATCH\"");
        patchCmd.append(" --type ").append(rType.name().toLowerCase(Locale.ROOT));

        var rAppNamespace = runContext.render(this.appNamespace).as(String.class).orElse(null);
        if (rAppNamespace != null) {
            patchCmd.append(" --app-namespace ").append(shellQuote(rAppNamespace));
        }

        var commands = new ArrayList<String>();
        commands.add(patchCmd.toString());

        var stdOutBuilder = new StringBuilder();
        var logConsumer = buildStdoutConsumer(stdOutBuilder, runContext);

        var scriptOutput = executeCommands(runContext, commands, logConsumer);

        var rawOutput = stdOutBuilder.toString().trim();
        String syncStatus = null;
        String healthStatus = null;
        Map<String, Object> spec = null;

        try {
            if (!rawOutput.isEmpty()) {
                var result = YAML_MAPPER.readValue(rawOutput, new TypeReference<Map<String, Object>>() {
                });

                if (result.get("status") instanceof Map<?, ?> status) {
                    @SuppressWarnings("unchecked")
                    var statusMap = (Map<String, Object>) status;

                    syncStatus = parseSyncStatus(statusMap);
                    healthStatus = parseHealthStatus(statusMap);
                }

                if (result.get("spec") instanceof Map<?, ?> specMap) {
                    @SuppressWarnings("unchecked")
                    var castSpec = (Map<String, Object>) specMap;
                    spec = castSpec;
                }
            }
        } catch (Exception e) {
            runContext.logger().warn("Failed to parse ArgoCD output as YAML: {}", e.getMessage());
        }

        runContext.logger().info("ArgoCD patch applied on {} - Sync: {}, Health: {}", rApplication, syncStatus, healthStatus);

        return Output.builder()
            .exitCode(scriptOutput.getExitCode())
            .stdOutLineCount(scriptOutput.getStdOutLineCount())
            .stdErrLineCount(scriptOutput.getStdErrLineCount())
            .vars(scriptOutput.getVars())
            .syncStatus(syncStatus)
            .healthStatus(healthStatus)
            .spec(spec)
            .rawOutput(rawOutput)
            .build();
    }

    @Override
    protected Map<String, String> getEnvironmentVariables(RunContext runContext) throws IllegalVariableEvaluationException {
        var envVars = super.getEnvironmentVariables(runContext);
        envVars.put("ARGOCD_PATCH", fetchContent(runContext, this.patch, "patch"));

        return envVars;
    }

    @SuperBuilder
    @Getter
    public static class Output extends ScriptOutput {

        @Schema(
            title = "Sync status",
            description = "Synchronization status of the patched application (e.g., `Synced`, `OutOfSync`)."
        )
        private final String syncStatus;

        @Schema(
            title = "Health status",
            description = "Health of the patched application (e.g., `Healthy`, `Progressing`, `Degraded`)."
        )
        private final String healthStatus;

        @Schema(
            title = "Application spec",
            description = "The `spec` section of the application after the patch."
        )
        private final Map<String, Object> spec;

        @Schema(
            title = "Raw output",
            description = "Unparsed CLI YAML for debugging when parsing fails or for additional fields."
        )
        private final String rawOutput;
    }
}

package io.kestra.plugin.argocd.apps;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.property.URIFetcher;
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
    title = "Create an ArgoCD application",
    description = "Runs `argocd app create`, either from a full Application manifest or from the repository and destination properties. With `upsert` enabled, an existing application is updated instead of failing."
)
@Plugin(
    examples = {
        @Example(
            title = "Create an application from a Git repository",
            full = true,
            code = """
                id: argocd_create
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.argocd.apps.Create
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: guestbook
                    repo: https://github.com/argoproj/argocd-example-apps.git
                    path: guestbook
                    destServer: https://kubernetes.default.svc
                    destNamespace: default
                """
        ),
        @Example(
            title = "Create an application with an automated sync policy",
            full = true,
            code = """
                id: argocd_create_automated
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.argocd.apps.Create
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: guestbook
                    repo: https://github.com/argoproj/argocd-example-apps.git
                    path: guestbook
                    revision: HEAD
                    destName: in-cluster
                    destNamespace: default
                    project: default
                    syncPolicy: AUTOMATED
                    autoPrune: true
                    selfHeal: true
                    upsert: true
                """
        ),
        @Example(
            title = "Create an application from an Application manifest",
            full = true,
            code = """
                id: argocd_create_manifest
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.argocd.apps.Create
                    server: "{{ secret('ARGOCD_SERVER') }}"
                    token: "{{ secret('ARGOCD_TOKEN') }}"
                    application: guestbook
                    manifest: nsfile:///applications/guestbook.yaml
                """
        )
    }
)
public class Create extends AbstractArgoCD implements RunnableTask<Create.Output> {

    private static final Pattern RESULT_PATTERN = Pattern.compile("application '(?<name>.+)' (?<action>created|updated|unchanged)");

    public enum SyncPolicy {
        MANUAL,
        AUTOMATED
    }

    @Schema(
        title = "Application manifest",
        description = "Inline YAML or JSON of the Application resource, or a `kestra://`, `file://` or `nsfile://` URI pointing to it. The manifest is piped to `--file -`, and `repo`, `path` and the other source properties are ignored when it is set."
    )
    @PluginProperty(internalStorageURI = true, group = "source")
    Property<String> manifest;

    @Schema(
        title = "Repository URL",
        description = "Git or Helm repository holding the application manifests."
    )
    @PluginProperty(group = "source")
    Property<String> repo;

    @Schema(
        title = "Repository path",
        description = "Path to the application directory inside the repository."
    )
    @PluginProperty(group = "source")
    Property<String> path;

    @Schema(
        title = "Target revision",
        description = "Branch, tag, commit, or Helm chart version the application tracks."
    )
    @PluginProperty(group = "source")
    Property<String> revision;

    @Schema(
        title = "Destination cluster URL",
        description = "Kubernetes API URL of the target cluster (e.g. `https://kubernetes.default.svc`). Mutually exclusive with `destName`."
    )
    @PluginProperty(group = "destination")
    Property<String> destServer;

    @Schema(
        title = "Destination cluster name",
        description = "Name of the target cluster as registered in ArgoCD (e.g. `in-cluster`). Mutually exclusive with `destServer`."
    )
    @PluginProperty(group = "destination")
    Property<String> destName;

    @Schema(
        title = "Destination namespace",
        description = "Kubernetes namespace the application deploys to."
    )
    @PluginProperty(group = "destination")
    Property<String> destNamespace;

    @Schema(
        title = "ArgoCD project",
        description = "Project the application belongs to; defaults to `default` on the ArgoCD side."
    )
    @PluginProperty(group = "main")
    Property<String> project;

    @Schema(
        title = "Sync policy",
        description = "`MANUAL` requires an explicit sync, `AUTOMATED` lets ArgoCD sync on its own."
    )
    @PluginProperty(group = "advanced")
    Property<SyncPolicy> syncPolicy;

    @Schema(
        title = "Automatic pruning",
        description = "With an automated sync policy, delete resources removed from Git."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Boolean> autoPrune = Property.ofValue(false);

    @Schema(
        title = "Self healing",
        description = "With an automated sync policy, revert manual changes made on the cluster."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Boolean> selfHeal = Property.ofValue(false);

    @Schema(
        title = "Upsert",
        description = "Update the application when it already exists instead of failing."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Boolean> upsert = Property.ofValue(false);

    @Schema(
        title = "Validate",
        description = "Validate the repository and the destination cluster before creating; enabled by default on the ArgoCD side."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Boolean> validate = Property.ofValue(true);

    @Schema(
        title = "Labels",
        description = "Labels applied to the created application."
    )
    @PluginProperty(group = "advanced")
    Property<Map<String, String>> labels;

    @Schema(
        title = "Annotations",
        description = "Annotations applied to the created application."
    )
    @PluginProperty(group = "advanced")
    Property<Map<String, String>> annotations;

    @Schema(
        title = "Application namespace",
        description = "Namespace the application object is created in (`--app-namespace`)."
    )
    @PluginProperty(group = "advanced")
    Property<String> appNamespace;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rApplication = runContext.render(this.application).as(String.class).orElseThrow();

        StringBuilder createCmd = new StringBuilder();
        createCmd.append("argocd app create ").append(shellQuote(rApplication));
        createCmd.append(getServerArgs(runContext));

        if (this.manifest != null) {
            // The manifest goes through an environment variable, then to the CLI on stdin.
            createCmd.insert(0, "printf '%s' \"$ARGOCD_MANIFEST\" | ");
            createCmd.append(" --file -");
        }

        appendIfPresent(createCmd, "--repo", runContext.render(this.repo).as(String.class).orElse(null));
        appendIfPresent(createCmd, "--path", runContext.render(this.path).as(String.class).orElse(null));
        appendIfPresent(createCmd, "--revision", runContext.render(this.revision).as(String.class).orElse(null));
        appendIfPresent(createCmd, "--dest-server", runContext.render(this.destServer).as(String.class).orElse(null));
        appendIfPresent(createCmd, "--dest-name", runContext.render(this.destName).as(String.class).orElse(null));
        appendIfPresent(createCmd, "--dest-namespace", runContext.render(this.destNamespace).as(String.class).orElse(null));
        appendIfPresent(createCmd, "--project", runContext.render(this.project).as(String.class).orElse(null));
        appendIfPresent(createCmd, "--app-namespace", runContext.render(this.appNamespace).as(String.class).orElse(null));

        SyncPolicy rSyncPolicy = runContext.render(this.syncPolicy).as(SyncPolicy.class).orElse(null);
        if (rSyncPolicy != null) {
            createCmd.append(" --sync-policy ").append(rSyncPolicy.name().toLowerCase(Locale.ROOT));
        }

        if (runContext.render(this.autoPrune).as(Boolean.class).orElse(false)) {
            createCmd.append(" --auto-prune");
        }

        if (runContext.render(this.selfHeal).as(Boolean.class).orElse(false)) {
            createCmd.append(" --self-heal");
        }

        if (runContext.render(this.upsert).as(Boolean.class).orElse(false)) {
            createCmd.append(" --upsert");
        }

        if (!runContext.render(this.validate).as(Boolean.class).orElse(true)) {
            createCmd.append(" --validate=false");
        }

        runContext.render(this.labels).asMap(String.class, String.class)
            .forEach((key, value) -> createCmd.append(" --label ").append(shellQuote(key + "=" + value)));

        runContext.render(this.annotations).asMap(String.class, String.class)
            .forEach((key, value) -> createCmd.append(" --annotations ").append(shellQuote(key + "=" + value)));

        List<String> commands = new ArrayList<>();
        commands.add(createCmd.toString());

        StringBuilder stdOutBuilder = new StringBuilder();
        AbstractLogConsumer logConsumer = buildStdoutConsumer(stdOutBuilder, runContext);

        ScriptOutput scriptOutput = executeCommands(runContext, commands, logConsumer);

        String rawOutput = stdOutBuilder.toString().trim();
        String createdApplication = null;
        String action = null;

        Matcher matcher = RESULT_PATTERN.matcher(rawOutput);
        if (matcher.find()) {
            createdApplication = matcher.group("name");
            action = matcher.group("action");
        }

        runContext.logger().info("ArgoCD application {} {}", rApplication, action != null ? action : "create returned no result");

        return Output.builder()
            .exitCode(scriptOutput.getExitCode())
            .stdOutLineCount(scriptOutput.getStdOutLineCount())
            .stdErrLineCount(scriptOutput.getStdErrLineCount())
            .vars(scriptOutput.getVars())
            .application(createdApplication)
            .action(action)
            .rawOutput(rawOutput)
            .build();
    }

    @Override
    protected Map<String, String> getEnvironmentVariables(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> envVars = super.getEnvironmentVariables(runContext);

        if (this.manifest != null) {
            envVars.put("ARGOCD_MANIFEST", renderManifest(runContext));
        }

        return envVars;
    }

    private void appendIfPresent(StringBuilder command, String flag, String value) {
        if (value != null) {
            command.append(" ").append(flag).append(" ").append(shellQuote(value));
        }
    }

    private String renderManifest(RunContext runContext) throws IllegalVariableEvaluationException {
        String rManifest = runContext.render(this.manifest).as(String.class).orElseThrow();

        if (!URIFetcher.supports(rManifest)) {
            return rManifest;
        }

        try (InputStream inputStream = URIFetcher.of(rManifest).fetch(runContext)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the manifest file " + rManifest, e);
        }
    }

    @SuperBuilder
    @Getter
    public static class Output extends ScriptOutput {

        @Schema(
            title = "Application name",
            description = "Name of the application as reported by the CLI."
        )
        private final String application;

        @Schema(
            title = "Action",
            description = "What the CLI did: `created`, `updated`, or `unchanged`."
        )
        private final String action;

        @Schema(
            title = "Raw output",
            description = "Unparsed CLI output for debugging."
        )
        private final String rawOutput;
    }
}

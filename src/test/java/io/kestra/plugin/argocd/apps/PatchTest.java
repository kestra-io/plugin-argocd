package io.kestra.plugin.argocd.apps;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;

import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@KestraTest
class PatchTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldParseYamlOutput() throws Exception {
        var task = new StubPatch("""
            spec:
              source:
                targetRevision: master
            status:
              sync:
                status: OutOfSync
              health:
                status: Healthy
            """);
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");
        task.patch = Property.ofValue("{\"spec\": {\"source\": {\"targetRevision\": \"master\"}}}");
        task.patchType = Property.ofValue(Patch.PatchType.MERGE);

        var output = task.run(runContext());

        assertEquals(0, output.getExitCode());
        assertEquals("OutOfSync", output.getSyncStatus());
        assertEquals("Healthy", output.getHealthStatus());
        assertNotNull(output.getSpec());
        assertEquals(
            "argocd app patch my-application --server argocd.example.com --auth-token $ARGOCD_TOKEN --patch \"$ARGOCD_PATCH\" --type merge",
            task.executedCommands().getFirst()
        );
    }

    @Test
    void shouldPassPatchBodyThroughEnvironment() throws Exception {
        var task = new StubPatch("");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");
        task.patch = Property.ofValue("[{\"op\": \"replace\", \"path\": \"/spec/source/path\", \"value\": \"newPath\"}]");

        assertEquals(
            "[{\"op\": \"replace\", \"path\": \"/spec/source/path\", \"value\": \"newPath\"}]",
            task.getEnvironmentVariables(runContext()).get("ARGOCD_PATCH")
        );
    }

    @Test
    void shouldReadPatchBodyFromInternalStorage() throws Exception {
        var runContext = runContext();
        var patchFile = Files.createTempFile("argocd-patch", ".json");
        Files.writeString(patchFile, "{\"spec\": {\"source\": {\"targetRevision\": \"master\"}}}");
        var uri = runContext.storage().putFile(patchFile.toFile());

        var task = new StubPatch("");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");
        task.patch = Property.ofValue(uri.toString());

        assertEquals(
            "{\"spec\": {\"source\": {\"targetRevision\": \"master\"}}}",
            task.getEnvironmentVariables(runContext).get("ARGOCD_PATCH")
        );
    }

    @Test
    void shouldReturnRawOutputWhenYamlIsInvalid() throws Exception {
        var task = new StubPatch("not-yaml: [");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");
        task.patch = Property.ofValue("{}");

        var output = task.run(runContext());

        assertEquals(0, output.getExitCode());
        assertNull(output.getSyncStatus());
        assertNull(output.getHealthStatus());
        assertNull(output.getSpec());
        assertEquals("not-yaml: [", output.getRawOutput());
    }

    private RunContext runContext() {
        return runContextFactory.of(
            Map.of(
                "flow", Map.of(
                    "id", "flow",
                    "namespace", "io.kestra.test",
                    "tenantId", "main"
                )
            )
        );
    }

    private static final class StubPatch extends Patch {
        private final String stdout;
        private List<String> executedCommands = List.of();

        private StubPatch(String stdout) {
            this.stdout = stdout;
        }

        @Override
        protected ScriptOutput executeCommands(RunContext runContext, List<String> commands, AbstractLogConsumer logConsumer) {
            this.executedCommands = List.copyOf(commands);
            logConsumer.accept(this.stdout, false);

            return ScriptOutput.builder()
                .vars(Map.of())
                .exitCode(0)
                .stdOutLineCount(1)
                .stdErrLineCount(0)
                .build();
        }

        private List<String> executedCommands() {
            return executedCommands;
        }
    }
}

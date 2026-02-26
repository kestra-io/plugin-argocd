package io.kestra.plugin.argocd.apps;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@KestraTest
class StatusTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldParseJsonOutput() throws Exception {
        var task = new StubStatus("""
            {"status":{"sync":{"status":"Synced"},"health":{"status":"Healthy"},"conditions":[{"type":"Warning"}],"resources":[{"kind":"Deployment","name":"demo"}]}}
            """);
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");

        var output = task.run(runContext());

        assertEquals(0, output.getExitCode());
        assertEquals("Synced", output.getSyncStatus());
        assertEquals("Healthy", output.getHealthStatus());
        assertNotNull(output.getConditions());
        assertNotNull(output.getResources());
        assertEquals("Warning", output.getConditions().getFirst().get("type"));
        assertEquals("Deployment", output.getResources().getFirst().get("kind"));
        assertEquals(
            "argocd app get my-application --server argocd.example.com --auth-token token --insecure --output json",
            task.executedCommands().getFirst()
        );
    }

    @Test
    void shouldReturnRawOutputWhenJsonIsInvalid() throws Exception {
        var task = new StubStatus("not-json");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");

        var output = task.run(runContext());

        assertEquals(0, output.getExitCode());
        assertNull(output.getSyncStatus());
        assertNull(output.getHealthStatus());
        assertEquals("not-json", output.getRawOutput());
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of(
            "flow", Map.of(
                "id", "flow",
                "namespace", "io.kestra.test",
                "tenantId", "main"
            )
        ));
    }

    private static final class StubStatus extends Status {
        private final String stdout;
        private List<String> executedCommands = List.of();

        private StubStatus(String stdout) {
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

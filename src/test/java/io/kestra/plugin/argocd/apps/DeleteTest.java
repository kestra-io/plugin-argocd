package io.kestra.plugin.argocd.apps;

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

@KestraTest
class DeleteTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldCascadeByDefault() throws Exception {
        var task = new StubDelete("");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");

        var output = task.run(runContext());

        assertEquals(0, output.getExitCode());
        assertEquals(
            "argocd app delete 'my-application' --server argocd.example.com --auth-token $ARGOCD_TOKEN"
                + " --yes --cascade=true --propagation-policy foreground",
            task.executedCommands().getFirst()
        );
    }

    @Test
    void shouldSkipPropagationPolicyWhenNotCascading() throws Exception {
        var task = new StubDelete("");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");
        task.cascade = Property.ofValue(false);
        task.wait = Property.ofValue(true);
        task.appNamespace = Property.ofValue("argocd");

        task.run(runContext());

        assertEquals(
            "argocd app delete 'my-application' --server argocd.example.com --auth-token $ARGOCD_TOKEN"
                + " --yes --cascade=false --wait --app-namespace 'argocd'",
            task.executedCommands().getFirst()
        );
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

    private static final class StubDelete extends Delete {
        private final String stdout;
        private List<String> executedCommands = List.of();

        private StubDelete(String stdout) {
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

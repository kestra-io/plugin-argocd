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
import static org.junit.jupiter.api.Assertions.assertNull;

@KestraTest
class CreateTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldBuildCommandFromSourceProperties() throws Exception {
        var task = new StubCreate("application 'guestbook' created");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("guestbook");
        task.repo = Property.ofValue("https://github.com/argoproj/argocd-example-apps.git");
        task.path = Property.ofValue("guestbook");
        task.destServer = Property.ofValue("https://kubernetes.default.svc");
        task.destNamespace = Property.ofValue("default");
        task.syncPolicy = Property.ofValue(Create.SyncPolicy.AUTOMATED);
        task.autoPrune = Property.ofValue(true);
        task.upsert = Property.ofValue(true);
        task.labels = Property.ofValue(Map.of("env", "prod"));

        var output = task.run(runContext());

        assertEquals(0, output.getExitCode());
        assertEquals("guestbook", output.getApplication());
        assertEquals("created", output.getAction());
        assertEquals(
            "argocd app create 'guestbook' --server argocd.example.com --auth-token $ARGOCD_TOKEN"
                + " --repo 'https://github.com/argoproj/argocd-example-apps.git' --path 'guestbook'"
                + " --dest-server 'https://kubernetes.default.svc' --dest-namespace 'default'"
                + " --sync-policy automated --auto-prune --upsert --label 'env=prod'",
            task.executedCommands().getFirst()
        );
    }

    @Test
    void shouldPipeManifestOnStdin() throws Exception {
        var runContext = runContext();
        var manifestFile = Files.createTempFile("argocd-app", ".yaml");
        Files.writeString(manifestFile, "apiVersion: argoproj.io/v1alpha1\nkind: Application\n");
        var uri = runContext.storage().putFile(manifestFile.toFile());

        var task = new StubCreate("application 'guestbook' updated");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("guestbook");
        task.manifest = Property.ofValue(uri.toString());

        var output = task.run(runContext);

        assertEquals("updated", output.getAction());
        assertEquals(
            "printf '%s' \"$ARGOCD_MANIFEST\" | argocd app create 'guestbook'"
                + " --server argocd.example.com --auth-token $ARGOCD_TOKEN --file -",
            task.executedCommands().getFirst()
        );
        assertEquals(
            "apiVersion: argoproj.io/v1alpha1\nkind: Application\n",
            task.getEnvironmentVariables(runContext).get("ARGOCD_MANIFEST")
        );
    }

    @Test
    void shouldNotSetManifestEnvironmentVariableWhenUnused() throws Exception {
        var task = new StubCreate("");
        task.server = Property.ofValue("https://argocd.example.com");
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("guestbook");

        var output = task.run(runContext());

        assertNull(task.getEnvironmentVariables(runContext()).get("ARGOCD_MANIFEST"));
        assertNull(output.getAction());
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

    private static final class StubCreate extends Create {
        private final String stdout;
        private List<String> executedCommands = List.of();

        private StubCreate(String stdout) {
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

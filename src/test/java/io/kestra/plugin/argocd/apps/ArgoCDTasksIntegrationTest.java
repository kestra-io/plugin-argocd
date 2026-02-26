package io.kestra.plugin.argocd.apps;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ArgoCDTasksIntegrationTest {
    private static final String ARGOCD_IMAGE = "argoproj/argocd:latest";
    private static final String UNREACHABLE_SERVER = "http://127.0.0.1:1";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void statusShouldFailOnUnreachableServer() {
        var task = new Status();
        configureCommon(task);

        var exception = assertThrows(Exception.class, () -> task.run(runContext(task)));
        assertNotNull(exception.getMessage());
    }

    @Test
    void syncShouldFailOnUnreachableServer() {
        var task = new Sync();
        configureCommon(task);

        var exception = assertThrows(Exception.class, () -> task.run(runContext(task)));
        assertNotNull(exception.getMessage());
    }

    private static void configureCommon(AbstractArgoCD task) {
        task.server = Property.ofValue(UNREACHABLE_SERVER);
        task.token = Property.ofValue("token");
        task.application = Property.ofValue("my-application");
        task.containerImage = Property.ofValue(ARGOCD_IMAGE);
        task.plaintext = Property.ofValue(true);
        task.insecure = Property.ofValue(true);
    }

    private RunContext runContext(AbstractArgoCD task) {
        return runContextFactory.of(task, Map.of(
            "flow", Map.of(
                "id", "flow",
                "namespace", "io.kestra.test",
                "tenantId", "main"
            )
        ));
    }
}

package io.kestra.plugin.argocd.apps;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
class LogConsumerTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldParseControlLinesAndCaptureOnlyRegularStdout() {
        var runContext = runContextFactory.of(Map.of(
            "flow", Map.of("id", "flow", "namespace", "io.kestra.test", "tenantId", "main")
        ));
        var builder = new StringBuilder();
        var consumer = new Status().buildStdoutConsumer(builder, runContext);

        consumer.accept("::{\"outputs\":{\"k\":\"v\"}}::", false);
        consumer.accept("::{\"checkpoints\":{\"c\":\"d\"}}::", false);
        consumer.accept("{\"status\":{\"sync\":{\"status\":\"Synced\"}}}", false);
        consumer.accept("some warning", true);

        assertEquals("v", consumer.getOutputs().get("k"));
        assertEquals(1, consumer.getOutputs().size());
        assertEquals(3, consumer.getStdOutCount());
        assertEquals(1, consumer.getStdErrCount());
        assertEquals("{\"status\":{\"sync\":{\"status\":\"Synced\"}}}\n", builder.toString());
    }

    @Test
    void shouldNotParsePlainLines() {
        var runContext = runContextFactory.of(Map.of(
            "flow", Map.of("id", "flow", "namespace", "io.kestra.test", "tenantId", "main")
        ));
        var builder = new StringBuilder();
        var consumer = new Status().buildStdoutConsumer(builder, runContext);

        consumer.accept("attempt 2: resumed", false);

        assertTrue(consumer.getOutputs().isEmpty());
        assertEquals("attempt 2: resumed\n", builder.toString());
    }

    @Test
    void shouldExtractJson() {
        assertEquals("{\"a\":1}", AbstractArgoCD.extractJson("attempt 2: resumed\n{\"a\":1}"));
        assertEquals("{\"a\":{\"b\":2}}", AbstractArgoCD.extractJson("{\"a\":{\"b\":2}}"));
        assertEquals("no braces", AbstractArgoCD.extractJson("no braces"));
        assertEquals("", AbstractArgoCD.extractJson(""));
    }
}

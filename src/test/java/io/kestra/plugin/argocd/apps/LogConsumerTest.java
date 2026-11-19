package io.kestra.plugin.argocd.apps;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;

@KestraTest
class LogConsumerTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldParseOutputLinesAndCaptureStdout() {
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
        // Checkpoints are only interpreted by newer Kestra cores; here we check the line goes through the same parser.
        assertEquals(3, consumer.getStdOutCount());
        assertEquals(1, consumer.getStdErrCount());
        assertEquals(
            "::{\"outputs\":{\"k\":\"v\"}}::\n::{\"checkpoints\":{\"c\":\"d\"}}::\n{\"status\":{\"sync\":{\"status\":\"Synced\"}}}\n",
            builder.toString()
        );
    }
}

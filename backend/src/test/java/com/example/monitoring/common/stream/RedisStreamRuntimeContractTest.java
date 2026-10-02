package com.example.monitoring.common.stream;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStreamRuntimeContractTest {

    @Test
    void exposesOneCommitBeforeAckCallbackSurfaceForEveryPartCConsumer() throws Exception {
        Class<?> handler = Class.forName("com.example.monitoring.common.stream.StreamRecordHandler");
        Class<?> record = Class.forName("com.example.monitoring.common.stream.StreamRecord");
        Class<?> spec = Class.forName("com.example.monitoring.common.stream.StreamWorkerSpec");
        Class<?> worker = Class.forName("com.example.monitoring.common.stream.RedisStreamWorker");

        Method verifyPrerequisite = handler.getMethod("verifyPrerequisite");
        Method handle = handler.getMethod("handle", record);

        assertThat(handler.isInterface()).isTrue();
        assertThat(verifyPrerequisite.isDefault()).isTrue();
        assertThat(handle.getReturnType()).isEqualTo(void.class);
        assertThat(Arrays.stream(spec.getRecordComponents()).map(component -> component.getName()))
                .containsExactly(
                        "sourceStream",
                        "consumerGroup",
                        "threadName",
                        "deadLetterStream",
                        "reclaimMinIdle",
                        "reclaimInterval");
        assertThat(Modifier.isFinal(worker.getModifiers())).isTrue();
    }
}

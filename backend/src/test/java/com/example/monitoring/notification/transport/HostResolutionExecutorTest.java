package com.example.monitoring.notification.transport;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostResolutionExecutorTest {
    @Test
    void springContextCloseInterruptsResolverWorkAndStopsAcceptingTasks() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(HostResolutionExecutor.class, () -> new HostResolutionExecutor(1));
        context.refresh();
        HostResolutionExecutor resolutions = context.getBean(HostResolutionExecutor.class);
        resolutions.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        });

        try {
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            context.close();

            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> resolutions.submit(() -> new InetAddress[0]))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(resolverThreads()).isEmpty();
        } finally {
            release.countDown();
            context.close();
        }
    }

    private List<String> resolverThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("notification-dns-resolver-"))
                .toList();
    }
}

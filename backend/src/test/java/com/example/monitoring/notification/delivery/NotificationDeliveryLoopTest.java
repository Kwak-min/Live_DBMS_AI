package com.example.monitoring.notification.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationDeliveryLoopTest {

    @Test
    void blockedDeliveryDoesNotStarveUnqualifiedApplicationScheduling() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(IsolationConfig.class);
            context.refresh();
            BlockingDelivery blocking = context.getBean(BlockingDelivery.class);
            UnqualifiedCounter counter = context.getBean(UnqualifiedCounter.class);

            assertThat(blocking.entered.await(2, TimeUnit.SECONDS)).isTrue();
            int before = counter.value.get();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter.value.get()).isGreaterThan(before));
            blocking.release.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class IsolationConfig {
        @Bean
        BlockingDelivery blockingDelivery() {
            return new BlockingDelivery();
        }

        @Bean
        NotificationDeliveryWorker deliveryWorker(BlockingDelivery blocking) throws Exception {
            NotificationDeliveryWorker worker = mock(NotificationDeliveryWorker.class);
            when(worker.runOnce()).thenAnswer(invocation -> {
                blocking.entered.countDown();
                blocking.release.await(5, TimeUnit.SECONDS);
                return 0;
            });
            return worker;
        }

        @Bean
        NotificationDeliveryLoop deliveryLoop(NotificationDeliveryWorker worker) {
            return new NotificationDeliveryLoop(worker, 10);
        }

        @Bean
        UnqualifiedCounter unqualifiedCounter() {
            return new UnqualifiedCounter();
        }
    }

    static final class BlockingDelivery {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }

    static final class UnqualifiedCounter {
        private final AtomicInteger value = new AtomicInteger();

        @Scheduled(fixedDelay = 20)
        void tick() {
            value.incrementAndGet();
        }
    }
}

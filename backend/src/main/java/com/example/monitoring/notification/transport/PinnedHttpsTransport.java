package com.example.monitoring.notification.transport;

import com.example.monitoring.notification.security.PublicAddressPolicy;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public final class PinnedHttpsTransport {
    private final PushEndpointPolicy pushEndpointPolicy;
    private final SlackWebhookPolicy slackWebhookPolicy;
    private final HostResolver hostResolver;
    private final HostResolutionExecutor resolutionExecutor;
    private final PublicAddressPolicy publicAddressPolicy;
    private final PinnedHttpExecutor executor;

    public PinnedHttpsTransport(PushEndpointPolicy pushEndpointPolicy, SlackWebhookPolicy slackWebhookPolicy,
                                HostResolver hostResolver, HostResolutionExecutor resolutionExecutor,
                                PublicAddressPolicy publicAddressPolicy, PinnedHttpExecutor executor) {
        this.pushEndpointPolicy = pushEndpointPolicy;
        this.slackWebhookPolicy = slackWebhookPolicy;
        this.hostResolver = hostResolver;
        this.resolutionExecutor = resolutionExecutor;
        this.publicAddressPolicy = publicAddressPolicy;
        this.executor = executor;
    }

    public PinnedHttpsResponse execute(PinnedHttpsRequest request) {
        Deadline deadline = new Deadline(request.timeout());
        URI uri = switch (request.provider()) {
            case WEB_PUSH -> pushEndpointPolicy.validate(request.uri().toString());
            case SLACK -> slackWebhookPolicy.validate(request.uri().toString());
        };
        InetAddress[] resolved = resolve(uri.getHost(), deadline);
        deadline.requireRemaining();
        InetAddress[] pinned = publicAddressPolicy.requirePublic(uri.getHost(), resolved);
        Duration remaining = deadline.remainingDuration();
        PinnedHttpsRequest bounded = new PinnedHttpsRequest(request.provider(), uri, request.method(),
                request.headers(), request.body(), remaining);
        return executor.execute(bounded, pinned);
    }

    private InetAddress[] resolve(String host, Deadline deadline) {
        final Future<InetAddress[]> result;
        try {
            result = resolutionExecutor.submit(() -> hostResolver.resolve(host));
        } catch (RejectedExecutionException exception) {
            throw timeout(exception);
        }
        try {
            return result.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (PinnedTransportException exception) {
            result.cancel(true);
            throw exception;
        } catch (TimeoutException exception) {
            result.cancel(true);
            throw timeout(exception);
        } catch (InterruptedException exception) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new PinnedTransportException(PinnedTransportException.Kind.CONNECTION, exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof UnknownHostException unknownHost) {
                throw new PinnedTransportException(PinnedTransportException.Kind.CONNECTION, unknownHost);
            }
            throw new PinnedTransportException(PinnedTransportException.Kind.CONNECTION, exception.getCause());
        }
    }

    private PinnedTransportException timeout(Throwable cause) {
        return new PinnedTransportException(PinnedTransportException.Kind.TIMEOUT, cause);
    }

    private final class Deadline {
        private final long startedNanos = System.nanoTime();
        private final long budgetNanos;

        private Deadline(Duration timeout) {
            budgetNanos = timeout.toNanos();
        }

        private long remainingNanos() {
            if (Thread.currentThread().isInterrupted()) {
                throw timeout(new InterruptedException("Notification transport was interrupted."));
            }
            long remaining = budgetNanos - (System.nanoTime() - startedNanos);
            if (remaining <= 0) {
                throw timeout(new TimeoutException("Notification transport deadline elapsed."));
            }
            return remaining;
        }

        private void requireRemaining() {
            remainingNanos();
        }

        private Duration remainingDuration() {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos());
            if (remainingMillis < 1) {
                throw timeout(new TimeoutException("Notification transport deadline elapsed."));
            }
            return Duration.ofMillis(remainingMillis);
        }
    }
}

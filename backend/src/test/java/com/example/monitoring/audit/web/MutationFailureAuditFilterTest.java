package com.example.monitoring.audit.web;

import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.service.AuditEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MutationFailureAuditFilterTest {

    private final AuditEventService audits = mock(AuditEventService.class);
    private final MutationFailureAuditFilter filter = new MutationFailureAuditFilter(audits);

    @Test
    void preservesExistingDatabaseFailureClassification() throws Exception {
        invoke("PATCH", "/api/v1/databases/12", 409);

        verify(audits).failureCurrent(AuditAction.DATABASE_UPDATED, AuditTargetType.DATABASE,
                "12", 12L, "Request rejected with HTTP 409");
    }

    @ParameterizedTest
    @MethodSource("partCMutations")
    void classifiesPartCMutationFailures(String method, String path, AuditAction action,
                                         AuditTargetType targetType, String targetId, Long databaseId)
            throws Exception {
        invoke(method, path, 400);

        verify(audits).failureCurrent(action, targetType, targetId, databaseId,
                "Request rejected with HTTP 400");
    }

    @Test
    void ignoresSuccessfulAndUnrelatedPaths() throws Exception {
        invoke("PUT", "/api/v1/databases/12/risk-policy", 200);
        invoke("PUT", "/api/v1/databases/12/risk-policy/extra", 400);

        verify(audits, never()).failureCurrent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditsMalformedAndUnsafePartCIdsWithoutRecordingUntrustedSegments() throws Exception {
        invoke("DELETE", "/api/v1/notifications/push-subscriptions/not-an-id", 400);
        invoke("PUT", "/api/v1/databases/9007199254740992/risk-policy", 400);
        invoke("PATCH", "/api/v1/notifications/webhooks/" + "token=secret".repeat(20), 400);

        verify(audits).failureCurrent(AuditAction.PUSH_DELETED, AuditTargetType.PUSH_SUBSCRIPTION,
                null, null, "Request rejected with HTTP 400");
        verify(audits).failureCurrent(AuditAction.POLICY_UPDATED, AuditTargetType.POLICY,
                null, null, "Request rejected with HTTP 400");
        verify(audits).failureCurrent(AuditAction.WEBHOOK_UPDATED, AuditTargetType.WEBHOOK,
                null, null, "Request rejected with HTTP 400");
    }

    private void invoke(String method, String path, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, actualResponse) ->
                ((MockHttpServletResponse) actualResponse).setStatus(status));
    }

    private static Stream<Arguments> partCMutations() {
        return Stream.of(
                Arguments.of("PUT", "/api/v1/databases/12/risk-policy", AuditAction.POLICY_UPDATED,
                        AuditTargetType.POLICY, "12", 12L),
                Arguments.of("POST", "/api/v1/notifications/push-subscriptions", AuditAction.PUSH_REGISTERED,
                        AuditTargetType.PUSH_SUBSCRIPTION, null, null),
                Arguments.of("DELETE", "/api/v1/notifications/push-subscriptions/31", AuditAction.PUSH_DELETED,
                        AuditTargetType.PUSH_SUBSCRIPTION, "31", null),
                Arguments.of("POST", "/api/v1/notifications/webhooks", AuditAction.WEBHOOK_CREATED,
                        AuditTargetType.WEBHOOK, null, null),
                Arguments.of("PATCH", "/api/v1/notifications/webhooks/41", AuditAction.WEBHOOK_UPDATED,
                        AuditTargetType.WEBHOOK, "41", null),
                Arguments.of("DELETE", "/api/v1/notifications/webhooks/41", AuditAction.WEBHOOK_DELETED,
                        AuditTargetType.WEBHOOK, "41", null));
    }
}

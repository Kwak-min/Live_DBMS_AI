package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.dto.SignupRequest;
import com.example.monitoring.auth.dto.UpdateUserRoleRequest;
import com.example.monitoring.auth.dto.UpdateUserStatusRequest;
import com.example.monitoring.auth.dto.UserResponse;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.notification.session.PushSubscriptionLifecyclePort;
import com.example.monitoring.service.AuditEventService;
import com.example.monitoring.common.persistence.PartBTransactionLocks;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordHashingService passwordHashingService;
    private final AuditEventService auditEventService;
    private final PartBTransactionLocks transactionLocks;
    private final PushSubscriptionLifecyclePort pushSubscriptions;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Transactional
    public UserResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        String displayName = normalizeDisplayName(request.displayName());
        String passwordHash = hashPassword(request.password());

        if (userAccountRepository.existsByEmail(email)) {
            throw emailAlreadyExists();
        }

        UserAccount user = UserAccount.builder()
                .email(email)
                .displayName(displayName)
                .passwordHash(passwordHash)
                .role(UserRole.USER)
                .enabled(true)
                .authVersion(1L)
                .build();
        try {
            UserAccount saved = userAccountRepository.save(user);
            auditEventService.successCurrent(AuditAction.USER_SIGNUP, AuditTargetType.USER, saved.getId().toString(), null,
                    "User account created");
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            // The database unique constraint is authoritative when concurrent signups race.
            throw emailAlreadyExists();
        }
    }

    @Transactional
    public UserAccount createBootstrapAdmin(String email, String displayName, String password) {
        transactionLocks.lockActiveAdminInvariant();
        if (userAccountRepository.count() != 0) {
            throw new IllegalStateException("Bootstrap admin can only run when the user table is empty.");
        }
        return userAccountRepository.save(UserAccount.builder()
                .email(normalizeEmail(email))
                .displayName(normalizeDisplayName(displayName))
                .passwordHash(hashPassword(password))
                .role(UserRole.ADMIN)
                .enabled(true)
                .authVersion(1L)
                .build());
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        ApiId.require(id, "id");
        return UserResponse.from(findUser(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(int page, int size) {
        if (page < 0 || page > 10_000 || size < 1 || size > 100) {
            throw validation("page", "OUT_OF_RANGE", "page는 0~10000, size는 1~100 범위여야 합니다.");
        }
        return PageResponse.from(userAccountRepository.findAllByOrderByIdAsc(PageRequest.of(page, size)), UserResponse::from);
    }

    @Transactional
    public UserResponse updateRole(Long id, UpdateUserRoleRequest request) {
        ApiId.require(id, "id");
        transactionLocks.lockActiveAdminInvariant();
        UserAccount user = findUserForUpdate(id);
        if (user.getRole() == UserRole.ADMIN && user.isEnabled() && request.role() != UserRole.ADMIN) {
            protectLastAdmin();
        }
        UserRole previousRole = user.getRole();
        user.changeRole(request.role());
        revokeSessionsIfChanged(user, previousRole != user.getRole());
        auditEventService.successCurrent(AuditAction.USER_ROLE_CHANGED, AuditTargetType.USER, user.getId().toString(), null,
                "User role changed");
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateStatus(Long id, UpdateUserStatusRequest request) {
        ApiId.require(id, "id");
        transactionLocks.lockActiveAdminInvariant();
        UserAccount user = findUserForUpdate(id);
        if (user.getRole() == UserRole.ADMIN && user.isEnabled() && !request.enabled()) {
            protectLastAdmin();
        }
        boolean previousEnabled = user.isEnabled();
        user.changeEnabled(request.enabled());
        revokeSessionsIfChanged(user, previousEnabled != user.isEnabled());
        auditEventService.successCurrent(AuditAction.USER_STATUS_CHANGED, AuditTargetType.USER, user.getId().toString(), null,
                "User enabled state changed");
        return UserResponse.from(user);
    }

    private void protectLastAdmin() {
        if (userAccountRepository.findActiveAdminsForUpdate().size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN", "마지막 활성 ADMIN은 변경할 수 없습니다.");
        }
    }

    private void revokeSessionsIfChanged(UserAccount user, boolean changed) {
        if (changed) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            authSessionRepository.revokeAllByUserId(user.getId(), now);
            pushSubscriptions.deactivateByUser(user.getId(), now);
            events.publishEvent(AuthSessionsRevokedEvent.user(user.getId()));
        }
    }

    private UserAccount findUser(Long id) {
        return userAccountRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
    }

    private UserAccount findUserForUpdate(Long id) {
        return userAccountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
    }

    private String normalizeEmail(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw validation("email", "REQUIRED", "이메일은 필수입니다.");
        }
        String email = value.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254) {
            throw validation("email", "INVALID_VALUE", "이메일은 254자 이하여야 합니다.");
        }
        return email;
    }

    private String normalizeDisplayName(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw validation("displayName", "REQUIRED", "표시명은 필수입니다.");
        }
        String displayName = value.trim();
        if (displayName.codePointCount(0, displayName.length()) > 100) {
            throw validation("displayName", "INVALID_VALUE", "표시명은 100자 이하여야 합니다.");
        }
        return displayName;
    }

    private String hashPassword(String password) {
        try {
            return passwordHashingService.hash(password);
        } catch (PasswordHashingService.PasswordPolicyException exception) {
            throw validation("password", "INVALID_VALUE", exception.getMessage());
        }
    }

    private ApiException emailAlreadyExists() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.");
    }

    private ApiException validation(String field, String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.",
                List.of(new FieldErrorResponse(field, code, message)));
    }
}

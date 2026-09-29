package com.example.monitoring.auth.web;

import com.example.monitoring.auth.dto.CsrfResponse;
import com.example.monitoring.auth.dto.LoginRequest;
import com.example.monitoring.auth.dto.SignupRequest;
import com.example.monitoring.auth.dto.TokenResponse;
import com.example.monitoring.auth.dto.UserResponse;
import com.example.monitoring.auth.service.AuthenticationService;
import com.example.monitoring.auth.service.AuthenticationRateLimitService;
import com.example.monitoring.auth.service.CsrfTokenService;
import com.example.monitoring.auth.service.RequestOriginValidator;
import com.example.monitoring.auth.service.UserAccountService;
import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refreshToken";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final UserAccountService userAccountService;
    private final AuthenticationService authenticationService;
    private final AuthenticationRateLimitService authenticationRateLimitService;
    private final CsrfTokenService csrfTokenService;
    private final RequestOriginValidator originValidator;
    private final Clock clock = Clock.systemUTC();

    @Value("${app.auth.secure-cookies}")
    private boolean secureCookies;

    @GetMapping("/csrf")
    @Operation(summary = "Issue CSRF token", description = "Public. Reuses the existing session token; returns a host-only HttpOnly csrfSession cookie when a new session is created.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "CSRF token issued"),
            @ApiResponse(responseCode = "403", description = "Origin not allowed"),
            @ApiResponse(responseCode = "503", description = "Redis unavailable")})
    public ResponseEntity<CsrfResponse> csrf(HttpServletRequest request, HttpServletResponse response) {
        originValidator.validateCsrfBootstrap(request);
        String existingSession = AuthCsrfInterceptor.cookie(request, AuthCsrfInterceptor.CSRF_COOKIE);
        CsrfTokenService.CsrfTokenIssue issue = csrfTokenService.issue(existingSession);
        if (issue.newSession()) {
            addCookie(response, AuthCsrfInterceptor.CSRF_COOKIE, issue.session(), CsrfTokenService.TTL, true);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(issue.token()));
    }

    @PostMapping("/signup")
    @Operation(summary = "Sign up as USER", description = "Public with CSRF. Creates a USER account; does not issue tokens.")
    @Parameters(@Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true,
            description = "Token returned by GET /api/v1/auth/csrf"))
    @ApiResponses({@ApiResponse(responseCode = "201", description = "User created, Location header set"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "CSRF or Origin rejected"),
            @ApiResponse(responseCode = "409", description = "Email already exists"),
            @ApiResponse(responseCode = "429", description = "Rate limited"),
            @ApiResponse(responseCode = "503", description = "Redis unavailable")})
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest httpRequest) {
        authenticationRateLimitService.checkSignup(httpRequest);
        UserResponse user = userAccountService.signup(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + user.id()))
                .cacheControl(CacheControl.noStore())
                .body(user);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Public with CSRF. Returns a 15-minute Bearer token and a host-only HttpOnly Refresh cookie.")
    @Parameters(@Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true,
            description = "Token returned by GET /api/v1/auth/csrf"))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Token and user returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "403", description = "CSRF or Origin rejected"),
            @ApiResponse(responseCode = "429", description = "Rate limited"),
            @ApiResponse(responseCode = "503", description = "Redis unavailable")})
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse response) {
        authenticationRateLimitService.checkLogin(request.email(), httpRequest);
        AuthenticationService.AuthenticationResult result = authenticationService.login(request);
        setRefreshCookie(response, result);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.response());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate Refresh token", description = "Public endpoint requiring the Refresh cookie and CSRF. Rotation never extends the original seven-day session expiry.")
    @Parameters(@Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true,
            description = "Token returned by GET /api/v1/auth/csrf"))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "New Access token and Refresh cookie"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or reused Refresh token"),
            @ApiResponse(responseCode = "403", description = "CSRF or Origin rejected"),
            @ApiResponse(responseCode = "429", description = "Rate limited"),
            @ApiResponse(responseCode = "503", description = "Redis unavailable")})
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        try {
            AuthenticationService.AuthenticationResult result = authenticationService.refresh(refreshToken);
            setRefreshCookie(response, result);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.response());
        } catch (ApiException exception) {
            if ("REFRESH_TOKEN_INVALID".equals(exception.getCode())) {
                expireCookie(response, REFRESH_COOKIE);
            }
            throw exception;
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Log out", description = "Public endpoint requiring CSRF. Revokes the session if present and clears both authentication cookies. Repeated logout returns 204.")
    @Parameters(@Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true,
            description = "Token returned by GET /api/v1/auth/csrf"))
    @ApiResponses({@ApiResponse(responseCode = "204", description = "Cookies cleared; no body"),
            @ApiResponse(responseCode = "403", description = "CSRF or Origin rejected"),
            @ApiResponse(responseCode = "503", description = "Redis unavailable")})
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = AuthCsrfInterceptor.CSRF_COOKIE, required = false) String csrfSession,
            HttpServletResponse response) {
        authenticationService.logout(refreshToken);
        csrfTokenService.delete(csrfSession);
        expireCookie(response, REFRESH_COOKIE);
        expireCookie(response, AuthCsrfInterceptor.CSRF_COOKIE);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Current user"),
            @ApiResponse(responseCode = "401", description = "Access token missing, expired, or session revoked")})
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(userAccountService.getUser(principal.userId()));
    }

    private void setRefreshCookie(HttpServletResponse response,
                                  AuthenticationService.AuthenticationResult result) {
        Duration remaining = Duration.between(clock.instant(), result.sessionExpiresAt());
        addCookie(response, REFRESH_COOKIE, result.refreshToken(), remaining, true);
    }

    private void addCookie(HttpServletResponse response, String name, String value,
                           Duration maxAge, boolean httpOnly) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secureCookies)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void expireCookie(HttpServletResponse response, String name) {
        addCookie(response, name, "", Duration.ZERO, true);
    }
}

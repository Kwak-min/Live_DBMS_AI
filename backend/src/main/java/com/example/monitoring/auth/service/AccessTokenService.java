package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.common.api.ApiException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccessTokenService {

    static final String ISSUER = "live-dbms-ai";
    static final String AUDIENCE = "live-dbms-web";
    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration FUTURE_IAT_TOLERANCE = Duration.ofSeconds(30);

    private final JwtKeySet keySet;
    private final Clock clock = Clock.systemUTC();

    public IssuedAccessToken issue(UserAccount user, UUID sessionId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ACCESS_TTL);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(Long.toString(user.getId()))
                .claim("role", user.getRole().name())
                .claim("sid", sessionId.toString())
                .claim("ver", user.getAuthVersion())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keySet.activeKeyId()).build(), claims);
        try {
            token.sign(new MACSigner(keySet.activeKey()));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign access token.", exception);
        }
        return new IssuedAccessToken(token.serialize(), expiresAt);
    }

    public VerifiedAccessToken verify(String serializedToken) {
        try {
            SignedJWT token = SignedJWT.parse(serializedToken);
            JWSHeader header = token.getHeader();
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm()) || header.getKeyID() == null) {
                throw invalidToken();
            }
            byte[] key = keySet.key(header.getKeyID());
            if (key == null || !token.verify(new MACVerifier(key))) {
                throw invalidToken();
            }
            return validateClaims(token.getJWTClaimsSet());
        } catch (ApiException exception) {
            throw exception;
        } catch (ParseException | JOSEException | RuntimeException exception) {
            throw invalidToken();
        }
    }

    private VerifiedAccessToken validateClaims(JWTClaimsSet claims) throws ParseException {
        Instant now = clock.instant();
        Instant issuedAt = requiredDate(claims.getIssueTime()).toInstant();
        Instant expiresAt = requiredDate(claims.getExpirationTime()).toInstant();
        if (!ISSUER.equals(claims.getIssuer()) || !List.of(AUDIENCE).equals(claims.getAudience())) {
            throw invalidToken();
        }
        if (!expiresAt.isAfter(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_EXPIRED", "Access token has expired.");
        }
        if (issuedAt.isAfter(now.plus(FUTURE_IAT_TOLERANCE)) || !expiresAt.equals(issuedAt.plus(ACCESS_TTL))) {
            throw invalidToken();
        }
        long userId = Long.parseLong(required(claims.getSubject()));
        if (userId <= 0 || userId > 9_007_199_254_740_991L) {
            throw invalidToken();
        }
        String role = required(claims.getStringClaim("role"));
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw invalidToken();
        }
        UUID sessionId = UUID.fromString(required(claims.getStringClaim("sid")));
        long authVersion = claims.getLongClaim("ver");
        if (authVersion < 1) {
            throw invalidToken();
        }
        UUID.fromString(required(claims.getJWTID()));
        return new VerifiedAccessToken(userId, role, sessionId, authVersion, issuedAt, expiresAt);
    }

    private Date requiredDate(Date value) {
        if (value == null) {
            throw invalidToken();
        }
        return value;
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw invalidToken();
        }
        return value;
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 Access token입니다.");
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {
    }

    public record VerifiedAccessToken(
            long userId,
            String role,
            UUID sessionId,
            long authVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}

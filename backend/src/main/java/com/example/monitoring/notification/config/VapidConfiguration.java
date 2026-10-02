package com.example.monitoring.notification.config;

import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.Base64;

public final class VapidConfiguration {
    private static final ECDomainParameters P256 = p256();

    private final String publicKey;
    private final String privateKey;
    private final String subject;
    private final byte[] publicKeyBytes;
    private final byte[] privateKeyBytes;

    VapidConfiguration(String publicKey, String privateKey, String subject) {
        this.publicKeyBytes = decode(publicKey);
        this.privateKeyBytes = decode(privateKey);
        validatePublicKey(publicKeyBytes);
        BigInteger scalar = validatePrivateKey(privateKeyBytes);
        byte[] derivedPublic = P256.getG().multiply(scalar).normalize().getEncoded(false);
        if (!MessageDigest.isEqual(publicKeyBytes, derivedPublic)) {
            throw invalid();
        }
        validateSubject(subject);
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.subject = subject;
    }

    public String publicKey() {
        return publicKey;
    }

    public String privateKey() {
        return privateKey;
    }

    public String subject() {
        return subject;
    }

    public byte[] publicKeyBytes() {
        return publicKeyBytes.clone();
    }

    public byte[] privateKeyBytes() {
        return privateKeyBytes.clone();
    }

    @Override
    public String toString() {
        return "VapidConfiguration[redacted]";
    }

    private static byte[] decode(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_-]+")) {
            throw invalid();
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void validatePublicKey(byte[] value) {
        if (value.length != 65 || value[0] != 0x04) {
            throw invalid();
        }
        try {
            ECPoint point = P256.getCurve().decodePoint(value);
            if (point.isInfinity()) {
                throw invalid();
            }
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static BigInteger validatePrivateKey(byte[] value) {
        if (value.length != 32) {
            throw invalid();
        }
        BigInteger scalar = new BigInteger(1, value);
        if (scalar.signum() <= 0 || scalar.compareTo(P256.getN()) >= 0) {
            throw invalid();
        }
        return scalar;
    }

    private static void validateSubject(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw invalid();
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
        boolean mailto = "mailto".equalsIgnoreCase(uri.getScheme())
                && uri.getRawSchemeSpecificPart() != null && uri.getRawSchemeSpecificPart().contains("@")
                && uri.getRawFragment() == null;
        boolean https = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        if (!mailto && !https) {
            throw invalid();
        }
    }

    private static ECDomainParameters p256() {
        var parameters = CustomNamedCurves.getByName("secp256r1");
        return new ECDomainParameters(parameters.getCurve(), parameters.getG(), parameters.getN(), parameters.getH());
    }

    private static VapidConfigurationException invalid() {
        return new VapidConfigurationException("Invalid Web Push signing configuration.");
    }
}

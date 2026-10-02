package com.example.monitoring.integration;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

record PartCTestEcFixture(String publicKey, String privateKey) {

    static PartCTestEcFixture generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
            ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();
            byte[] uncompressed = new byte[65];
            uncompressed[0] = 0x04;
            System.arraycopy(unsigned32(publicKey.getW().getAffineX()), 0, uncompressed, 1, 32);
            System.arraycopy(unsigned32(publicKey.getW().getAffineY()), 0, uncompressed, 33, 32);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new PartCTestEcFixture(
                    encoder.encodeToString(uncompressed),
                    encoder.encodeToString(unsigned32(privateKey.getS())));
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] encoded = value.toByteArray();
        int offset = encoded.length == 33 && encoded[0] == 0 ? 1 : 0;
        int length = encoded.length - offset;
        if (length > 32) throw new IllegalArgumentException("P-256 coordinate is too large");
        byte[] fixed = new byte[32];
        System.arraycopy(encoded, offset, fixed, 32 - length, length);
        return fixed;
    }
}

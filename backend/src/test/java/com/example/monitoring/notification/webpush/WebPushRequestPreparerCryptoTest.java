package com.example.monitoring.notification.webpush;

import com.example.monitoring.notification.config.VapidConfigurationProvider;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.transport.NotificationType;
import com.example.monitoring.notification.transport.PinnedHttpsRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushRequestPreparerCryptoTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Pattern VAPID = Pattern.compile("^vapid t=([^,]+), k=([A-Za-z0-9_-]+)$");

    @BeforeAll
    static void installProvider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void preparesDecryptableRfc8291PayloadAndValidVapidWithoutSending() throws Exception {
        KeyPair recipient = ecKeyPair();
        byte[] recipientPublic = ((ECPublicKey) recipient.getPublic()).getQ().getEncoded(false);
        byte[] authSecret = new byte[16];
        Arrays.fill(authSecret, (byte) 0x5a);

        byte[] vapidPrivate = new byte[32];
        vapidPrivate[31] = 1;
        byte[] vapidPublic = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1")
                .getG().getEncoded(false);
        VapidConfigurationProvider configuration = new VapidConfigurationProvider(
                URL_ENCODER.encodeToString(vapidPublic), URL_ENCODER.encodeToString(vapidPrivate),
                "mailto:ops@example.com");
        WebPushRequestPreparer preparer = new WebPushRequestPreparer(configuration,
                new WebPushPayloadRenderer(MAPPER), new PushEndpointPolicy(""));

        UUID incidentId = UUID.fromString("d38f135a-34c3-40df-915a-f26b2ebf4162");
        WebPushMessage message = new WebPushMessage(81L, incidentId, NotificationType.INCIDENT_OPENED,
                "CRITICAL database alert", "CPU usage threshold exceeded", Instant.parse("2026-10-03T01:02:03.456789Z"));
        WebPushRecipient target = new WebPushRecipient("https://fcm.googleapis.com/push/local-proof",
                URL_ENCODER.encodeToString(recipientPublic), URL_ENCODER.encodeToString(authSecret));

        PinnedHttpsRequest request = preparer.prepare(target, message);
        byte[] plaintext = decryptAes128Gcm(request.body(), recipient, recipientPublic, authSecret);
        Map<String, Object> payload = MAPPER.readValue(plaintext, new TypeReference<>() { });

        assertThat(request.uri()).isEqualTo(URI.create("https://fcm.googleapis.com/push/local-proof"));
        assertThat(header(request, "Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(header(request, "TTL")).isEqualTo("600");
        assertThat(payload).containsOnlyKeys("schemaVersion", "deliveryId", "incidentId", "type", "title", "body",
                        "url", "tag", "sentAt")
                .containsEntry("schemaVersion", 1)
                .containsEntry("deliveryId", 81)
                .containsEntry("incidentId", incidentId.toString())
                .containsEntry("type", "INCIDENT_OPENED")
                .containsEntry("url", "/incidents/" + incidentId)
                .containsEntry("tag", "incident:" + incidentId)
                .containsEntry("sentAt", "2026-10-03T01:02:03.456Z");
        verifyVapid(header(request, "Authorization"), vapidPublic, "https://fcm.googleapis.com",
                "mailto:ops@example.com");
    }

    @Test
    void chromeFcmSubscriptionIsSentToTheVapidPathAndStillDecrypts() throws Exception {
        KeyPair recipient = ecKeyPair();
        byte[] recipientPublic = ((ECPublicKey) recipient.getPublic()).getQ().getEncoded(false);
        byte[] authSecret = new byte[16];
        Arrays.fill(authSecret, (byte) 0x33);
        byte[] vapidPublic = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1")
                .getG().getEncoded(false);
        WebPushRequestPreparer preparer = preparer(vapidPublic);
        String token = "dQw4w9WgXcQ:APA91bH-fcm_token-example";
        WebPushRecipient chrome = new WebPushRecipient("https://fcm.googleapis.com/fcm/send/" + token,
                URL_ENCODER.encodeToString(recipientPublic), URL_ENCODER.encodeToString(authSecret));
        WebPushMessage message = new WebPushMessage(9L, UUID.randomUUID(), NotificationType.INCIDENT_RESOLVED,
                "RESOLVED", "recovered", Instant.parse("2026-10-08T00:00:00Z"));

        PinnedHttpsRequest request = preparer.prepare(chrome, message);

        // web-push가 Chrome 구독 경로를 VAPID 전송 경로로 바꾼다. 이전에는 이 변경 때문에 발송 전에 거절됐다.
        assertThat(request.uri()).isEqualTo(URI.create("https://fcm.googleapis.com/wp/" + token));
        assertThat(decryptAes128Gcm(request.body(), recipient, recipientPublic, authSecret)).isNotEmpty();
        verifyVapid(header(request, "Authorization"), vapidPublic, "https://fcm.googleapis.com",
                "mailto:ops@example.com");
    }

    @Test
    void onlyTheFcmVapidPathRewriteIsAccepted() {
        byte[] vapidPublic = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1")
                .getG().getEncoded(false);
        WebPushRequestPreparer preparer = preparer(vapidPublic);
        URI fcm = URI.create("https://fcm.googleapis.com/fcm/send/token-1");
        URI mozilla = URI.create("https://updates.push.services.mozilla.com/wpush/v2/abc");

        assertThat(preparer.sendTarget(mozilla, mozilla)).isEqualTo(mozilla);
        assertThat(preparer.sendTarget(fcm, URI.create("https://fcm.googleapis.com/wp/token-1")))
                .isEqualTo(URI.create("https://fcm.googleapis.com/wp/token-1"));
        for (String changed : new String[]{
                "https://evil.example/wp/token-1",
                "https://fcm.googleapis.com/wp/other-token",
                "https://fcm.googleapis.com/send/token-1",
                "http://fcm.googleapis.com/wp/token-1",
                "https://fcm.googleapis.com:8443/wp/token-1",
                "https://user@fcm.googleapis.com/wp/token-1",
                "https://fcm.googleapis.com/wp/token-1?x=1"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> preparer.sendTarget(fcm, URI.create(changed)))
                    .as(changed).isInstanceOf(RuntimeException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> preparer.sendTarget(mozilla,
                        URI.create("https://updates.push.services.mozilla.com/wp/abc")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static WebPushRequestPreparer preparer(byte[] vapidPublic) {
        byte[] vapidPrivate = new byte[32];
        vapidPrivate[31] = 1;
        VapidConfigurationProvider configuration = new VapidConfigurationProvider(
                URL_ENCODER.encodeToString(vapidPublic), URL_ENCODER.encodeToString(vapidPrivate),
                "mailto:ops@example.com");
        return new WebPushRequestPreparer(configuration, new WebPushPayloadRenderer(MAPPER), new PushEndpointPolicy(""));
    }

    private static byte[] decryptAes128Gcm(byte[] body, KeyPair recipient, byte[] recipientPublic,
                                            byte[] authSecret) throws Exception {
        ByteBuffer record = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        record.get(salt);
        int recordSize = record.getInt();
        int keyIdLength = Byte.toUnsignedInt(record.get());
        byte[] serverPublic = new byte[keyIdLength];
        record.get(serverPublic);
        byte[] ciphertext = new byte[record.remaining()];
        record.get(ciphertext);

        assertThat(recordSize).isGreaterThanOrEqualTo(ciphertext.length);
        assertThat(serverPublic).hasSize(65);
        assertThat(serverPublic[0]).isEqualTo((byte) 0x04);

        java.security.interfaces.ECPublicKey recipientJca =
                (java.security.interfaces.ECPublicKey) recipient.getPublic();
        java.security.PublicKey senderKey = publicKey(serverPublic, recipientJca);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(recipient.getPrivate());
        agreement.doPhase(senderKey, true);
        byte[] sharedSecret = agreement.generateSecret();

        byte[] prkKey = hkdfExtract(authSecret, sharedSecret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), recipientPublic, serverPublic);
        byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);
        byte[] prk = hkdfExtract(salt, ikm);
        byte[] cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        int delimiter = padded.length - 1;
        while (delimiter >= 0 && padded[delimiter] == 0) {
            delimiter--;
        }
        assertThat(delimiter).isGreaterThanOrEqualTo(0);
        assertThat(padded[delimiter]).isEqualTo((byte) 0x02);
        return Arrays.copyOf(padded, delimiter);
    }

    private static void verifyVapid(String authorization, byte[] expectedPublic, String audience,
                                    String subject) throws Exception {
        Matcher matcher = VAPID.matcher(authorization);
        assertThat(matcher.matches()).isTrue();
        assertThat(URL_DECODER.decode(matcher.group(2))).containsExactly(expectedPublic);

        String[] parts = matcher.group(1).split("\\.");
        assertThat(parts).hasSize(3);
        Map<String, Object> header = MAPPER.readValue(URL_DECODER.decode(parts[0]), new TypeReference<>() { });
        Map<String, Object> claims = MAPPER.readValue(URL_DECODER.decode(parts[1]), new TypeReference<>() { });
        assertThat(header).containsEntry("alg", "ES256");
        assertThat(claims).containsEntry("aud", audience).containsEntry("sub", subject);
        long expiration = ((Number) claims.get("exp")).longValue();
        assertThat(expiration).isGreaterThan(Instant.now().getEpochSecond())
                .isLessThanOrEqualTo(Instant.now().plusSeconds(86_400).getEpochSecond());

        java.security.interfaces.ECPublicKey publicKey = (java.security.interfaces.ECPublicKey) publicKey(
                expectedPublic, (java.security.interfaces.ECPublicKey) ecKeyPair().getPublic());
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(signature.verify(joseToDer(URL_DECODER.decode(parts[2])))).isTrue();
    }

    private static java.security.PublicKey publicKey(byte[] uncompressed,
                                                      java.security.interfaces.ECPublicKey template) throws Exception {
        int coordinateLength = (uncompressed.length - 1) / 2;
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 1 + coordinateLength));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1 + coordinateLength, uncompressed.length));
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(x, y), template.getParams()));
    }

    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    private static byte[] concat(byte[]... values) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value);
        }
        return output.toByteArray();
    }

    private static byte[] joseToDer(byte[] signature) {
        byte[] r = unsignedInteger(Arrays.copyOfRange(signature, 0, signature.length / 2));
        byte[] s = unsignedInteger(Arrays.copyOfRange(signature, signature.length / 2, signature.length));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x30);
        output.write(2 + r.length + 2 + s.length);
        output.write(0x02);
        output.write(r.length);
        output.writeBytes(r);
        output.write(0x02);
        output.write(s.length);
        output.writeBytes(s);
        return output.toByteArray();
    }

    private static byte[] unsignedInteger(byte[] value) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) {
            first++;
        }
        byte[] trimmed = Arrays.copyOfRange(value, first, value.length);
        if ((trimmed[0] & 0x80) == 0) {
            return trimmed;
        }
        byte[] positive = new byte[trimmed.length + 1];
        System.arraycopy(trimmed, 0, positive, 1, trimmed.length);
        return positive;
    }

    private static String header(PinnedHttpsRequest request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}

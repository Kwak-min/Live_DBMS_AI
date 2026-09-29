package com.example.monitoring.realtime.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

final class DeadLetterPayloadSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final String INVALID_UTF8 = "[INVALID_UTF8]";
    private static final String UNPARSEABLE_JSON = "[UNPARSEABLE_JSON]";

    private final ObjectMapper mapper;

    DeadLetterPayloadSanitizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String sanitize(byte[] payload) {
        String decoded = decode(payload);
        if (INVALID_UTF8.equals(decoded)) {
            return decoded;
        }
        String sanitized = sanitizeJson(decoded);
        return truncateUtf8(sanitized, MetricPayloadParser.MAX_PAYLOAD_BYTES);
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return INVALID_UTF8;
        }
    }

    private String sanitizeJson(String decoded) {
        try {
            JsonNode root = mapper.readTree(decoded);
            if (root == null || !root.isObject()) {
                return UNPARSEABLE_JSON;
            }
            redact(root);
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException ignored) {
            return UNPARSEABLE_JSON;
        }
    }

    private void redact(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSecretKey(field.getKey())) {
                    ((ObjectNode) node).set(field.getKey(), TextNode.valueOf(REDACTED));
                } else {
                    redact(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::redact);
        }
    }

    private boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("credential")
                || normalized.contains("apikey")
                || normalized.contains("privatekey")
                || normalized.equals("username");
    }

    private String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder(value.length());
        int bytes = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            result.append(character);
            bytes += characterBytes;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }
}

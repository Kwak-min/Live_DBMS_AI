package com.example.monitoring.ai.llm;

/** AI 생성 실패. code는 ai_reports.error_code로 그대로 저장되고 API에도 노출된다(비밀 없음). */
public class AiGenerationException extends RuntimeException {

    public static final String UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String REFUSED = "AI_REFUSED";
    public static final String TRUNCATED = "AI_TRUNCATED";
    public static final String RATE_LIMITED = "AI_RATE_LIMITED";
    public static final String AUTH_FAILED = "AI_AUTH_FAILED";
    public static final String UPSTREAM_ERROR = "AI_UPSTREAM_ERROR";
    public static final String INVALID_OUTPUT = "AI_INVALID_OUTPUT";

    private final String code;

    public AiGenerationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AiGenerationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

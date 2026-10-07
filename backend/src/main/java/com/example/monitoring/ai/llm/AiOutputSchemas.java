package com.example.monitoring.ai.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Gemini 구조화 출력용 JSON Schema. {@link com.example.monitoring.ai.model.DailyReportInsight},
 * {@link com.example.monitoring.ai.model.QueryAnalysisInsight}와 필드가 같아야 한다.
 * (Claude는 SDK가 레코드에서 스키마를 만든다.)
 */
final class AiOutputSchemas {

    private static final String DAILY = """
            {
              "type": "object",
              "properties": {
                "summary": {"type": "string", "description": "Korean summary of the day in 3-5 sentences, citing key numbers"},
                "overallStatus": {"type": "string", "enum": ["HEALTHY", "WARNING", "CRITICAL"]},
                "healthScore": {"type": "integer", "minimum": 0, "maximum": 100,
                                "description": "0 (down all day) to 100 (no issues)"},
                "findings": {
                  "type": "array",
                  "description": "Notable findings, most severe first; empty when nothing stands out",
                  "items": {
                    "type": "object",
                    "properties": {
                      "severity": {"type": "string", "enum": ["INFO", "WARNING", "CRITICAL"]},
                      "title": {"type": "string", "description": "Short Korean headline, under 60 characters"},
                      "detail": {"type": "string", "description": "Korean explanation citing the concrete numbers"}
                    },
                    "required": ["severity", "title", "detail"]
                  }
                },
                "recommendations": {"type": "array", "items": {"type": "string"},
                                    "description": "Concrete Korean action items, most important first"}
              },
              "required": ["summary", "overallStatus", "healthScore", "findings", "recommendations"]
            }
            """;

    private static final String QUERY = """
            {
              "type": "object",
              "properties": {
                "summary": {"type": "string", "description": "Korean overview in 2-4 sentences"},
                "overallRisk": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"]},
                "queries": {
                  "type": "array",
                  "description": "One assessment per sample, every queryId exactly once, most risky first",
                  "items": {
                    "type": "object",
                    "properties": {
                      "queryId": {"type": "string", "description": "The sample queryId, copied exactly"},
                      "riskLevel": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"]},
                      "category": {"type": "string",
                                   "description": "FULL_SCAN, MISSING_INDEX, LARGE_SORT, LOCK_RISK, UNBOUNDED_RESULT, HEAVY_WRITE, N_PLUS_ONE or OK"},
                      "problem": {"type": "string", "description": "Korean explanation citing the statistics"},
                      "recommendation": {"type": "string", "description": "Korean concrete fix"},
                      "suggestedIndex": {"type": "string",
                                         "description": "CREATE INDEX statement if an index would help, otherwise empty"}
                    },
                    "required": ["queryId", "riskLevel", "category", "problem", "recommendation", "suggestedIndex"]
                  }
                },
                "generalRecommendations": {"type": "array", "items": {"type": "string"},
                                           "description": "Korean server-wide recommendations"}
              },
              "required": ["summary", "overallRisk", "queries", "generalRecommendations"]
            }
            """;

    private AiOutputSchemas() {
    }

    static Map<String, Object> daily(ObjectMapper mapper) {
        return parse(mapper, DAILY);
    }

    static Map<String, Object> query(ObjectMapper mapper) {
        return parse(mapper, QUERY);
    }

    private static Map<String, Object> parse(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Invalid AI output schema", e);
        }
    }
}

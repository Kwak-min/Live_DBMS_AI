package com.example.monitoring.ai.llm;

import com.example.monitoring.ai.model.AiFinding;
import com.example.monitoring.ai.model.DailyReportInsight;
import com.example.monitoring.ai.model.QueryAnalysisInsight;
import com.example.monitoring.ai.model.QueryInsight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemini용 손으로 쓴 JSON Schema가 Claude가 쓰는 레코드와 같은 필드를 요구하는지 확인한다. */
class AiOutputSchemasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dailySchemaMatchesRecords() {
        Map<String, Object> schema = AiOutputSchemas.daily(mapper);
        assertFields(schema, DailyReportInsight.class);
        assertFields(items(properties(schema).get("findings")), AiFinding.class);
    }

    @Test
    void querySchemaMatchesRecords() {
        Map<String, Object> schema = AiOutputSchemas.query(mapper);
        assertFields(schema, QueryAnalysisInsight.class);
        assertFields(items(properties(schema).get("queries")), QueryInsight.class);
    }

    @Test
    void schemaShapedJsonDeserializesIntoRecords() throws Exception {
        DailyReportInsight daily = mapper.readValue("""
                {"summary":"요약","overallStatus":"WARNING","healthScore":72,
                 "findings":[{"severity":"CRITICAL","title":"t","detail":"d"}],"recommendations":["r"]}
                """, DailyReportInsight.class);
        assertThat(daily.findings()).hasSize(1);

        QueryAnalysisInsight query = mapper.readValue("""
                {"summary":"s","overallRisk":"HIGH","queries":[{"queryId":"Q1","riskLevel":"HIGH",
                 "category":"FULL_SCAN","problem":"p","recommendation":"r","suggestedIndex":""}],
                 "generalRecommendations":[]}
                """, QueryAnalysisInsight.class);
        assertThat(query.queries().get(0).queryId()).isEqualTo("Q1");
    }

    private static void assertFields(Map<String, Object> schema, Class<? extends Record> type) {
        List<String> components = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(properties(schema).keySet()).as(type.getSimpleName()).containsExactlyInAnyOrderElementsOf(components);
        assertThat(asList(schema.get("required"))).as(type.getSimpleName()).containsExactlyInAnyOrderElementsOf(components);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> items(Object arraySchema) {
        return (Map<String, Object>) ((Map<String, Object>) arraySchema).get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
        return (List<String>) value;
    }
}

package fun.fengwk.kkstudio.harness.runtime.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelUsage;

import java.time.Instant;
import java.util.Map;

class RunEventPayloadsTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** attempt 事件必须固定携带 attempt/turnIndex，并稳定编码 journal 使用的全部字段类型。 */
  @Test
  void encodesAttemptScopeAndSupportedFieldTypes() throws Exception {
    AgentRun run =
        new AgentRun(
            1L,
            2L,
            3L,
            RunStatus.RUNNING,
            4,
            5,
            6L,
            "worker",
            NOW.plusSeconds(30),
            NOW,
            null,
            NOW,
            NOW,
            null,
            NOW);

    JsonNode payload =
        OBJECT_MAPPER.readTree(
            RunEventPayloads.forAttempt(
                run,
                "nullable",
                null,
                "text",
                "value",
                "integer",
                7,
                "long",
                8L,
                "boolean",
                true,
                "instant",
                NOW,
                "map",
                Map.of("key", "value"),
                "record",
                new ModelUsage(1, 2, 3, 4, 5, 6, 21),
                "attempt",
                99,
                "turnIndex",
                99,
                "schemaVersion",
                99));

    assertEquals(1, payload.get("schemaVersion").intValue());
    assertEquals(5, payload.get("attempt").intValue());
    assertEquals(4, payload.get("turnIndex").intValue());
    assertTrue(payload.get("nullable").isNull());
    assertEquals("value", payload.get("text").textValue());
    assertEquals(7, payload.get("integer").intValue());
    assertEquals(8L, payload.get("long").longValue());
    assertTrue(payload.get("boolean").booleanValue());
    assertEquals(NOW.toString(), payload.get("instant").textValue());
    assertEquals("value", payload.get("map").get("key").textValue());
    assertEquals(6L, payload.get("record").get("reasoningTokens").longValue());
    assertEquals(21L, payload.get("record").get("providerTotalTokens").longValue());
  }

  /** key/value 不成对时必须在写 journal 前失败。 */
  @Test
  void rejectsOddEventFields() {
    assertThrows(IllegalArgumentException.class, () -> RunEventPayloads.of("orphan"));
  }
}

package fun.fengwk.kkstudio.harness.builtin.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;

import java.time.Instant;

/** GoalStateCodec 的详细序列化、反序列化与严格模式验证测试。 */
class GoalStateCodecTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:01:00Z");

  private final GoalStateCodec codec = new GoalStateCodec();

  @Test
  void encodesAndDecodesActiveStateSuccessfully() {
    GoalState state = new GoalState("implement goal", 5000L, GoalStatus.ACTIVE, null, T0, T1);
    String json = codec.encode(state);
    assertNotNull(json);

    CustomEntryPayload payload =
        new CustomEntryPayload("builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, json);
    GoalState decoded = codec.decode(payload);

    assertEquals(state.objective(), decoded.objective());
    assertEquals(state.tokenBudget(), decoded.tokenBudget());
    assertEquals(state.status(), decoded.status());
    assertNull(decoded.reason());
    assertEquals(state.createdAt(), decoded.createdAt());
    assertEquals(state.updatedAt(), decoded.updatedAt());
  }

  @Test
  void encodesAndDecodesTerminalStateSuccessfully() {
    GoalState state =
        new GoalState("implement goal", null, GoalStatus.COMPLETE, "all tests pass", T0, T1);
    String json = codec.encode(state);
    assertNotNull(json);

    CustomEntryPayload payload =
        new CustomEntryPayload("builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, json);
    GoalState decoded = codec.decode(payload);

    assertEquals(state.objective(), decoded.objective());
    assertNull(decoded.tokenBudget());
    assertEquals(state.status(), decoded.status());
    assertEquals("all tests pass", decoded.reason());
    assertEquals(state.createdAt(), decoded.createdAt());
    assertEquals(state.updatedAt(), decoded.updatedAt());
  }

  @Test
  void envelopeHandlesNullAndNonNullState() {
    String nullEnvelope = codec.envelope(null);
    assertTrue(nullEnvelope.contains("\"goal\":null"));

    GoalState state = new GoalState("obj", null, GoalStatus.ACTIVE, null, T0, T0);
    String stateEnvelope = codec.envelope(state);
    assertTrue(stateEnvelope.contains("\"goal\":{"));
    assertTrue(stateEnvelope.contains("\"objective\":\"obj\""));
  }

  @Test
  void decodeRejectsInvalidSchemaVersion() {
    CustomEntryPayload payload = new CustomEntryPayload("builtin", "goal.state", 99, "{}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(payload));
  }

  @Test
  void parseRejectsNonObjectJson() {
    JsonNode node = codec.parse("[]", "goal state");
    assertFalse(node.isObject());
  }

  @Test
  void decodeRejectsMissingOrExtraFields() {
    // Missing fields
    CustomEntryPayload missing =
        new CustomEntryPayload(
            "builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, "{\"objective\":\"x\"}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missing));

    // Extra field
    String extraJson =
        "{\"objective\":\"x\",\"tokenBudget\":null,\"status\":\"active\",\"reason\":null,"
            + "\"createdAt\":\""
            + T0
            + "\",\"updatedAt\":\""
            + T1
            + "\",\"extra\":\"value\"}";
    CustomEntryPayload extra =
        new CustomEntryPayload("builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, extraJson);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(extra));
  }

  @Test
  void decodeRejectsInvalidFieldTypes() {
    // Objective not text
    String badObjective =
        "{\"objective\":123,\"tokenBudget\":null,\"status\":\"active\",\"reason\":null,"
            + "\"createdAt\":\""
            + T0
            + "\",\"updatedAt\":\""
            + T1
            + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                new CustomEntryPayload(
                    "builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, badObjective)));

    // TokenBudget negative or non-integer
    String badBudget =
        "{\"objective\":\"obj\",\"tokenBudget\":-1,\"status\":\"active\",\"reason\":null,"
            + "\"createdAt\":\""
            + T0
            + "\",\"updatedAt\":\""
            + T1
            + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                new CustomEntryPayload(
                    "builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, badBudget)));

    // Reason not text or null
    String badReason =
        "{\"objective\":\"obj\",\"tokenBudget\":null,\"status\":\"active\",\"reason\":123,"
            + "\"createdAt\":\""
            + T0
            + "\",\"updatedAt\":\""
            + T1
            + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                new CustomEntryPayload(
                    "builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, badReason)));

    // CreatedAt invalid instant
    String badCreatedAt =
        "{\"objective\":\"obj\",\"tokenBudget\":null,\"status\":\"active\",\"reason\":null,"
            + "\"createdAt\":\"not-an-instant\",\"updatedAt\":\""
            + T1
            + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                new CustomEntryPayload(
                    "builtin", "goal.state", GoalStateCodec.SCHEMA_VERSION, badCreatedAt)));
  }

  @Test
  void parseRejectsMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.parse("not json", "test"));
  }
}

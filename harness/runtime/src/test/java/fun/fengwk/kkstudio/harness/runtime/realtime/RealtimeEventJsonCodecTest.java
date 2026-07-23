package fun.fengwk.kkstudio.harness.runtime.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;

import java.time.Instant;

/**
 * Realtime event 严格确定性 codec 测试。覆盖三种 delta 形态（TextDelta / ThinkingDelta /
 * ToolCallDelta，ToolCallDelta 含 raw fragment passthrough）、canonical round-trip、exact-field 拒绝
 * （unknown / missing / wrong type / null / trailing / duplicate）、wire discriminator
 * case-sensitivity、 构造器不变量传播。
 */
class RealtimeEventJsonCodecTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final RealtimeEventJsonCodec codec = new RealtimeEventJsonCodec();

  // ---------- Canonical round-trip for each delta type ----------

  @Test
  void textDeltaRoundTrips() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(7L, 42L, 3, new ProviderStreamEvent.TextDelta("hi"), now);
    String canonical =
        "{\"threadId\":\"7\",\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"42\","
            + "\"attempt\":3,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"hi\"},"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    assertEquals(canonical, codec.encode(event));
    assertEquals(event, codec.decode(canonical));
  }

  @Test
  void thinkingDeltaRoundTrips() {
    Instant now = Instant.parse("2026-01-02T01:02:03Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(8L, 9L, 1, new ProviderStreamEvent.ThinkingDelta("plan"), now);
    String canonical =
        "{\"threadId\":\"8\",\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"9\","
            + "\"attempt\":1,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"THINKING_DELTA\",\"text\":\"plan\"},"
            + "\"createdAt\":\"2026-01-02T01:02:03Z\"}";
    assertEquals(canonical, codec.encode(event));
    assertEquals(event, codec.decode(canonical));
  }

  @Test
  void toolCallDeltaEncodesExplicitNullsAndPreservesFragment() {
    Instant now = Instant.parse("2026-01-03T03:04:05Z");
    ProviderStreamEvent.ToolCallDelta delta =
        new ProviderStreamEvent.ToolCallDelta(0, null, null, "{\"a\":");
    RealtimeEvent.ModelDelta event = new RealtimeEvent.ModelDelta(10L, 11L, 1, delta, now);
    String canonical =
        "{\"threadId\":\"10\",\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"11\","
            + "\"attempt\":1,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"TOOL_CALL_DELTA\",\"index\":0,\"id\":null,\"name\":null,"
            + "\"argumentsJson\":\"{\\\"a\\\":\"},"
            + "\"createdAt\":\"2026-01-03T03:04:05Z\"}";
    assertEquals(canonical, codec.encode(event));
    assertEquals(event, codec.decode(canonical));
  }

  @Test
  void toolCallDeltaPreservesRawFragmentWithoutParsing() {
    Instant now = Instant.parse("2026-01-04T00:00:00Z");
    // Not a complete JSON object; codec must not reject or repair it.
    String rawFragment = "  broken: not json  ";
    ProviderStreamEvent.ToolCallDelta delta =
        new ProviderStreamEvent.ToolCallDelta(2, "call-1", "read", rawFragment);
    RealtimeEvent.ModelDelta event = new RealtimeEvent.ModelDelta(1L, 2L, 1, delta, now);
    String encoded = codec.encode(event);
    assertEquals(event, codec.decode(encoded));
    RealtimeEvent.ModelDelta decoded = (RealtimeEvent.ModelDelta) codec.decode(encoded);
    ProviderStreamEvent.ToolCallDelta decodedDelta =
        (ProviderStreamEvent.ToolCallDelta) decoded.delta();
    assertEquals(rawFragment, decodedDelta.argumentsJson());
  }

  @Test
  void toolCallDeltaWithAllFieldsNullRejectedByConstructor() {
    // All-null construction is rejected by ProviderStreamEvent.ToolCallDelta itself.
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderStreamEvent.ToolCallDelta(0, null, null, null));
    // Ensure we are still safely constructing ModelDelta with a valid delta.
    ProviderStreamEvent.ToolCallDelta valid =
        new ProviderStreamEvent.ToolCallDelta(0, "c", null, null);
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(1L, 2L, 1, valid, Instant.parse("2026-01-04T01:00:00Z"));
    assertEquals(event, codec.decode(codec.encode(event)));
  }

  // ---------- Exact field set & wire invariant ----------

  @Test
  void rejectsUnknownTopLevelField() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("extra", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsMissingTopLevelField() {
    ObjectNode node = canonicalTextDeltaNode();
    node.remove("attempt");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsAttemptInsidePayload() {
    ObjectNode node = canonicalTextDeltaNode();
    ((ObjectNode) node.get("payload")).put("attempt", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsUnknownPayloadKind() {
    ObjectNode node = canonicalTextDeltaNode();
    ((ObjectNode) node.get("payload")).put("kind", "OTHER");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsCaseSensitiveKindMismatch() {
    ObjectNode node = canonicalTextDeltaNode();
    ((ObjectNode) node.get("payload")).put("kind", "textdelta");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsCaseSensitiveSubjectKind() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("subjectKind", "model_invocation");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsCaseSensitiveTypeDiscriminator() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("type", "model_delta");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNonModelInvocationSubjectKind() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("subjectKind", "THREAD");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsUnknownType() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("type", "OTHER");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsToolCallDeltaMissingField() {
    ObjectNode node = canonicalTextDeltaNode();
    ObjectNode payload = (ObjectNode) node.get("payload");
    payload.put("kind", "TOOL_CALL_DELTA");
    payload.put("index", 0);
    payload.remove("id");
    payload.remove("name");
    payload.remove("argumentsJson");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsToolCallDeltaUnknownField() {
    ObjectNode node = canonicalTextDeltaNode();
    ObjectNode payload = (ObjectNode) node.get("payload");
    payload.put("kind", "TOOL_CALL_DELTA");
    payload.put("index", 0);
    payload.put("id", "call-1");
    payload.put("name", "read");
    payload.put("argumentsJson", "{}");
    payload.put("extra", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsToolCallDeltaBlankIdWhenProvided() {
    ObjectNode node = canonicalTextDeltaNode();
    ObjectNode payload = (ObjectNode) node.get("payload");
    payload.put("kind", "TOOL_CALL_DELTA");
    payload.put("index", 0);
    payload.put("id", "  ");
    payload.putNull("name");
    payload.putNull("argumentsJson");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsToolCallDeltaBlankNameWhenProvided() {
    ObjectNode node = canonicalTextDeltaNode();
    ObjectNode payload = (ObjectNode) node.get("payload");
    payload.put("kind", "TOOL_CALL_DELTA");
    payload.put("index", 0);
    payload.putNull("id");
    payload.put("name", "");
    payload.putNull("argumentsJson");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsToolCallDeltaAllFieldsNull() {
    ObjectNode node = canonicalTextDeltaNode();
    ObjectNode payload = (ObjectNode) node.get("payload");
    payload.put("kind", "TOOL_CALL_DELTA");
    payload.put("index", 0);
    payload.putNull("id");
    payload.putNull("name");
    payload.putNull("argumentsJson");
    // Constructor invariants: must contain at least one non-null field.
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  // ---------- Numeric invariants ----------

  @Test
  void rejectsNonPositiveThreadId() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("threadId", "0");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNumericThreadId() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("threadId", 7);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNonPositiveAttempt() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("attempt", 0);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNonPositiveSubjectId() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("subjectId", "-1");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsNonDecimalSubjectId() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("subjectId", "not-a-number");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsMalformedCreatedAt() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("createdAt", "yesterday");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  // ---------- Strict mapper enforcement ----------

  @Test
  void rejectsTrailingTokens() {
    ObjectNode node = canonicalTextDeltaNode();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString() + " trailing"));
  }

  @Test
  void rejectsTopLevelDuplicateField() {
    String dup =
        "{\"threadId\":\"7\",\"threadId\":\"8\","
            + "\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"42\","
            + "\"attempt\":3,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"hi\"},"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(dup));
  }

  @Test
  void rejectsNestedDuplicateField() {
    String dup =
        "{\"threadId\":\"7\",\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"42\","
            + "\"attempt\":3,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"hi\",\"text\":\"there\"},"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(dup));
  }

  @Test
  void rejectsPayloadNonObject() {
    ObjectNode node = canonicalTextDeltaNode();
    node.replace("payload", NODES.arrayNode());
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  @Test
  void rejectsExplicitTopLevelNullForRequiredFields() {
    ObjectNode node = canonicalTextDeltaNode();
    node.putNull("type");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  // ---------- API null guarding ----------

  @Test
  void guardsNullArguments() {
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
  }

  // ---------- Determinism ----------

  @Test
  void encodingIsDeterministic() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(1L, 2L, 1, new ProviderStreamEvent.TextDelta("hello"), now);
    assertEquals(codec.encode(event), codec.encode(event));
  }

  // ---------- helpers ----------

  private static ObjectNode canonicalTextDeltaNode() {
    ObjectNode node = NODES.objectNode();
    node.put("threadId", "7");
    node.put("subjectKind", "MODEL_INVOCATION");
    node.put("subjectId", "42");
    node.put("attempt", 3);
    node.put("type", "MODEL_DELTA");
    ObjectNode payload = NODES.objectNode();
    payload.put("kind", "TEXT_DELTA");
    payload.put("text", "hi");
    node.set("payload", payload);
    node.put("createdAt", "2026-01-01T00:00:00Z");
    return node;
  }
}

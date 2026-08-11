package fun.fengwk.kkstudio.harness.runtime.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Realtime event 严格确定性 codec 测试。覆盖三种 delta 形态（TextDelta / ThinkingDelta /
 * ToolCallDelta，ToolCallDelta 含 raw fragment passthrough）、canonical round-trip、exact-field 拒绝
 * （unknown / missing / wrong type / null / trailing / duplicate）、wire discriminator
 * case-sensitivity、 构造器不变量传播。
 */
class RealtimeEventJsonCodecTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final RealtimeEventJsonCodec codec = new RealtimeEventJsonCodec();

  // ---------- 每种 delta 类型的 canonical 往返 ----------

  @Test
  void textDeltaRoundTrips() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            UUID.fromString("00000000-0000-0000-0000-00000000002a"),
            3,
            5L,
            new ProviderStreamEvent.TextDelta("hi"),
            now);
    String canonical =
        "{\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"subjectKind\":\"MODEL_INVOCATION\","
            + "\"subjectId\":\"00000000-0000-0000-0000-00000000002a\","
            + "\"attempt\":3,\"sequence\":5,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"hi\"},"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    assertEquals(canonical, codec.encode(event));
    assertEquals(event, codec.decode(canonical));
  }

  @Test
  void thinkingDeltaRoundTrips() {
    Instant now = Instant.parse("2026-01-02T01:02:03Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            UUID.fromString("00000000-0000-0000-0000-000000000008"),
            UUID.fromString("00000000-0000-0000-0000-000000000009"),
            1,
            1L,
            new ProviderStreamEvent.ThinkingDelta("plan"),
            now);
    String canonical =
        "{\"threadId\":\"00000000-0000-0000-0000-000000000008\","
            + "\"subjectKind\":\"MODEL_INVOCATION\","
            + "\"subjectId\":\"00000000-0000-0000-0000-000000000009\","
            + "\"attempt\":1,\"sequence\":1,\"type\":\"MODEL_DELTA\","
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
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            UUID.fromString("00000000-0000-0000-0000-00000000000a"),
            UUID.fromString("00000000-0000-0000-0000-00000000000b"),
            1,
            1L,
            delta,
            now);
    String canonical =
        "{\"threadId\":\"00000000-0000-0000-0000-00000000000a\","
            + "\"subjectKind\":\"MODEL_INVOCATION\","
            + "\"subjectId\":\"00000000-0000-0000-0000-00000000000b\","
            + "\"attempt\":1,\"sequence\":1,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"TOOL_CALL_DELTA\",\"index\":0,\"id\":null,\"name\":null,"
            + "\"argumentsJson\":\"{\\\"a\\\":\"},"
            + "\"createdAt\":\"2026-01-03T03:04:05Z\"}";
    assertEquals(canonical, codec.encode(event));
    assertEquals(event, codec.decode(canonical));
  }

  @Test
  void toolCallDeltaPreservesRawFragmentWithoutParsing() {
    Instant now = Instant.parse("2026-01-04T00:00:00Z");
    // 不是完整的 JSON object；codec 不得拒绝或修正它。
    String rawFragment = "  broken: not json  ";
    ProviderStreamEvent.ToolCallDelta delta =
        new ProviderStreamEvent.ToolCallDelta(2, "call-1", "read", rawFragment);
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            1,
            1L,
            delta,
            now);
    String encoded = codec.encode(event);
    assertEquals(event, codec.decode(encoded));
    RealtimeEvent.ModelDelta decoded = (RealtimeEvent.ModelDelta) codec.decode(encoded);
    ProviderStreamEvent.ToolCallDelta decodedDelta =
        (ProviderStreamEvent.ToolCallDelta) decoded.delta();
    assertEquals(rawFragment, decodedDelta.argumentsJson());
  }

  @Test
  void toolPartialRoundTripsWithCanonicalToolResultPayload() {
    Instant now = Instant.parse("2026-01-05T00:00:00Z");
    RealtimeEvent.ToolPartial event =
        new RealtimeEvent.ToolPartial(
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            UUID.fromString("00000000-0000-0000-0000-000000000063"),
            2,
            new ToolResult("call-1", List.of(new TextToolContent("partial")), false, "{}", false),
            now);
    String canonical =
        "{\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"subjectKind\":\"TOOL_INVOCATION\","
            + "\"subjectId\":\"00000000-0000-0000-0000-000000000063\","
            + "\"attempt\":2,\"type\":\"TOOL_PARTIAL\","
            + "\"payload\":{\"toolCallId\":\"call-1\",\"contents\":[{\"type\":\"text\","
            + "\"text\":\"partial\"}],\"error\":false,\"details\":{}},"
            + "\"createdAt\":\"2026-01-05T00:00:00Z\"}";

    assertEquals(canonical, codec.encode(event));
    assertEquals(event, codec.decode(canonical));
  }

  @Test
  void rejectsToolPartialWithWrongSubjectKindOrUnknownResultField() {
    ObjectNode wrongSubject = canonicalToolPartialNode();
    wrongSubject.put("subjectKind", "MODEL_INVOCATION");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(wrongSubject));

    ObjectNode invalidPayload = canonicalToolPartialNode();
    ((ObjectNode) invalidPayload.get("payload")).put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(invalidPayload));
  }

  @Test
  void toolCallDeltaWithAllFieldsNullRejectedByConstructor() {
    // 全 null 构造由 ProviderStreamEvent.ToolCallDelta 自身拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderStreamEvent.ToolCallDelta(0, null, null, null));
    // 确认仍然可以用合法 delta 安全地构造 ModelDelta。
    ProviderStreamEvent.ToolCallDelta valid =
        new ProviderStreamEvent.ToolCallDelta(0, "c", null, null);
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            1,
            1L,
            valid,
            Instant.parse("2026-01-04T01:00:00Z"));
    assertEquals(event, codec.decode(codec.encode(event)));
  }

  // ---------- 精确字段集与 wire 不变量 ----------

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

    ObjectNode missingType = canonicalTextDeltaNode();
    missingType.remove("type");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(missingType));
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
  void rejectsInvalidTypeAndNullSubjectKind() {
    ObjectNode node = canonicalTextDeltaNode();
    node.put("type", "OTHER");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));

    ObjectNode nullType = canonicalTextDeltaNode();
    nullType.putNull("type");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(nullType));

    ObjectNode nullSubjectKind = canonicalTextDeltaNode();
    nullSubjectKind.putNull("subjectKind");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(nullSubjectKind));
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
    // 构造器不变量：必须至少包含一个非 null 字段。
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
  }

  // ---------- 数值不变量 ----------

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

  // ---------- 严格 mapper 强制 ----------

  @Test
  void rejectsTrailingTokens() {
    ObjectNode node = canonicalTextDeltaNode();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString() + " trailing"));
  }

  @Test
  void rejectsTopLevelDuplicateField() {
    String dup =
        "{\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"threadId\":\"00000000-0000-0000-0000-000000000008\","
            + "\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"00000000-0000-0000-0000-00000000002a\","
            + "\"attempt\":3,\"type\":\"MODEL_DELTA\","
            + "\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"hi\"},"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(dup));
  }

  @Test
  void rejectsNestedDuplicateField() {
    String dup =
        "{\"threadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"00000000-0000-0000-0000-00000000002a\","
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

  // ---------- API null 防护 ----------

  @Test
  void guardsNullArguments() {
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
  }

  // ---------- 确定性 ----------

  @Test
  void encodingIsDeterministic() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            1,
            1L,
            new ProviderStreamEvent.TextDelta("hello"),
            now);
    assertEquals(codec.encode(event), codec.encode(event));
  }

  // ---------- 辅助方法 ----------

  private static ObjectNode canonicalTextDeltaNode() {
    ObjectNode node = NODES.objectNode();
    node.put("threadId", "00000000-0000-0000-0000-000000000007");
    node.put("subjectKind", "MODEL_INVOCATION");
    node.put("subjectId", "00000000-0000-0000-0000-00000000002a");
    node.put("attempt", 3);
    node.put("sequence", 5);
    node.put("type", "MODEL_DELTA");
    ObjectNode payload = NODES.objectNode();
    payload.put("kind", "TEXT_DELTA");
    payload.put("text", "hi");
    node.set("payload", payload);
    node.put("createdAt", "2026-01-01T00:00:00Z");
    return node;
  }

  private static ObjectNode canonicalToolPartialNode() {
    ObjectNode node = NODES.objectNode();
    node.put("threadId", "00000000-0000-0000-0000-000000000007");
    node.put("subjectKind", "TOOL_INVOCATION");
    node.put("subjectId", "00000000-0000-0000-0000-000000000063");
    node.put("attempt", 2);
    node.put("type", "TOOL_PARTIAL");
    ObjectNode payload = NODES.objectNode();
    payload.put("toolCallId", "call-1");
    payload.set("contents", NODES.arrayNode());
    payload.put("error", false);
    payload.set("details", NODES.objectNode());
    node.set("payload", payload);
    node.put("createdAt", "2026-01-05T00:00:00Z");
    return node;
  }
}

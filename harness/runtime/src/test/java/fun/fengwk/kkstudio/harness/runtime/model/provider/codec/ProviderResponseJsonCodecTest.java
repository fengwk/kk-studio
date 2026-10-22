package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

class ProviderResponseJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String CANONICAL_RESOURCE =
      "/fun/fengwk/kkstudio/harness/runtime/model/provider/codec/provider-response.json";
  private static final String FIRST_ARGUMENTS = "{\n  \"q\":\"paris\"\n}";
  private static final String SECOND_ARGUMENTS = "{\"city\":\"Paris\", \"units\":\"metric\"}";
  private static final String RAW_USAGE =
      "{\n  \"completion_tokens\":20,\n  \"prompt_tokens\":100\n}";

  private final ProviderResponseJsonCodec codec = new ProviderResponseJsonCodec();

  /**
   * 规范 fixture 在一次完整 response 中验证精确的字段顺序、tool-call 顺序、decimal 字符串、null metadata 以及逐字保留的 raw usage
   * JSON。
   */
  @Test
  void roundTripsCompleteResponseAndMatchesCanonicalFixture() {
    ProviderResponse response = canonicalResponse();
    String expectedJson = canonicalNode().toString();

    String encoded = codec.encode(response);
    ProviderResponse decoded = codec.decode(encoded);

    assertEquals(expectedJson, encoded);
    assertEquals(response, decoded);
    assertEquals(encoded, codec.encode(decoded));
    assertEquals(response, codec.decodeNode(codec.encodeNode(response)));
    assertEquals(
        List.of("call-1", "call-2"),
        decoded.toolCalls().stream().map(ProviderToolCall::id).toList());
    assertEquals(FIRST_ARGUMENTS, decoded.toolCalls().get(0).argumentsJson());
    assertEquals(SECOND_ARGUMENTS, decoded.toolCalls().get(1).argumentsJson());
    // 冻结的历史 action 是 durable 事实：非空 action 与 null（无 Tool 语义映射）都必须无损往返。
    assertEquals("look up the capital of paris", decoded.toolCalls().get(0).historyAction());
    assertNull(decoded.toolCalls().get(1).historyAction());
    assertEquals(RAW_USAGE, decoded.rawUsageJson());
  }

  /**
   * Harness 观测计时是 canonical 可空严格字段：字段必须存在，显式 null（不可观测）与带值都精确往返；缺失不被当作旧 durable
   * 兼容形态接受，负值、非整数与非数字类型在 boundary 拒绝。
   */
  @Test
  void roundTripsNullableDecodeDurationAndRejectsMissingField() {
    ProviderResponse timed = canonicalResponse().withDecodeDurationMillis(1500L);
    ObjectNode expected = canonicalNode();
    expected.put("decodeDurationMillis", 1500);

    String encoded = codec.encode(timed);
    assertEquals(expected.toString(), encoded);
    assertEquals(timed, codec.decode(encoded));
    assertEquals(1500L, codec.decode(encoded).decodeDurationMillis());

    // 不可观测计时仍显式占据 canonical 字段位：encode 输出 null，decode 与 null 同义。
    String untimed = codec.encode(canonicalResponse());
    assertEquals(canonicalNode().toString(), untimed);
    assertNull(codec.decode(untimed).decodeDurationMillis());
    assertNull(codec.decodeNode(canonicalNode()).decodeDurationMillis());

    // 缺失该字段即拒绝：canonical JSON 不再接受旧 durable 行形态。
    assertRejected(root -> root.remove("decodeDurationMillis"));

    assertRejected(root -> root.put("decodeDurationMillis", -1));
    assertRejected(root -> root.put("decodeDurationMillis", 1.5));
    assertRejected(root -> root.put("decodeDurationMillis", "1500"));
    assertRejected(root -> root.put("decodeDurationMillis", true));
    // 超出 long 范围的整数同样被拒绝（不静默截断）。
    assertRejected(
        root ->
            root.set(
                "decodeDurationMillis",
                NODES.numberNode(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))));
  }

  /** nullable metadata 同时支持显式 string 形态，raw usage 也可以是 ordered array。 */
  @Test
  void roundTripsStringMetadataAndRawUsageArray() {
    String rawUsageJson = " \n[ {\"cached\":true}, 2 ]\n ";
    ProviderResponse response =
        new ProviderResponse(
            null,
            null,
            List.of(),
            GenerationStopReason.COMPLETE,
            new ModelUsage(1, 8, 3, 5, Map.of(3600L, 5L), 6L, 21L),
            "request-7",
            "priority",
            rawUsageJson);

    ProviderResponse decoded = codec.decode(codec.encode(response));

    assertEquals(response, decoded);
    assertEquals("", decoded.text());
    assertEquals("", decoded.thinking());
    assertEquals("request-7", decoded.requestId());
    assertEquals("priority", decoded.serviceTier());
    assertEquals(rawUsageJson, decoded.rawUsageJson());
  }

  /** String、tool-argument 与 raw-usage JSON 必须拒绝 duplicate field 与 trailing document。 */
  @Test
  void rejectsDuplicateAndTrailingDocumentsAtStrictJsonBoundaries() {
    ProviderResponse response = canonicalResponse();
    String encoded = codec.encode(response);
    String duplicateTopLevel = "{\"text\":\"duplicate\"," + encoded.substring(1);

    assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded + " []"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateTopLevel));

    ObjectNode duplicateArguments = canonicalNode();
    toolCall(duplicateArguments).put("argumentsJson", "{\"city\":\"Paris\",\"city\":\"Lyon\"}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateArguments.toString()));

    ProviderResponse invalidArguments =
        new ProviderResponse(
            response.text(),
            response.thinking(),
            List.of(new ProviderToolCall("call", "weather", "{\"q\":1,\"q\":2}")),
            response.stopReason(),
            response.usage(),
            response.requestId(),
            response.serviceTier(),
            response.rawUsageJson());
    assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidArguments));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderResponse(
                response.text(),
                response.thinking(),
                response.toolCalls(),
                response.stopReason(),
                response.usage(),
                response.requestId(),
                response.serviceTier(),
                "{\"total\":1,\"total\":2}"));
  }

  /** 每个 response 对象层都独立拒绝未知、缺失以及类型错误的字段。 */
  @Test
  void rejectsUnknownMissingAndWrongTypedFieldsAtEveryObjectLayer() {
    assertStrictLayer(
        root -> root.put("extra", true),
        root -> root.remove("text"),
        root -> root.set("toolCalls", NODES.objectNode()));
    assertStrictLayer(
        root -> toolCall(root).put("extra", true),
        root -> toolCall(root).remove("name"),
        root -> toolCall(root).set("argumentsJson", NODES.objectNode()));
    // historyAction 是严格字段：缺失与非文本同样必须在 boundary 上被拒绝。
    assertRejected(root -> toolCall(root).remove("historyAction"));
    assertRejected(root -> toolCall(root).put("historyAction", 1));
    assertStrictLayer(
        root -> usage(root).put("extra", true),
        root -> usage(root).remove("inputTokens"),
        root -> usage(root).put("inputTokens", "100"));
  }

  /** 未知 stop reason 与非文本 enum 值必须被拒绝，不得降级处理。 */
  @Test
  void rejectsUnknownOrWrongTypedStopReason() {
    assertRejected(root -> root.put("stopReason", "UNKNOWN_STOP"));
    assertRejected(root -> root.put("stopReason", 1));
  }

  /** CONTINUE 是 durable stop reason：零工具意图时可无损往返，携带 tool call 的 payload 在 boundary 上被拒绝。 */
  @Test
  void roundTripsContinuationStopReasonAndRejectsToolIntent() {
    ProviderResponse continuation =
        new ProviderResponse(
            "paused",
            "",
            List.of(),
            GenerationStopReason.CONTINUE,
            new ModelUsage(1, 2, 0, 0, Map.of(), 0L, 3L),
            "request-8",
            null,
            "{}");

    assertEquals(continuation, codec.decode(codec.encode(continuation)));
    assertRejected(root -> root.put("stopReason", "CONTINUE"));
  }

  /**
   * Malformed 根节点、numeric 越界、raw JSON 字段以及缺失 nullable 字段都必须在持久化 boundary 上 抛出
   * IllegalArgumentException。
   */
  @Test
  void rejectsMalformedRootsNumbersJsonAndNullableFields() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("   "));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));

    assertRejected(root -> root.remove("requestId"));
    assertRejected(root -> root.remove("serviceTier"));
    assertRejected(root -> root.put("requestId", 1));
    assertRejected(root -> root.put("serviceTier", false));
    assertRejected(root -> root.putNull("text"));
    assertRejected(root -> root.putNull("rawUsageJson"));
    assertRejected(root -> root.put("rawUsageJson", "not-json"));
    assertRejected(root -> root.put("rawUsageJson", "\"scalar\""));
    assertRejected(root -> root.put("rawUsageJson", " "));

    assertRejected(root -> ((ArrayNode) root.path("toolCalls")).set(0, NODES.numberNode(1)));
    assertRejected(root -> toolCall(root).put("argumentsJson", "[]"));
    assertRejected(root -> toolCall(root).put("argumentsJson", "{"));
    assertRejected(root -> toolCall(root).put("argumentsJson", " "));

    assertRejected(root -> usage(root).put("inputTokens", -1));
    assertRejected(root -> usage(root).put("inputTokens", 1.5));
    assertRejected(
        root ->
            usage(root)
                .set(
                    "inputTokens",
                    NODES.numberNode(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))));
  }

  private void assertStrictLayer(
      Consumer<ObjectNode> unknownMutation,
      Consumer<ObjectNode> missingMutation,
      Consumer<ObjectNode> wrongTypeMutation) {
    assertRejected(unknownMutation);
    assertRejected(missingMutation);
    assertRejected(wrongTypeMutation);
  }

  private void assertRejected(Consumer<ObjectNode> mutation) {
    ObjectNode root = canonicalNode();
    mutation.accept(root);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  private static ProviderResponse canonicalResponse() {
    return new ProviderResponse(
        "The capital of France is Paris.",
        "user asked geography",
        List.of(
            new ProviderToolCall(
                "call-1", "lookup", FIRST_ARGUMENTS, "look up the capital of paris"),
            new ProviderToolCall("call-2", "weather", SECOND_ARGUMENTS)),
        GenerationStopReason.COMPLETE,
        new ModelUsage(100, 25, 0, 50, Map.of(), 5L, 175L),
        null,
        null,
        RAW_USAGE);
  }

  private static ObjectNode canonicalNode() {
    try {
      return (ObjectNode) OBJECT_MAPPER.readTree(readResource(CANONICAL_RESOURCE));
    } catch (JacksonException exception) {
      throw new IllegalStateException("invalid canonical provider response fixture", exception);
    }
  }

  private static String readResource(String resource) {
    try (InputStream input =
        Objects.requireNonNull(
            ProviderResponseJsonCodecTest.class.getResourceAsStream(resource), resource)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static ObjectNode toolCall(ObjectNode root) {
    return (ObjectNode) root.path("toolCalls").path(0);
  }

  private static ObjectNode usage(ObjectNode root) {
    return (ObjectNode) root.path("usage");
  }

  /** 自由 JSON 字段（rawUsageJson/argumentsJson）按原始文本搬运，不经过 double。 */
  @Test
  void preservesRawFreeFormJsonNumberTextWithoutDoubleCoercion() {
    ObjectNode root = canonicalNode();
    root.put("rawUsageJson", "{\"big\":1e309,\"tiny\":1e-324}");
    toolCall(root).put("argumentsJson", "{\"big\":1e309}");

    ProviderResponse decoded = codec.decodeNode(root);
    String encoded = codec.encode(decoded);

    assertTrue(encoded.contains("1e309"), encoded);
    assertTrue(encoded.contains("1e-324"), encoded);
    assertFalse(encoded.contains("Infinity"), encoded);
    assertEquals(encoded, codec.encode(codec.decode(encoded)));
  }

  /**
   * toolCallDiagnostics 是 canonical 严格数组：nullable id/name 与必填文本都精确往返；callIndex 必须落在 toolCalls +
   * diagnostics 的总数内，超出 int 范围的索引也在 boundary 拒绝而不静默截断。
   */
  @Test
  void roundTripsToolCallDiagnosticsAndRejectsCallIndexBoundaries() {
    ProviderResponse base = canonicalResponse();
    ProviderResponse response =
        new ProviderResponse(
            base.text(),
            base.thinking(),
            base.toolCalls(),
            base.stopReason(),
            base.usage(),
            base.requestId(),
            base.serviceTier(),
            base.rawUsageJson(),
            List.of(
                new ProviderToolCallDiagnostic(0, "call-1", "lookup", "{\"q\":1}", "partial"),
                new ProviderToolCallDiagnostic(1, null, null, "{}", "dropped")),
            base.decodeDurationMillis());

    String encoded = codec.encode(response);
    ProviderResponse decoded = codec.decode(encoded);

    assertEquals(response, decoded);
    assertEquals(encoded, codec.encode(decoded));
    assertEquals("{\"q\":1}", decoded.toolCallDiagnostics().get(0).partialArguments());
    assertNull(decoded.toolCallDiagnostics().get(1).id());
    assertNull(decoded.toolCallDiagnostics().get(1).name());

    // 总数 = 2 个 toolCalls + 1 个 diagnostic，callIndex 4 越界。
    ObjectNode outOfBounds = canonicalNode();
    outOfBounds
        .putArray("toolCallDiagnostics")
        .addObject()
        .put("callIndex", 4)
        .putNull("id")
        .putNull("name")
        .put("partialArguments", "{}")
        .put("message", "out-of-bounds");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(outOfBounds));

    // 非负 long 仍是合法 JSON 整数，但超出 int 的诊断索引必须拒绝。
    ObjectNode overflow = canonicalNode();
    overflow
        .putArray("toolCallDiagnostics")
        .addObject()
        .put("callIndex", (long) Integer.MAX_VALUE + 1)
        .putNull("id")
        .putNull("name")
        .put("partialArguments", "{}")
        .put("message", "overflow");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(overflow));
  }
}

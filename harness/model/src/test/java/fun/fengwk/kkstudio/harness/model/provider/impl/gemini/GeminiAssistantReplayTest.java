package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;

/**
 * 测试意图：锁定 assistant replay 的回退与拒绝边界——空 blocks、缺失元数据、协议或 affinity 不匹配、未知 opaque 形态都回退语义编码
 * 而不抛错；只有结构性损坏（如工具参数不是 JSON object）才抛 {@code INVALID_REQUEST}。语义编码路径只跳过无法投影的块。
 */
class GeminiAssistantReplayTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String MODEL_ID = "gemini-2.5-flash";

  private static ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "google-test",
        ProviderType.GOOGLE,
        "https://generativelanguage.googleapis.com/v1beta",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  /** 复用一个与请求 modelId 匹配的 replay 元数据。 */
  private static ProviderBlockData data(ProviderReplayFormat protocol, String affinityModel) {
    return new ProviderBlockData(
        protocol, descriptor().affinity(affinityModel), NODES.objectNode());
  }

  private static ProviderBlockData data(String signature, Boolean thought, ObjectNode fields) {
    return new ProviderBlockData(
        ProviderReplayFormat.GEMINI_CONTENT,
        descriptor().affinity(MODEL_ID),
        signature,
        null,
        null,
        null,
        null,
        false,
        thought,
        fields);
  }

  @Test
  void emptyBlocksAreNotReplayable() {
    assertNull(GeminiAssistantReplay.buildParts(descriptor(), MODEL_ID, List.of()));
  }

  /** 缺元数据、协议不符或 affinity 指向别的模型时回退语义编码。 */
  @Test
  void mismatchedIdentityFallsBackToSemanticEncoding() {
    assertNull(
        GeminiAssistantReplay.buildParts(
            descriptor(), MODEL_ID, List.of(new ProviderTextBlock("plain"))));

    ProviderTextBlock otherProtocol =
        new ProviderTextBlock("plain", data(ProviderReplayFormat.ANTHROPIC_MESSAGES, MODEL_ID));
    assertNull(GeminiAssistantReplay.buildParts(descriptor(), MODEL_ID, List.of(otherProtocol)));

    ProviderTextBlock otherModel =
        new ProviderTextBlock("plain", data(ProviderReplayFormat.GEMINI_CONTENT, "gemini-3-pro"));
    assertNull(GeminiAssistantReplay.buildParts(descriptor(), MODEL_ID, List.of(otherModel)));
  }

  /** 不属于 gemini_part 的 opaque block 无法回放；语义编码路径则直接跳过它。 */
  @Test
  void opaqueBlockWithForeignTypeFallsBackAndIsDroppedFromSemanticEncoding() {
    ProviderOpaqueBlock opaque =
        new ProviderOpaqueBlock(
            "anthropic_block", data(ProviderReplayFormat.GEMINI_CONTENT, MODEL_ID));
    assertNull(GeminiAssistantReplay.buildParts(descriptor(), MODEL_ID, List.of(opaque)));

    ArrayNode parts = NODES.arrayNode();
    GeminiAssistantReplay.encodeBlock(parts, opaque);
    assertTrue(parts.isEmpty());
  }

  /** 语义编码路径跳过 redacted thinking，并把 thinking 投影为文本 part。 */
  @Test
  void semanticEncodingProjectsThinkingAndDropsRedactedThinking() {
    ArrayNode parts = NODES.arrayNode();
    GeminiAssistantReplay.encodeBlock(parts, new ProviderThinkingBlock("plan"));
    GeminiAssistantReplay.encodeBlock(
        parts,
        new ProviderRedactedThinkingBlock(
            "opaque", data(ProviderReplayFormat.GEMINI_CONTENT, MODEL_ID)));

    assertEquals(1, parts.size());
    assertEquals("plan", parts.get(0).path("text").asString(""));
  }

  /** text/thinking 的原生字段、thought 与签名原样回放。 */
  @Test
  void replaysTextAndThinkingPartsWithNativeFields() {
    ObjectNode textFields = NODES.objectNode();
    textFields.put("extra", "kept");
    ProviderTextBlock text = new ProviderTextBlock("answer", data("sig-1", null, textFields));
    ProviderThinkingBlock thinking =
        new ProviderThinkingBlock("plan", data(null, null, NODES.objectNode()));

    List<ObjectNode> parts =
        GeminiAssistantReplay.buildParts(descriptor(), MODEL_ID, List.of(thinking, text));

    assertNotNull(parts);
    assertEquals(2, parts.size());
    assertEquals("plan", parts.get(0).path("text").asString(""));
    assertTrue(parts.get(0).path("thought").asBoolean(false));
    assertEquals("answer", parts.get(1).path("text").asString(""));
    assertEquals("kept", parts.get(1).path("extra").asString(""));
    assertEquals("sig-1", parts.get(1).path("thoughtSignature").asString(""));
  }

  /** tool call 的 functionCall 扩展字段原位回放，id/name/args 来自 durable 事实。 */
  @Test
  void replaysToolCallWithFunctionCallExtensions() {
    ObjectNode fields = NODES.objectNode();
    fields.putObject("functionCall").put("callExtra", true);
    ProviderToolCallBlock block =
        new ProviderToolCallBlock(
            new ProviderToolCall("c1", "read_file", "{\"path\":\"a\"}"), data(null, null, fields));

    List<ObjectNode> parts =
        GeminiAssistantReplay.buildParts(descriptor(), MODEL_ID, List.of(block));

    assertNotNull(parts);
    ObjectNode functionCall = (ObjectNode) parts.get(0).path("functionCall");
    assertEquals("read_file", functionCall.path("name").asString(""));
    assertTrue(functionCall.path("callExtra").asBoolean(false));
    assertEquals("a", functionCall.path("args").path("path").asString(""));
    assertEquals("c1", functionCall.path("id").asString(""));
  }

  /** 工具参数不是 JSON object 属于请求事实损坏，必须显式拒绝。 */
  @Test
  void replayRejectsNonObjectToolArguments() {
    ProviderToolCallBlock block =
        new ProviderToolCallBlock(
            new ProviderToolCall("c1", "read_file", "[1]"), data(null, null, NODES.objectNode()));

    ProviderException failure =
        assertThrows(
            ProviderException.class,
            () -> GeminiAssistantReplay.buildParts(descriptor(), MODEL_ID, List.of(block)));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failure.kind());
  }

  /** 语义编码路径同样拒绝非 object 的工具参数。 */
  @Test
  void semanticEncodingRejectsNonObjectToolArguments() {
    ArrayNode parts = NODES.arrayNode();
    ProviderToolCallBlock block =
        new ProviderToolCallBlock(new ProviderToolCall("c1", "read_file", "[1]"));

    ProviderException failure =
        assertThrows(
            ProviderException.class, () -> GeminiAssistantReplay.encodeBlock(parts, block));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failure.kind());
  }

  /** payload 结构或已知字段形状损坏一律 INVALID_REQUEST。 */
  @Test
  void rejectsStructurallyBrokenReplayPayloads() {
    ProviderTextBlock durable = new ProviderTextBlock("answer");

    assertRejected(null, durable);
    assertRejected(NODES.arrayNode(), durable);
    assertRejected(payload(NODES.objectNode().put("role", "user")), durable);
    assertRejected(NODES.objectNode().put("role", "model"), durable);
    assertRejected(payloadWithExtraTopLevelField(), durable);
    assertRejected(payloadWithNonObjectPart(), durable);
    assertRejected(payloadWithTextAndFunctionCall(), durable);
    assertRejected(payloadWithPart(NODES.objectNode().put("text", 1)), durable);
    assertRejected(
        payloadWithPart(NODES.objectNode().put("text", "a").put("thought", "yes")), durable);
    assertRejected(
        payloadWithPart(NODES.objectNode().put("text", "a").put("thoughtSignature", " ")), durable);
    assertRejected(payloadWithFunctionCallMissingArgs(), durable);
    assertRejected(payloadWithFunctionCallNonStringId(), durable);
  }

  /** 与 durable 语义不一致时返回 false（回退语义编码），不抛错。 */
  @Test
  void reportsSemanticMismatchWithoutThrowing() {
    assertFalse(
        GeminiAssistantReplay.validateReplayPayload(
            payloadWithPart(NODES.objectNode().put("text", "other")),
            List.of(new ProviderTextBlock("answer"))));
    assertFalse(
        GeminiAssistantReplay.validateReplayPayload(
            payloadWithPart(NODES.objectNode().put("text", "a").put("thought", true)),
            List.of(new ProviderTextBlock("a"))));
    assertFalse(
        GeminiAssistantReplay.validateReplayPayload(
            payloadWithFunctionCall("t", NODES.objectNode()),
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "other", "{}")))));
    assertFalse(
        GeminiAssistantReplay.validateReplayPayload(
            payloadWithFunctionCall("t", NODES.objectNode()),
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "t", "{\"x\":1}")))));
    assertFalse(
        GeminiAssistantReplay.validateReplayPayload(
            payloadWithFunctionCall("t", NODES.objectNode()), List.of()));
  }

  /** durable 工具参数无法解析属于请求事实损坏，必须拒绝而不是回退。 */
  @Test
  void rejectsUnparseableDurableToolArguments() {
    ProviderToolCallBlock block =
        new ProviderToolCallBlock(new ProviderToolCall("c1", "t", "{broken"));
    ProviderException failure =
        assertThrows(
            ProviderException.class,
            () ->
                GeminiAssistantReplay.validateReplayPayload(
                    payloadWithFunctionCall("t", NODES.objectNode()), List.of(block)));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failure.kind());
  }

  private static void assertRejected(JsonNode payload, ProviderContentBlock durable) {
    ProviderException failure =
        assertThrows(
            ProviderException.class,
            () -> GeminiAssistantReplay.validateReplayPayload(payload, List.of(durable)));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failure.kind());
  }

  private static ObjectNode payload(ObjectNode withParts) {
    withParts.set("parts", NODES.arrayNode());
    return withParts;
  }

  private static ObjectNode payloadWithPart(ObjectNode part) {
    ObjectNode payload = NODES.objectNode().put("role", "model");
    payload.putArray("parts").add(part);
    return payload;
  }

  private static ObjectNode payloadWithFunctionCall(String name, JsonNode args) {
    ObjectNode functionCall = NODES.objectNode().put("name", name);
    functionCall.set("args", args);
    return payloadWithPart(NODES.objectNode().set("functionCall", functionCall));
  }

  private static ObjectNode payloadWithExtraTopLevelField() {
    ObjectNode payload = payloadWithPart(NODES.objectNode().put("text", "answer"));
    payload.put("extra", true);
    return payload;
  }

  private static ObjectNode payloadWithNonObjectPart() {
    ObjectNode payload = NODES.objectNode().put("role", "model");
    payload.putArray("parts").add("not-an-object");
    return payload;
  }

  private static ObjectNode payloadWithTextAndFunctionCall() {
    ObjectNode part = NODES.objectNode().put("text", "a");
    part.set("functionCall", NODES.objectNode().put("name", "t").set("args", NODES.objectNode()));
    return payloadWithPart(part);
  }

  /** functionCall 缺少 args。 */
  private static ObjectNode payloadWithFunctionCallMissingArgs() {
    ObjectNode part = NODES.objectNode();
    part.set("functionCall", NODES.objectNode().put("name", "t"));
    return payloadWithPart(part);
  }

  /** functionCall 的 id 不是字符串。 */
  private static ObjectNode payloadWithFunctionCallNonStringId() {
    ObjectNode functionCall = NODES.objectNode().put("name", "t").put("id", 1);
    functionCall.set("args", NODES.objectNode());
    ObjectNode part = NODES.objectNode();
    part.set("functionCall", functionCall);
    return payloadWithPart(part);
  }
}

package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.EnvironmentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ExecutionPolicySnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolSchemaElement;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 5 类最终 Runtime Entry payload 的契约与 codec 测试。覆盖：每类 canonical round-trip（含 message 含全部 content 类型与完整
 * assistant metadata），encode/decode 严格对称（raw {@code argumentsJson} / {@code detailsJson} / {@code
 * json} 校验 + tool-result 嵌套拒绝），Node/ObjectMapper 严格拒绝路径（unknown / missing / wrong type / null /
 * trailing / duplicate / unknown enum / unknown discriminator），构造器不变量的传播（ASSISTANT role ↔ metadata
 * ↔ tool-call / TOOL_CALLS、assistant-error kind+message），null guard。
 */
class RuntimeEntryPayloadJsonCodecTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String MESSAGE_FIXTURE =
      "/fun/fengwk/kkstudio/harness/runtime/entry/runtime-entry-payload-message.json";
  private static final String ASSISTANT_ERROR_FIXTURE =
      "/fun/fengwk/kkstudio/harness/runtime/entry/runtime-entry-payload-assistant-error.json";
  private static final RuntimeConfigJsonCodec CONFIG_CODEC = new RuntimeConfigJsonCodec();

  private final RuntimeEntryPayloadJsonCodec codec = new RuntimeEntryPayloadJsonCodec();

  // ---------- 8-类 canonical round-trip ----------

  @Test
  void rootCanonicalIsEmptyObject() {
    assertEquals("{}", codec.encode(new RootEntryPayload()));
    assertEquals(new RootEntryPayload(), codec.decode(EntryType.ROOT, "{}"));
  }

  @Test
  void messageCanonicalFixtureIsBitIdenticalAndRoundTrips() {
    ObjectNode canonical = canonicalNode(MESSAGE_FIXTURE);
    String expectedJson = canonical.toString();
    MessageEntryPayload payload =
        (MessageEntryPayload) codec.decodeNode(EntryType.MESSAGE, canonical);

    String encoded = codec.encode(payload);
    assertEquals(expectedJson, encoded);
    assertEquals(payload, codec.decode(EntryType.MESSAGE, encoded));
    assertEquals(EntryType.MESSAGE, payload.type());
    assertEquals(AgentMessageRole.ASSISTANT, payload.message().role());
    assertEquals(7, payload.message().contents().size());
    assertEquals(ProviderStopReason.TOOL_CALLS, payload.assistantMetadata().stopReason());
    assertEquals(new BigDecimal("0.000004200000"), payload.assistantMetadata().cost().total());
    assertInstanceOf(ToolCallMessageContent.class, payload.message().contents().get(5));
    assertNull(
        assertInstanceOf(ArtifactMessageContent.class, payload.message().contents().get(6))
            .preview());
  }

  @Test
  void messageEncodeNodeMatchesCanonicalNode() {
    ObjectNode canonical = canonicalNode(MESSAGE_FIXTURE);
    MessageEntryPayload payload =
        (MessageEntryPayload) codec.decodeNode(EntryType.MESSAGE, canonical);
    assertEquals(canonical.toString(), codec.encodeNode(payload).toString());
  }

  @Test
  void userMessageRoundTripsWithExplicitNullMetadata() {
    String canonical =
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
            + "\"assistantMetadata\":null}";
    MessageEntryPayload payload = (MessageEntryPayload) codec.decode(EntryType.MESSAGE, canonical);
    assertEquals(AgentMessageRole.USER, payload.message().role());
    assertNull(payload.assistantMetadata());
    assertEquals(canonical, codec.encode(payload));
  }

  @Test
  void customMessageCanonicalCarriesOnlyMessage() {
    AgentMessage message =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi")));
    CustomMessageEntryPayload payload = new CustomMessageEntryPayload(message);
    String canonical =
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
    assertEquals(canonical, codec.encode(payload));
    assertEquals(payload, codec.decode(EntryType.CUSTOM_MESSAGE, canonical));
  }

  @Test
  void assistantErrorCanonicalShapeMatchesFixture() {
    ObjectNode canonical = canonicalNode(ASSISTANT_ERROR_FIXTURE);
    String expectedJson = canonical.toString();
    AssistantErrorEntryPayload payload =
        (AssistantErrorEntryPayload) codec.decodeNode(EntryType.ASSISTANT_ERROR, canonical);
    assertEquals(expectedJson, codec.encode(payload));
    assertEquals(payload, codec.decode(EntryType.ASSISTANT_ERROR, expectedJson));
    assertEquals(ProviderErrorKind.TRANSIENT, payload.error().kind());
    assertEquals("provider temporarily unavailable", payload.error().message());
  }

  @Test
  void assistantErrorShapeIsWrapperObjectAroundError() {
    ObjectNode canonical = canonicalNode(ASSISTANT_ERROR_FIXTURE);
    assertEquals(1, canonical.size());
    assertTrue(canonical.has("error"));
    ObjectNode error = (ObjectNode) canonical.get("error");
    assertEquals(Set.of("kind", "message"), fieldNames(error));
  }

  @Test
  void runtimeConfigDelegatesToConfigCodec() {
    RuntimeConfigSnapshot snapshot = canonicalConfig();
    String configEncoded = CONFIG_CODEC.encode(snapshot);
    ObjectNode configNode = CONFIG_CODEC.encodeNode(snapshot);

    assertEquals(configEncoded, codec.encode(snapshot));
    assertEquals(configNode, codec.encodeNode(snapshot));
    assertEquals(snapshot, codec.decode(EntryType.RUNTIME_CONFIG, configEncoded));
    assertEquals(snapshot, codec.decodeNode(EntryType.RUNTIME_CONFIG, (JsonNode) configNode));
  }

  // ---------- Empty text / raw JSON / decimal plain / whitespace preservation ----------

  @Test
  void emptyTextAndThinkingAreAllowed() {
    AgentMessage message =
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("")));
    MessageEntryPayload payload = new MessageEntryPayload(message, completedMetadata());
    String encoded = codec.encode(payload);
    assertTrue(encoded.contains("\"text\":\"\""));
    MessageEntryPayload decoded = (MessageEntryPayload) codec.decode(EntryType.MESSAGE, encoded);
    TextMessageContent text =
        assertInstanceOf(TextMessageContent.class, decoded.message().contents().get(0));
    assertEquals("", text.text());

    String thinking =
        "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"thinking\",\"text\":\"\"}]},"
            + "\"assistantMetadata\":"
            + zeroMetadataJson(ProviderStopReason.COMPLETED)
            + "}";
    MessageEntryPayload thinkingPayload =
        (MessageEntryPayload) codec.decode(EntryType.MESSAGE, thinking);
    ThinkingMessageContent thinkingContent =
        assertInstanceOf(ThinkingMessageContent.class, thinkingPayload.message().contents().get(0));
    assertEquals("", thinkingContent.text());
  }

  @Test
  void artifactTextPreviewRoundTrips() {
    ArtifactMessageContent artifact =
        new ArtifactMessageContent("artifact-1", "text/plain", "preview");
    MessageEntryPayload payload =
        new MessageEntryPayload(new AgentMessage(AgentMessageRole.USER, List.of(artifact)));

    String encoded = codec.encode(payload);
    MessageEntryPayload decoded = (MessageEntryPayload) codec.decode(EntryType.MESSAGE, encoded);

    ArtifactMessageContent decodedArtifact =
        assertInstanceOf(ArtifactMessageContent.class, decoded.message().contents().get(0));
    assertEquals("preview", decodedArtifact.preview());
  }

  @Test
  void rawJsonPreservedVerbatimWithSurroundingWhitespace() {
    String assistantWithRaw =
        "{\"message\":{\"role\":\"ASSISTANT\","
            + "\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"call-1\",\"toolName\":\"read\","
            + "\"argumentsJson\":\"  {\\\"path\\\":\\\"README.md\\\"}  \"}]},"
            + "\"assistantMetadata\":"
            + zeroMetadataJson(ProviderStopReason.TOOL_CALLS)
            + "}";
    MessageEntryPayload payload =
        (MessageEntryPayload) codec.decode(EntryType.MESSAGE, assistantWithRaw);
    ToolCallMessageContent toolCall =
        assertInstanceOf(ToolCallMessageContent.class, payload.message().contents().get(0));
    assertEquals("  {\"path\":\"README.md\"}  ", toolCall.argumentsJson());

    MessageEntryPayload toolPayload =
        new MessageEntryPayload(toolMessageWithRawDetails("  {\"k\":\"v\"}  "));
    String toolEncoded = codec.encode(toolPayload);
    assertTrue(toolEncoded.contains("\"detailsJson\":\"  {\\\"k\\\":\\\"v\\\"}  \""));
    MessageEntryPayload toolDecoded =
        (MessageEntryPayload) codec.decode(EntryType.MESSAGE, toolEncoded);
    ToolResultMessageContent result =
        assertInstanceOf(ToolResultMessageContent.class, toolDecoded.message().contents().get(0));
    assertEquals("  {\"k\":\"v\"}  ", result.detailsJson());
  }

  @Test
  void costFieldsAreSerialisedAsPlainStringBigDecimal() {
    AssistantMessageMetadata metadata = canonicalCostMetadata();
    AgentMessage message =
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("x")));
    MessageEntryPayload payload = new MessageEntryPayload(message, metadata);
    String encoded = codec.encode(payload);
    assertTrue(encoded.contains("\"input\":\"0.000001000000\""));
    assertTrue(encoded.contains("\"cacheWriteLong\":\"0\""));
    assertTrue(encoded.contains("\"total\":\"0.000004200000\""));
    MessageEntryPayload decoded = (MessageEntryPayload) codec.decode(EntryType.MESSAGE, encoded);
    assertEquals(new BigDecimal("0.000004200000"), decoded.assistantMetadata().cost().total());
    assertEquals(new BigDecimal("0"), decoded.assistantMetadata().cost().cacheWriteLong());
  }

  // ---------- Reject: non-supported payload implementation ----------

  @Test
  void rejectsUnsupportedEntryPayloadImplementation() {
    EntryPayload unknown =
        new EntryPayload() {
          @Override
          public EntryType type() {
            return EntryType.MESSAGE;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> codec.encode(unknown));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeNode(unknown));
  }

  // ---------- Reject: top-level / nested field set ----------

  @Test
  void rejectsUnknownTopLevelFieldOnEveryType() {
    ObjectNode m = canonicalNode(MESSAGE_FIXTURE);
    m.put("extra", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m));

    ObjectNode c = NODES.objectNode();
    ObjectNode message = NODES.objectNode();
    message.put("role", "USER");
    message.putArray("contents").add(textContentNode("x"));
    c.set("message", message);
    c.put("agentMetadata", "x");
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.CUSTOM_MESSAGE, c));

    ObjectNode err = canonicalNode(ASSISTANT_ERROR_FIXTURE);
    err.put("retry", true);
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.ASSISTANT_ERROR, err));

    ObjectNode root = NODES.objectNode().put("x", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.ROOT, root));
  }

  @Test
  void rejectsMissingTopLevelFieldOnMessage() {
    ObjectNode m1 = canonicalNode(MESSAGE_FIXTURE);
    m1.remove("message");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m1));
    ObjectNode m2 = canonicalNode(MESSAGE_FIXTURE);
    m2.remove("assistantMetadata");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m2));
  }

  @Test
  void rejectsExplicitTopLevelNulls() {
    ObjectNode m = NODES.objectNode();
    m.putNull("message");
    m.putNull("assistantMetadata");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m));
    ObjectNode c = NODES.objectNode().putNull("message");
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.CUSTOM_MESSAGE, c));
    ObjectNode e = NODES.objectNode().putNull("error");
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.ASSISTANT_ERROR, e));
  }

  @Test
  void rejectsUnknownOrMissingNestedFieldsOnMessage() {
    ObjectNode m1 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m1.get("message")).put("agent", "x");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m1));

    ObjectNode m2 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m2.get("message")).remove("contents");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m2));
  }

  @Test
  void rejectsUnknownNestedFieldOnAssistantMetadata() {
    ObjectNode m = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m.get("assistantMetadata")).put("vendor", "openai");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m));
  }

  // ---------- Reject: enum & discriminator ----------

  @Test
  void rejectsUnknownRoleAndStopReason() {
    ObjectNode m1 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m1.get("message")).put("role", "USER_AGENT");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m1));

    ObjectNode m2 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m2.get("message")).put("role", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m2));

    ObjectNode m3 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m3.get("assistantMetadata")).put("stopReason", "UNKNOWN");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m3));

    ObjectNode e = canonicalNode(ASSISTANT_ERROR_FIXTURE);
    ((ObjectNode) e.get("error")).put("kind", "NETWORK");
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.ASSISTANT_ERROR, e));
  }

  @Test
  void rejectsUnknownOrMissingContentDiscriminator() {
    ObjectNode m1 = canonicalNode(MESSAGE_FIXTURE);
    ArrayNode c1 = (ArrayNode) ((ObjectNode) m1.get("message")).get("contents");
    ObjectNode badType = NODES.objectNode();
    badType.put("type", "stego");
    badType.put("text", "x");
    c1.set(0, badType);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m1));

    ObjectNode m2 = canonicalNode(MESSAGE_FIXTURE);
    ArrayNode c2 = (ArrayNode) ((ObjectNode) m2.get("message")).get("contents");
    ObjectNode missingType = NODES.objectNode();
    missingType.put("text", "x");
    c2.set(0, missingType);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m2));

    ObjectNode m3 = canonicalNode(MESSAGE_FIXTURE);
    ArrayNode c3 = (ArrayNode) ((ObjectNode) m3.get("message")).get("contents");
    ((ObjectNode) c3.get(0)).put("type", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m3));
  }

  // ---------- Reject: numeric, decimal, currency ----------

  @Test
  void rejectsNegativeOrFractionalUsageAndBadCostDecimal() {
    ObjectNode m1 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m1.get("assistantMetadata").get("usage")).put("inputTokens", -1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m1));

    ObjectNode m2 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m2.get("assistantMetadata").get("usage")).put("inputTokens", 1.5);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m2));

    ObjectNode m3 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m3.get("assistantMetadata").get("cost")).put("total", "not-a-decimal");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m3));

    ObjectNode m4 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m4.get("assistantMetadata").get("cost")).put("total", "0.000000100000");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m4));

    ObjectNode m5 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) m5.get("assistantMetadata").get("cost")).put("currency", " ");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m5));
  }

  @Test
  void rejectsWrongNestedTypesAndOutOfRangeIntegers() {
    String wrongContents =
        "{\"message\":{\"role\":\"USER\",\"contents\":{}},\"assistantMetadata\":null}";
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, wrongContents));

    ObjectNode wrongText = canonicalNode(MESSAGE_FIXTURE);
    ArrayNode contents = (ArrayNode) ((ObjectNode) wrongText.get("message")).get("contents");
    ((ObjectNode) contents.get(0)).put("text", 1);
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, wrongText));

    String wrongBoolean =
        "{\"message\":{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\","
            + "\"toolCallId\":\"c\",\"toolName\":\"t\",\"contents\":[{\"type\":\"text\","
            + "\"text\":\"x\"}],\"error\":\"false\",\"detailsJson\":\"{}\"}]},"
            + "\"assistantMetadata\":null}";
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, wrongBoolean));

    ObjectNode wrongDecimal = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) wrongDecimal.get("assistantMetadata").get("cost")).put("input", 1);
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, wrongDecimal));

    ObjectNode usageOverflow = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) usageOverflow.get("assistantMetadata").get("usage"))
        .put("inputTokens", new BigInteger("922337203685477580812345678901234567890"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, usageOverflow));
  }

  // ---------- Reject: raw JSON (argumentsJson / detailsJson / json) ----------

  @Test
  void rejectsRawJsonWrongObjectTrailingDuplicateInToolCall() {
    ObjectNode m1 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) ((ArrayNode) ((ObjectNode) m1.get("message")).get("contents")).get(5))
        .put("argumentsJson", "[]");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m1));

    ObjectNode m2 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) ((ArrayNode) ((ObjectNode) m2.get("message")).get("contents")).get(5))
        .put("argumentsJson", "{} extra");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m2));

    ObjectNode m3 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) ((ArrayNode) ((ObjectNode) m3.get("message")).get("contents")).get(5))
        .put("argumentsJson", "{\"a\":1,\"a\":2}");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m3));
  }

  @Test
  void rejectsRawJsonWrongObjectTrailingDuplicateInToolResult() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encode(new MessageEntryPayload(toolMessageWithRawDetails("[]"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.encode(new MessageEntryPayload(toolMessageWithRawDetails("{\"a\":1,\"a\":2}"))));
  }

  @Test
  void rejectsRawJsonValueForJsonMessageContent() {
    ObjectNode m1 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) ((ArrayNode) ((ObjectNode) m1.get("message")).get("contents")).get(4))
        .put("json", "not-json");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m1));

    ObjectNode m2 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) ((ArrayNode) ((ObjectNode) m2.get("message")).get("contents")).get(4))
        .put("json", "1 {}");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m2));

    ObjectNode m3 = canonicalNode(MESSAGE_FIXTURE);
    ((ObjectNode) ((ArrayNode) ((ObjectNode) m3.get("message")).get("contents")).get(4))
        .put("json", "{\"a\":1,\"a\":2}");
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m3));

    JsonMessageContent bad = new JsonMessageContent("not-json");
    AgentMessage badMessage = new AgentMessage(AgentMessageRole.ASSISTANT, List.of(bad));
    MessageEntryPayload encPayload = new MessageEntryPayload(badMessage, completedMetadata());
    assertThrows(IllegalArgumentException.class, () -> codec.encode(encPayload));
  }

  // ---------- Reject: tool-result nesting ----------

  @Test
  void rejectsToolResultNestingToolCallAndToolResult() {
    String nestedCallJson =
        "{\"message\":{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\","
            + "\"toolCallId\":\"outer\",\"toolName\":\"read\",\"contents\":[{\"type\":\"tool_call\","
            + "\"toolCallId\":\"inner\",\"toolName\":\"read\",\"argumentsJson\":\"{}\"}],"
            + "\"error\":false,\"detailsJson\":\"{}\"}]},\"assistantMetadata\":null}";
    String nestedResultJson =
        "{\"message\":{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\","
            + "\"toolCallId\":\"outer\",\"toolName\":\"read\",\"contents\":[{\"type\":\"tool_result\","
            + "\"toolCallId\":\"inner\",\"toolName\":\"read\",\"contents\":[{\"type\":\"text\","
            + "\"text\":\"x\"}],\"error\":false,\"detailsJson\":\"{}\"}],\"error\":false,"
            + "\"detailsJson\":\"{}\"}]},\"assistantMetadata\":null}";
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, nestedCallJson));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, nestedResultJson));

    ToolResultMessageContent outerWithCall =
        new ToolResultMessageContent(
            "outer",
            "read",
            List.of(new ToolCallMessageContent("inner", "read", "{}")),
            false,
            "{}");
    ToolResultMessageContent innerResult =
        new ToolResultMessageContent(
            "inner", "read", List.of(new TextMessageContent("x")), false, "{}");
    ToolResultMessageContent outerWithResult =
        new ToolResultMessageContent("outer", "read", List.of(innerResult), false, "{}");
    MessageEntryPayload callPayload =
        new MessageEntryPayload(new AgentMessage(AgentMessageRole.TOOL, List.of(outerWithCall)));
    MessageEntryPayload resultPayload =
        new MessageEntryPayload(new AgentMessage(AgentMessageRole.TOOL, List.of(outerWithResult)));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(callPayload));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(resultPayload));
  }

  @Test
  void rejectsArtifactPreviewNonText() {
    ObjectNode m = canonicalNode(MESSAGE_FIXTURE);
    ArrayNode contents = (ArrayNode) ((ObjectNode) m.get("message")).get("contents");
    ((ObjectNode) contents.get(6)).put("preview", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(EntryType.MESSAGE, m));
  }

  // ---------- Constructor invariant propagation ----------

  @Test
  void assistantMetadataMustMatchAssistantRole() {
    String assistantNoMetadata =
        "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
            + "\"assistantMetadata\":null}";
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, assistantNoMetadata));

    String userWithMetadata =
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
            + "\"assistantMetadata\":"
            + zeroMetadataJson(ProviderStopReason.COMPLETED)
            + "}";
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, userWithMetadata));
  }

  @Test
  void toolCallMustMatchStopReason() {
    String toolCallWithCompleted =
        "{\"message\":{\"role\":\"ASSISTANT\","
            + "\"contents\":[{\"type\":\"tool_call\",\"toolCallId\":\"c\",\"toolName\":\"read\","
            + "\"argumentsJson\":\"{}\"}]},"
            + "\"assistantMetadata\":"
            + zeroMetadataJson(ProviderStopReason.COMPLETED)
            + "}";
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(EntryType.MESSAGE, toolCallWithCompleted));
  }

  @Test
  void rejectsEmptyContentsAndToolResultEmptyContents() {
    String empty = "{\"message\":{\"role\":\"USER\",\"contents\":[]},\"assistantMetadata\":null}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, empty));

    String emptyTool =
        "{\"message\":{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\","
            + "\"toolCallId\":\"c\",\"toolName\":\"t\",\"contents\":[],\"error\":false,"
            + "\"detailsJson\":\"{}\"}]},\"assistantMetadata\":null}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, emptyTool));
  }

  // ---------- Strict mapper enforcement ----------

  @Test
  void rejectsTopLevelTrailingAndDuplicateFields() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, "{} trailing"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, "{\"x\":1,\"x\":2}"));

    String nestedDup =
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\",\"text\":\"y\"}]},"
            + "\"assistantMetadata\":null}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, nestedDup));

    String nestedTrailing =
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"} extra]"
            + "},\"assistantMetadata\":null}";
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.MESSAGE, nestedTrailing));
  }

  @Test
  void rejectsMalformedRootJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, ""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, "{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, "[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, "null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, "\"x\""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.ROOT, "42"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeNode(EntryType.ROOT, NODES.arrayNode()));
  }

  // ---------- API null guarding ----------

  @Test
  void guardsNullArguments() {
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null, "{}"));
    assertThrows(NullPointerException.class, () -> codec.decode(EntryType.ROOT, null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null, NODES.objectNode()));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(EntryType.ROOT, null));
  }

  // ---------- Determinism ----------

  @Test
  void encodingIsDeterministic() {
    MessageEntryPayload payload =
        (MessageEntryPayload) codec.decodeNode(EntryType.MESSAGE, canonicalNode(MESSAGE_FIXTURE));
    assertEquals(codec.encode(payload), codec.encode(payload));
  }

  // ---------- helpers ----------

  private static ObjectNode canonicalNode(String resource) {
    try {
      JsonNode parsed = MAPPER.readTree(readResource(resource));
      if (!(parsed instanceof ObjectNode node)) {
        throw new IllegalStateException("canonical fixture is not an object: " + resource);
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("invalid canonical fixture: " + resource, error);
    }
  }

  private static String readResource(String resource) {
    try (InputStream input =
        Objects.requireNonNull(
            RuntimeEntryPayloadJsonCodecTest.class.getResourceAsStream(resource), resource)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static Set<String> fieldNames(ObjectNode node) {
    Set<String> names = new LinkedHashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static ObjectNode textContentNode(String text) {
    ObjectNode node = NODES.objectNode();
    node.put("type", "text");
    node.put("text", text);
    return node;
  }

  private static AssistantMessageMetadata completedMetadata() {
    return zeroMetadata(ProviderStopReason.COMPLETED);
  }

  private static AssistantMessageMetadata toolCallsMetadata() {
    return zeroMetadata(ProviderStopReason.TOOL_CALLS);
  }

  private static AssistantMessageMetadata zeroMetadata(ProviderStopReason stopReason) {
    return new AssistantMessageMetadata(
        stopReason,
        new ModelUsage(0, 0, 0, 0, 0, 0, 0),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static AssistantMessageMetadata canonicalCostMetadata() {
    return new AssistantMessageMetadata(
        ProviderStopReason.COMPLETED,
        new ModelUsage(321, 45, 6, 7, 0, 8, 387),
        new ModelCost(
            "USD",
            new BigDecimal("0.000001000000"),
            new BigDecimal("0.000001000000"),
            new BigDecimal("0.000001000000"),
            new BigDecimal("0.000001200000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("0.000004200000")));
  }

  private static String zeroMetadataJson(ProviderStopReason stopReason) {
    return "{\"stopReason\":\""
        + stopReason.name()
        + "\","
        + "\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"cacheReadTokens\":0,"
        + "\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,"
        + "\"providerTotalTokens\":0},"
        + "\"cost\":{\"currency\":\"USD\",\"input\":\"0\",\"output\":\"0\","
        + "\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteLong\":\"0\","
        + "\"reasoning\":\"0\",\"total\":\"0\"}}";
  }

  private static AgentMessage toolMessageWithRawDetails(String rawDetails) {
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "c", "t", List.of(new TextMessageContent("x")), false, rawDetails);
    return new AgentMessage(AgentMessageRole.TOOL, List.of(result));
  }

  private static RuntimeConfigSnapshot canonicalConfig() {
    Map<String, ToolSchemaElement> properties = new LinkedHashMap<>();
    properties.put("query", new ToolStringSchema("search query"));
    ToolDescriptor tool =
        new ToolDescriptor(
            "search",
            "v1",
            "search helper",
            "search",
            new ToolParamsSchema("params", properties, Set.of("query"), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMillis(1000));
    ToolBinding binding = ToolBinding.of(tool);
    RuntimeConfigSnapshot snapshot =
        new RuntimeConfigSnapshot(
            new AgentSnapshot(1001L, "primary-agent", "You are a careful assistant."),
            new ModelSnapshot(canonicalModelDescriptor(), canonicalModelVariant()),
            List.of(binding),
            List.of(new SkillSnapshot("code-review", "code review skill", "sandbox")),
            new ExecutionPolicySnapshot(16, 8, 4, null, List.of("researcher", "writer"), false),
            new EnvironmentSnapshot("sandbox", null));
    assertNotNull(snapshot);
    return snapshot;
  }

  private static ModelDescriptor canonicalModelDescriptor() {
    return new ModelDescriptor(
        9001L,
        8001L,
        ProviderType.OPENAI,
        "gpt-5-mini",
        "GPT-5 Mini",
        200000,
        16384,
        EnumSet.allOf(ModelInputModality.class),
        true,
        true,
        List.of(canonicalModelVariant()),
        canonicalModelPricing(),
        canonicalPromptCachePolicy());
  }

  private static ModelVariant canonicalModelVariant() {
    return new ModelVariant("balanced", null, 0.2, 0.9, null, null, null, List.of(), null);
  }

  private static ModelPricing canonicalModelPricing() {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        new BigDecimal("1.5"),
        "v1",
        new BigDecimal("3.0"),
        new BigDecimal("6.0"),
        new BigDecimal("0.3"),
        new BigDecimal("3.75"),
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static PromptCachePolicy canonicalPromptCachePolicy() {
    return new PromptCachePolicy(
        new PromptCacheCapability(
            PromptCacheMode.BREAKPOINTS,
            EnumSet.of(PromptCacheRetention.LONG, PromptCacheRetention.SHORT),
            EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
        PromptCacheRetention.SHORT);
  }
}

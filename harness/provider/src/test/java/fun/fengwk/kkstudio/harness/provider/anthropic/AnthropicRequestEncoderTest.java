package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderToolCallDiagnosticJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** AnthropicRequestEncoder 的纯单元与 Golden 测试。 */
class AnthropicRequestEncoderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String SYSTEM_INSTRUCTION = "Test system instruction.";

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  @Test
  void encodesBasicUserMessageWithDefaultMaxTokensAndStream() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("Hello Anthropic"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    assertNotNull(encoded);
    assertNotNull(encoded.sourcePrefixHash());

    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals("claude-3-5-sonnet", root.path("model").asText());
    assertEquals(1024, root.path("max_tokens").asInt());
    assertTrue(root.path("stream").asBoolean());
    assertEquals(1, root.path("messages").size());
    assertEquals("user", root.path("messages").get(0).path("role").asText());
    assertEquals(
        "Hello Anthropic",
        root.path("messages").get(0).path("content").get(0).path("text").asText());
  }

  @Test
  void usesModelIdAsWireModelAndRequestOutputBudgetAsMaxTokens() throws IOException {
    // 逻辑名与 wire modelId 不同：wire 必须发 modelId，逻辑名不得出现在请求体
    ModelDescriptor logicalModel =
        new ModelDescriptor(
            "test-anthropic",
            "MiniMax-M3",
            "claude-fable-5-dd-3M-xaMiniM",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            pricing());
    ProviderRequest request =
        new ProviderRequest(
            logicalModel,
            new ModelVariant("default"),
            4096,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertEquals("claude-fable-5-dd-3M-xaMiniM", root.path("model").asText());
    assertEquals(4096, root.path("max_tokens").asInt());
    assertFalse(root.has("temperature"));
    assertFalse(root.has("top_p"));
    assertFalse(root.has("top_k"));
    assertFalse(root.has("stop_sequences"));
  }

  @Test
  void validatesToolsInputSchema() throws IOException {
    // 合法工具定义
    ProviderToolDefinition validTool =
        new ProviderToolDefinition(
            "calc",
            "calculate something",
            "{\"type\":\"object\",\"properties\":{\"expr\":{\"type\":\"string\"}}}");
    ProviderRequest reqValid =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(validTool),
            ProviderCacheControl.none());
    AnthropicEncodedRequest encoded = encoder.encode(reqValid, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals("calc", root.path("tools").get(0).path("name").asText());
    assertEquals("object", root.path("tools").get(0).path("input_schema").path("type").asText());

    // 非法工具定义（非合法 JSON）
    ProviderToolDefinition malformedTool = new ProviderToolDefinition("bad", "desc", "{not-json}");
    ProviderRequest reqBad =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(malformedTool),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqBad, descriptor));

    // 非法工具定义（不是 JSON Object）
    ProviderToolDefinition arrayTool = new ProviderToolDefinition("arr", "desc", "[\"item\"]");
    ProviderRequest reqArr =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(arrayTool),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqArr, descriptor));
  }

  @Test
  void encodesImageAndDocumentBlocksWithUrlAndBase64() throws IOException {
    String base64Img =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    String base64Pdf = "data:application/pdf;base64,JVBERi0xLjUK";

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderTextBlock("inspect this:"),
                    new ProviderImageBlock("image/png", base64Img),
                    new ProviderImageBlock("image/jpeg", "https://example.com/test.jpg"),
                    new ProviderDocumentBlock("application/pdf", base64Pdf),
                    new ProviderJsonBlock("{\"key\":\"value\"}"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode contents =
        MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content");

    assertEquals(5, contents.size());
    // 0: text
    assertEquals("text", contents.get(0).path("type").asText());
    // 1: base64 image —— 必须逐字节保留去掉 data URI 前缀后的原始 base64 载荷
    assertEquals("image", contents.get(1).path("type").asText());
    assertEquals("base64", contents.get(1).path("source").path("type").asText());
    assertEquals("image/png", contents.get(1).path("source").path("media_type").asText());
    assertEquals(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        contents.get(1).path("source").path("data").asText());
    // 2: url image
    assertEquals("image", contents.get(2).path("type").asText());
    assertEquals("url", contents.get(2).path("source").path("type").asText());
    assertEquals(
        "https://example.com/test.jpg", contents.get(2).path("source").path("url").asText());
    // 3: base64 document —— 同样必须逐字节保留原始 base64 载荷
    assertEquals("document", contents.get(3).path("type").asText());
    assertEquals("base64", contents.get(3).path("source").path("type").asText());
    assertEquals("application/pdf", contents.get(3).path("source").path("media_type").asText());
    assertEquals("JVBERi0xLjUK", contents.get(3).path("source").path("data").asText());
    // 4: json as text
    assertEquals("text", contents.get(4).path("type").asText());
    assertEquals("{\"key\":\"value\"}", contents.get(4).path("text").asText());
  }

  @Test
  void rejectsDocumentWithUrlSource() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderDocumentBlock("application/pdf", "https://example.com/doc.pdf"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }

  /** 系统指令是唯一的顶层 system 单块文本，请求正文（含文档）只来自会话消息。 */
  @Test
  void encodesSystemInstructionAsSingleTopLevelTextBlock() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("user message"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode systemNode = root.path("system");
    assertEquals(1, systemNode.size());
    assertEquals("text", systemNode.get(0).path("type").asText());
    assertEquals("Test system instruction.", systemNode.get(0).path("text").asText());

    // 会话数组中绝无 SYSTEM，且系统指令未重复为任何会话消息。
    JsonNode messagesNode = root.path("messages");
    assertEquals(1, messagesNode.size());
    assertEquals("user", messagesNode.get(0).path("role").asText());
    assertEquals("user message", messagesNode.get(0).path("content").get(0).path("text").asText());
    assertFalse(root.toString().contains("SYSTEM"));
  }

  @Test
  void rejectsUnsupportedMediaTypesOrInvalidSources() {
    // 不受支持的图片类型
    ProviderRequest badImgType =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderImageBlock("image/bmp", "https://ex.com/a.bmp"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(badImgType, descriptor));

    // 不受支持的文档类型
    ProviderRequest badDocType =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderDocumentBlock("application/msword", "https://ex.com/a.doc"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(badDocType, descriptor));

    // 非法 source（既不是 http URL 也不是匹配的 base64）
    ProviderRequest badSource =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderImageBlock("image/png", "file:///local/path.png"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(badSource, descriptor));
  }

  @Test
  void encodesToolMessageWithResultBlock() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_123",
                            "calc",
                            List.of(new ProviderTextBlock("Result data")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode msg = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0);
    assertEquals("user", msg.path("role").asText());
    JsonNode res = msg.path("content").get(0);
    assertEquals("tool_result", res.path("type").asText());
    assertEquals("call_123", res.path("tool_use_id").asText());
    assertFalse(res.path("is_error").asBoolean());
    assertEquals("Result data", res.path("content").get(0).path("text").asText());
  }

  @Test
  void replayingAssistantMatchesWhenAffinityAndPrefixHashMatch() throws IOException {
    // 构造第一条用户消息
    ProviderMessage userMsg = userMsg(new ProviderTextBlock("hi"));

    // 预计算当时的 prefix hash
    String expectedHash =
        AnthropicPrefixHasher.calculateHash(
            systemBlocks(),
            NODES.arrayNode(),
            NODES
                .arrayNode()
                .add(
                    NODES
                        .objectNode()
                        .put("role", "user")
                        .set(
                            "content",
                            NODES
                                .arrayNode()
                                .add(NODES.objectNode().put("type", "text").put("text", "hi")))));

    // 构造匹配的 ReplayState
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ArrayNode content = payload.putArray("content");
    content
        .addObject()
        .put("type", "thinking")
        .put("thinking", "think text")
        .put("signature", "sig-123");
    content.addObject().put("type", "text").put("text", "response text");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            payload);

    ProviderMessage assistantMsg =
        asstMsg(
            List.of(
                new ProviderThinkingBlock("think text"), new ProviderTextBlock("response text")),
            replayState);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg, assistantMsg),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);

    assertEquals("thinking", asstWire.path("content").get(0).path("type").asText());
    assertEquals("sig-123", asstWire.path("content").get(0).path("signature").asText());
    assertEquals("text", asstWire.path("content").get(1).path("type").asText());
  }

  @Test
  void fallbackWhenReplayAffinityOrHashMismatches() throws IOException {
    ProviderMessage userMsg = userMsg(new ProviderTextBlock("hi"));

    // 构造 hash 不匹配的 ReplayState
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ArrayNode content = payload.putArray("content");
    content
        .addObject()
        .put("type", "thinking")
        .put("thinking", "think text")
        .put("signature", "sig-123");
    content.addObject().put("type", "text").put("text", "response text");
    ObjectNode toolNode = content.addObject();
    toolNode.put("type", "tool_use").put("id", "call_1").put("name", "calc");
    toolNode.putObject("input").put("x", 1);

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            "0000000000000000000000000000000000000000000000000000000000000001",
            payload);

    ProviderMessage assistantMsg =
        asstMsg(
            List.of(
                new ProviderThinkingBlock("think text"),
                new ProviderTextBlock("response text"),
                new ProviderToolCallBlock(new ProviderToolCall("call_1", "calc", "{\"x\":1}"))),
            replayState);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg, assistantMsg),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);

    // Fallback: thinking 降级为 text，toolCall 降级为 tool_use
    assertEquals("text", asstWire.path("content").get(0).path("type").asText());
    assertEquals("think text", asstWire.path("content").get(0).path("text").asText());

    assertEquals("text", asstWire.path("content").get(1).path("type").asText());
    assertEquals("response text", asstWire.path("content").get(1).path("text").asText());

    assertEquals("tool_use", asstWire.path("content").get(2).path("type").asText());
    assertEquals("call_1", asstWire.path("content").get(2).path("id").asText());
    assertEquals("calc", asstWire.path("content").get(2).path("name").asText());
    assertEquals(1, asstWire.path("content").get(2).path("input").path("x").asInt());
  }

  @Test
  void appliesCacheMarkersInToolsSystemMessagesOrder() throws IOException {
    ProviderToolDefinition tool =
        new ProviderToolDefinition("calc", "calc desc", "{\"type\":\"object\"}");

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("User 1")),
                asstMsg(List.of(new ProviderTextBlock("Asst 1")), null),
                userMsg(new ProviderTextBlock("User 2"))),
            List.of(tool),
            new ProviderCacheControl(
                PromptCacheRetention.LONG,
                "test-affinity",
                Set.of(
                    PromptCacheBreakpoint.TOOLS,
                    PromptCacheBreakpoint.SYSTEM,
                    PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    // TOOLS: 标记在最后一个 tool
    assertEquals(
        "ephemeral", root.path("tools").get(0).path("cache_control").path("type").asText());
    assertEquals("1h", root.path("tools").get(0).path("cache_control").path("ttl").asText());

    // SYSTEM: 唯一的系统指令块被标记（之前的 system 数组只有这一块）
    assertEquals(1, root.path("system").size());
    assertEquals(
        "ephemeral", root.path("system").get(0).path("cache_control").path("type").asText());

    // CONVERSATION: 标记在最新的合格 block (User 2 的 text)
    JsonNode lastMsgBlock = root.path("messages").get(2).path("content").get(0);
    assertEquals("ephemeral", lastMsgBlock.path("cache_control").path("type").asText());
  }

  /** 意图：Anthropic 只接受显式断点控制，AFFINITY 形态必须在发起网络请求前确定性拒绝。 */
  @Test
  void rejectsAffinityCacheControlWithoutBreakpoints() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("User"))),
            List.of(),
            ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "test-affinity"));

    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));

    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals(
        "Anthropic prompt cache control requires at least one breakpoint (SYSTEM, TOOLS, CONVERSATION)",
        error.getMessage());
  }

  @Test
  void appliesReasoningAdaptiveAndEffortWhenEnabled() throws IOException {
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());
    ModelVariant variantWithReasoning = new ModelVariant("v", "high");

    ProviderRequest req =
        new ProviderRequest(
            reasoningModel,
            variantWithReasoning,
            1024,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(req, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertEquals("adaptive", root.path("thinking").path("type").asText());
    assertEquals("summarized", root.path("thinking").path("display").asText());
    assertEquals("high", root.path("output_config").path("effort").asText());
    assertFalse(encoded.requiresInterleavedThinkingBeta());
  }

  @Test
  void appliesReasoningBudgetForSupportedEfforts() throws IOException {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // reasoning effort 精确映射为预算；max_tokens 原样使用请求输出预算。
    List<String> efforts = List.of("low", "medium", "high");
    List<Integer> outputTokensList = List.of(4096, 16384, 32768);
    List<Integer> expectedBudgets = List.of(2048, 8192, 16384);

    for (int i = 0; i < efforts.size(); i++) {
      String effort = efforts.get(i);
      int outputTokens = outputTokensList.get(i);
      int expectedBudget = expectedBudgets.get(i);

      ProviderRequest req =
          new ProviderRequest(
              reasoningModel,
              new ModelVariant("v", effort),
              outputTokens,
              "Test system instruction.",
              List.of(userMsg(new ProviderTextBlock("hi"))),
              List.of(),
              ProviderCacheControl.none());

      AnthropicEncodedRequest encoded = budgetEncoder.encode(req, descriptor);
      JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

      assertEquals("enabled", root.path("thinking").path("type").asText());
      assertEquals(expectedBudget, root.path("thinking").path("budget_tokens").asInt());
      assertEquals("summarized", root.path("thinking").path("display").asText());
      assertEquals(outputTokens, root.path("max_tokens").asInt());
      assertFalse(
          root.has("output_config"),
          "BUDGET mode must omit output_config, but was: " + root.path("output_config"));
      assertTrue(encoded.requiresInterleavedThinkingBeta());
    }
  }

  @Test
  void rejectsBudgetThatDoesNotFitRatherThanDowngradingEffort() {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // high 固定映射 16384；请求预算不足时必须拒绝，不能静默降档到 outputTokens - 1。
    ProviderRequest req =
        new ProviderRequest(
            reasoningModel,
            new ModelVariant("v", "high"),
            4096,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException error =
        assertThrows(ProviderException.class, () -> budgetEncoder.encode(req, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals("budget_tokens must be lower than max_tokens", error.getMessage());
  }

  @Test
  void rejectsBudgetWhenRequestOutputBudgetTooSmall() {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // outputTokens = 1 时无法容纳 low 固定映射的 2048 token reasoning budget。
    ProviderRequest req =
        new ProviderRequest(
            reasoningModel,
            new ModelVariant("v", "low"),
            1,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex =
        assertThrows(ProviderException.class, () -> budgetEncoder.encode(req, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals("budget_tokens must be lower than max_tokens", ex.getMessage());
  }

  @Test
  void rejectsUnsupportedEffortsInBudgetMode() throws IOException {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // off 是显式关闭，不进入 budget 映射：只发 thinking:{type:"disabled"}，不发 budget_tokens
    ProviderRequest offRequest =
        new ProviderRequest(
            reasoningModel,
            new ModelVariant("v", "off"),
            65536,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode offRoot =
        MAPPER.readTree(budgetEncoder.encode(offRequest, descriptor).bodyUtf8Bytes());
    assertEquals("disabled", offRoot.path("thinking").path("type").asText());
    assertFalse(offRoot.path("thinking").has("budget_tokens"));
    assertFalse(offRoot.has("output_config"));

    // BUDGET 模式仅支持 low/medium/high，其他厂商自定义 effort 在编码阶段被拒绝
    for (String unsupportedEffort : List.of("xhigh", "max", "arbitrary", "ultra")) {
      ProviderRequest unsupportedRequest =
          new ProviderRequest(
              reasoningModel,
              new ModelVariant("v", unsupportedEffort),
              65536,
              "Test system instruction.",
              List.of(userMsg(new ProviderTextBlock("hi"))),
              List.of(),
              ProviderCacheControl.none());
      ProviderException ex =
          assertThrows(
              ProviderException.class, () -> budgetEncoder.encode(unsupportedRequest, descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    }
  }

  @Test
  void omitsThinkingAndOutputConfigWhenReasoningNotRequestedInBudgetMode() throws IOException {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));

    // 1. model.reasoning = false 且 variant 带有 reasoningEffort
    ModelDescriptor nonReasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-5-sonnet",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            pricing());
    ModelVariant variantWithEffort = new ModelVariant("v", "high");
    ProviderRequest req1 =
        new ProviderRequest(
            nonReasoningModel,
            variantWithEffort,
            1024,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    AnthropicEncodedRequest enc1 = budgetEncoder.encode(req1, descriptor);
    JsonNode root1 = MAPPER.readTree(enc1.bodyUtf8Bytes());
    assertFalse(root1.has("thinking"));
    assertFalse(root1.has("output_config"));
    assertFalse(enc1.requiresInterleavedThinkingBeta());

    // 2. model.reasoning = true 但 variant.reasoningEffort = null
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());
    ModelVariant variantNullEffort = new ModelVariant("v");
    ProviderRequest req2 =
        new ProviderRequest(
            reasoningModel,
            variantNullEffort,
            1024,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    AnthropicEncodedRequest enc2 = budgetEncoder.encode(req2, descriptor);
    JsonNode root2 = MAPPER.readTree(enc2.bodyUtf8Bytes());
    assertFalse(root2.has("thinking"));
    assertFalse(root2.has("output_config"));
    assertFalse(enc2.requiresInterleavedThinkingBeta());

    // 3. model.reasoning = false 且 variant.reasoningEffort = null
    ProviderRequest req3 =
        new ProviderRequest(
            nonReasoningModel,
            variantNullEffort,
            1024,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    AnthropicEncodedRequest enc3 = budgetEncoder.encode(req3, descriptor);
    JsonNode root3 = MAPPER.readTree(enc3.bodyUtf8Bytes());
    assertFalse(root3.has("thinking"));
    assertFalse(root3.has("output_config"));
    assertFalse(enc3.requiresInterleavedThinkingBeta());

    // 4. model.reasoning = true 且 variant.reasoningEffort = "off" -> 显式关闭
    ProviderRequest req4 =
        new ProviderRequest(
            reasoningModel,
            new ModelVariant("v", "off"),
            1024,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    AnthropicEncodedRequest enc4 = budgetEncoder.encode(req4, descriptor);
    JsonNode root4 = MAPPER.readTree(enc4.bodyUtf8Bytes());
    assertEquals("disabled", root4.path("thinking").path("type").asText());
    assertFalse(root4.path("thinking").has("budget_tokens"));
    assertFalse(
        root4.has("output_config"), "BUDGET mode with effort='off' must omit output_config");
    assertFalse(enc4.requiresInterleavedThinkingBeta());
  }

  @Test
  void disablesThinkingInAdaptiveModeWhenReasoningEffortIsOff() throws IOException {
    AnthropicRequestEncoder adaptiveEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // variant.reasoningEffort = "off" -> 显式关闭，且不发 output_config
    ProviderRequest reqOff =
        new ProviderRequest(
            reasoningModel,
            new ModelVariant("v", "off"),
            1024,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    AnthropicEncodedRequest encOff = adaptiveEncoder.encode(reqOff, descriptor);
    JsonNode rootOff = MAPPER.readTree(encOff.bodyUtf8Bytes());
    assertEquals("disabled", rootOff.path("thinking").path("type").asText());
    assertFalse(rootOff.path("thinking").has("display"));
    assertFalse(rootOff.path("thinking").has("budget_tokens"));
    assertFalse(
        rootOff.has("output_config"), "ADAPTIVE mode with effort='off' must omit output_config");
    assertFalse(encOff.requiresInterleavedThinkingBeta());
  }

  @Test
  void adaptiveModePreservesEffortAndOmitsBudgetTokens() throws IOException {
    AnthropicRequestEncoder adaptiveEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // ADAPTIVE 模式不受 budget constraint 限制：输出预算较小也照常下发 effort
    ProviderRequest req =
        new ProviderRequest(
            reasoningModel,
            new ModelVariant("v", "high"),
            512,
            "Test system instruction.",
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = adaptiveEncoder.encode(req, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals("adaptive", root.path("thinking").path("type").asText());
    assertEquals("summarized", root.path("thinking").path("display").asText());
    assertEquals("high", root.path("output_config").path("effort").asText());
    assertEquals(512, root.path("max_tokens").asInt());
    assertFalse(root.path("thinking").has("budget_tokens"));
    assertFalse(encoded.requiresInterleavedThinkingBeta());
  }

  @Test
  void rejectsUnsupportedToolResultBlocks() {
    // unsupported block inside tool result
    ProviderRequest reqBadBlock =
        request(
            defaultVariant(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_1",
                            "name",
                            List.of(new ProviderThinkingBlock("bad")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqBadBlock, descriptor));
  }

  @Test
  void encodesToolResultWithJsonImageAndDocumentBlocks() throws IOException {
    String base64Img = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAA";
    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_1",
                            "name",
                            List.of(
                                new ProviderJsonBlock("{\"ans\":1}"),
                                new ProviderImageBlock("image/png", base64Img),
                                new ProviderDocumentBlock(
                                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjUK")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(req, descriptor);
    JsonNode toolRes =
        MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content").get(0);
    assertEquals("tool_result", toolRes.path("type").asText());
    JsonNode nested = toolRes.path("content");
    assertEquals(3, nested.size());
    assertEquals("text", nested.get(0).path("type").asText());
    assertEquals("image", nested.get(1).path("type").asText());
    assertEquals("document", nested.get(2).path("type").asText());
    // 工具结果中的媒体同样必须逐字节保留原始 base64 载荷
    assertEquals("base64", nested.get(1).path("source").path("type").asText());
    assertEquals("image/png", nested.get(1).path("source").path("media_type").asText());
    assertEquals(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAA",
        nested.get(1).path("source").path("data").asText());
    assertEquals("base64", nested.get(2).path("source").path("type").asText());
    assertEquals("application/pdf", nested.get(2).path("source").path("media_type").asText());
    assertEquals("JVBERi0xLjUK", nested.get(2).path("source").path("data").asText());
  }

  @Test
  void encodesRedactedThinkingInReplayAndDetectsMismatches() throws IOException {
    ProviderMessage userMsg = userMsg(new ProviderTextBlock("hi"));

    String expectedHash =
        AnthropicPrefixHasher.calculateHash(
            systemBlocks(),
            NODES.arrayNode(),
            NODES
                .arrayNode()
                .add(
                    NODES
                        .objectNode()
                        .put("role", "user")
                        .set(
                            "content",
                            NODES
                                .arrayNode()
                                .add(NODES.objectNode().put("type", "text").put("text", "hi")))));

    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ArrayNode content = payload.putArray("content");
    content.addObject().put("type", "redacted_thinking").put("data", "abc==");
    content.addObject().put("type", "text").put("text", "response text");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            payload);

    ProviderMessage assistantMsg =
        asstMsg(List.of(new ProviderTextBlock("response text")), replayState);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg, assistantMsg),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);
    assertEquals("redacted_thinking", asstWire.path("content").get(0).path("type").asText());
    assertEquals("abc==", asstWire.path("content").get(0).path("data").asText());
  }

  @Test
  void rejectsMalformedAssistantToolCallInFallback() {
    ProviderMessage badToolCallMsg =
        asstMsg(
            List.of(new ProviderToolCallBlock(new ProviderToolCall("call_1", "calc", "not-json"))),
            null);
    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi")), badToolCallMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(req, descriptor));
  }

  @Test
  void rejectsUnsupportedBlocksAcrossRoles() {
    // USER 包含 ThinkingBlock
    ProviderRequest badUser =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderThinkingBlock("think"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(badUser, descriptor));
  }

  @Test
  void encodesToolResultWithErrorFlag() throws IOException {
    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_err",
                            "name",
                            List.of(new ProviderTextBlock("Error message")),
                            true,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(req, descriptor);
    JsonNode toolRes =
        MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(0).path("content").get(0);
    assertEquals(true, toolRes.path("is_error").asBoolean());
  }

  @Test
  void skipsThinkingBlocksWhenApplyingConversationCacheMarker() throws IOException {
    String hash =
        AnthropicPrefixHasher.calculateHash(
            systemBlocks(),
            NODES.arrayNode(),
            NODES
                .arrayNode()
                .add(
                    NODES
                        .objectNode()
                        .put("role", "user")
                        .set(
                            "content",
                            NODES
                                .arrayNode()
                                .add(
                                    NODES
                                        .objectNode()
                                        .put("type", "text")
                                        .put("text", "Text before")))));

    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    payload
        .putArray("content")
        .addObject()
        .put("type", "thinking")
        .put("thinking", "Last thinking")
        .put("signature", "sig-valid");

    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            hash,
            payload);

    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("Text before")),
                asstMsg(List.of(new ProviderThinkingBlock("Last thinking")), replay)),
            List.of(),
            new ProviderCacheControl(
                PromptCacheRetention.SHORT, "aff", Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(req, descriptor);
    JsonNode messages = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages");

    // 最后一个 assistant 消息全是 thinking，cache_control 应该跳过它并标记在倒数第二条的 Text before 上
    JsonNode lastBlock = messages.get(1).path("content").get(0);
    assertEquals("thinking", lastBlock.path("type").asText());
    assertFalse(lastBlock.has("cache_control"));

    JsonNode beforeBlock = messages.get(0).path("content").get(0);
    assertTrue(beforeBlock.has("cache_control"));
  }

  @Test
  void handlesReplayPayloadValidationMismatches() throws IOException {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));

    String expectedHash =
        AnthropicPrefixHasher.calculateHash(
            systemBlocks(),
            NODES.arrayNode(),
            NODES
                .arrayNode()
                .add(
                    NODES
                        .objectNode()
                        .put("role", "user")
                        .set(
                            "content",
                            NODES
                                .arrayNode()
                                .add(NODES.objectNode().put("type", "text").put("text", "hi")))));

    // 1. payload contains bad tool_use without input object
    ObjectNode badPayload1 = NODES.objectNode();
    badPayload1.put("role", "assistant");
    ArrayNode content1 = badPayload1.putArray("content");
    content1.addObject().put("type", "tool_use").put("id", "c1").put("name", "calc"); // 缺少 input
    ProviderReplayState replay1 =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            badPayload1);
    ProviderMessage asst1 =
        asstMsg(
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))), replay1);
    ProviderRequest req1 =
        request(defaultVariant(), List.of(user, asst1), List.of(), ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. payload contains unknown block type
    ObjectNode badPayload2 = NODES.objectNode();
    badPayload2.put("role", "assistant");
    badPayload2.putArray("content").addObject().put("type", "unknown_type");
    ProviderReplayState replay2 =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            badPayload2);
    ProviderMessage asst2 = asstMsg(List.of(new ProviderTextBlock("text")), replay2);
    ProviderRequest req2 =
        request(defaultVariant(), List.of(user, asst2), List.of(), ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
  }

  @Test
  void rejectsAssistantToolCallWithNonObjectJsonInFallback() {
    ProviderMessage badAsst =
        asstMsg(
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "[1, 2, 3]"))),
            null);
    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi")), badAsst),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(req, descriptor));
  }

  @Test
  void rejectsUnsupportedAssistantBlockInFallback() {
    ProviderMessage badAsst =
        asstMsg(List.of(new ProviderImageBlock("image/png", "https://ex.com/a.png")), null);
    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi")), badAsst),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(req, descriptor));
  }

  @Test
  void rejectsExceedingMaxRequestBodyLimit() {
    String hugeText = "A".repeat(33 * 1024 * 1024);
    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock(hugeText))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(req, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("exceeds"));
  }

  @Test
  void rejectsDocumentWithInvalidSourceUri() {
    ProviderRequest req =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderDocumentBlock("application/pdf", "file:///local.pdf"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(req, descriptor));
  }

  @Test
  void handlesReplayTextThinkingOrToolMismatches() throws IOException {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));

    String expectedHash =
        AnthropicPrefixHasher.calculateHash(
            systemBlocks(),
            NODES.arrayNode(),
            NODES
                .arrayNode()
                .add(
                    NODES
                        .objectNode()
                        .put("role", "user")
                        .set(
                            "content",
                            NODES
                                .arrayNode()
                                .add(NODES.objectNode().put("type", "text").put("text", "hi")))));

    // 1. payloadText mismatch
    ObjectNode payloadTextMismatch = NODES.objectNode();
    payloadTextMismatch.put("role", "assistant");
    payloadTextMismatch.putArray("content").addObject().put("type", "text").put("text", "A");
    ProviderReplayState replayText =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            payloadTextMismatch);
    ProviderMessage asstText = asstMsg(List.of(new ProviderTextBlock("B")), replayText);
    ProviderRequest req1 =
        request(defaultVariant(), List.of(user, asstText), List.of(), ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. payloadThinking mismatch
    ObjectNode payloadThinkMismatch = NODES.objectNode();
    payloadThinkMismatch.put("role", "assistant");
    payloadThinkMismatch
        .putArray("content")
        .addObject()
        .put("type", "thinking")
        .put("thinking", "think A")
        .put("signature", "sig");
    ProviderReplayState replayThink =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            payloadThinkMismatch);
    ProviderMessage asstThink = asstMsg(List.of(new ProviderThinkingBlock("think B")), replayThink);
    ProviderRequest req2 =
        request(defaultVariant(), List.of(user, asstThink), List.of(), ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());

    // 3. tool call args mismatch
    ObjectNode payloadToolMismatch = NODES.objectNode();
    payloadToolMismatch.put("role", "assistant");
    ArrayNode toolContent = payloadToolMismatch.putArray("content");
    ObjectNode toolObj = toolContent.addObject();
    toolObj.put("type", "tool_use").put("id", "c1").put("name", "calc");
    toolObj.putObject("input").put("x", 1);
    ProviderReplayState replayTool =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            payloadToolMismatch);
    ProviderMessage asstTool =
        asstMsg(
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{\"x\":2}"))),
            replayTool);
    ProviderRequest req3 =
        request(defaultVariant(), List.of(user, asstTool), List.of(), ProviderCacheControl.none());
    ProviderException ex3 =
        assertThrows(ProviderException.class, () -> encoder.encode(req3, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());
  }

  @Test
  void rejectsReplayStateMissingAssistantRoleOrHavingExtraProperties() throws IOException {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));
    String expectedHash =
        AnthropicPrefixHasher.calculateHash(
            systemBlocks(),
            NODES.arrayNode(),
            NODES
                .arrayNode()
                .add(
                    NODES
                        .objectNode()
                        .put("role", "user")
                        .set(
                            "content",
                            NODES
                                .arrayNode()
                                .add(NODES.objectNode().put("type", "text").put("text", "hi")))));

    // 缺少 role: assistant
    ObjectNode noRolePayload = NODES.objectNode();
    noRolePayload.putArray("content").addObject().put("type", "text").put("text", "hi");
    ProviderReplayState replayNoRole =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            noRolePayload);
    ProviderMessage asst1 = asstMsg(List.of(new ProviderTextBlock("hi")), replayNoRole);
    ProviderRequest req1 =
        request(defaultVariant(), List.of(user, asst1), List.of(), ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 带有额外未授权字段 (size != 2)
    ObjectNode extraPropPayload = NODES.objectNode();
    extraPropPayload.put("role", "assistant");
    extraPropPayload.put("extra_field", "forbidden");
    extraPropPayload.putArray("content").addObject().put("type", "text").put("text", "hi");
    ProviderReplayState replayExtra =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            expectedHash,
            extraPropPayload);
    ProviderMessage asst2 = asstMsg(List.of(new ProviderTextBlock("hi")), replayExtra);
    ProviderRequest req2 =
        request(defaultVariant(), List.of(user, asst2), List.of(), ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
  }

  @Test
  void encodesAssistantJsonBlockAsTextBlockInSemanticFallback() throws IOException {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));
    // Assistant 消息带有 ProviderJsonBlock（例如来自截断诊断序列化）
    ProviderMessage asstWithJson =
        asstMsg(List.of(new ProviderJsonBlock("{\"diagnostic\":\"truncated\"}")), null);

    AnthropicEncodedRequest encoded =
        encoder.encode(
            request(
                defaultVariant(),
                List.of(user, asstWithJson),
                List.of(),
                ProviderCacheControl.none()),
            descriptor);

    JsonNode content =
        MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1).path("content").get(0);
    assertEquals("text", content.path("type").asText());
    assertEquals("{\"diagnostic\":\"truncated\"}", content.path("text").asText());
  }

  @Test
  void rejectsInvalidBase64PayloadInImageAndDocument() {
    // 带有非法 base64 字符（如 @@##）的数据 URI
    ProviderRequest reqImage =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderImageBlock("image/png", "data:image/png;base64,invalid@@##"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqImage, descriptor));

    // 空 base64 数据
    ProviderRequest reqDocEmpty =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderDocumentBlock("application/pdf", "data:application/pdf;base64,"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqDocEmpty, descriptor));
  }

  @Test
  void verifiesSemanticChainFromLengthDiagnosticToAssistantTextBlockInNextRound()
      throws IOException {
    // 1. 构造一个 LENGTH 导致的 ProviderResponse，包含 toolCallDiagnostic（因截断缺失完整参数，replayState 为 null）
    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(
            0,
            "call_weather",
            "get_weather",
            null,
            "tool call truncated before arguments received");

    ProviderResponse lengthResponse =
        new ProviderResponse(
            "I was trying to call weather but got cut off",
            "",
            List.of(),
            GenerationStopReason.LENGTH,
            new ModelUsage(10, 15, 0, 0, 0, 0, 25),
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            "msg_len_1",
            null,
            "{}",
            List.of(diagnostic));

    // 2. 使用 HistoryPayloadMapper 将 ProviderResponse 投影为 Assistant MessagePayload
    HistoryPayloadMapper mapper = new HistoryPayloadMapper();
    MessagePayload assistantPayload = mapper.assistantPayload(lengthResponse, List.of());
    AgentMessage asstAgentMsg = assistantPayload.message();

    // 3. 使用 ProviderMessageProjector 将 Session 历史投影为 ProviderMessage
    AgentMessage userAgentMsg =
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent("What is the weather?")));
    ProviderMessageProjector projector = new ProviderMessageProjector(Set.of());
    List<ProviderMessage> projectedMessages =
        projector.project(List.of(userAgentMsg, asstAgentMsg));

    // 4. 构造下一轮的新请求并交由 AnthropicRequestEncoder 编码
    ProviderRequest nextRoundRequest =
        request(defaultVariant(), projectedMessages, List.of(), ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(nextRoundRequest, descriptor);
    assertNotNull(encoded);

    // 5. 断言下一轮不会报 INVALID_REQUEST，且 assistant diagnostic 被安全降级为普通 text block，文本严格等于 codec JSON
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode assistantContent = root.path("messages").get(1).path("content");
    assertEquals(2, assistantContent.size());

    // content 0: text
    assertEquals("text", assistantContent.get(0).path("type").asText());
    assertEquals(
        "I was trying to call weather but got cut off",
        assistantContent.get(0).path("text").asText());

    // content 1: diagnostic JSON 物化为普通 text block
    assertEquals("text", assistantContent.get(1).path("type").asText());
    String diagnosticJsonText = assistantContent.get(1).path("text").asText();
    ProviderToolCallDiagnosticJsonCodec codec = new ProviderToolCallDiagnosticJsonCodec();
    ProviderToolCallDiagnostic decodedDiag = codec.decode(diagnosticJsonText);
    assertEquals(diagnostic.callIndex(), decodedDiag.callIndex());
    assertEquals(diagnostic.id(), decodedDiag.id());
    assertEquals(diagnostic.name(), decodedDiag.name());
    assertEquals("", decodedDiag.partialArguments());
    assertEquals(diagnostic.message(), decodedDiag.message());
  }

  private static ProviderRequest request(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    ModelDescriptor model =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-5-sonnet",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
            true,
            false,
            pricing());
    return new ProviderRequest(
        model, variant, 1024, SYSTEM_INSTRUCTION, messages, tools, cacheControl);
  }

  private static ProviderMessage userMsg(ProviderContentBlock... blocks) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(blocks));
  }

  /** 与编码器冻结前缀哈希时一致的唯一系统指令块（未打 cache 标记）。 */
  private static ArrayNode systemBlocks() {
    ArrayNode system = NODES.arrayNode();
    system.addObject().put("type", "text").put("text", SYSTEM_INSTRUCTION);
    return system;
  }

  private static ProviderMessage asstMsg(
      List<ProviderContentBlock> blocks, ProviderReplayState replayState) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks, replayState);
  }

  private static ModelVariant defaultVariant() {
    return new ModelVariant("default");
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        BigDecimal.ONE,
        "v1",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  /**
   * 意图：验证同 format (ANTHROPIC_MESSAGES) 回放时，即使 affinity 或 sourcePrefixHash 失配，如果 payload 损坏或与
   * durable 内容不一致，必须严格抛出 INVALID_REQUEST，杜绝被判定为可 fallback。
   */
  @Test
  void rejectsCorruptedSameFormatReplayEvenUnderAffinityOrHashMismatch() {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));
    String mismatchedHash = "0000000000000000000000000000000000000000000000000000000000000002";

    // 1. hash 不匹配且 tool_use input 缺失
    ObjectNode badPayload = NODES.objectNode();
    badPayload.put("role", "assistant");
    badPayload
        .putArray("content")
        .addObject()
        .put("type", "tool_use")
        .put("id", "c1")
        .put("name", "calc");
    ProviderReplayState replay1 =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            mismatchedHash,
            badPayload);
    ProviderMessage asst1 =
        asstMsg(
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{\"x\":1}"))),
            replay1);
    ProviderRequest req1 =
        request(defaultVariant(), List.of(user, asst1), List.of(), ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. affinity 不匹配且 payload 文本与 durable 不一致
    ProviderReplayAffinity mismatchedAffinity = descriptor.affinity("other-model");
    ObjectNode textMismatchPayload = NODES.objectNode();
    textMismatchPayload.put("role", "assistant");
    textMismatchPayload
        .putArray("content")
        .addObject()
        .put("type", "text")
        .put("text", "mismatched");
    ProviderReplayState replay2 =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            mismatchedAffinity,
            mismatchedHash,
            textMismatchPayload);
    ProviderMessage asst2 = asstMsg(List.of(new ProviderTextBlock("original")), replay2);
    ProviderRequest req2 =
        request(defaultVariant(), List.of(user, asst2), List.of(), ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
  }

  /**
   * 意图：验证工具调用参数 arguments 为畸形、数组、标量或 JSON null 时，在 semantic fallback 和 durable 回放校验中均抛出
   * INVALID_REQUEST，且异常消息绝不泄漏原始参数。
   */
  @Test
  void rejectsInvalidToolCallArgumentsStrictlyWithoutLeakingRawValues() {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));

    List<String> invalidArguments =
        List.of("12345", "\"scalar_string\"", "true", "[1, 2, 3]", "{\"unclosed\":", "null");

    for (String badArg : invalidArguments) {
      // 1. Fallback 场景（无 replayState）
      ProviderMessage asstFallback =
          asstMsg(
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))), null);
      ProviderRequest reqFallback =
          request(
              defaultVariant(),
              List.of(user, asstFallback),
              List.of(),
              ProviderCacheControl.none());
      ProviderException exFallback =
          assertThrows(ProviderException.class, () -> encoder.encode(reqFallback, descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exFallback.kind());
      assertNull(exFallback.getCause());
      assertFalse(exFallback.getMessage().contains(badArg));

      // 2. 同 format Replay 场景（durable 包含非法参数）
      ObjectNode validPayload = NODES.objectNode();
      validPayload.put("role", "assistant");
      ObjectNode toolNode = validPayload.putArray("content").addObject();
      toolNode.put("type", "tool_use").put("id", "c1").put("name", "calc");
      toolNode.putObject("input");
      ProviderReplayState replay =
          new ProviderReplayState(
              ProviderReplayFormat.ANTHROPIC_MESSAGES,
              descriptor.affinity("claude-3-5-sonnet"),
              "0000000000000000000000000000000000000000000000000000000000000000",
              validPayload);
      ProviderMessage asstReplay =
          asstMsg(
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))),
              replay);
      ProviderRequest reqReplay =
          request(
              defaultVariant(), List.of(user, asstReplay), List.of(), ProviderCacheControl.none());
      ProviderException exReplay =
          assertThrows(ProviderException.class, () -> encoder.encode(reqReplay, descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exReplay.kind());
      assertNull(exReplay.getCause());
      assertFalse(exReplay.getMessage().contains(badArg));
    }

    // 3. Replay payload 中的 tool_use.input 为非 Object（如数组、标量）
    ObjectNode payloadArrayInput = NODES.objectNode();
    payloadArrayInput.put("role", "assistant");
    ObjectNode toolArrayNode = payloadArrayInput.putArray("content").addObject();
    toolArrayNode.put("type", "tool_use").put("id", "c1").put("name", "calc");
    toolArrayNode.putArray("input").add(1).add(2);
    ProviderReplayState replayArray =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            "0000000000000000000000000000000000000000000000000000000000000000",
            payloadArrayInput);
    ProviderMessage asstArrayInput =
        asstMsg(
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))),
            replayArray);
    ProviderRequest reqArrayInput =
        request(
            defaultVariant(),
            List.of(user, asstArrayInput),
            List.of(),
            ProviderCacheControl.none());
    ProviderException exArray =
        assertThrows(ProviderException.class, () -> encoder.encode(reqArrayInput, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exArray.kind());
  }

  /** 意图：验证不同 format (例如 OPENAI_CHAT) 的 replayState 能够安全降级为 semantic fallback 而不被同格式校验拦截。 */
  @Test
  void fallbackWhenReplayFormatDiffers() throws IOException {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));
    ObjectNode openAiPayload = NODES.objectNode();
    openAiPayload.put("role", "assistant").put("content", "hello from openai");
    ProviderReplayState differentFormatReplay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("claude-3-5-sonnet"),
            "0000000000000000000000000000000000000000000000000000000000000000",
            openAiPayload);

    ProviderMessage asst =
        asstMsg(
            List.of(
                new ProviderTextBlock("fallback response"),
                new ProviderToolCallBlock(new ProviderToolCall("call_1", "calc", "{\"a\":1}"))),
            differentFormatReplay);
    ProviderRequest req =
        request(defaultVariant(), List.of(user, asst), List.of(), ProviderCacheControl.none());
    AnthropicEncodedRequest encoded = encoder.encode(req, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);

    assertEquals("text", asstWire.path("content").get(0).path("type").asText());
    assertEquals("fallback response", asstWire.path("content").get(0).path("text").asText());
    assertEquals("tool_use", asstWire.path("content").get(1).path("type").asText());
    assertEquals("call_1", asstWire.path("content").get(1).path("id").asText());
    assertEquals(1, asstWire.path("content").get(1).path("input").path("a").asInt());
  }

  /** 意图：wire 根字段 model 始终取 ModelDescriptor.modelId，逻辑名与 modelId 可不同且不由配置映射。 */
  @Test
  void encodesWireModelFromDescriptorModelId() throws IOException {
    // MiniMax-M3 逻辑名对应的真实 wire modelId
    ProviderRequest reqMapped =
        requestWithModelId(
            "MiniMax-M3",
            "claude-fable-5-dd-3M-xaMiniM",
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("test"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootMapped = MAPPER.readTree(encoder.encode(reqMapped, descriptor).bodyUtf8Bytes());
    assertEquals("claude-fable-5-dd-3M-xaMiniM", rootMapped.path("model").asText());

    // 逻辑名与 modelId 相同时原样下发
    ProviderRequest reqSame =
        requestWithModel(
            "claude-3-5-sonnet",
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("test"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootSame = MAPPER.readTree(encoder.encode(reqSame, descriptor).bodyUtf8Bytes());
    assertEquals("claude-3-5-sonnet", rootSame.path("model").asText());
  }

  /** 意图：回放亲和性（replay affinity）严格绑定真实 wire modelId，逻辑名不参与亲和性判定。 */
  @Test
  void bindsReplayAffinityToWireModelId() throws IOException {
    // 构建前缀并计算 canonical prefix hash
    ProviderMessage user1 = userMsg(new ProviderTextBlock("question 1"));
    ArrayNode priorMessages = NODES.arrayNode();
    ObjectNode userWire = NODES.objectNode();
    userWire.put("role", "user");
    userWire.putArray("content").addObject().put("type", "text").put("text", "question 1");
    priorMessages.add(userWire);
    String prefixHash = AnthropicPrefixHasher.calculateHash(systemBlocks(), null, priorMessages);

    // Assistant 携带针对 wire modelId 的亲和性与有效 payload（包含 thinking 块与签名）
    ObjectNode anthropicPayload = NODES.objectNode();
    anthropicPayload.put("role", "assistant");
    ArrayNode content = anthropicPayload.putArray("content");
    content
        .addObject()
        .put("type", "thinking")
        .put("thinking", "deep reasoning")
        .put("signature", "sig_valid");
    content.addObject().put("type", "text").put("text", "replayed answer");
    content
        .addObject()
        .put("type", "tool_use")
        .put("id", "call_goal")
        .put("name", "get_goal")
        .set("input", NODES.objectNode());

    ProviderReplayState wireReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-fable-5-dd-3M-xaMiniM"),
            prefixHash,
            anthropicPayload);

    List<ProviderContentBlock> durableBlocks =
        List.of(
            new ProviderThinkingBlock("deep reasoning"),
            new ProviderTextBlock("replayed answer"),
            new ProviderToolCallBlock(new ProviderToolCall("call_goal", "get_goal", "{}")));

    ProviderMessage asst = asstMsg(durableBlocks, wireReplayState);

    ProviderMessage toolResult =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_goal",
                    "get_goal",
                    List.<ProviderContentBlock>of(new ProviderTextBlock("ok")),
                    false,
                    "{}")));

    ProviderRequest request =
        requestWithModelId(
            "MiniMax-M3",
            "claude-fable-5-dd-3M-xaMiniM",
            defaultVariant(),
            List.of(user1, asst, toolResult),
            List.of(),
            ProviderCacheControl.none());

    // 编码请求
    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    // 1. Wire 根字段 model 发出真实 modelId
    assertEquals("claude-fable-5-dd-3M-xaMiniM", root.path("model").asText());

    // 2. 历史 Assistant 消息由于逻辑亲和性完全吻合，成功采用 payload 原生回放（保留 thinking 块与 signature）
    JsonNode wireAsst = root.path("messages").get(1);
    assertEquals("assistant", wireAsst.path("role").asText());
    assertEquals("thinking", wireAsst.path("content").get(0).path("type").asText());
    assertEquals("deep reasoning", wireAsst.path("content").get(0).path("thinking").asText());
    assertEquals("sig_valid", wireAsst.path("content").get(0).path("signature").asText());
    assertEquals("text", wireAsst.path("content").get(1).path("type").asText());
    assertEquals("replayed answer", wireAsst.path("content").get(1).path("text").asText());
    assertEquals("tool_use", wireAsst.path("content").get(2).path("type").asText());
    assertEquals("call_goal", wireAsst.path("content").get(2).path("id").asText());

    // 3. 对比：如果 replayState 亲和性错误绑定到了逻辑模型名，则亲和性校验失败并降级为 semantic fallback（thinking 降级为
    // text，丢失 signature）
    ProviderReplayState logicalAffinityState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("MiniMax-M3"),
            prefixHash,
            anthropicPayload);
    ProviderMessage asstMismatch = asstMsg(durableBlocks, logicalAffinityState);
    ProviderRequest requestMismatch =
        requestWithModelId(
            "MiniMax-M3",
            "claude-fable-5-dd-3M-xaMiniM",
            defaultVariant(),
            List.of(user1, asstMismatch, toolResult),
            List.of(),
            ProviderCacheControl.none());
    AnthropicEncodedRequest encodedMismatch = encoder.encode(requestMismatch, descriptor);
    JsonNode wireAsstMismatch =
        MAPPER.readTree(encodedMismatch.bodyUtf8Bytes()).path("messages").get(1);
    assertEquals("text", wireAsstMismatch.path("content").get(0).path("type").asText());
    assertEquals("deep reasoning", wireAsstMismatch.path("content").get(0).path("text").asText());
    assertFalse(wireAsstMismatch.path("content").get(0).has("signature"));
  }

  private static ProviderRequest requestWithModelId(
      String modelName,
      String modelId,
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    ModelDescriptor model =
        new ModelDescriptor(
            "test-anthropic",
            modelName,
            modelId,
            Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
            true,
            false,
            pricing());
    return new ProviderRequest(
        model, variant, 1024, SYSTEM_INSTRUCTION, messages, tools, cacheControl);
  }

  private static ProviderRequest requestWithModel(
      String modelName,
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    ModelDescriptor model =
        new ModelDescriptor(
            "test-anthropic",
            modelName,
            modelName,
            Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
            true,
            false,
            pricing());
    return new ProviderRequest(
        model, variant, 1024, SYSTEM_INSTRUCTION, messages, tools, cacheControl);
  }
}

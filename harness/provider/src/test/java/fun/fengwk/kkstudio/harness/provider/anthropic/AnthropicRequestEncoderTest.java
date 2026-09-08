package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
  void overridesMaxTokensAndSamplingParametersFromVariant() throws IOException {
    ModelVariant variant =
        new ModelVariant("custom", 2048, 0.7, 0.9, 40, null, null, List.of("STOP_HERE"), null);
    ProviderRequest request =
        request(
            variant,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertEquals(2048, root.path("max_tokens").asInt());
    assertEquals(0.7, root.path("temperature").asDouble(), 0.001);
    assertEquals(0.9, root.path("top_p").asDouble(), 0.001);
    assertEquals(40, root.path("top_k").asInt());
    assertEquals("STOP_HERE", root.path("stop_sequences").get(0).asText());
  }

  @Test
  void rejectsPenaltiesWithInvalidRequest() {
    ModelVariant freq =
        new ModelVariant("freq", null, null, null, null, 0.5, null, List.of(), null);
    ProviderRequest req1 =
        request(
            freq,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    ModelVariant pres =
        new ModelVariant("pres", null, null, null, null, null, 0.5, List.of(), null);
    ProviderRequest req2 =
        request(
            pres,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
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
  void extractsLeadingSystemAndRejectsMidConversationSystem() throws IOException {
    // leading system
    ProviderRequest validSys =
        request(
            defaultVariant(),
            List.of(
                sysMsg(new ProviderTextBlock("You are helpful")),
                userMsg(new ProviderTextBlock("hello"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest enc = encoder.encode(validSys, descriptor);
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());
    assertEquals(1, root.path("system").size());
    assertEquals("You are helpful", root.path("system").get(0).path("text").asText());
    assertEquals(1, root.path("messages").size());

    // mid-conversation system
    ProviderRequest midSys =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("hello")),
                sysMsg(new ProviderTextBlock("late system"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(midSys, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
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
    // 1: base64 image
    assertEquals("image", contents.get(1).path("type").asText());
    assertEquals("base64", contents.get(1).path("source").path("type").asText());
    assertEquals("image/png", contents.get(1).path("source").path("media_type").asText());
    // 2: url image
    assertEquals("image", contents.get(2).path("type").asText());
    assertEquals("url", contents.get(2).path("source").path("type").asText());
    assertEquals(
        "https://example.com/test.jpg", contents.get(2).path("source").path("url").asText());
    // 3: base64 document
    assertEquals("document", contents.get(3).path("type").asText());
    assertEquals("base64", contents.get(3).path("source").path("type").asText());
    assertEquals("application/pdf", contents.get(3).path("source").path("media_type").asText());
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

  @Test
  void encodesSystemMessageWithTextAndPdfDocument() throws IOException {
    String base64Pdf = "data:application/pdf;base64,JVBERi0xLjUK";
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(
                        new ProviderTextBlock("system prompt"),
                        new ProviderDocumentBlock("application/pdf", base64Pdf))),
                userMsg(new ProviderTextBlock("user message"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode systemNode = root.path("system");
    assertEquals(2, systemNode.size());
    assertEquals("text", systemNode.get(0).path("type").asText());
    assertEquals("system prompt", systemNode.get(0).path("text").asText());
    assertEquals("document", systemNode.get(1).path("type").asText());
    assertEquals("base64", systemNode.get(1).path("source").path("type").asText());
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
            NODES.arrayNode(),
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
                sysMsg(new ProviderTextBlock("Sys 1"), new ProviderTextBlock("Sys 2")),
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

    // SYSTEM: 标记在最后一个 system block
    assertFalse(root.path("system").get(0).has("cache_control"));
    assertEquals(
        "ephemeral", root.path("system").get(1).path("cache_control").path("type").asText());

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
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());
    ModelVariant variantWithReasoning =
        new ModelVariant("v", null, null, null, null, null, null, List.of(), "high");

    ProviderRequest req =
        new ProviderRequest(
            reasoningModel,
            variantWithReasoning,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(req, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertEquals("adaptive", root.path("thinking").path("type").asText());
    assertEquals("high", root.path("output_config").path("effort").asText());
  }

  @Test
  void appliesReasoningBudgetForSupportedEfforts() throws IOException {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    List<String> efforts = List.of("minimal", "low", "medium", "high");
    List<Integer> expectedBudgets = List.of(1024, 2048, 8192, 16384);
    List<Integer> maxTokensList = List.of(2048, 4096, 16384, 32768);

    for (int i = 0; i < efforts.size(); i++) {
      String effort = efforts.get(i);
      int expectedBudget = expectedBudgets.get(i);
      int maxTokens = maxTokensList.get(i);

      ModelVariant variant =
          new ModelVariant("v", maxTokens, null, null, null, null, null, List.of(), effort);
      ProviderRequest req =
          new ProviderRequest(
              reasoningModel,
              variant,
              List.of(userMsg(new ProviderTextBlock("hi"))),
              List.of(),
              ProviderCacheControl.none());

      AnthropicEncodedRequest encoded = budgetEncoder.encode(req, descriptor);
      JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

      assertEquals("enabled", root.path("thinking").path("type").asText());
      assertEquals(expectedBudget, root.path("thinking").path("budget_tokens").asInt());
      assertFalse(
          root.has("output_config"),
          "BUDGET mode must omit output_config, but was: " + root.path("output_config"));
    }
  }

  @Test
  void rejectsBudgetWhenBudgetTokensNotStrictlyLowerThanMaxTokens() {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // 1. 缺省 max_tokens 为 1024，minimal 映射为 1024：1024 >= 1024 拒绝
    ModelVariant variantDefaultMax =
        new ModelVariant("v", null, null, null, null, null, null, List.of(), "minimal");
    ProviderRequest req1 =
        new ProviderRequest(
            reasoningModel,
            variantDefaultMax,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> budgetEncoder.encode(req1, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());
    assertEquals("budget_tokens must be strictly lower than max_tokens", ex1.getMessage());

    // 2. 显式 max_tokens = 2048，low 映射为 2048：2048 >= 2048 拒绝
    ModelVariant variantEqual =
        new ModelVariant("v", 2048, null, null, null, null, null, List.of(), "low");
    ProviderRequest req2 =
        new ProviderRequest(
            reasoningModel,
            variantEqual,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> budgetEncoder.encode(req2, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
    assertEquals("budget_tokens must be strictly lower than max_tokens", ex2.getMessage());

    // 3. 显式 max_tokens = 4096，high 映射为 16384：16384 >= 4096 拒绝
    ModelVariant variantLower =
        new ModelVariant("v", 4096, null, null, null, null, null, List.of(), "high");
    ProviderRequest req3 =
        new ProviderRequest(
            reasoningModel,
            variantLower,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex3 =
        assertThrows(ProviderException.class, () -> budgetEncoder.encode(req3, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());
    assertEquals("budget_tokens must be strictly lower than max_tokens", ex3.getMessage());
  }

  @Test
  void rejectsUnsupportedEffortsInBudgetMode() {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    for (String unsupportedEffort : List.of("xhigh", "max", "arbitrary", "ultra")) {
      ModelVariant variant =
          new ModelVariant("v", 65536, null, null, null, null, null, List.of(), unsupportedEffort);
      ProviderRequest req =
          new ProviderRequest(
              reasoningModel,
              variant,
              List.of(userMsg(new ProviderTextBlock("hi"))),
              List.of(),
              ProviderCacheControl.none());

      ProviderException ex =
          assertThrows(ProviderException.class, () -> budgetEncoder.encode(req, descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertEquals("unsupported reasoning effort for budget thinking", ex.getMessage());
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
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            pricing());
    ModelVariant variantWithEffort =
        new ModelVariant("v", 4096, null, null, null, null, null, List.of(), "high");
    ProviderRequest req1 =
        new ProviderRequest(
            nonReasoningModel,
            variantWithEffort,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root1 = MAPPER.readTree(budgetEncoder.encode(req1, descriptor).bodyUtf8Bytes());
    assertFalse(root1.has("thinking"));
    assertFalse(root1.has("output_config"));

    // 2. model.reasoning = true 但 variant.reasoningEffort = null
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());
    ModelVariant variantNullEffort =
        new ModelVariant("v", 4096, null, null, null, null, null, List.of(), null);
    ProviderRequest req2 =
        new ProviderRequest(
            reasoningModel,
            variantNullEffort,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root2 = MAPPER.readTree(budgetEncoder.encode(req2, descriptor).bodyUtf8Bytes());
    assertFalse(root2.has("thinking"));
    assertFalse(root2.has("output_config"));

    // 3. model.reasoning = false 且 variant.reasoningEffort = null
    ProviderRequest req3 =
        new ProviderRequest(
            nonReasoningModel,
            variantNullEffort,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root3 = MAPPER.readTree(budgetEncoder.encode(req3, descriptor).bodyUtf8Bytes());
    assertFalse(root3.has("thinking"));
    assertFalse(root3.has("output_config"));

    // 4. model.reasoning = true 且 variant.reasoningEffort = "none"
    ModelVariant variantNoneEffort =
        new ModelVariant("v", 4096, null, null, null, null, null, List.of(), "none");
    ProviderRequest req4 =
        new ProviderRequest(
            reasoningModel,
            variantNoneEffort,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root4 = MAPPER.readTree(budgetEncoder.encode(req4, descriptor).bodyUtf8Bytes());
    assertFalse(root4.has("thinking"), "BUDGET mode with effort='none' must omit thinking");
    assertFalse(
        root4.has("output_config"), "BUDGET mode with effort='none' must omit output_config");

    // 5. model.reasoning = true 且 variant.reasoningEffort 为纯空白
    ModelVariant variantBlankEffort =
        new ModelVariant("v", 4096, null, null, null, null, null, List.of(), "   ");
    ProviderRequest req5 =
        new ProviderRequest(
            reasoningModel,
            variantBlankEffort,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root5 = MAPPER.readTree(budgetEncoder.encode(req5, descriptor).bodyUtf8Bytes());
    assertFalse(root5.has("thinking"), "BUDGET mode with blank effort must omit thinking");
    assertFalse(
        root5.has("output_config"), "BUDGET mode with blank effort must omit output_config");
  }

  @Test
  void omitsThinkingAndOutputConfigWhenReasoningEffortIsNoneOrBlankInAdaptiveMode()
      throws IOException {
    AnthropicRequestEncoder adaptiveEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // 1. variant.reasoningEffort = "none"
    ModelVariant variantNone =
        new ModelVariant("v", 4096, null, null, null, null, null, List.of(), "none");
    ProviderRequest reqNone =
        new ProviderRequest(
            reasoningModel,
            variantNone,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootNone =
        MAPPER.readTree(adaptiveEncoder.encode(reqNone, descriptor).bodyUtf8Bytes());
    assertFalse(rootNone.has("thinking"), "ADAPTIVE mode with effort='none' must omit thinking");
    assertFalse(
        rootNone.has("output_config"), "ADAPTIVE mode with effort='none' must omit output_config");

    // 2. variant.reasoningEffort = "  "
    ModelVariant variantBlank =
        new ModelVariant("v", 4096, null, null, null, null, null, List.of(), "  ");
    ProviderRequest reqBlank =
        new ProviderRequest(
            reasoningModel,
            variantBlank,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootBlank =
        MAPPER.readTree(adaptiveEncoder.encode(reqBlank, descriptor).bodyUtf8Bytes());
    assertFalse(rootBlank.has("thinking"), "ADAPTIVE mode with blank effort must omit thinking");
    assertFalse(
        rootBlank.has("output_config"), "ADAPTIVE mode with blank effort must omit output_config");
  }

  @Test
  void adaptiveModePreservesEffortAndOmitsBudgetTokens() throws IOException {
    AnthropicRequestEncoder adaptiveEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE));
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-7-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());

    // 验证 ADAPTIVE 模式即使 max_tokens 小于 1024 也不受 budget constraint 限制
    ModelVariant variant =
        new ModelVariant("v", 512, null, null, null, null, null, List.of(), "high");
    ProviderRequest req =
        new ProviderRequest(
            reasoningModel,
            variant,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root = MAPPER.readTree(adaptiveEncoder.encode(req, descriptor).bodyUtf8Bytes());
    assertEquals("adaptive", root.path("thinking").path("type").asText());
    assertEquals("high", root.path("output_config").path("effort").asText());
    assertFalse(root.path("thinking").has("budget_tokens"));

    // 验证 ADAPTIVE 模式透传 xhigh 不被拒绝
    ModelVariant variantXhigh =
        new ModelVariant("v", 512, null, null, null, null, null, List.of(), "xhigh");
    ProviderRequest reqXhigh =
        new ProviderRequest(
            reasoningModel,
            variantXhigh,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootXhigh =
        MAPPER.readTree(adaptiveEncoder.encode(reqXhigh, descriptor).bodyUtf8Bytes());
    assertEquals("adaptive", rootXhigh.path("thinking").path("type").asText());
    assertEquals("xhigh", rootXhigh.path("output_config").path("effort").asText());
  }

  @Test
  void rejectsPenaltiesAndUnsupportedToolResultBlocks() {
    // frequencyPenalty / presencePenalty
    ModelVariant variantWithPenalty =
        new ModelVariant("v", null, null, null, null, 0.5, null, List.of(), null);
    ProviderRequest reqPenalty =
        request(
            variantWithPenalty,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqPenalty, descriptor));

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
                                new ProviderImageBlock("image/png", "https://ex.com/p.png"),
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
  }

  @Test
  void encodesRedactedThinkingInReplayAndDetectsMismatches() throws IOException {
    ProviderMessage userMsg = userMsg(new ProviderTextBlock("hi"));

    String expectedHash =
        AnthropicPrefixHasher.calculateHash(
            NODES.arrayNode(),
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
  void appliesSamplingParamsAndStopSequences() throws IOException {
    ModelVariant variant =
        new ModelVariant("v", 2048, 0.7, 0.9, null, null, null, List.of("STOP!"), null);
    ProviderRequest req =
        request(
            variant,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(req, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals(0.7, root.path("temperature").asDouble(), 0.001);
    assertEquals(0.9, root.path("top_p").asDouble(), 0.001);
    assertEquals(2048, root.path("max_tokens").asInt());
    assertEquals("STOP!", root.path("stop_sequences").get(0).asText());
  }

  @Test
  void rejectsUnsupportedBlocksAcrossRoles() {
    // SYSTEM 包含非 TextBlock
    ProviderRequest badSys =
        request(
            defaultVariant(),
            List.of(sysMsg(new ProviderImageBlock("image/png", "https://ex.com/a.png"))),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(badSys, descriptor));

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
            NODES.arrayNode(),
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
            NODES.arrayNode(),
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
    AnthropicEncodedRequest enc1 =
        encoder.encode(
            request(defaultVariant(), List.of(user, asst1), List.of(), ProviderCacheControl.none()),
            descriptor);
    // 触发 fallback 重放，tool_use 依然能够 fallback 编码
    assertTrue(MAPPER.readTree(enc1.bodyUtf8Bytes()).path("messages").get(1).has("content"));

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
    AnthropicEncodedRequest enc2 =
        encoder.encode(
            request(defaultVariant(), List.of(user, asst2), List.of(), ProviderCacheControl.none()),
            descriptor);
    assertTrue(MAPPER.readTree(enc2.bodyUtf8Bytes()).path("messages").get(1).has("content"));
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
            NODES.arrayNode(),
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
    AnthropicEncodedRequest enc1 =
        encoder.encode(
            request(
                defaultVariant(), List.of(user, asstText), List.of(), ProviderCacheControl.none()),
            descriptor);
    assertTrue(MAPPER.readTree(enc1.bodyUtf8Bytes()).path("messages").get(1).has("content"));

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
    AnthropicEncodedRequest enc2 =
        encoder.encode(
            request(
                defaultVariant(), List.of(user, asstThink), List.of(), ProviderCacheControl.none()),
            descriptor);
    assertTrue(MAPPER.readTree(enc2.bodyUtf8Bytes()).path("messages").get(1).has("content"));

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
    AnthropicEncodedRequest enc3 =
        encoder.encode(
            request(
                defaultVariant(), List.of(user, asstTool), List.of(), ProviderCacheControl.none()),
            descriptor);
    assertTrue(MAPPER.readTree(enc3.bodyUtf8Bytes()).path("messages").get(1).has("content"));
  }

  @Test
  void rejectsReplayStateMissingAssistantRoleOrHavingExtraProperties() throws IOException {
    ProviderMessage user = userMsg(new ProviderTextBlock("hi"));
    String expectedHash =
        AnthropicPrefixHasher.calculateHash(
            NODES.arrayNode(),
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
    AnthropicEncodedRequest enc1 =
        encoder.encode(
            request(defaultVariant(), List.of(user, asst1), List.of(), ProviderCacheControl.none()),
            descriptor);
    // fallback 重放成功
    assertEquals(
        "hi",
        MAPPER
            .readTree(enc1.bodyUtf8Bytes())
            .path("messages")
            .get(1)
            .path("content")
            .get(0)
            .path("text")
            .asText());

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
    AnthropicEncodedRequest enc2 =
        encoder.encode(
            request(defaultVariant(), List.of(user, asst2), List.of(), ProviderCacheControl.none()),
            descriptor);
    // fallback 重放成功
    assertEquals(
        "hi",
        MAPPER
            .readTree(enc2.bodyUtf8Bytes())
            .path("messages")
            .get(1)
            .path("content")
            .get(0)
            .path("text")
            .asText());
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
    ProviderMessageProjector projector = new ProviderMessageProjector();
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
            Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
            true,
            false,
            pricing());
    return new ProviderRequest(model, variant, messages, tools, cacheControl);
  }

  private static ProviderMessage userMsg(ProviderContentBlock... blocks) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(blocks));
  }

  private static ProviderMessage sysMsg(ProviderContentBlock... blocks) {
    return new ProviderMessage(ProviderMessageRole.SYSTEM, List.of(blocks));
  }

  private static ProviderMessage asstMsg(
      List<ProviderContentBlock> blocks, ProviderReplayState replayState) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks, replayState);
  }

  private static ModelVariant defaultVariant() {
    return new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
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
}

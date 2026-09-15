package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 覆盖“上游返回空 reasoning 摘要占位符”时的 Responses replay 语义。
 *
 * <p>MiniMax 等网关使用 Responses 协议时，终态 reasoning item 可能是 {@code {"type":"reasoning","summary":[]}}：
 * 既无 {@code encrypted_content}，也无法用摘要文本承载思考。此类空占位符不承载原生推理，必须由语义编码承载 durable
 * 思考，且不得影响密文、摘要与工具调用的严格校验。本测试覆盖：流式思考保留 → 可用 replay 判定 → durable codec 往返 → 下一轮请求成功。
 */
class OpenAiResponsesEmptyReasoningReplayRepairTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default", "medium");
  private static final String FIXTURE = "fixtures/empty-reasoning-summary.sse";
  private static final ProviderToolDefinition TOOL =
      new ProviderToolDefinition(
          "query",
          "query tool",
          """
          {"type":"object","properties":{"q":{"type":"string"}},"required":["q"],
           "additionalProperties":false}
          """);

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "minimax_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.example.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE);
    ModelDescriptor model =
        new ModelDescriptor(
            "minimax_test",
            "MiniMax-M2",
            "MiniMax-M2",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing);
    return new ProviderRequest(
        model, DEFAULT_VARIANT, 1024, messages, List.of(TOOL), ProviderCacheControl.none());
  }

  /** 按 SSE fixture 逐行喂入事件，复现真实网关的 `reasoning.summary: []` 终态。 */
  private static OpenAiResponsesStreamAccumulator accumulateFixture(
      ProviderRequest request, ProviderDescriptor descriptor, String prefixHash) throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(request, descriptor, prefixHash, e -> {});
    try (InputStream in =
        OpenAiResponsesEmptyReasoningReplayRepairTest.class.getResourceAsStream(FIXTURE)) {
      assertNotNull(in, "fixture must exist");
      String sseText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : sseText.split("\n")) {
        if (line.startsWith("data: ")) {
          String data = line.substring(6).trim();
          if (!data.isEmpty()) {
            accumulator.processEvent(MAPPER.readTree(data));
          }
        }
      }
    }
    return accumulator;
  }

  /** 构造一个历史遗留的、无法承载语义思考的 Responses replay payload（空 reasoning 占位符 + 消息）。 */
  private static ObjectNode emptyPlaceholderPayload(String text) {
    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode output = payload.putArray("output");
    output.addObject().put("type", "reasoning").putArray("summary");
    ObjectNode message = output.addObject();
    message.put("type", "message").put("role", "assistant");
    ObjectNode contentBlock = message.putArray("content").addObject();
    contentBlock.put("type", "output_text");
    contentBlock.put("text", text);
    return payload;
  }

  /** 意图：空 reasoning 占位符的终态不得吞掉流式思考，也不得冻结只含空占位符的 native replay。 */
  @Test
  void emptyReasoningSummaryPreservesStreamedThinkingAndDeclinesNativeReplay() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesEncodedRequest first =
        encoder.encode(firstRequest, descriptor, OpenAiResponsesConfig.defaultConfig());

    OpenAiResponsesStreamAccumulator accumulator =
        accumulateFixture(firstRequest, descriptor, first.sourcePrefixHash());

    ProviderResponse response = accumulator.response();
    assertEquals(
        "Weigh the tradeoffs of bounded replay repair",
        response.thinking(),
        "streamed thinking is the only semantic representation when terminal summary is empty");
    assertEquals("The fix is bounded.", response.text());
    assertNull(
        accumulator.replayState(),
        "an internally unusable empty-placeholder replay must not be frozen");
  }

  /**
   * 意图：MiniMax 形态的完整链路——fixture 流 → 语义思考 → durable codec 往返 → 下一轮请求成功且不伪造密文。
   *
   * <p>只含空占位符（空 reasoning 项，无密文、无可用摘要文本）的 replay 不承载原生推理，必须回退语义编码。
   */
  @Test
  void emptyPlaceholderReplayFallsBackToSemanticAndNextRequestSucceeds() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    String durableThinking = "Weigh the tradeoffs of bounded replay repair";
    String durableText = "The fix is bounded.";

    ProviderReplayState historicalReplay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emptyPlaceholderPayload(durableText));

    // durable 持久化：严格 codec 往返必须无损保留该（不完整）payload 结构。
    ProviderReplayStateJsonCodec codec = new ProviderReplayStateJsonCodec();
    ProviderReplayState durableReplay = codec.decode(codec.encode(historicalReplay));
    assertEquals(historicalReplay, durableReplay);

    List<ProviderMessage> projected =
        new ProviderMessageProjector()
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(new TextMessageContent("propose a fix")))),
                    ProviderMessageProjector.ProjectedMessage.of(
                        new AgentMessage(
                            AgentMessageRole.ASSISTANT,
                            List.of(
                                new ThinkingMessageContent(durableThinking),
                                new TextMessageContent(durableText))),
                        durableReplay)));

    // 下一轮请求必须成功（语义回退），不得因空占位符而失败。
    OpenAiResponsesEncodedRequest second =
        encoder.encode(request(projected), descriptor, OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(second.bodyUtf8Bytes());
    JsonNode input = root.get("input");

    assertEquals(3, input.size());
    assertEquals("message", input.get(0).path("type").asText());
    // 语义回退以 summary 承载 durable 思考；绝不伪造 encrypted_content。
    assertEquals("reasoning", input.get(1).path("type").asText());
    assertEquals(durableThinking, input.get(1).path("summary").get(0).path("text").asText());
    assertFalse(
        input.get(1).has("encrypted_content"),
        "semantic fallback must never forge encrypted_content");
    assertEquals(durableText, input.get(2).path("content").get(0).path("text").asText());
  }

  /** 意图：即使 affinity 与前缀哈希都失配，损坏的 replay 仍必须先被强校验拒绝——不因“可回退”而放弃结构/一致性检查。 */
  @Test
  void malformedEmptyPlaceholderAdjacentReplayStillRejectedBeforeFallback() {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    // 1. summary 非数组：结构损坏，必须拒绝。
    ObjectNode summaryNotArray = MAPPER.createObjectNode();
    summaryNotArray
        .putArray("output")
        .addObject()
        .put("type", "reasoning")
        .put("summary", "not_an_array");
    assertRejected(
        encoder,
        descriptor,
        summaryNotArray,
        "summary must be an array even when the item is otherwise an empty placeholder");

    // 2. 占位符之外存在与 durable 文本矛盾的 message：必须拒绝。
    ObjectNode textMismatch = emptyPlaceholderPayload("different text");
    assertRejected(encoder, descriptor, textMismatch, "replay text must match durable text");

    // 3. 占位符之外存在非空但不一致的摘要：属于矛盾而非“不可用”，必须拒绝。
    ObjectNode contradictorySummary = MAPPER.createObjectNode();
    ArrayNode output = contradictorySummary.putArray("output");
    output
        .addObject()
        .put("type", "reasoning")
        .putArray("summary")
        .addObject()
        .put("type", "summary_text")
        .put("text", "different thought");
    ObjectNode message = output.addObject();
    message.put("type", "message").put("role", "assistant");
    message
        .putArray("content")
        .addObject()
        .put("type", "output_text")
        .put("text", "The fix is bounded.");
    assertRejected(
        encoder,
        descriptor,
        contradictorySummary,
        "a non-empty contradictory summary must not be treated as an unusable placeholder");
  }

  /** 意图：占位符 replay 必须与 durable 文本及工具调用保持一致，任何工具不匹配仍是硬失败。 */
  @Test
  void emptyPlaceholderReplayStillEnforcesToolCallConsistency() {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode output = payload.putArray("output");
    output.addObject().put("type", "reasoning").putArray("summary");
    ObjectNode call = output.addObject();
    call.put("type", "function_call")
        .put("call_id", "call_a")
        .put("name", "query")
        .put("arguments", "{\"q\":\"Paris\"}");

    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            payload);
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("durable thought"),
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_b", "query", "{\"q\":\"Paris\"}"))),
            replay);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(assistant)),
                    descriptor,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("replay tool call mismatch with durable tool call"));
  }

  /** 意图：含 opaque 密文但无摘要的 replay 无法承载 durable 语义思考时，仍必须严格拒绝。 */
  @Test
  void encryptedOnlyReplayStillRejectedWhenDurableThinkingCannotBeRepresented() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode output = payload.putArray("output");
    output.addObject().put("type", "reasoning").put("encrypted_content", "opaque_blob");
    ObjectNode message = output.addObject();
    message.put("type", "message").put("role", "assistant");
    message.putArray("content").addObject().put("type", "output_text").put("text", "answer");

    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            payload);
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("answer")),
            replay);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(assistant)),
                    descriptor,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }

  /** 意图：合法的 encrypted + summary replay 必须原样原位回放，opaque 数据逐字节保持。 */
  @Test
  void validEncryptedAndSummaryReplayStillReplaysInPlaceAndPreservesOpaqueData() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesEncodedRequest first =
        encoder.encode(firstRequest, descriptor, OpenAiResponsesConfig.defaultConfig());

    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            firstRequest, descriptor, first.sourcePrefixHash(), e -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_valid","status":"completed","output":[
              {"id":"rs_valid","type":"reasoning","encrypted_content":"opaque_blob_exact",
               "summary":[{"type":"summary_text","text":"durable thought"}]},
              {"id":"msg_valid","type":"message","role":"assistant","content":[
                {"type":"output_text","text":"answer"}]}
            ]}}
            """));
    ProviderReplayState replay = accumulator.replayState();
    assertNotNull(replay, "a usable encrypted replay must still be frozen");

    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("answer")),
            replay);
    JsonNode input =
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(
                            List.of(
                                new ProviderMessage(
                                    ProviderMessageRole.USER,
                                    List.of(new ProviderTextBlock("propose a fix"))),
                                assistant)),
                        descriptor,
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");

    assertEquals(3, input.size());
    assertEquals("reasoning", input.get(1).path("type").asText());
    assertEquals("opaque_blob_exact", input.get(1).path("encrypted_content").asText());
    assertEquals("durable thought", input.get(1).path("summary").get(0).path("text").asText());
  }

  /** 意图：即使 durable 没有 thinking，全空占位符 replay 也必须回退语义编码——绝不把空 reasoning 项直接发给上游。 */
  @Test
  void emptyPlaceholderReplayWithoutDurableThinkingStillFallsBackToSemantic() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesEncodedRequest first =
        encoder.encode(firstRequest, descriptor, OpenAiResponsesConfig.defaultConfig());

    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            first.sourcePrefixHash(),
            emptyPlaceholderPayload("answer"));

    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("answer")), replay);
    JsonNode input =
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(
                            List.of(
                                new ProviderMessage(
                                    ProviderMessageRole.USER,
                                    List.of(new ProviderTextBlock("propose a fix"))),
                                assistant)),
                        descriptor,
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");

    // 无 durable thinking 时占位符不构成“矛盾”，但空 reasoning 不承载任何语义，仍必须回退为纯语义消息。
    assertEquals(2, input.size());
    assertEquals("message", input.get(0).path("type").asText());
    assertEquals("message", input.get(1).path("type").asText());
    assertEquals("answer", input.get(1).path("content").get(0).path("text").asText());
  }

  /** 意图：代际/前缀失配时仍按既有规则回退语义编码，思考由 summary 承载。 */
  @Test
  void generationMismatchStillFallsBackToSemanticEncoding() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emptyPlaceholderPayload("answer"));
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("answer")),
            replay);

    ProviderDescriptor otherGeneration =
        new ProviderDescriptor(
            "minimax_test",
            ProviderType.OPENAI_RESPONSES,
            "https://api.example.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
            UUID.fromString("22222222-2222-2222-2222-222222222222"));

    JsonNode input =
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(List.of(assistant)),
                        otherGeneration,
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");

    assertEquals(2, input.size());
    assertEquals("reasoning", input.get(0).path("type").asText());
    assertEquals("durable thought", input.get(0).path("summary").get(0).path("text").asText());
    assertEquals("answer", input.get(1).path("content").get(0).path("text").asText());
  }

  /** 意图：工具调用链在空占位符回退后仍保持 function_call/function_call_output 的一致顺序。 */
  @Test
  void semanticFallbackKeepsToolCallAndResultConsistent() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    // 历史 replay 与 durable 工具调用完全一致，仅 reasoning 是无法承载思考的空占位符。
    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode output = payload.putArray("output");
    output.addObject().put("type", "reasoning").putArray("summary");
    ObjectNode call = output.addObject();
    call.put("type", "function_call")
        .put("call_id", "call_1")
        .put("name", "query")
        .put("arguments", "{\"q\":\"Paris\"}");
    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            payload);

    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("durable thought"),
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "query", "{\"q\":\"Paris\"}"))),
            replay);
    ProviderMessage toolResult =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_1", "query", List.of(new ProviderTextBlock("sunny")), false, null)));

    JsonNode input =
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(List.of(assistant, toolResult)),
                        descriptor,
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");

    assertEquals(3, input.size());
    assertEquals("reasoning", input.get(0).path("type").asText());
    assertEquals("durable thought", input.get(0).path("summary").get(0).path("text").asText());
    assertEquals("function_call", input.get(1).path("type").asText());
    assertEquals("call_1", input.get(1).path("call_id").asText());
    assertEquals("function_call_output", input.get(2).path("type").asText());
    assertEquals("sunny", input.get(2).path("output").asText());

    // replay 只剩空 output 却对应 durable 工具调用：属于矛盾而非不可用占位符，仍必须硬失败。
    ObjectNode emptyOutputPayload = MAPPER.createObjectNode();
    emptyOutputPayload.putArray("output");
    ProviderMessage contradictoryAssistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("durable thought"),
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "query", "{\"q\":\"Paris\"}"))),
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_RESPONSES,
                descriptor.affinity("MiniMax-M2"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                emptyOutputPayload));
    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(List.of(contradictoryAssistant, toolResult)),
                descriptor,
                OpenAiResponsesConfig.defaultConfig()),
        "an empty output contradicting durable tool calls must still be rejected");
  }

  /**
   * 意图：多 Provider 连接交接回归——连接 A（MiniMax Responses）产出的不完整 replay 在连接 B（不同
   * connectionGenerationId）以及另一格式编码器上交接时都不得让请求失败，也不得把 Responses 原生结构泄漏出去。
   */
  @Test
  void handoffAcrossProviderConnectionsAndFormatsStaysUsable() throws Exception {
    ProviderDescriptor responsesDescriptor = createDescriptor();
    OpenAiResponsesRequestEncoder responsesEncoder = new OpenAiResponsesRequestEncoder();

    // 连接 B：同 Provider 类型但不同 connectionGenerationId（代际切换后的新连接）。
    ProviderDescriptor otherConnection =
        new ProviderDescriptor(
            "minimax_test",
            ProviderType.OPENAI_RESPONSES,
            "https://api.example.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
            UUID.fromString("33333333-3333-3333-3333-333333333333"));

    String durableThinking = "weigh tradeoffs";
    String durableText = "answer";

    ProviderMessage assistantFromConnectionA =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock(durableThinking), new ProviderTextBlock(durableText)),
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_RESPONSES,
                responsesDescriptor.affinity("MiniMax-M2"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                emptyPlaceholderPayload(durableText)));

    // 连接 A 自身消费：识别为不完整 replay → 语义回退，绝不伪造密文。
    JsonNode sameConnectionInput =
        MAPPER
            .readTree(
                responsesEncoder
                    .encode(
                        request(
                            List.of(
                                new ProviderMessage(
                                    ProviderMessageRole.USER,
                                    List.of(new ProviderTextBlock("propose a fix"))),
                                assistantFromConnectionA)),
                        responsesDescriptor,
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");
    assertEquals(3, sameConnectionInput.size());
    assertEquals("reasoning", sameConnectionInput.get(1).path("type").asText());
    assertEquals(
        durableThinking, sameConnectionInput.get(1).path("summary").get(0).path("text").asText());
    assertFalse(sameConnectionInput.get(1).has("encrypted_content"));

    // 连接 B 交接：代际失配走既有安全规则，仍以语义编码承载思考而非失败。
    JsonNode otherConnectionInput =
        MAPPER
            .readTree(
                responsesEncoder
                    .encode(
                        request(
                            List.of(
                                new ProviderMessage(
                                    ProviderMessageRole.USER,
                                    List.of(new ProviderTextBlock("propose a fix"))),
                                assistantFromConnectionA)),
                        otherConnection,
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");
    assertEquals(3, otherConnectionInput.size());
    assertEquals("reasoning", otherConnectionInput.get(1).path("type").asText());
    assertEquals(
        durableThinking, otherConnectionInput.get(1).path("summary").get(0).path("text").asText());
    assertFalse(otherConnectionInput.get(1).has("encrypted_content"));

    // 跨格式交接由各目标格式编码器保证（见 Chat 编码器的跨格式回退回归）。
  }

  /**
   * 意图：终态 reasoning 只有 {@code encrypted_content}（无摘要）时，opaque 密文必须原样冻结进 native replay，
   * 绝不被剥离；密文也不得作为 thinking 文本外泄。
   */
  @Test
  void terminalEncryptedOnlyReasoningKeepsOpaqueReplay() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesEncodedRequest first =
        encoder.encode(firstRequest, descriptor, OpenAiResponsesConfig.defaultConfig());

    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            firstRequest, descriptor, first.sourcePrefixHash(), e -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"opaque reasoning\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_enc_only","status":"completed","output":[
              {"id":"rs_enc_only","type":"reasoning","encrypted_content":"opaque_blob"},
              {"id":"msg_enc_only","type":"message","role":"assistant","content":[
                {"type":"output_text","text":"answer"}]}
            ]}}
            """));

    ProviderResponse response = accumulator.response();
    assertEquals("opaque reasoning", response.thinking());
    assertFalse(
        response.thinking().contains("opaque_blob"),
        "opaque encrypted content must never be exposed as thinking text");
    ProviderReplayState replay = accumulator.replayState();
    assertNotNull(replay, "含密文的 reasoning 不属于空占位符，opaque replay 必须原样保留");
    assertEquals(
        "opaque_blob",
        replay.payload().get("output").get(0).path("encrypted_content").asText(),
        "opaque 密文绝不允许被剥离");
  }

  /** 意图：终态没有任何 reasoning item 时，按既有语义丢弃未被终态声明的流式思考草稿。 */
  @Test
  void terminalWithoutReasoningItemStillClearsStreamedThinkingDraft() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(firstRequest, descriptor, "a".repeat(64), e -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"draft thinking\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_no_reasoning","status":"completed","output":[
              {"id":"msg_only","type":"message","role":"assistant","content":[
                {"type":"output_text","text":"answer"}]}
            ]}}
            """));

    assertEquals("", accumulator.response().thinking(), "思考未被终态 reasoning 声明时必须按既有语义清除，不得误保留草稿");
  }

  /** 意图：终态 reasoning 的 summary 以纯文本简写给出且非空时，该摘要仍是权威语义思考并被正常保留。 */
  @Test
  void terminalTextualReasoningSummaryIsStillAuthoritative() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(firstRequest, descriptor, "a".repeat(64), e -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"draft thinking\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_textual_summary","status":"completed","output":[
              {"id":"rs_textual","type":"reasoning","summary":"authoritative summary"},
              {"id":"msg_textual","type":"message","role":"assistant","content":[
                {"type":"output_text","text":"answer"}]}
            ]}}
            """));

    ProviderResponse response = accumulator.response();
    assertEquals("authoritative summary", response.thinking());
    assertNotNull(accumulator.replayState(), "a usable textual summary keeps the native replay");
  }

  /**
   * 意图：终态给出多个空白 summary 文本块时仍属于空占位符，不冻结 native replay。
   *
   * <p>同时覆盖多块占位符的逐块判定（全部文本为空白时不得误判为可用摘要）。
   */
  @Test
  void terminalMultipleBlankSummaryBlocksStillDeclineNativeReplay() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(firstRequest, descriptor, "a".repeat(64), e -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"streamed thinking\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_blank_blocks","status":"completed","output":[
              {"id":"rs_blank","type":"reasoning","summary":[
                {"type":"summary_text","text":""},
                {"type":"summary_text","text":""}]},
              {"id":"msg_blank","type":"message","role":"assistant","content":[
                {"type":"output_text","text":"answer"}]}
            ]}}
            """));

    assertEquals("streamed thinking", accumulator.response().thinking());
    assertNull(accumulator.replayState(), "多个空白摘要块仍无法承载思考，native replay 必须整体放弃");
  }

  /** 意图：producer 绝不因为“没有可用摘要”而丢弃带 {@code encrypted_content} 的 opaque reasoning——密文必须原样冻结。 */
  @Test
  void producerNeverStripsEncryptedReasoningWhenSummaryIsEmpty() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(firstRequest, descriptor, "a".repeat(64), e -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_enc_keep","status":"completed","output":[
              {"id":"rs_keep","type":"reasoning","encrypted_content":"opaque_keep_exact","summary":[]},
              {"id":"msg_keep","type":"message","role":"assistant","content":[
                {"type":"output_text","text":"answer"}]}
            ]}}
            """));

    ProviderReplayState replay = accumulator.replayState();
    assertNotNull(replay, "带密文的 reasoning 不属于空占位符，native replay 必须保留");
    JsonNode reasoning = replay.payload().get("output").get(0);
    assertEquals(
        "opaque_keep_exact", reasoning.path("encrypted_content").asText(), "opaque 密文绝不允许被剥离");
  }

  /** 意图：终态只给空白摘要时不改变实际流式思考——不得出现额外前导空白或换行拼接。 */
  @Test
  void whitespaceOnlyTerminalSummaryKeepsStreamedThinkingExact() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("propose a fix")))));
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(firstRequest, descriptor, "a".repeat(64), e -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"exact streamed thinking\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_ws","status":"completed","output":[
              {"id":"rs_ws","type":"reasoning","summary":[
                {"type":"summary_text","text":"  \\n "}]},
              {"id":"msg_ws","type":"message","role":"assistant","content":[
                {"type":"output_text","text":"answer"}]}
            ]}}
            """));

    assertEquals(
        "exact streamed thinking", accumulator.response().thinking(), "空白终态摘要不得给流式思考引入前导空白或换行");
  }

  /** 意图：空白摘要与空占位符等价——消费历史 replay 时同样回退语义编码，而非把纯空白占位发出去。 */
  @Test
  void whitespaceOnlySummaryPlaceholderReplayAlsoFallsBackToSemantic() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode output = payload.putArray("output");
    output
        .addObject()
        .put("type", "reasoning")
        .putArray("summary")
        .addObject()
        .put("type", "summary_text")
        .put("text", "  \n ");
    ObjectNode message = output.addObject();
    message.put("type", "message").put("role", "assistant");
    ObjectNode contentBlock = message.putArray("content").addObject();
    contentBlock.put("type", "output_text");
    contentBlock.put("text", "answer");

    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("answer")),
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_RESPONSES,
                descriptor.affinity("MiniMax-M2"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                payload));

    JsonNode input =
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(List.of(assistant)),
                        descriptor,
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");

    assertEquals(2, input.size());
    assertEquals("reasoning", input.get(0).path("type").asText());
    assertEquals("durable thought", input.get(0).path("summary").get(0).path("text").asText());
    assertEquals("answer", input.get(1).path("content").get(0).path("text").asText());
  }

  private void assertRejected(
      OpenAiResponsesRequestEncoder encoder,
      ProviderDescriptor descriptor,
      ObjectNode payload,
      String scenario) {
    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            payload);
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("durable thought"),
                new ProviderTextBlock("The fix is bounded.")),
            replay);
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(assistant)), descriptor, OpenAiResponsesConfig.defaultConfig()),
            scenario);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind(), scenario);
  }
}

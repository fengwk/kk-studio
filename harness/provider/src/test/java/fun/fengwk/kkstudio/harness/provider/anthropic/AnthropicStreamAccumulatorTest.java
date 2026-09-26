package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** AnthropicStreamAccumulator 的纯状态机与终态解析测试。 */
class AnthropicStreamAccumulatorTest {

  private final List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
  private final ProviderStreamHandler recordingHandler =
      new ProviderStreamHandler() {
        @Override
        public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
          emittedEvents.add(event);
        }

        @Override
        public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

        @Override
        public void onError(ProviderException error, ProviderStream stream) {}
      };

  private AnthropicStreamBridge bridge;
  private ProviderDescriptor descriptor;
  private static final String VALID_PREFIX_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private ProviderRequest request;

  @BeforeEach
  void setUp() {
    emittedEvents.clear();
    bridge = new AnthropicStreamBridge(recordingHandler);
    descriptor =
        new ProviderDescriptor(
            "test-anthropic",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
            new UUID(1L, 2L));
    ModelDescriptor model =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-5-sonnet",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.valueOf(3.0),
                BigDecimal.valueOf(15.0),
                BigDecimal.valueOf(0.3),
                BigDecimal.valueOf(3.75),
                BigDecimal.valueOf(6.0),
                BigDecimal.ZERO));
    ModelVariant variant = new ModelVariant("default");
    request =
        new ProviderRequest(
            model,
            variant,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
            List.of(),
            ProviderCacheControl.none());
  }

  @Test
  void handlesCompleteTurnWithThinkingTextToolAndUsage() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // message_start
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":5,\"cache_creation_input_tokens\":20,\"cache_creation\":{\"ephemeral_5m\":12,\"ephemeral_1h\":8}}}}");

    // Block 0: thinking
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"Let me \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"think.\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_xyz\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    // Block 1: text
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"Answer \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"content\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");

    // Block 2: tool_use
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_abc\",\"name\":\"calc\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\": \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"42}\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}");

    // message_delta
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":25}}");

    // message_stop
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);

    ProviderResponse response = completion.response();
    assertEquals("Answer content", response.text());
    assertEquals("Let me think.", response.thinking());
    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals("msg_1", response.requestId());

    // usage
    assertEquals(10, response.usage().inputTokens());
    assertEquals(25, response.usage().outputTokens());
    assertEquals(5, response.usage().cacheReadTokens());
    assertEquals(12, response.usage().cacheWriteTokens()); // 5m
    assertEquals(8, response.usage().cacheWriteLongTokens()); // 1h

    // tool calls
    assertEquals(1, response.toolCalls().size());
    assertEquals("call_abc", response.toolCalls().get(0).id());
    assertEquals("calc", response.toolCalls().get(0).name());
    assertEquals("{\"x\": 42}", response.toolCalls().get(0).argumentsJson());
    assertTrue(response.toolCallDiagnostics().isEmpty());

    // replay state
    assertNotNull(completion.replayState());
    assertEquals(ProviderReplayFormat.ANTHROPIC_MESSAGES, completion.replayState().format());
    assertEquals(VALID_PREFIX_HASH, completion.replayState().sourcePrefixHash());

    // 校验 emitted events 顺序与内容
    assertEquals(7, emittedEvents.size());
    assertTrue(
        emittedEvents.get(0) instanceof ProviderStreamEvent.ThinkingDelta td
            && td.text().equals("Let me "));
    assertTrue(
        emittedEvents.get(1) instanceof ProviderStreamEvent.ThinkingDelta td
            && td.text().equals("think."));
    assertTrue(
        emittedEvents.get(2) instanceof ProviderStreamEvent.TextDelta td
            && td.text().equals("Answer "));
    assertTrue(
        emittedEvents.get(3) instanceof ProviderStreamEvent.TextDelta td
            && td.text().equals("content"));
    assertTrue(
        emittedEvents.get(4) instanceof ProviderStreamEvent.ToolCallDelta tc
            && tc.id().equals("call_abc"));
    assertTrue(
        emittedEvents.get(5) instanceof ProviderStreamEvent.ToolCallDelta tc
            && tc.argumentsJson().equals("{\"x\": "));
    assertTrue(
        emittedEvents.get(6) instanceof ProviderStreamEvent.ToolCallDelta tc
            && tc.argumentsJson().equals("42}"));
  }

  @Test
  void handlesLengthTruncationWithToolCallDiagnostic() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"usage\":{\"input_tokens\":5}}}");

    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_trunc\",\"name\":\"weather\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\": \\\"New\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":10}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.LENGTH, response.stopReason());
    assertTrue(response.toolCalls().isEmpty());
    assertEquals(1, response.toolCallDiagnostics().size());
    assertEquals(0, response.toolCallDiagnostics().get(0).callIndex());
    assertEquals("call_trunc", response.toolCallDiagnostics().get(0).id());
    assertEquals("{\"city\": \"New", response.toolCallDiagnostics().get(0).partialArguments());

    // 存在 diagnostic 时不得生成 replayState
    assertNull(completion.replayState());
  }

  @Test
  void throwsInvalidResponseOnIncompleteToolArgumentsUnderCompleteStopReason() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_3\",\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"n1\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{invalid\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  @Test
  void throwsInvalidResponseOnCacheBreakdownMismatch() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // 总数 20，但 5m + 1h = 10 + 5 = 15 != 20
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"cache_creation_input_tokens\":20,\"cache_creation\":{\"ephemeral_5m\":10,\"ephemeral_1h\":5}}}}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /**
   * 测试意图：usage 快照中的官方未知字段（server_tool_use 等）必须原样保留在 rawUsageJson 中，normalized 计数仍严格解析并 覆盖同名键；usage
   * 提供的 service_tier 归一化到 ProviderResponse.serviceTier。
   */
  @Test
  void preservesUnknownUsageFieldsAndParsesServiceTier() throws Exception {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_usage\",\"usage\":{\"input_tokens\":10,\"service_tier\":\"standard\",\"server_tool_use\":{\"web_search_requests\":3},\"future_counter\":7}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"ok\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    // 增量快照按顶层字段合并：service_tier 被覆盖，未知字段继续保留
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5,\"service_tier\":\"priority\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderResponse response = accumulator.finish().response();
    assertEquals("priority", response.serviceTier());
    assertEquals(10, response.usage().inputTokens());
    assertEquals(5, response.usage().outputTokens());

    JsonNode rawUsage = new ObjectMapper().readTree(response.rawUsageJson());
    assertEquals(10, rawUsage.path("input_tokens").asInt());
    assertEquals(5, rawUsage.path("output_tokens").asInt());
    assertEquals("priority", rawUsage.path("service_tier").asText());
    assertEquals(3, rawUsage.path("server_tool_use").path("web_search_requests").asInt());
    assertEquals(7, rawUsage.path("future_counter").asInt());
    // normalized cache 明细照旧补齐
    assertTrue(rawUsage.path("cache_creation").has("ephemeral_5m_input_tokens"));
  }

  /** 测试意图：usage.service_tier 类型非法时显式失败，而不是静默忽略未知形态。 */
  @Test
  void rejectsNonTextualServiceTier() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleEvent(
                    "message_start",
                    "{\"type\":\"message_start\",\"message\":{\"usage\":{\"service_tier\":1}}}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    assertEquals("usage service_tier must be a non-blank string", ex.getMessage());
  }

  @Test
  void ignoresPingAndDoneAndFutureUnknownEvents() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent("ping", "{}");
    accumulator.handleEvent("future_unknown_event", "{\"data\":123}");
    accumulator.handleEvent(null, "[DONE]");

    // 仍然保持未启动状态，正常接收 message_start
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
  }

  @Test
  void handlesSseErrorEventByMappingAndThrowing() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleEvent(
                    "error",
                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"fake_key_err\"}}"));

    assertEquals(ProviderErrorKind.AUTHENTICATION, ex.kind());
    assertTrue(ex.getMessage().contains("authentication_error"));
    assertTrue(ex.getMessage().contains("fake_key_err"));
  }

  @Test
  void throwsInvalidResponseOnUnexpectedEofBeforeMessageStop() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    // 未收到 message_stop 提前调用 finish
    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  @Test
  void handlesRedactedThinkingAndSignatureDeltaInReplayState() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");

    // block 0: redacted_thinking
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"redacted-data\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    // block 1: thinking with signature delta
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"some thought\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-part-1\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");

    // message_delta with end_turn
    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    // 验证 replayState 里包含 redacted_thinking 和 thinking 及其 signature
    assertNotNull(completion.replayState());
    JsonNode replayContent = completion.replayState().payload().path("content");
    assertEquals("redacted_thinking", replayContent.get(0).path("type").asText());
    assertEquals("redacted-data", replayContent.get(0).path("data").asText());
    assertEquals("thinking", replayContent.get(1).path("type").asText());
    assertEquals("sig-part-1", replayContent.get(1).path("signature").asText());
  }

  @Test
  void refusalYieldsFilteredStopReasonAndNullReplayState() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"refused\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.FILTERED, completion.response().stopReason());
    assertNull(completion.replayState());
  }

  @Test
  void acceptsMonotonicNonConsecutiveIndicesAndRejectsDuplicatesOrOutOfOrder() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");

    // 允许单调非连续：0 -> 2 -> 5
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"a\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"b\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}");

    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":5,\"content_block\":{\"type\":\"text\",\"text\":\"c\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":5}");

    // 重复 index 5 拒绝
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":5,\"content_block\":{\"type\":\"text\",\"text\":\"d\"}}"));

    // 倒退 index 3 拒绝
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"text\",\"text\":\"d\"}}"));
  }

  @Test
  void handlesToolInitialInputPlaceholderAndTerminalGapCompletion() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");

    // 初始提供占位符 {}
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_placeholder\",\"name\":\"no_args\",\"input\":{}}}");
    // 未收到任何 delta，直接 stop
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals("{}", completion.response().toolCalls().get(0).argumentsJson());
    assertNotNull(completion.replayState());
  }

  @Test
  void handlesLengthTruncationWithoutAnyToolArgumentsDoesNotFabricateEmptyObject() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");

    // 无 input 且无任何 delta 到达
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_no_args\",\"name\":\"fetch\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertTrue(completion.response().toolCalls().isEmpty());
    assertEquals(1, completion.response().toolCallDiagnostics().size());
    assertEquals("", completion.response().toolCallDiagnostics().get(0).partialArguments());
    assertNull(completion.replayState());
  }

  @Test
  void rejectsDeltaWhenToolArgumentsAlreadyFinalized() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");

    // 初始提供完整 input（非空对象）
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_full\",\"name\":\"fetch\",\"input\":{\"key\":\"val\"}}}");

    // 尝试追加 delta -> 必须拒绝
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"extra\"}}"));
  }

  @Test
  void rejectsActiveBlocksAndAppliesMultipleCumulativeMessageDeltas() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"hello\"}}");

    // active block 存在时发送 message_delta -> 拒绝
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}"));

    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"input_tokens\":30,\"output_tokens\":10}}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":15}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(30, completion.response().usage().inputTokens());
    assertEquals(15, completion.response().usage().outputTokens());
  }

  @Test
  void filteredStopReasonClearsToolsAndDiagnosticsWithoutThrowing() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_refused\",\"name\":\"dangerous_op\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.FILTERED, completion.response().stopReason());
    assertTrue(completion.response().toolCalls().isEmpty());
    assertTrue(completion.response().toolCallDiagnostics().isEmpty());
    assertNull(completion.replayState());
  }

  @Test
  void rejectsPayloadWithDuplicateKeysUnderStrictJsonFeatures() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // 重复 key
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "message_start",
                "{\"type\":\"message_start\",\"type\":\"message_start\",\"message\":{\"usage\":{}}}"));
  }

  @Test
  void rejectsNullOrNonObjectUsageValues() {
    assertInvalidResponse(
        "usage must be an object",
        () ->
            newAccumulator()
                .handleEvent(
                    "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":null}}"));
    assertInvalidResponse(
        "usage input_tokens must be an integer",
        () ->
            newAccumulator()
                .handleEvent(
                    "message_start",
                    "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":null}}}"));

    AnthropicStreamAccumulator accumulator = startedAccumulator();
    assertInvalidResponse(
        "usage must be an object",
        () ->
            accumulator.handleEvent(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":[]}"));
  }

  @Test
  void rejectsMessageDeltaWithoutObjectDelta() {
    AnthropicStreamAccumulator accumulator = startedAccumulator();
    assertInvalidResponse(
        "message_delta missing delta object",
        () ->
            accumulator.handleEvent(
                "message_delta", "{\"type\":\"message_delta\",\"delta\":null}"));
  }

  @Test
  void rejectsDeltaWithoutMessageStart() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}"));
  }

  @Test
  void handlesThinkingWithoutSignatureOrRedactedWithoutDataDisablesReplay() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":5}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"some thought\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");

    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals("some thought", completion.response().thinking());
    assertNull(completion.replayState());
  }

  @Test
  void rejectsDeltaWithMissingDeltaObjectOrMismatchedType() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");

    // missing delta object
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0}"));

    // mismatched delta type for text block
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"t\"}}"));

    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    // delta on stopped block
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"t\"}}"));

    // duplicate stop on stopped block
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));

    // delta on non-existent block
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":99,\"delta\":{\"type\":\"text_delta\",\"text\":\"t\"}}"));
  }

  @Test
  void rejectsMismatchedDeltaTypesForThinkingAndToolUse() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");

    // mismatched delta for thinking
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"unknown_delta\"}}"));

    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"fn\"}}");

    // mismatched delta for tool_use
    assertThrows(
        ProviderException.class,
        () ->
            accumulator.handleEvent(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"bad\"}}"));
  }

  @Test
  void rejectsMessageStopWithActiveBlock() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"unclosed\"}}");

    // message_stop while index 0 is still active
    assertThrows(
        ProviderException.class,
        () -> accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}"));
  }

  @Test
  void handlesFlatCacheCreationBreakdownFields() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "usage": {
              "input_tokens": 100,
              "cache_creation_input_tokens": 30,
              "cache_creation_ephemeral_5m_input_tokens": 20,
              "cache_creation_ephemeral_1h_input_tokens": 10
            }
          }
        }
        """);
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":10}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(20, completion.response().usage().cacheWriteTokens());
    assertEquals(10, completion.response().usage().cacheWriteLongTokens());
    assertEquals(0, completion.response().usage().totalTokens());
    assertEquals(0, completion.response().usage().providerTotalTokens());
    assertEquals(140, completion.response().usage().categorizedTokens());
  }

  @Test
  void rejectsInvalidLifecycleStatesAndMalformedPayloads() {
    // 每个断言使用独立状态机并检查精确错误，避免前一条畸形 block 污染 index 后误命中其他分支。
    assertInvalidResponse(
        "failed to parse SSE event JSON payload",
        () -> newAccumulator().handleEvent("message_start", "not a json"));
    assertInvalidResponse(
        "SSE event data is not a JSON object",
        () -> newAccumulator().handleEvent("message_start", "[]"));
    assertInvalidResponse(
        "mismatched SSE event type",
        () ->
            newAccumulator()
                .handleEvent("message_start", "{\"type\":\"message_delta\",\"message\":{}}"));
    assertInvalidResponse(
        "message_start missing message object",
        () -> newAccumulator().handleEvent("message_start", "{\"type\":\"message_start\"}"));
    assertInvalidResponse(
        "invalid message id",
        () ->
            newAccumulator()
                .handleEvent(
                    "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":1}}"));

    AnthropicStreamAccumulator started = startedAccumulator();
    assertInvalidResponse(
        "unexpected message_start event state",
        () -> started.handleEvent("message_start", "{\"type\":\"message_start\",\"message\":{}}"));

    assertInvalidResponse(
        "invalid content_block_start index",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":\"0\",\"content_block\":{\"type\":\"text\"}}"));
    assertInvalidResponse(
        "content_block_start missing content_block object",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start", "{\"type\":\"content_block_start\",\"index\":0}"));
    assertInvalidResponse(
        "content_block missing type",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{}}"));
    assertInvalidResponse(
        "text content_block text must be string",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":1}}"));
    assertInvalidResponse(
        "thinking content_block thinking must be string",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":1}}"));
    assertInvalidResponse(
        "redacted_thinking data must be string",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":1}}"));
    assertInvalidResponse(
        "tool_use content_block missing valid id or name",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"name\":\"calc\"}}"));
    assertInvalidResponse(
        "tool_use content_block missing valid id or name",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\" \",\"name\":\"calc\"}}"));
    assertInvalidResponse(
        "tool_use input must be a JSON object",
        () ->
            startedAccumulator()
                .handleEvent(
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"id1\",\"name\":\"calc\",\"input\":[]}}"));
  }

  @Test
  void handlesToolInitialInputPlaceholderFollowedByJsonDeltas() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");

    // 初始提供占位符 {}
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"calc\",\"input\":{}}}");

    // 随后收到两个 json delta
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\": \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"1}\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals("{\"a\": 1}", completion.response().toolCalls().get(0).argumentsJson());

    // 断言 delta 序列：第 1 个 ToolCallDelta 不含 "{}"
    assertEquals(3, emittedEvents.size());
    ProviderStreamEvent.ToolCallDelta d0 = (ProviderStreamEvent.ToolCallDelta) emittedEvents.get(0);
    assertEquals(0, d0.index());
    assertEquals("c1", d0.id());
    assertEquals("calc", d0.name());
    assertNull(d0.argumentsJson());

    ProviderStreamEvent.ToolCallDelta d1 = (ProviderStreamEvent.ToolCallDelta) emittedEvents.get(1);
    assertEquals(0, d1.index());
    assertEquals("{\"a\": ", d1.argumentsJson());

    ProviderStreamEvent.ToolCallDelta d2 = (ProviderStreamEvent.ToolCallDelta) emittedEvents.get(2);
    assertEquals(0, d2.index());
    assertEquals("1}", d2.argumentsJson());
  }

  @Test
  void rejectsConflictingCacheBreakdownAliases() {
    // 1. nested 内部别名冲突 (10 vs 12)
    AnthropicStreamAccumulator acc1 =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);
    assertThrows(
        ProviderException.class,
        () ->
            acc1.handleEvent(
                "message_start",
                """
                {
                  "type": "message_start",
                  "message": {
                    "usage": {
                      "cache_creation_input_tokens": 10,
                      "cache_creation": {
                        "ephemeral_5m_input_tokens": 10,
                        "ephemeral_5m": 12
                      }
                    }
                  }
                }
                """));

    // 2. nested 与 flat 别名冲突 (10 vs 15)
    AnthropicStreamAccumulator acc2 =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);
    assertThrows(
        ProviderException.class,
        () ->
            acc2.handleEvent(
                "message_start",
                """
                {
                  "type": "message_start",
                  "message": {
                    "usage": {
                      "cache_creation_input_tokens": 10,
                      "cache_creation_ephemeral_5m_input_tokens": 15,
                      "cache_creation": {
                        "ephemeral_5m": 10
                      }
                    }
                  }
                }
                """));
  }

  @Test
  void acceptsConsistentSameValueCacheBreakdownAliases() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // 多个别名同时出现但值完全一致（均为 10）
    accumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "usage": {
              "cache_creation_input_tokens": 15,
              "cache_creation_ephemeral_5m_input_tokens": 10,
              "cache_creation": {
                "ephemeral_5m_input_tokens": 10,
                "ephemeral_5m": 10,
                "ephemeral_1h": 5
              }
            }
          }
        }
        """);
    accumulator.handleEvent(
        "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(10, completion.response().usage().cacheWriteTokens());
    assertEquals(5, completion.response().usage().cacheWriteLongTokens());
  }

  @Test
  void mergesCacheBreakdownAcrossDifferentDimensionsAndEvents() {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // message_start 提供 nested 5m
    accumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "usage": {
              "cache_creation_input_tokens": 10,
              "cache_creation": {
                "ephemeral_5m": 10
              }
            }
          }
        }
        """);

    // message_delta 提供累计 cache total 与 flat 1h
    accumulator.handleEvent(
        "message_delta",
        """
        {
          "type": "message_delta",
          "delta": {"stop_reason": "end_turn"},
          "usage": {
            "cache_creation_input_tokens": 15,
            "cache_creation_ephemeral_1h_input_tokens": 5,
            "output_tokens": 2
          }
        }
        """);
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(10, completion.response().usage().cacheWriteTokens());
    assertEquals(5, completion.response().usage().cacheWriteLongTokens());
    assertEquals(
        15,
        completion.response().usage().cacheWriteTokens()
            + completion.response().usage().cacheWriteLongTokens());
  }

  private AnthropicStreamAccumulator newAccumulator() {
    return new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);
  }

  private AnthropicStreamAccumulator startedAccumulator() {
    AnthropicStreamAccumulator accumulator = newAccumulator();
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    return accumulator;
  }

  private static void assertInvalidResponse(String message, Runnable action) {
    ProviderException error = assertThrows(ProviderException.class, action::run);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals(message, error.getMessage());
  }
}

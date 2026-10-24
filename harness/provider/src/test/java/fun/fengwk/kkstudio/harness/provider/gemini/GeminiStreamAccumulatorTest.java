package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.provider.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Gemini 流式累加器及 SSE 消息解析、去重、状态机测试。 */
class GeminiStreamAccumulatorTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private ProviderDescriptor descriptor;
  private ProviderRequest request;
  private List<ProviderStreamEvent> emittedEvents;
  private ProviderStreamBridge bridge;

  private final ProviderStreamHandler handler =
      new ProviderStreamHandler() {
        @Override
        public void onEvent(ProviderStreamEvent event) {
          emittedEvents.add(event);
        }

        @Override
        public void onError(ProviderException error) {}

        @Override
        public void onComplete(ProviderCompletion completion) {}
      };

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "google-test",
            ProviderType.GOOGLE,
            "https://generativelanguage.googleapis.com",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
            new UUID(1L, 2L));

    ModelDescriptor model =
        new ModelDescriptor(
            "google-test",
            "gemini-2.5-flash",
            "gemini-2.5-flash",
            Set.of(ModelInputModality.TEXT),
            true,
            true);

    ModelVariant variant = new ModelVariant("default");
    request =
        new ProviderRequest(
            model,
            variant,
            1024,
            "Test system instruction.",
            List.of(),
            List.of(),
            ProviderCacheControl.none());

    emittedEvents = new ArrayList<>();
    bridge = new ProviderStreamBridge(handler);
  }

  /** 验证纯增量流中多次分片 text 正确合并，不丢失、不双计。 */
  @Test
  void handlesPureIncrementalTextStream() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk1 =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Hello " }] }
          }]
        }
        """;
    String chunk2 =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "world!" }] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 5,
            "candidatesTokenCount": 3,
            "totalTokenCount": 8
          }
        }
        """;

    accumulator.handleEvent("message", chunk1);
    accumulator.handleEvent("message", chunk2);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals("Hello world!", response.text());

    // 检查发射的事件：TextDelta("Hello ") -> TextDelta("world!")
    List<ProviderStreamEvent.TextDelta> textDeltas =
        emittedEvents.stream()
            .filter(e -> e instanceof ProviderStreamEvent.TextDelta)
            .map(e -> (ProviderStreamEvent.TextDelta) e)
            .toList();
    assertEquals(2, textDeltas.size());
    assertEquals("Hello ", textDeltas.get(0).text());
    assertEquals("world!", textDeltas.get(1).text());

    assertNotNull(completion.replayState());
    assertEquals(ProviderReplayFormat.GEMINI_CONTENT, completion.replayState().format());
  }

  /** 测试意图：缓存关系不一致（breakdown）与输出计数溢出（overflow）在 finish() 判定并抛出 INVALID_RESPONSE。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"promptTokenCount\":1,\"candidatesTokenCount\":0,\"cachedContentTokenCount\":2}",
        "{\"promptTokenCount\":0,\"candidatesTokenCount\":9223372036854775807,\"thoughtsTokenCount\":1}"
      })
  void rejectsInconsistentOrOverflowingUsage(String usage) {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":"
            + usage
            + "}");
    ProviderException error = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
  }

  /** 测试意图：多 part 帧按各自 slot 保持边界，不能把 index 1 追加到 index 0。 */
  @Test
  void multiPartFramesKeepIndependentSlots() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"role":"model","parts":[{"text":"A"},{"text":"B"}]}}]}
        """);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"role":"model","parts":[{"text":"AC"},{"text":"BD"}]},"finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}
        """);
    ProviderCompletion completion = accumulator.finish();
    assertEquals("ACBD", completion.response().text());
    JsonNode parts = completion.replayState().payload().path("parts");
    assertEquals("AC", parts.get(0).path("text").asString(""));
    assertEquals("BD", parts.get(1).path("text").asString(""));
  }

  /** 测试意图：终态后禁止新 content 和第二个 finishReason，usage-only 空 candidates 仍可更新用量。 */
  @Test
  void terminalAllowsUsageOnlyAndRejectsLaterContent() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"parts":[{"text":"done"}]},"finishReason":"STOP"}]}
        """);
    accumulator.handleEvent(
        "message",
        """
        {"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1,"totalTokenCount":4},"candidates":[]}
        """);
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleEvent(
                    "message",
                    """
                    {"candidates":[{"content":{"parts":[{"text":"tail"}]}}]}
                    """));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
  }

  /** 验证全量快照重复流中，文本增量被正确提取去重，绝不重复拼接。 */
  @Test
  void handlesSnapshotRepetitiveTextStream_deduplicatesAccumulatedPrefix() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    // 服务端发出的每个 chunk 携带迄今为止的全部文本
    String chunk1 =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Hello" }] }
          }]
        }
        """;
    String chunk2 =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Hello world" }] }
          }]
        }
        """;
    String chunk3 =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Hello world!" }] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk1);
    accumulator.handleEvent("message", chunk2);
    accumulator.handleEvent("message", chunk3);
    ProviderCompletion completion = accumulator.finish();

    assertEquals("Hello world!", completion.response().text());

    List<String> deltas =
        emittedEvents.stream()
            .filter(e -> e instanceof ProviderStreamEvent.TextDelta)
            .map(e -> ((ProviderStreamEvent.TextDelta) e).text())
            .toList();
    assertEquals(List.of("Hello", " world", "!"), deltas);
  }

  /** 验证带有 thought=true 的 part 正确发射 ThinkingDelta，并与后续正常文本分流。 */
  @Test
  void handlesThinkingParts_emitsThinkingDeltasSeparately() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk1 =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [{ "text": "Thinking deeply...", "thought": true, "thoughtSignature": "sig1" }]
            }
          }]
        }
        """;
    String chunk2 =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [{ "text": "Final answer." }]
            },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk1);
    accumulator.handleEvent("message", chunk2);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals("Thinking deeply...", response.thinking());
    assertEquals("Final answer.", response.text());

    // 检查发射的事件：ThinkingDelta -> TextDelta
    assertTrue(emittedEvents.get(0) instanceof ProviderStreamEvent.ThinkingDelta);
    assertEquals(
        "Thinking deeply...", ((ProviderStreamEvent.ThinkingDelta) emittedEvents.get(0)).text());
    assertTrue(emittedEvents.get(1) instanceof ProviderStreamEvent.TextDelta);
    assertEquals("Final answer.", ((ProviderStreamEvent.TextDelta) emittedEvents.get(1)).text());
  }

  /** 验证单次与并行工具调用被正确累加，生成稳定的 toolCall id 并发射 ToolCallDelta。 */
  @Test
  void handlesFunctionCallParts_singleAndParallel() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [
                {
                  "functionCall": {
                    "id": "call_1",
                    "name": "lookup",
                    "args": { "query": "gemini" }
                  }
                },
                {
                  "functionCall": {
                    "name": "calculate",
                    "args": { "expr": "2+2" }
                  }
                }
              ]
            },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    List<ProviderToolCall> calls = response.toolCalls();
    assertEquals(2, calls.size());
    ProviderToolCall t1 = calls.get(0);
    assertEquals("call_1", t1.id());
    assertEquals("lookup", t1.name());

    ProviderToolCall t2 = calls.get(1);
    assertEquals("calculate", t2.name());
    assertNotNull(t2.id());

    List<ProviderStreamEvent.ToolCallDelta> toolDeltas =
        emittedEvents.stream()
            .filter(e -> e instanceof ProviderStreamEvent.ToolCallDelta)
            .map(e -> (ProviderStreamEvent.ToolCallDelta) e)
            .toList();
    assertEquals(2, toolDeltas.size());
  }

  /** 验证 MAX_TOKENS 导致 LENGTH 截断终态，且不生成 replayState（未闭合或截断无 replay）。 */
  @Test
  void handlesFinishReasonMaxTokens_resultsInLengthStopReasonWithoutReplay() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Partial text..." }] },
            "finishReason": "MAX_TOKENS"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();

    assertEquals(GenerationStopReason.LENGTH, completion.response().stopReason());
    assertNull(completion.replayState(), "LENGTH truncated output must not have replayState");
  }

  /** 验证 SAFETY/RECITATION 等过滤原因映射为 FILTERED 终态，无 replayState。 */
  @Test
  void handlesSafetyFilterFinishReason_resultsInFilteredStopReasonWithoutReplay() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Unsafe..." }] },
            "finishReason": "SAFETY"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();

    assertEquals(GenerationStopReason.FILTERED, completion.response().stopReason());
    assertNull(completion.replayState());
  }

  /**
   * 意图：ESCALATION 表示请求被升级规则过滤，属于过滤终态而非协议错误，必须映射为 FILTERED， 同时忽略 tool call 且不生成
   * replayState，避免把被过滤的输出当作可回放上下文。
   */
  @Test
  void handlesEscalationFinishReason_resultsInFilteredStopReasonWithoutToolsOrReplay()
      throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "Escalated..." },
              { "functionCall": { "name": "do_something", "args": { "k": "v" } } }
            ] },
            "finishReason": "ESCALATION"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();

    assertEquals(GenerationStopReason.FILTERED, completion.response().stopReason());
    assertTrue(completion.response().toolCalls().isEmpty());
    assertNull(completion.replayState());
  }

  /** 验证 promptFeedback blockReason（输入提示词被拒绝且无 candidate）映射为 FILTERED。 */
  @Test
  void handlesPromptFeedbackBlockReason_resultsInFilteredStopReason() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "promptFeedback": {
            "blockReason": "SAFETY"
          },
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 0,
            "totalTokenCount": 10
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();

    assertEquals(GenerationStopReason.FILTERED, completion.response().stopReason());
    assertEquals("", completion.response().text());
    assertNull(completion.replayState());
  }

  /** 验证 MALFORMED_FUNCTION_CALL 异常终态被转换为 ProviderException(INVALID_RESPONSE)。 */
  @Test
  void handlesMalformedFunctionCall_throwsInvalidResponse() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "error" }] },
            "finishReason": "MALFORMED_FUNCTION_CALL"
          }]
        }
        """;

    ProviderException ex =
        assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    assertTrue(ex.getMessage().contains("MALFORMED_FUNCTION_CALL"));
  }

  /** 验证流结束时没有合法 finishReason 或 promptFeedback 时，明确抛出 INVALID_RESPONSE（不以 HTTP 200/EOF 单独当成功）。 */
  @Test
  void rejectsStreamEndingWithoutTerminalFinishReason() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Incomplete without finishReason" }] }
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    assertEquals("Gemini stream completed without terminal finish reason", ex.getMessage());
  }

  /** 测试意图：UNSPECIFIED 大小写均为非终态，后续 STOP 接收新文本且保留前帧内容。 */
  @ParameterizedTest
  @ValueSource(strings = {"FINISH_REASON_UNSPECIFIED", "finish_reason_unspecified"})
  void unspecifiedFinishReasonPreservesTextUntilStop(String finishReason) throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"parts":[{"text":"Hello "}]},
        "finishReason":"%s"}]}
        """
            .formatted(finishReason));
    assertEquals(List.of(new ProviderStreamEvent.TextDelta("Hello ")), emittedEvents);

    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"parts":[{"text":"world!"}]},"finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}
        """);
    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals("Hello world!", completion.response().text());
    assertEquals("", completion.response().thinking());
    assertTrue(completion.response().toolCalls().isEmpty());
    assertEquals(
        List.of(
            new ProviderStreamEvent.TextDelta("Hello "),
            new ProviderStreamEvent.TextDelta("world!")),
        emittedEvents);
    assertNotNull(completion.replayState());
    assertEquals(
        MAPPER.readTree("{\"role\":\"model\",\"parts\":[{\"text\":\"Hello world!\"}]}"),
        completion.replayState().payload());
  }

  /** 测试意图：UNSPECIFIED 帧即使已交付文本，EOF 仍缺少终态，不能合成成功或多发增量。 */
  @ParameterizedTest
  @ValueSource(strings = {"FINISH_REASON_UNSPECIFIED", "finish_reason_unspecified"})
  void unspecifiedFinishReasonAtEofFailsWithoutAdditionalEvents(String finishReason) {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"parts":[{"text":"partial"}]},
        "finishReason":"%s"}],
        "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}
        """
            .formatted(finishReason));
    assertEquals(List.of(new ProviderStreamEvent.TextDelta("partial")), emittedEvents);
    ProviderException error = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals("Gemini stream completed without terminal finish reason", error.getMessage());
    assertEquals(List.of(new ProviderStreamEvent.TextDelta("partial")), emittedEvents);
  }

  /** 验证 usageMetadata 规范化映射到 ModelUsage，互斥非负，rawUsageJson 无损保留全部字段。 */
  @Test
  void normalizesUsageMetadata_promptOrdinaryAndCached_mutuallyExclusive() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Done" }] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 100,
            "cachedContentTokenCount": 80,
            "candidatesTokenCount": 25,
            "thoughtsTokenCount": 10,
            "totalTokenCount": 125,
            "serviceTier": "priority",
            "promptTokensDetails": [{"modality": "TEXT", "tokenCount": 100}],
            "futureUsage": {"nested": [1, true]}
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    ModelUsage usage = response.usage();
    assertNotNull(usage);
    assertEquals(20L, usage.inputTokens(), "promptTokenCount(100) - cached(80) = 20");
    assertEquals(80L, usage.cacheReadTokens());
    assertEquals(35L, usage.outputTokens(), "candidates plus thoughts");
    assertEquals(10L, usage.reasoningTokens());
    assertEquals(125L, usage.providerTotalTokens());

    // 未知字段与 token detail 数组必须无损保留，不只是一份已知计数白名单。
    assertNotNull(response.rawUsageJson());
    JsonNode raw = MAPPER.readTree(response.rawUsageJson());
    assertEquals(MAPPER.readTree(chunk).get("usageMetadata"), raw);
    assertEquals("priority", response.serviceTier());
  }

  /** 测试意图：全部已知计数均使用 long，且仅最新 usage 快照决定 normalized/raw/serviceTier。 */
  @Test
  void retainsLatestLargeUsageSnapshot() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        "{\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"serviceTier\":\"old\"}}");
    String latest =
        """
        {"promptTokenCount":4000000000,"cachedContentTokenCount":3000000000,
        "candidatesTokenCount":3500000000,"thoughtsTokenCount":3200000000,
        "totalTokenCount":7500000000,"serviceTier":"flex",
        "candidatesTokensDetails":[{"modality":"TEXT","tokenCount":3500000000}],
        "unknown":{"flag":true}}
        """;
    accumulator.handleEvent(
        "message",
        "{\"usageMetadata\":" + latest + ",\"candidates\":[{\"finishReason\":\"STOP\"}]}");
    ProviderResponse response = accumulator.finish().response();
    assertEquals(1_000_000_000L, response.usage().inputTokens());
    assertEquals(3_000_000_000L, response.usage().cacheReadTokens());
    assertEquals(6_700_000_000L, response.usage().outputTokens());
    assertEquals(3_200_000_000L, response.usage().reasoningTokens());
    assertEquals(7_500_000_000L, response.usage().providerTotalTokens());
    assertEquals("flex", response.serviceTier());
    assertEquals(MAPPER.readTree(latest), MAPPER.readTree(response.rawUsageJson()));
  }

  /** 测试意图：坏类型、负数、超出 long 上限及无效 serviceTier 都不能进入 usage；可选计数显式 null 保持 unknown。 */
  @Test
  void rejectsMalformedKnownUsageValues() {
    for (String value : List.of("null", "\"bad\"", "[]")) {
      GeminiStreamAccumulator accumulator =
          new GeminiStreamAccumulator(request, descriptor, bridge);
      ProviderException error =
          assertThrows(
              ProviderException.class,
              () -> accumulator.handleEvent("message", "{\"usageMetadata\":" + value + "}"));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    }
    for (String field :
        List.of(
            "promptTokenCount",
            "cachedContentTokenCount",
            "candidatesTokenCount",
            "thoughtsTokenCount",
            "totalTokenCount")) {
      List<String> malformed =
          field.equals("thoughtsTokenCount") || field.equals("totalTokenCount")
              ? List.of("\"1\"", "1.5", "true", "-1", "9223372036854775808")
              : List.of("null", "\"1\"", "1.5", "true", "-1", "9223372036854775808");
      for (String value : malformed) {
        GeminiStreamAccumulator accumulator =
            new GeminiStreamAccumulator(request, descriptor, bridge);
        String payload = "{\"usageMetadata\":{\"" + field + "\":" + value + "}}";
        if ("null".equals(value)) {
          accumulator.handleEvent("message", payload);
          ProviderException error = assertThrows(ProviderException.class, accumulator::finish);
          assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind(), field + ":" + value);
        } else {
          ProviderException error =
              assertThrows(
                  ProviderException.class, () -> accumulator.handleEvent("message", payload));
          assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind(), field + ":" + value);
        }
      }
    }
    for (String value : List.of("null", "\"  \"", "7", "false", "{}")) {
      GeminiStreamAccumulator accumulator =
          new GeminiStreamAccumulator(request, descriptor, bridge);
      ProviderException error =
          assertThrows(
              ProviderException.class,
              () ->
                  accumulator.handleEvent(
                      "message", "{\"usageMetadata\":{\"serviceTier\":" + value + "}}"));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    }
  }

  /** 测试意图：可选计数缺失时保留未知语义，未出现 tier 不继承旧值。 */
  @Test
  void doesNotInheritOptionalUsageFields() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        "{\"usageMetadata\":{\"serviceTier\":\"old\",\"promptTokenCount\":10,\"candidatesTokenCount\":5}}");
    accumulator.handleEvent(
        "message",
        "{\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":0,\"cachedContentTokenCount\":3},"
            + "\"candidates\":[{\"finishReason\":\"STOP\"}]}");
    ProviderResponse response = accumulator.finish().response();
    assertEquals(0L, response.usage().inputTokens());
    assertEquals(3L, response.usage().cacheReadTokens());
    assertEquals(0L, response.usage().outputTokens());
    assertNull(response.usage().reasoningTokens());
    assertNull(response.usage().providerTotalTokens());
    assertNull(response.serviceTier());
    assertEquals(
        MAPPER.readTree(
            "{\"promptTokenCount\":3,\"candidatesTokenCount\":0,\"cachedContentTokenCount\":3}"),
        MAPPER.readTree(response.rawUsageJson()));
  }

  /** 意图：验证显式合法的空对象参数 {} 正确保留在 toolCalls 与 replayState 中，不发生异常。 */
  @Test
  void test_validExplicitEmptyObjectFunctionCallArgs() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [{
                "functionCall": {
                  "id": "call_custom_1",
                  "name": "get_current_time",
                  "args": {}
                }
              }]
            },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals(1, response.toolCalls().size());
    ProviderToolCall call = response.toolCalls().get(0);
    assertEquals("call_custom_1", call.id());
    assertEquals("get_current_time", call.name());
    assertEquals("{}", call.argumentsJson());

    assertNotNull(completion.replayState());
    JsonNode replayPart = completion.replayState().payload().get("parts").get(0);
    JsonNode replayFn = replayPart.get("functionCall");
    assertEquals("get_current_time", replayFn.get("name").asString(""));
    assertEquals("call_custom_1", replayFn.get("id").asString(""));
    assertTrue(replayFn.get("args").isObject());
    assertEquals(0, replayFn.get("args").size());
  }

  /** 意图：验证缺失 args 字段的 functionCall 严格抛出 INVALID_RESPONSE，绝不隐式合成 {}。 */
  @Test
  void test_missingFunctionCallArgsThrowsInvalidResponse() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [{
                "functionCall": {
                  "name": "get_time"
                }
              }]
            },
            "finishReason": "STOP"
          }]
        }
        """;

    ProviderException ex =
        assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 意图：验证 args 字段若为非 Object（字符串、数组、数字、null 等），严格抛出 INVALID_RESPONSE 且绝不泄露敏感参数。 */
  @Test
  void test_nonObjectFunctionCallArgsThrowsInvalidResponseAndDoesNotLeakPayload() {
    List<String> invalidArgsList =
        List.of("\"sensitive_password_12345\"", "[1, 2, 3]", "12345", "null");

    for (String invalidArgs : invalidArgsList) {
      GeminiStreamAccumulator accumulator =
          new GeminiStreamAccumulator(request, descriptor, bridge);

      String chunk =
          "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [{\"functionCall\": {\"name\": \"query\", \"args\": "
              + invalidArgs
              + "}}]}, \"finishReason\": \"STOP\"}]}";

      ProviderException ex =
          assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
      assertFalse(
          ex.getMessage().contains("sensitive_password_12345"),
          "exception must not leak raw sensitive argument");
      assertFalse(ex.getMessage().contains("12345"), "exception must not leak raw argument value");
    }
  }

  /** 意图：验证 functionCall name 缺失、空白或非字符串类型时，严格抛出 INVALID_RESPONSE。 */
  @Test
  void test_invalidOrMissingFunctionCallNameThrowsInvalidResponse() {
    List<String> invalidNameSnippets =
        List.of(
            "\"args\": {}",
            "\"name\": \"\", \"args\": {}",
            "\"name\": \"   \", \"args\": {}",
            "\"name\": null, \"args\": {}",
            "\"name\": 123, \"args\": {}",
            "\"name\": [\"tool\"], \"args\": {}");

    for (String snippet : invalidNameSnippets) {
      GeminiStreamAccumulator accumulator =
          new GeminiStreamAccumulator(request, descriptor, bridge);

      String chunk =
          "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [{\"functionCall\": {"
              + snippet
              + "}}]}, \"finishReason\": \"STOP\"}]}";

      ProviderException ex =
          assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证 optional id 若显式提供但为空白或非字符串类型时抛出 INVALID_RESPONSE；若为 null 或省略则允许并合成协议 ID。 */
  @Test
  void test_invalidFunctionCallIdThrowsInvalidResponse() throws Exception {
    List<String> invalidIdSnippets =
        List.of(
            "\"id\": \"\", \"name\": \"query\", \"args\": {}",
            "\"id\": \"   \", \"name\": \"query\", \"args\": {}",
            "\"id\": 123, \"name\": \"query\", \"args\": {}",
            "\"id\": [\"id1\"], \"name\": \"query\", \"args\": {}");

    for (String snippet : invalidIdSnippets) {
      GeminiStreamAccumulator accumulator =
          new GeminiStreamAccumulator(request, descriptor, bridge);

      String chunk =
          "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [{\"functionCall\": {"
              + snippet
              + "}}]}, \"finishReason\": \"STOP\"}]}";

      ProviderException ex =
          assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }

    // 验证 id 为 null 时成功接收并合成 call_0
    GeminiStreamAccumulator validNullIdAcc =
        new GeminiStreamAccumulator(request, descriptor, bridge);
    String validNullIdChunk =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [{
                "functionCall": {
                  "id": null,
                  "name": "query",
                  "args": {}
                }
              }]
            },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """;
    validNullIdAcc.handleEvent("message", validNullIdChunk);
    ProviderCompletion comp = validNullIdAcc.finish();
    assertEquals("call_0", comp.response().toolCalls().get(0).id());
  }

  /** 意图：验证 functionCall 字段本身若为非 Object（标量、数组、null），严格抛出 INVALID_RESPONSE 且不泄露 payload。 */
  @Test
  void test_nonObjectFunctionCallThrowsInvalidResponseAndDoesNotLeakPayload() {
    List<String> nonObjectFcList =
        List.of("\"sensitive_secret_blob_123\"", "[1, 2, 3]", "99999", "null");

    for (String nonObjectFc : nonObjectFcList) {
      GeminiStreamAccumulator accumulator =
          new GeminiStreamAccumulator(request, descriptor, bridge);

      String chunk =
          "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [{\"functionCall\": "
              + nonObjectFc
              + "}]}, \"finishReason\": \"STOP\"}]}";

      ProviderException ex =
          assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
      assertFalse(
          ex.getMessage().contains("sensitive_secret_blob_123"),
          "exception must not leak raw secret payload");
      assertFalse(ex.getMessage().contains("99999"), "exception must not leak raw scalar payload");
    }
  }

  /** 意图：未知官方 Part（inlineData/executableCode 等）整体保留进 replay payload，不再被静默丢弃或补成空 text。 */
  @Test
  void preservesUnknownPartsVerbatimInReplayState() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [
                { "text": "answer" },
                { "inlineData": { "mimeType": "image/png", "data": "QQ==" } },
                { "executableCode": { "language": "PYTHON", "code": "print(1)" } }
              ]
            },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals("answer", completion.response().text());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(3, parts.size());
    assertEquals("answer", parts.get(0).path("text").asString(""));
    assertEquals("image/png", parts.get(1).path("inlineData").path("mimeType").asString(""));
    assertEquals("QQ==", parts.get(1).path("inlineData").path("data").asString(""));
    assertEquals("PYTHON", parts.get(2).path("executableCode").path("language").asString(""));
    assertEquals("print(1)", parts.get(2).path("executableCode").path("code").asString(""));
    assertFalse(parts.get(1).has("text"), "unknown part must not be rewritten as empty text");
  }

  /**
   * 意图：带未知字段的 part 是完整原生边界，绝不按字符串拼接/数组追加/对象递归合并成虚构的单个 part。
   *
   * <p>每个 chunk 的 part 各自原样保留（未知字段值与形状都不被改写），文本仍按 part 顺序确定性累积，durable 文本不丢不重。
   */
  @Test
  void preservesUnknownPartFieldsVerbatimWithoutSpeculativeMerging() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{
              "text": "Hello",
              "futureString": "a",
              "futureArray": [{ "a": 1 }],
              "futureObject": { "nested": { "x": 1 } },
              "futureScalar": 1
            }] }
          }]
        }
        """);
    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{
              "text": " world",
              "futureString": "b",
              "futureArray": [{ "b": 2 }],
              "futureObject": { "nested": { "y": 2 }, "z": 3 },
              "futureScalar": 2
            }] }
          }]
        }
        """);
    accumulator.handleEvent(
        "message",
        """
        {"candidates": [{"content": {"role": "model", "parts": [{"text": "!"}]},
          "finishReason": "STOP"}],
         "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
         }}
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals("Hello world!", completion.response().text());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(3, parts.size(), "每个带未知字段的 part 都必须保留自己的原生边界");

    JsonNode first = parts.get(0);
    assertEquals("Hello", first.path("text").asString(""));
    assertEquals("a", first.path("futureString").asString(""));
    assertEquals(1, first.path("futureArray").size());
    assertEquals(1, first.path("futureArray").get(0).path("a").asInt(0));
    assertEquals(1, first.path("futureObject").path("nested").path("x").asInt(0));
    assertEquals(1, first.path("futureScalar").asInt(0));

    JsonNode second = parts.get(1);
    assertEquals(" world", second.path("text").asString(""));
    assertEquals("b", second.path("futureString").asString(""), "未知字符串字段绝不被跨 chunk 拼接");
    assertEquals(1, second.path("futureArray").size(), "未知数组字段绝不被跨 chunk 追加");
    assertEquals(2, second.path("futureArray").get(0).path("b").asInt(0));
    assertEquals(2, second.path("futureObject").path("nested").path("y").asInt(0));
    assertEquals(3, second.path("futureObject").path("z").asInt(0));
    assertEquals(2, second.path("futureScalar").asInt(0));

    assertEquals("!", parts.get(2).path("text").asString(""));
  }

  /**
   * 意图：未知 union 成员（inlineData / toolResponse）绝不与相邻 part 递归合并成单个合成对象；同一快照重复发送时按原始 JSON 相等去重， 新出现的未知
   * part 按原顺序原样追加，functionCall 边界与未知 part 互不干扰。
   */
  @Test
  void keepsUnknownUnionPartsAsVerbatimBoundaries() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String textAndUnknown =
        """
        {"candidates": [{"content": {"role": "model", "parts": [
          { "text": "answer", "futurePartField": { "trace": "t-1" } },
          { "inlineData": { "mimeType": "image/png", "data": "QQ==" } }
        ]}}]}
        """;
    accumulator.handleEvent("message", textAndUnknown);
    // 同一快照重复发送：完全一致的原生 part 不得重复保留
    accumulator.handleEvent("message", textAndUnknown);
    accumulator.handleEvent(
        "message",
        """
        {"candidates": [{"content": {"role": "model", "parts": [
          { "text": "answer", "futurePartField": { "trace": "t-1" } },
          { "inlineData": { "mimeType": "image/png", "data": "QQ==" } },
          { "toolResponse": { "name": "lookup", "response": { "k": "v" } } },
          { "functionCall": { "id": "c1", "name": "fn", "args": { "k": 1 } } }
        ]}, "finishReason": "STOP"}],
         "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
         }}
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals("answer", completion.response().text(), "重复快照绝不被重复计入文本");
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals("fn", completion.response().toolCalls().get(0).name());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(4, parts.size(), "未知 union 成员与 functionCall 必须各自保留为独立 part");
    assertEquals("t-1", parts.get(0).path("futurePartField").path("trace").asString(""));
    assertEquals("QQ==", parts.get(1).path("inlineData").path("data").asString(""));
    assertFalse(parts.get(1).has("text"), "未知 part 绝不被补成空 text");
    assertEquals("v", parts.get(2).path("toolResponse").path("response").path("k").asString(""));
    assertFalse(parts.get(2).has("text"), "未知 union 成员绝不与相邻文本 part 合并");
    assertEquals("fn", parts.get(3).path("functionCall").path("name").asString(""));
  }

  /**
   * 意图：part 同时声明 text 与 functionCall（同一 data oneof 冲突）时无法写入合法 replay，显式不冻结 replay；
   * 文本与工具调用仍完整交付，绝不静默丢弃其中任何一方。
   */
  @Test
  void skipsReplayFreezingWhenTextAndFunctionCallConflictInOnePart() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        "message",
        """
        {"candidates": [{"content": {"role": "model", "parts": [
          { "text": "partial ", "functionCall": { "id": "c1", "name": "fn", "args": { "k": 1 } } }
        ]}, "finishReason": "STOP"}],
         "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
         }}
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals("partial ", completion.response().text());
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals("fn", completion.response().toolCalls().get(0).name());
    assertNull(completion.replayState(), "无法保真的 union 冲突必须显式不冻结 replay");
  }

  /**
   * 意图：text / thought / thoughtSignature 是文本累积与签名回放直接依赖的已知字段，类型不合法时明确 INVALID_RESPONSE 且不泄露
   * payload； 空白签名与 null 值不承载语义，按不存在处理并保持 part 其它原生事实。
   */
  @Test
  void rejectsMalformedKnownPartFieldsAndIgnoresValuelessKnownFields() throws Exception {
    List<String> malformedParts =
        List.of(
            "{ \"text\": 123 }",
            "{ \"text\": \"think\", \"thought\": \"yes\" }",
            "{ \"text\": \"think\", \"thoughtSignature\": 999 }");
    for (String part : malformedParts) {
      GeminiStreamAccumulator accumulator =
          new GeminiStreamAccumulator(request, descriptor, bridge);
      String chunk =
          "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [" + part + "]}}]}";

      ProviderException ex =
          assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
      assertFalse(ex.getMessage().contains("think"), "exception must not leak part payload");
    }

    GeminiStreamAccumulator valuelessKnownFieldAccumulator =
        new GeminiStreamAccumulator(request, descriptor, bridge);
    String valuelessFieldParts =
        """
        {"candidates": [{"content": {"role": "model", "parts": [
          { "text": "answer", "thoughtSignature": "   " },
          { "text": null, "thoughtSignature": null, "inlineData": { "mimeType": "image/png", "data": "QQ==" } }
        ]}}]}
        """;
    valuelessKnownFieldAccumulator.handleEvent("message", valuelessFieldParts);
    // 同一快照重复发送：即使存在空白签名 / null 已知字段，也必须按可证明重复去重，绝不重复计入文本
    valuelessKnownFieldAccumulator.handleEvent("message", valuelessFieldParts);
    valuelessKnownFieldAccumulator.handleEvent(
        "message",
        """
        {"candidates": [{"content": {"role": "model", "parts": [
          { "text": "answer", "thoughtSignature": "   " },
          { "text": null, "thoughtSignature": null, "inlineData": { "mimeType": "image/png", "data": "QQ==" } }
        ]}, "finishReason": "STOP"}],
         "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
         }}
        """);
    ProviderCompletion completion = valuelessKnownFieldAccumulator.finish();

    assertEquals("answer", completion.response().text());
    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(2, parts.size());
    assertEquals("answer", parts.get(0).path("text").asString(""));
    assertFalse(parts.get(0).has("thoughtSignature"), "空白签名无法写入合法 replay，按不存在处理");
    assertEquals("QQ==", parts.get(1).path("inlineData").path("data").asString(""));
    assertFalse(parts.get(1).has("text"), "null text 无法写入合法 replay，且不承载语义");
    assertFalse(parts.get(1).has("thoughtSignature"));
  }

  /** 意图：同一 slot 的文本 part 在形状为超集且文本严格扩展时按完整累积形态整体替换（未知字段整体替换而非逐字段合并）， 形状不同的新 part 依旧按原顺序原样追加。 */
  @Test
  void replacesUnknownPartFieldsOnCumulativeSnapshot() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{
              "text": "Hello",
              "futureString": "a",
              "futureObject": { "a": 1 }
            }] }
          }]
        }
        """);
    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              {
                "text": "Hello world",
                "futureString": "b",
                "futureObject": { "b": 2 }
              },
              { "inlineData": { "mimeType": "image/jpeg", "data": "AA==" } }
            ] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals("Hello world", completion.response().text());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(2, parts.size());
    assertEquals("Hello world", parts.get(0).path("text").asString(""));
    assertEquals("b", parts.get(0).path("futureString").asString(""));
    assertEquals(2, parts.get(0).path("futureObject").path("b").asInt(0));
    assertFalse(parts.get(0).path("futureObject").has("a"), "snapshot must replace unknown field");
    assertEquals("image/jpeg", parts.get(1).path("inlineData").path("mimeType").asString(""));
  }

  /** 意图：已知 functionCall 以规范化累积结果写回 replay，同时保留 part 级未知字段。 */
  @Test
  void normalizesFunctionCallAndPreservesUnknownPartFields() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{
              "functionCall": { "id": "c1", "name": "fn", "args": { "k": 1 } },
              "futurePartField": { "trace": "t-1" }
            }] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """);

    JsonNode part = accumulator.finish().replayState().payload().get("parts").get(0);
    assertEquals("fn", part.path("functionCall").path("name").asString(""));
    assertEquals("c1", part.path("functionCall").path("id").asString(""));
    assertEquals(1, part.path("functionCall").path("args").path("k").asInt(0));
    assertEquals("t-1", part.path("futurePartField").path("trace").asString(""));
  }

  @Test
  void missingUsageMetadataThrowsInvalidResponseOnFinish() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"parts":[{"text":"Done"}]},"finishReason":"STOP"}]}
        """);
    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    assertTrue(ex.getMessage().contains("missing complete usage"));
  }

  /** proto3 普通 int32 的零值可省略：最终 usage 对象存在但缺 candidates 时解释为零，原始 JSON 不补字段。 */
  @Test
  void omittedCandidatesAreZeroForThinkingExhaustedMaxTokens() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    String usage = "{\"promptTokenCount\":12,\"thoughtsTokenCount\":8,\"totalTokenCount\":20}";
    accumulator.handleEvent(
        "message",
        "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}],\"usageMetadata\":" + usage + "}");
    ProviderResponse response = accumulator.finish().response();
    assertEquals(GenerationStopReason.LENGTH, response.stopReason());
    assertEquals(12L, response.usage().inputTokens());
    assertEquals(8L, response.usage().outputTokens());
    assertEquals(8L, response.usage().reasoningTokens());
    assertEquals(20L, response.usage().providerTotalTokens());
    assertEquals(MAPPER.readTree(usage), MAPPER.readTree(response.rawUsageJson()));
  }

  /** promptFeedback 阻断且 usage 省略 candidates/cache 时，按 proto3 零值完成，不要求显式字段。 */
  @Test
  void omittedCandidatesAreZeroWhenPromptIsBlocked() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"usageMetadata\":{\"promptTokenCount\":9}}");
    ProviderResponse response = accumulator.finish().response();
    assertEquals(GenerationStopReason.FILTERED, response.stopReason());
    assertEquals(9L, response.usage().inputTokens());
    assertEquals(0L, response.usage().outputTokens());
    assertEquals(0L, response.usage().cacheReadTokens());
    assertNull(response.usage().reasoningTokens());
    assertNull(response.usage().providerTotalTokens());
  }

  /** 显式零与省略零的计费结果相同，但原始 JSON 必须保留显式零，不能互相改写。 */
  @Test
  void explicitZeroRemainsDistinctInRawUsage() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    String usage =
        "{\"promptTokenCount\":0,\"candidatesTokenCount\":0,\"cachedContentTokenCount\":0}";
    accumulator.handleEvent(
        "message",
        "{\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":" + usage + "}");
    ProviderResponse response = accumulator.finish().response();
    assertEquals(0L, response.usage().inputTokens());
    assertEquals(0L, response.usage().outputTokens());
    assertEquals(0L, response.usage().cacheReadTokens());
    assertEquals(MAPPER.readTree(usage), MAPPER.readTree(response.rawUsageJson()));
  }

  /** prompt、candidates、cache 的显式 null 不是 proto3 省略，终态必须拒绝。 */
  @ParameterizedTest
  @ValueSource(strings = {"promptTokenCount", "candidatesTokenCount", "cachedContentTokenCount"})
  void explicitNullProto3CountIsInvalid(String field) {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        "{\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":{\""
            + field
            + "\":null}}");
    ProviderException error = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
  }

  @Test
  void realZeroTokensCompletesSuccessfully() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"parts":[{"text":"Zero"}]},"finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":0,"candidatesTokenCount":0,"totalTokenCount":0}}
        """);
    ProviderCompletion completion = accumulator.finish();
    assertEquals(0L, completion.response().usage().inputTokens());
    assertEquals(0L, completion.response().usage().outputTokens());
    assertEquals(0L, completion.response().usage().providerTotalTokens());
  }

  @Test
  void nullableTotalAndThoughtsTokenCount() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {"candidates":[{"content":{"parts":[{"text":"Result"}]},"finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":30,"candidatesTokenCount":20,"thoughtsTokenCount":8}}
        """);
    ProviderCompletion completion = accumulator.finish();
    assertEquals(30L, completion.response().usage().inputTokens());
    assertEquals(
        28L, completion.response().usage().outputTokens(), "candidates 20 + thoughts 8 = 28");
    assertEquals(8L, completion.response().usage().reasoningTokens());
    assertNull(completion.response().usage().providerTotalTokens());
  }

  /**
   * 测试意图：中间帧 usage 只有 promptTokenCount（模型生成中尚未产出 output 计数）， 主代码在 handleEvent 阶段不提前报错，最终帧给出完整 usage
   * 后 finish() 成功完成。
   */
  @Test
  void intermediateUsageWithOnlyPromptSucceedsWhenFinalUsageIsComplete() {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Hello " }] }
          }],
          "usageMetadata": {
            "promptTokenCount": 10
          }
        }
        """);
    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "world!" }] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5,
            "totalTokenCount": 15
          }
        }
        """);

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    assertEquals("Hello world!", completion.response().text());
    ModelUsage usage = completion.response().usage();
    assertNotNull(usage);
    assertEquals(10L, usage.inputTokens());
    assertEquals(5L, usage.outputTokens());
    assertEquals(15L, usage.providerTotalTokens());
  }

  /** 最新快照独立解释：前帧有 candidates 不能填补最终省略值；最终省略按 proto3 为零，原始 JSON 仍只含最终字段。 */
  @Test
  void finalOmittedCandidatesDoNotInheritPreviousFrame() throws Exception {
    // 场景 A：前帧有 candidatesTokenCount，最终帧缺少 candidatesTokenCount
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "chunk1" }] }
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5
          }
        }
        """);
    accumulator.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "chunk2" }] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "promptTokenCount": 12
          }
        }
        """);

    ProviderResponse response = accumulator.finish().response();
    assertEquals(12L, response.usage().inputTokens());
    assertEquals(0L, response.usage().outputTokens());
    assertFalse(MAPPER.readTree(response.rawUsageJson()).has("candidatesTokenCount"));

    // 场景 B：前帧有 promptTokenCount，最终帧缺少 promptTokenCount
    GeminiStreamAccumulator accumulator2 = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator2.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "chunk1" }] }
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 5
          }
        }
        """);
    accumulator2.handleEvent(
        "message",
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "chunk2" }] },
            "finishReason": "STOP"
          }],
          "usageMetadata": {
            "candidatesTokenCount": 8
          }
        }
        """);

    ProviderResponse response2 = accumulator2.finish().response();
    assertEquals(0L, response2.usage().inputTokens());
    assertEquals(8L, response2.usage().outputTokens());
    assertFalse(MAPPER.readTree(response2.rawUsageJson()).has("promptTokenCount"));
  }

  /**
   * 测试意图：即使 usageMetadata 仅包含部分字段（非完整 mandatory usage）， 只要显式给出的字段值是非法数字（负数、浮点、非数字字符串、超出 long 范围等），
   * 必须在 handleEvent 阶段立即拒绝，不推迟到 finish()。
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"promptTokenCount\": -1}",
        "{\"promptTokenCount\": 1.5}",
        "{\"promptTokenCount\": \"bad\"}",
        "{\"promptTokenCount\": 9223372036854775808}",
        "{\"candidatesTokenCount\": -5}",
        "{\"candidatesTokenCount\": 3.14}",
        "{\"candidatesTokenCount\": \"invalid\"}",
        "{\"cachedContentTokenCount\": -1}",
        "{\"thoughtsTokenCount\": -1}",
        "{\"totalTokenCount\": -1}"
      })
  void partialUsageWithExplicitInvalidNumberRejectedImmediately(String partialUsage) {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> accumulator.handleEvent("message", "{\"usageMetadata\":" + partialUsage + "}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /**
   * 测试意图：最终 usageMetadata 快照中的未知扩展字段（如嵌套对象、自定义数组、布尔与数字标记等） 必须原样保真保留在 rawUsageJson 中，不得被白名单过滤或丢失。
   */
  @Test
  void rawUsageFinalSnapshotPreservesUnknownFields() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    String finalUsageJson =
        """
        {
          "promptTokenCount": 20,
          "candidatesTokenCount": 10,
          "totalTokenCount": 30,
          "customBillingInfo": {
            "accountId": "acc-12345",
            "tierDetails": ["fast", "extra"]
          },
          "experimentalFeatureEnabled": true,
          "unknownMetric": 999
        }
        """;
    accumulator.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":"
            + finalUsageJson
            + "}");

    ProviderCompletion completion = accumulator.finish();
    String rawJson = completion.response().rawUsageJson();
    assertNotNull(rawJson);
    JsonNode expected = MAPPER.readTree(finalUsageJson);
    JsonNode actual = MAPPER.readTree(rawJson);
    assertEquals(expected, actual);
    assertTrue(actual.has("customBillingInfo"));
    assertEquals("acc-12345", actual.path("customBillingInfo").path("accountId").asString(""));
    assertTrue(actual.path("experimentalFeatureEnabled").asBoolean(false));
    assertEquals(999, actual.path("unknownMetric").asInt(0));
  }

  /** 2026-10-24 Gemini 代表的真实 usage 数字；候选外壳是合成的，不是完整原始响应。 */
  @Test
  void geminiObservedFinalSnapshotReplacesEarlierUsage() throws Exception {
    JsonNode fixture =
        MAPPER.readTree(
            GeminiStreamAccumulatorTest.class.getResourceAsStream("gemini-usage-2026-10-24.json"));
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        "message", syntheticGeminiFrame(fixture.path("first"), null).toString());
    accumulator.handleEvent(
        "message", syntheticGeminiFrame(fixture.path("last"), "STOP").toString());
    ProviderResponse response = accumulator.finish().response();
    assertEquals(143L, response.usage().inputTokens());
    assertEquals(111L, response.usage().outputTokens());
    assertEquals(110L, response.usage().reasoningTokens());
    assertEquals(254L, response.usage().providerTotalTokens());
    assertEquals(fixture.path("last"), MAPPER.readTree(response.rawUsageJson()));
  }

  private static ObjectNode syntheticGeminiFrame(JsonNode usage, String finishReason) {
    ObjectNode frame = MAPPER.createObjectNode();
    ObjectNode candidate = frame.putArray("candidates").addObject();
    if (finishReason != null) {
      candidate.put("finishReason", finishReason);
    }
    frame.set("usageMetadata", usage);
    return frame;
  }
}

package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Gemini 流式累加器及 SSE 消息解析、去重、状态机测试。 */
class GeminiStreamAccumulatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProviderDescriptor descriptor;
  private ProviderRequest request;
  private List<ProviderStreamEvent> emittedEvents;
  private GeminiStreamBridge bridge;

  private final ProviderStreamHandler handler =
      new ProviderStreamHandler() {
        @Override
        public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
          emittedEvents.add(event);
        }

        @Override
        public void onError(ProviderException error, ProviderStream stream) {}

        @Override
        public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
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

    ModelPricing pricing =
        new ModelPricing(
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

    ModelDescriptor model =
        new ModelDescriptor(
            "google-test",
            "gemini-2.5-flash",
            "gemini-2.5-flash",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing);

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
    bridge = new GeminiStreamBridge(handler);
  }

  /** 验证纯增量流中多次分片 text 正确合并，不丢失、不双计。 */
  @Test
  void handlesPureIncrementalTextStream() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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

  /** 验证全量快照重复流中，文本增量被正确提取去重，绝不重复拼接。 */
  @Test
  void handlesSnapshotRepetitiveTextStream_deduplicatesAccumulatedPrefix() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Partial text..." }] },
            "finishReason": "MAX_TOKENS"
          }]
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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Unsafe..." }] },
            "finishReason": "SAFETY"
          }]
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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "Escalated..." },
              { "functionCall": { "name": "do_something", "args": { "k": "v" } } }
            ] },
            "finishReason": "ESCALATION"
          }]
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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    String chunk =
        """
        {
          "promptFeedback": {
            "blockReason": "SAFETY"
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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "Incomplete without finishReason" }] }
          }]
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 验证 usageMetadata 规范化映射到 ModelUsage，互斥非负，rawUsageJson 仅白名单。 */
  @Test
  void normalizesUsageMetadata_promptOrdinaryAndCached_mutuallyExclusive() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
            "totalTokenCount": 125
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
    assertEquals(25L, usage.outputTokens());
    assertEquals(10L, usage.reasoningTokens());
    assertEquals(125L, usage.providerTotalTokens());

    // 检查 rawUsageJson 仅保留官方白名单字段
    assertNotNull(response.rawUsageJson());
    JsonNode raw = MAPPER.readTree(response.rawUsageJson());
    assertEquals(100, raw.get("promptTokenCount").asInt());
    assertEquals(80, raw.get("cachedContentTokenCount").asInt());
    assertEquals(25, raw.get("candidatesTokenCount").asInt());
    assertEquals(10, raw.get("thoughtsTokenCount").asInt());
    assertEquals(125, raw.get("totalTokenCount").asInt());
  }

  /** 意图：验证显式合法的空对象参数 {} 正确保留在 toolCalls 与 replayState 中，不发生异常。 */
  @Test
  void test_validExplicitEmptyObjectFunctionCallArgs() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
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
    assertEquals("get_current_time", replayFn.get("name").asText());
    assertEquals("call_custom_1", replayFn.get("id").asText());
    assertTrue(replayFn.get("args").isObject());
    assertEquals(0, replayFn.get("args").size());
  }

  /** 意图：验证缺失 args 字段的 functionCall 严格抛出 INVALID_RESPONSE，绝不隐式合成 {}。 */
  @Test
  void test_missingFunctionCallArgsThrowsInvalidResponse() {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          new GeminiStreamAccumulator(
              request,
              descriptor,
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              bridge);

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
          new GeminiStreamAccumulator(
              request,
              descriptor,
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              bridge);

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
          new GeminiStreamAccumulator(
              request,
              descriptor,
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              bridge);

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
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);
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
          }]
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
          new GeminiStreamAccumulator(
              request,
              descriptor,
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              bridge);

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
}

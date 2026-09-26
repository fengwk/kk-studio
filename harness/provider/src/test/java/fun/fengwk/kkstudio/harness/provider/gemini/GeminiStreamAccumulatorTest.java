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

  /** 意图：未知官方 Part（inlineData/executableCode 等）整体保留进 replay payload，不再被静默丢弃或补成空 text。 */
  @Test
  void preservesUnknownPartsVerbatimInReplayState() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
        }
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals("answer", completion.response().text());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(3, parts.size());
    assertEquals("answer", parts.get(0).path("text").asText());
    assertEquals("image/png", parts.get(1).path("inlineData").path("mimeType").asText());
    assertEquals("QQ==", parts.get(1).path("inlineData").path("data").asText());
    assertEquals("PYTHON", parts.get(2).path("executableCode").path("language").asText());
    assertEquals("print(1)", parts.get(2).path("executableCode").path("code").asText());
    assertFalse(parts.get(1).has("text"), "unknown part must not be rewritten as empty text");
  }

  /**
   * 意图：带未知字段的 part 是完整原生边界，绝不按字符串拼接/数组追加/对象递归合并成虚构的单个 part。
   *
   * <p>每个 chunk 的 part 各自原样保留（未知字段值与形状都不被改写），文本仍按 part 顺序确定性累积，durable 文本不丢不重。
   */
  @Test
  void preservesUnknownPartFieldsVerbatimWithoutSpeculativeMerging() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          "finishReason": "STOP"}]}
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals("Hello world!", completion.response().text());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(3, parts.size(), "每个带未知字段的 part 都必须保留自己的原生边界");

    JsonNode first = parts.get(0);
    assertEquals("Hello", first.path("text").asText());
    assertEquals("a", first.path("futureString").asText());
    assertEquals(1, first.path("futureArray").size());
    assertEquals(1, first.path("futureArray").get(0).path("a").asInt());
    assertEquals(1, first.path("futureObject").path("nested").path("x").asInt());
    assertEquals(1, first.path("futureScalar").asInt());

    JsonNode second = parts.get(1);
    assertEquals(" world", second.path("text").asText());
    assertEquals("b", second.path("futureString").asText(), "未知字符串字段绝不被跨 chunk 拼接");
    assertEquals(1, second.path("futureArray").size(), "未知数组字段绝不被跨 chunk 追加");
    assertEquals(2, second.path("futureArray").get(0).path("b").asInt());
    assertEquals(2, second.path("futureObject").path("nested").path("y").asInt());
    assertEquals(3, second.path("futureObject").path("z").asInt());
    assertEquals(2, second.path("futureScalar").asInt());

    assertEquals("!", parts.get(2).path("text").asText());
  }

  /**
   * 意图：未知 union 成员（inlineData / toolResponse）绝不与相邻 part 递归合并成单个合成对象；同一快照重复发送时按原始 JSON 相等去重， 新出现的未知
   * part 按原顺序原样追加，functionCall 边界与未知 part 互不干扰。
   */
  @Test
  void keepsUnknownUnionPartsAsVerbatimBoundaries() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
        ]}, "finishReason": "STOP"}]}
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals("answer", completion.response().text(), "重复快照绝不被重复计入文本");
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals("fn", completion.response().toolCalls().get(0).name());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(4, parts.size(), "未知 union 成员与 functionCall 必须各自保留为独立 part");
    assertEquals("t-1", parts.get(0).path("futurePartField").path("trace").asText());
    assertEquals("QQ==", parts.get(1).path("inlineData").path("data").asText());
    assertFalse(parts.get(1).has("text"), "未知 part 绝不被补成空 text");
    assertEquals("v", parts.get(2).path("toolResponse").path("response").path("k").asText());
    assertFalse(parts.get(2).has("text"), "未知 union 成员绝不与相邻文本 part 合并");
    assertEquals("fn", parts.get(3).path("functionCall").path("name").asText());
  }

  /**
   * 意图：part 同时声明 text 与 functionCall（同一 data oneof 冲突）时无法写入合法 replay，显式不冻结 replay；
   * 文本与工具调用仍完整交付，绝不静默丢弃其中任何一方。
   */
  @Test
  void skipsReplayFreezingWhenTextAndFunctionCallConflictInOnePart() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleEvent(
        "message",
        """
        {"candidates": [{"content": {"role": "model", "parts": [
          { "text": "partial ", "functionCall": { "id": "c1", "name": "fn", "args": { "k": 1 } } }
        ]}, "finishReason": "STOP"}]}
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
          new GeminiStreamAccumulator(
              request,
              descriptor,
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              bridge);
      String chunk =
          "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [" + part + "]}}]}";

      ProviderException ex =
          assertThrows(ProviderException.class, () -> accumulator.handleEvent("message", chunk));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
      assertFalse(ex.getMessage().contains("think"), "exception must not leak part payload");
    }

    GeminiStreamAccumulator valuelessKnownFieldAccumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);
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
        ]}, "finishReason": "STOP"}]}
        """);
    ProviderCompletion completion = valuelessKnownFieldAccumulator.finish();

    assertEquals("answer", completion.response().text());
    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(2, parts.size());
    assertEquals("answer", parts.get(0).path("text").asText());
    assertFalse(parts.get(0).has("thoughtSignature"), "空白签名无法写入合法 replay，按不存在处理");
    assertEquals("QQ==", parts.get(1).path("inlineData").path("data").asText());
    assertFalse(parts.get(1).has("text"), "null text 无法写入合法 replay，且不承载语义");
    assertFalse(parts.get(1).has("thoughtSignature"));
  }

  /** 意图：同一 slot 的文本 part 在形状为超集且文本严格扩展时按完整累积形态整体替换（未知字段整体替换而非逐字段合并）， 形状不同的新 part 依旧按原顺序原样追加。 */
  @Test
  void replacesUnknownPartFieldsOnCumulativeSnapshot() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
        }
        """);

    ProviderCompletion completion = accumulator.finish();
    assertEquals("Hello world", completion.response().text());

    JsonNode parts = completion.replayState().payload().get("parts");
    assertEquals(2, parts.size());
    assertEquals("Hello world", parts.get(0).path("text").asText());
    assertEquals("b", parts.get(0).path("futureString").asText());
    assertEquals(2, parts.get(0).path("futureObject").path("b").asInt());
    assertFalse(parts.get(0).path("futureObject").has("a"), "snapshot must replace unknown field");
    assertEquals("image/jpeg", parts.get(1).path("inlineData").path("mimeType").asText());
  }

  /** 意图：已知 functionCall 以规范化累积结果写回 replay，同时保留 part 级未知字段。 */
  @Test
  void normalizesFunctionCallAndPreservesUnknownPartFields() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
        }
        """);

    JsonNode part = accumulator.finish().replayState().payload().get("parts").get(0);
    assertEquals("fn", part.path("functionCall").path("name").asText());
    assertEquals("c1", part.path("functionCall").path("id").asText());
    assertEquals(1, part.path("functionCall").path("args").path("k").asInt());
    assertEquals("t-1", part.path("futurePartField").path("trace").asText());
  }
}

package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
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

/** 测试意图：全面验证 OpenAI Chat 流式累积器状态机、事件派发、终态映射、截断诊断、互斥用量度量及 Replay 保真。 */
class OpenAiChatStreamAccumulatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProviderDescriptor descriptor;
  private ModelDescriptor modelDesc;
  private ProviderRequest request;
  private List<ProviderStreamEvent> recordedEvents;
  private OpenAiChatStreamBridge bridge;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "openai",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "standard",
            "tier1",
            BigDecimal.ONE,
            "v1",
            new BigDecimal("2.50"),
            new BigDecimal("10.00"),
            new BigDecimal("1.25"),
            new BigDecimal("1.25"),
            new BigDecimal("1.25"),
            new BigDecimal("10.00"));
    modelDesc =
        new ModelDescriptor(
            "openai", "gpt-4o", "gpt-4o", Set.of(ModelInputModality.TEXT), true, false, pricing);
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")));
    ModelVariant defaultVariant = new ModelVariant("default");
    request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());

    recordedEvents = new ArrayList<>();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            recordedEvents.add(event);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };
    bridge = new OpenAiChatStreamBridge(handler);
  }

  @Test
  @DisplayName("文本 content delta 派发与尾部 usage 读取")
  void testContentDeltaAndTrailingUsage() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData(
        "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}");
    accumulator.handleData(
        "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world!\"},\"finish_reason\":\"stop\"}]}");
    accumulator.handleData(
        """
        {
          "id": "c1",
          "choices": [],
          "usage": {
            "prompt_tokens": 10,
            "completion_tokens": 5,
            "total_tokens": 15,
            "prompt_tokens_details": {"cached_tokens": 2}
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertEquals("Hello world!", completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    // 验证事件流派发
    assertEquals(2, recordedEvents.size());
    assertEquals("Hello", ((ProviderStreamEvent.TextDelta) recordedEvents.get(0)).text());
    assertEquals(" world!", ((ProviderStreamEvent.TextDelta) recordedEvents.get(1)).text());

    // 验证互斥用量
    ModelUsage usage = completion.response().usage();
    assertEquals(8, usage.inputTokens()); // 10 - 2
    assertEquals(5, usage.outputTokens());
    assertEquals(2, usage.cacheReadTokens());
    assertEquals(0, usage.cacheWriteTokens());
    assertEquals(0, usage.reasoningTokens());
    assertEquals(15, usage.totalTokens());
    assertEquals(15, usage.categorizedTokens());

    // 验证生成了合法的 ReplayState
    assertNotNull(completion.replayState());
    assertEquals(ProviderReplayFormat.OPENAI_CHAT, completion.replayState().format());
    assertEquals("Hello world!", completion.replayState().payload().path("content").asText());
  }

  @Test
  @DisplayName("reasoning_content 派发 ThinkingDelta，reasoning_details 仅存 native replay")
  void testReasoningContentAndDetails() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData(
        """
        {
          "id": "c2",
          "choices": [{
            "index": 0,
            "delta": {
              "role": "assistant",
              "reasoning_content": "Pondering problem...",
              "reasoning_details": [{"step": 1, "status": "ok"}]
            }
          }]
        }
        """);
    accumulator.handleData(
        """
        {
          "id": "c2",
          "choices": [{
            "index": 0,
            "delta": {
              "content": "42",
              "reasoning_details": [{"step": 2, "status": "done"}]
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": 20,
            "completion_tokens": 10,
            "total_tokens": 30,
            "completion_tokens_details": {"reasoning_tokens": 8}
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertEquals("42", completion.response().text());
    assertEquals("Pondering problem...", completion.response().thinking());

    // 验证派发了 ThinkingDelta 与 TextDelta，reasoning_details 未被拼为普通文本
    assertEquals(2, recordedEvents.size());
    assertTrue(recordedEvents.get(0) instanceof ProviderStreamEvent.ThinkingDelta);
    assertEquals(
        "Pondering problem...", ((ProviderStreamEvent.ThinkingDelta) recordedEvents.get(0)).text());
    assertTrue(recordedEvents.get(1) instanceof ProviderStreamEvent.TextDelta);
    assertEquals("42", ((ProviderStreamEvent.TextDelta) recordedEvents.get(1)).text());

    // 验证 usage
    ModelUsage usage = completion.response().usage();
    assertEquals(20, usage.inputTokens());
    assertEquals(2, usage.outputTokens()); // 10 - 8
    assertEquals(8, usage.reasoningTokens());

    // 验证 replay payload 保真了 reasoning_content 与 reasoning_details
    assertNotNull(completion.replayState());
    assertEquals(
        "Pondering problem...",
        completion.replayState().payload().path("reasoning_content").asText());
    assertEquals(2, completion.replayState().payload().path("reasoning_details").size());
  }

  @Test
  @DisplayName("单工具与并行工具片段聚合与完成")
  void testToolCallsAggregation() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData(
        """
        {
          "id": "c3",
          "choices": [{
            "index": 0,
            "delta": {
              "tool_calls": [
                {"index": 0, "id": "call_1", "type": "function", "function": {"name": "f1", "arguments": "{\\"a\\":"}},
                {"index": 1, "id": "call_2", "type": "function", "function": {"name": "f2", "arguments": "{\\"b\\":"}}
              ]
            }
          }]
        }
        """);
    accumulator.handleData(
        """
        {
          "id": "c3",
          "choices": [{
            "index": 0,
            "delta": {
              "tool_calls": [
                {"index": 0, "function": {"arguments": "1}"}},
                {"index": 1, "function": {"arguments": "2}"}}
              ]
            },
            "finish_reason": "tool_calls"
          }]
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals(2, completion.response().toolCalls().size());
    assertEquals("call_1", completion.response().toolCalls().get(0).id());
    assertEquals("f1", completion.response().toolCalls().get(0).name());
    assertEquals("{\"a\":1}", completion.response().toolCalls().get(0).argumentsJson());

    assertEquals("call_2", completion.response().toolCalls().get(1).id());
    assertEquals("f2", completion.response().toolCalls().get(1).name());
    assertEquals("{\"b\":2}", completion.response().toolCalls().get(1).argumentsJson());

    // 验证 replay payload
    assertNotNull(completion.replayState());
    assertEquals(2, completion.replayState().payload().path("tool_calls").size());
  }

  @Test
  @DisplayName("LENGTH 截断：未闭合工具调用转诊断，不补 JSON，不执行，不存 replay")
  void testLengthTruncationDiagnostics() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData(
        """
        {
          "id": "c4",
          "choices": [{
            "index": 0,
            "delta": {
              "tool_calls": [
                {"index": 0, "id": "call_trunc", "function": {"name": "bash", "arguments": "{\\"cmd\\":\\"cat /var/log"}}
              ]
            },
            "finish_reason": "length"
          }]
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.LENGTH, completion.response().stopReason());
    assertTrue(completion.response().toolCalls().isEmpty()); // 未闭合工具调用不进入 toolCalls
    assertEquals(1, completion.response().toolCallDiagnostics().size());
    assertEquals(
        "{\"cmd\":\"cat /var/log",
        completion.response().toolCallDiagnostics().get(0).partialArguments());
    assertNull(completion.replayState()); // LENGTH 截断不可保存 replay
  }

  @Test
  @DisplayName("FILTERED 终态：清空 toolCalls，不存 replay")
  void testContentFilterFinalState() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData(
        "{\"id\":\"c5\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"censored\"},\"finish_reason\":\"content_filter\"}]}");
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.FILTERED, completion.response().stopReason());
    assertTrue(completion.response().toolCalls().isEmpty());
    assertNull(completion.replayState());
  }

  @Test
  @DisplayName("未知 finish_reason 明确报错")
  void testUnsupportedFinishReason() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    assertThrows(
        ProviderException.class,
        () -> accumulator.handleData("{\"choices\":[{\"finish_reason\":\"unknown_reason\"}]}"));
  }

  @Test
  @DisplayName("openAiChatRequireDone 缺省为 true 时缺少 [DONE] 报错")
  void testMissingDoneWhenRequired() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}");
    assertThrows(ProviderException.class, accumulator::finish);
  }

  @Test
  @DisplayName("openAiChatRequireDone 为 false 时已看到有效 finish_reason 且 EOF 可完成")
  void testOptionalDoneWithValidFinishReason() {
    OpenAiChatConfiguration config =
        new OpenAiChatConfiguration(
            true, false, Set.of(), OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            config,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}");
    // 没有 [DONE]，直接 EOF finish
    ProviderCompletion completion = accumulator.finish();
    assertEquals("hi", completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
  }

  @Test
  @DisplayName("openAiChatRequireDone 为 false 时未见有效 finish_reason 单纯 EOF 报错")
  void testOptionalDoneWithoutFinishReasonFailsOnEof() {
    OpenAiChatConfiguration config =
        new OpenAiChatConfiguration(
            true, false, Set.of(), OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request,
            descriptor,
            config,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

    accumulator.handleData("{\"choices\":[{\"delta\":{\"content\":\"incomplete\"}}]}");
    assertThrows(ProviderException.class, accumulator::finish);
  }

  @Test
  @DisplayName("三种 read placement 之标准 nested prompt_tokens_details.cached_tokens 互斥度量与原始 JSON 保持")
  void testUsageReadPlacementNestedPromptTokensDetails() throws Exception {
    // 测试意图：验证标准 nested prompt_tokens_details.cached_tokens 正确解析为 cacheReadTokens，
    // 按 ordinaryInput = max(0, prompt_tokens - cacheRead - cacheWrite) 规则互斥扣减，并完整保真 rawUsageJson。
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"nested\"},\"finish_reason\":\"stop\"}]}");
    accumulator.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 30,
            "total_tokens": 130,
            "prompt_tokens_details": {
              "cached_tokens": 40
            }
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ModelUsage usage = completion.response().usage();
    assertEquals(40L, usage.cacheReadTokens());
    assertEquals(0L, usage.cacheWriteTokens());
    assertEquals(60L, usage.inputTokens());
    assertEquals(30L, usage.outputTokens());
    assertEquals(130L, usage.totalTokens());
    assertEquals(130L, usage.categorizedTokens());

    JsonNode rawUsage = MAPPER.readTree(completion.response().rawUsageJson());
    assertEquals(40L, rawUsage.path("prompt_tokens_details").path("cached_tokens").asLong());
  }

  @Test
  @DisplayName("三种 read placement 之 DeepSeek 顶层 prompt_cache_hit_tokens 互斥度量与 miss tokens 隔离")
  void testUsageReadPlacementDeepSeekTopLevelHitAndMissMutualExclusion() throws Exception {
    // 测试意图：验证 DeepSeek 风格顶层 prompt_cache_hit_tokens 解析为 cacheReadTokens，
    // prompt_cache_miss_tokens 作为普通未缓存输入由 ordinaryInput 吸收且绝不误算为 cacheWriteTokens，
    // rawUsageJson 保留 hit 与 miss 原始事实。
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"deepseek\"},\"finish_reason\":\"stop\"}]}");
    accumulator.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 20,
            "total_tokens": 120,
            "prompt_cache_hit_tokens": 75,
            "prompt_cache_miss_tokens": 25
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ModelUsage usage = completion.response().usage();
    assertEquals(75L, usage.cacheReadTokens());
    assertEquals(0L, usage.cacheWriteTokens());
    assertEquals(25L, usage.inputTokens());
    assertEquals(20L, usage.outputTokens());
    assertEquals(120L, usage.totalTokens());
    assertEquals(120L, usage.categorizedTokens());

    JsonNode rawUsage = MAPPER.readTree(completion.response().rawUsageJson());
    assertEquals(75L, rawUsage.path("prompt_cache_hit_tokens").asLong());
    assertEquals(25L, rawUsage.path("prompt_cache_miss_tokens").asLong());
  }

  @Test
  @DisplayName("三种 read placement 之兼容顶层 cached_tokens 互斥度量与 rawUsageJson 保持")
  void testUsageReadPlacementTopLevelCachedTokens() throws Exception {
    // 测试意图：验证部分兼容实现（如 Kimi）顶层 cached_tokens 正确解析为 cacheReadTokens，
    // 完成 ordinaryInput 互斥扣减并保真 rawUsageJson。
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"kimi\"},\"finish_reason\":\"stop\"}]}");
    accumulator.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 80,
            "completion_tokens": 15,
            "total_tokens": 95,
            "cached_tokens": 30
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ModelUsage usage = completion.response().usage();
    assertEquals(30L, usage.cacheReadTokens());
    assertEquals(0L, usage.cacheWriteTokens());
    assertEquals(50L, usage.inputTokens());
    assertEquals(15L, usage.outputTokens());
    assertEquals(95L, usage.totalTokens());
    assertEquals(95L, usage.categorizedTokens());

    JsonNode rawUsage = MAPPER.readTree(completion.response().rawUsageJson());
    assertEquals(30L, rawUsage.path("cached_tokens").asLong());
  }

  @Test
  @DisplayName("优先级覆盖：标准 nested 字段优先于顶层 prompt_cache_hit_tokens 与 cached_tokens")
  void testUsageNestedPriorityOverTopLevelHitAndTopLevelCached() {
    // 测试意图：验证明确优先级：当 nested prompt_tokens_details.cached_tokens、顶层 prompt_cache_hit_tokens
    // 与顶层 cached_tokens 同时存在时，必须严格取 nested cached_tokens。
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"priority\"},\"finish_reason\":\"stop\"}]}");
    accumulator.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 10,
            "total_tokens": 110,
            "prompt_cache_hit_tokens": 50,
            "cached_tokens": 60,
            "prompt_tokens_details": {
              "cached_tokens": 20
            }
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ModelUsage usage = completion.response().usage();
    assertEquals(20L, usage.cacheReadTokens());
    assertEquals(80L, usage.inputTokens());
  }

  @Test
  @DisplayName("优先级覆盖：无 nested 时顶层 prompt_cache_hit_tokens 优先于顶层 cached_tokens")
  void testUsageTopLevelHitPriorityOverTopLevelCached() {
    // 测试意图：验证在无 nested 缓存字段时，顶层 prompt_cache_hit_tokens 优先级高于顶层 cached_tokens。
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"fallback\"},\"finish_reason\":\"stop\"}]}");
    accumulator.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 10,
            "total_tokens": 110,
            "prompt_cache_hit_tokens": 45,
            "cached_tokens": 55
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ModelUsage usage = completion.response().usage();
    assertEquals(45L, usage.cacheReadTokens());
    assertEquals(55L, usage.inputTokens());
  }

  @Test
  @DisplayName("支持 nested cache_write_tokens 与 cache_creation_input_tokens")
  void testUsageCacheWriteTokensSupport() {
    // 测试意图：验证 cache write 支持 nested cache_write_tokens 与 cache_creation_input_tokens 两种命名，
    // 并与 prompt_tokens 构成三方互斥度量。
    OpenAiChatStreamAccumulator accumulator1 = createAccumulator();
    accumulator1.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"w1\"},\"finish_reason\":\"stop\"}]}");
    accumulator1.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 20,
            "total_tokens": 120,
            "prompt_tokens_details": {
              "cached_tokens": 30,
              "cache_write_tokens": 20
            }
          }
        }
        """);
    accumulator1.handleData("[DONE]");

    ProviderCompletion completion1 = accumulator1.finish();
    ModelUsage usage1 = completion1.response().usage();
    assertEquals(30L, usage1.cacheReadTokens());
    assertEquals(20L, usage1.cacheWriteTokens());
    assertEquals(50L, usage1.inputTokens()); // 100 - 30 - 20

    OpenAiChatStreamAccumulator accumulator2 = createAccumulator();
    accumulator2.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"w2\"},\"finish_reason\":\"stop\"}]}");
    accumulator2.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 20,
            "total_tokens": 120,
            "prompt_tokens_details": {
              "cached_tokens": 15,
              "cache_creation_input_tokens": 25
            }
          }
        }
        """);
    accumulator2.handleData("[DONE]");

    ProviderCompletion completion2 = accumulator2.finish();
    ModelUsage usage2 = completion2.response().usage();
    assertEquals(15L, usage2.cacheReadTokens());
    assertEquals(25L, usage2.cacheWriteTokens());
    assertEquals(60L, usage2.inputTokens()); // 100 - 15 - 25
  }

  @Test
  @DisplayName("无缓存字段时 cache 用量默认为 0，完全无 usage 时所有用量归零")
  void testUsageWithoutCacheFieldsDefaultsToZero() {
    // 测试意图：验证 usage 中不存在任何缓存字段时，cacheRead 与 cacheWrite 默认为 0，
    // inputTokens 与 prompt_tokens 完全一致；且流中未发送 usage 块时所有用量均安全默认为 0。
    OpenAiChatStreamAccumulator accumulator1 = createAccumulator();
    accumulator1.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"plain\"},\"finish_reason\":\"stop\"}]}");
    accumulator1.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 50,
            "completion_tokens": 10,
            "total_tokens": 60
          }
        }
        """);
    accumulator1.handleData("[DONE]");

    ProviderCompletion completion1 = accumulator1.finish();
    ModelUsage usage1 = completion1.response().usage();
    assertEquals(0L, usage1.cacheReadTokens());
    assertEquals(0L, usage1.cacheWriteTokens());
    assertEquals(50L, usage1.inputTokens());
    assertEquals(10L, usage1.outputTokens());
    assertEquals(60L, usage1.totalTokens());

    OpenAiChatStreamAccumulator accumulator2 = createAccumulator();
    accumulator2.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"no_usage\"},\"finish_reason\":\"stop\"}]}");
    accumulator2.handleData("[DONE]");

    ProviderCompletion completion2 = accumulator2.finish();
    ModelUsage usage2 = completion2.response().usage();
    assertEquals(0L, usage2.inputTokens());
    assertEquals(0L, usage2.outputTokens());
    assertEquals(0L, usage2.cacheReadTokens());
    assertEquals(0L, usage2.cacheWriteTokens());
    assertEquals(0L, usage2.totalTokens());
    assertEquals("{}", completion2.response().rawUsageJson());
  }

  @Test
  @DisplayName(
      "缺失 total_tokens 时 providerTotalTokens 严格为 0，不合成 prompt+completion，保留原生 rawUsageJson")
  void testMissingTotalTokensPreservesZeroWithoutSynthesis() {
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData(
        """
        {"id":"chat-no-total","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":15,"completion_tokens":25}}
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ModelUsage usage = completion.response().usage();
    assertEquals(15L, usage.inputTokens());
    assertEquals(25L, usage.outputTokens());
    // 关键断言：缺失 total_tokens 时严格置 0，不合成 40
    assertEquals(0L, usage.providerTotalTokens());
    assertEquals(0L, usage.totalTokens());
    assertEquals(40L, usage.categorizedTokens());

    // 验证 rawUsageJson 原样保留，未合成 total_tokens 字段
    String rawJson = completion.response().rawUsageJson();
    assertTrue(rawJson.contains("\"prompt_tokens\":15"));
    assertTrue(rawJson.contains("\"completion_tokens\":25"));
    assertFalse(rawJson.contains("total_tokens"));
  }

  @Test
  @DisplayName("非法负数严格映射为 INVALID_RESPONSE，绝不被静默篡改")
  void testUsageNegativeFieldsFailStrictResponseValidation() {
    // 测试意图：验证所有计量维度的非法负数在接收 usage 块时立即失败，确保异步回调可稳定映射为
    // INVALID_RESPONSE，而不是让未检查异常逃逸或将负数静默截断。
    assertInvalidUsage(
        "{\"prompt_tokens\":-1,\"completion_tokens\":10,\"total_tokens\":9}", "prompt_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":-1}}",
        "prompt_tokens_details.cached_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20,"
            + "\"prompt_cache_hit_tokens\":-1}",
        "prompt_cache_hit_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20,"
            + "\"cached_tokens\":-1}",
        "cached_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":10,\"completion_tokens\":-1,\"total_tokens\":9}", "completion_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":-1}", "total_tokens");
  }

  @Test
  @DisplayName("usage 非整数或 details 非 Object 时严格映射为 INVALID_RESPONSE")
  void testUsageInvalidShapesFailStrictResponseValidation() {
    // 测试意图：验证字符串、浮点数、null 计量值及错误 details shape 不会被 Jackson asLong
    // 宽松转换为 0；流式中间块的 usage:null 仍按协议允许。
    assertInvalidUsage(
        "{\"prompt_tokens\":\"10\",\"completion_tokens\":1,\"total_tokens\":11}", "prompt_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":10.5,\"completion_tokens\":1,\"total_tokens\":11}", "prompt_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":null,\"completion_tokens\":1,\"total_tokens\":1}", "prompt_tokens");
    assertInvalidUsage(
        "{\"prompt_tokens\":10,\"completion_tokens\":1,\"total_tokens\":11,"
            + "\"prompt_tokens_details\":[]}",
        "prompt_tokens_details");

    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData("{\"usage\":null,\"choices\":[]}");
  }

  @Test
  @DisplayName("promptTokens 小于 cachedTokens 时 ordinaryInput 互斥计算安全截断为 0")
  void testUsagePromptTokensLessThanCachedTokensClampedToZero() {
    // 测试意图：验证当上游报告的合法 cachedTokens 大于 promptTokens 时，
    // ordinaryInput = max(0, prompt_tokens - cacheRead - cacheWrite) 正确截断为 0，
    // 且合法通过 ModelUsage 校验。
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"content\":\"clamp\"},\"finish_reason\":\"stop\"}]}");
    accumulator.handleData(
        """
        {
          "usage": {
            "prompt_tokens": 20,
            "completion_tokens": 10,
            "total_tokens": 30,
            "prompt_cache_hit_tokens": 30
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ModelUsage usage = completion.response().usage();
    assertEquals(0L, usage.inputTokens());
    assertEquals(30L, usage.cacheReadTokens());
    assertEquals(10L, usage.outputTokens());
  }

  private OpenAiChatStreamAccumulator createAccumulator() {
    return new OpenAiChatStreamAccumulator(
        request,
        descriptor,
        OpenAiChatConfiguration.defaults(),
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        bridge);
  }

  private void assertInvalidUsage(String usageJson, String field) {
    OpenAiChatStreamAccumulator accumulator = createAccumulator();
    ProviderException failure =
        assertThrows(
            ProviderException.class, () -> accumulator.handleData("{\"usage\":" + usageJson + "}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failure.kind());
    assertTrue(failure.getMessage().contains(field));
  }
}

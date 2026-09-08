package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            "openai", "gpt-4o", Set.of(ModelInputModality.TEXT), true, false, pricing);
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")));
    ModelVariant defaultVariant =
        new ModelVariant("default", null, null, null, null, null, null, null, null);
    request =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(userMsg), List.of(), ProviderCacheControl.none());

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
}

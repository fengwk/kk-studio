package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
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

/**
 * 测试意图：深度验证 DeepSeek / OpenAI 兼容推理字段（reasoning_content, reasoning_details）流式聚合、 ThinkingDelta
 * 派发、用量结算及跨轮 ASSISTANT 回放保真。
 */
class OpenAiChatThinkingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProviderDescriptor descriptor;
  private ModelDescriptor modelDesc;
  private List<ProviderStreamEvent> recordedEvents;
  private OpenAiChatStreamBridge bridge;
  private OpenAiChatRequestEncoder encoder;

  private ModelVariant defaultVariant;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "deepseek",
            ProviderType.OPENAI,
            "https://api.deepseek.com/v1",
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
            "deepseek", "deepseek-reasoner", Set.of(ModelInputModality.TEXT), true, true, pricing);
    defaultVariant = new ModelVariant("default", null, null, null, null, null, null, null, null);
    encoder = new OpenAiChatRequestEncoder();
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
  @DisplayName("DeepSeek 推理流式聚合、事件派发与次轮 ASSISTANT 原位回放保真")
  void testThinkingStreamAndTurnTwoReplay() throws Exception {
    // 1. 首轮请求编码
    ProviderMessage user1 =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("What is 1+1?")));
    ProviderRequest turn1Request =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(user1), List.of(), ProviderCacheControl.none());

    OpenAiChatEncodedRequest encoded1 =
        encoder.encode(turn1Request, descriptor, OpenAiChatConfiguration.defaults());
    String prefixHash = encoded1.sourcePrefixHash();

    // 2. 模拟收到包含 reasoning_content 与 reasoning_details 的 SSE 流
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            turn1Request, descriptor, OpenAiChatConfiguration.defaults(), prefixHash, bridge);

    accumulator.handleData(
        """
        {"id":"ds-1","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Step 1: calculate."}}]}
        """);
    accumulator.handleData(
        """
        {"id":"ds-1","choices":[{"index":0,"delta":{"reasoning_content":" Step 2: result is 2."}}]}
        """);
    accumulator.handleData(
        """
        {"id":"ds-1","choices":[{"index":0,"delta":{"content":"The sum is 2.","reasoning_details":{"confidence":0.99}},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":25,"total_tokens":35,"completion_tokens_details":{"reasoning_tokens":18}}}
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertEquals("The sum is 2.", completion.response().text());
    assertEquals("Step 1: calculate. Step 2: result is 2.", completion.response().thinking());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals(18, completion.response().usage().reasoningTokens());

    // 校验 ThinkingDelta 事件派发
    assertEquals(3, recordedEvents.size());
    assertTrue(recordedEvents.get(0) instanceof ProviderStreamEvent.ThinkingDelta);
    assertEquals(
        "Step 1: calculate.", ((ProviderStreamEvent.ThinkingDelta) recordedEvents.get(0)).text());
    assertTrue(recordedEvents.get(1) instanceof ProviderStreamEvent.ThinkingDelta);
    assertEquals(
        " Step 2: result is 2.",
        ((ProviderStreamEvent.ThinkingDelta) recordedEvents.get(1)).text());
    assertTrue(recordedEvents.get(2) instanceof ProviderStreamEvent.TextDelta);
    assertEquals("The sum is 2.", ((ProviderStreamEvent.TextDelta) recordedEvents.get(2)).text());

    // 校验 ReplayState
    ProviderReplayState replayState = completion.replayState();
    assertNotNull(replayState);
    assertEquals(ProviderReplayFormat.OPENAI_CHAT, replayState.format());
    assertEquals(descriptor.affinity("deepseek-reasoner"), replayState.affinity());
    assertEquals(prefixHash, replayState.sourcePrefixHash());

    JsonNode payload = replayState.payload();
    assertEquals("The sum is 2.", payload.path("content").asText());
    assertEquals(
        "Step 1: calculate. Step 2: result is 2.", payload.path("reasoning_content").asText());
    assertEquals(0.99, payload.path("reasoning_details").path("confidence").asDouble());

    // 3. 次轮对话携带 ReplayState，验证 wire 上原位保真
    ProviderMessage turn1Asst =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("The sum is 2.")),
            replayState);
    ProviderMessage user2 =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("What about 2+2?")));
    ProviderRequest turn2Request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(user1, turn1Asst, user2),
            List.of(),
            ProviderCacheControl.none());

    OpenAiChatEncodedRequest encoded2 =
        encoder.encode(turn2Request, descriptor, OpenAiChatConfiguration.defaults());
    JsonNode root2 = MAPPER.readTree(encoded2.bodyUtf8Bytes());
    ArrayNode messages2 = (ArrayNode) root2.path("messages");
    assertEquals(3, messages2.size());

    JsonNode wireAsst = messages2.get(1);
    assertEquals("assistant", wireAsst.path("role").asText());
    assertEquals("The sum is 2.", wireAsst.path("content").asText());
    assertEquals(
        "Step 1: calculate. Step 2: result is 2.", wireAsst.path("reasoning_content").asText());
    assertEquals(0.99, wireAsst.path("reasoning_details").path("confidence").asDouble());
  }
}

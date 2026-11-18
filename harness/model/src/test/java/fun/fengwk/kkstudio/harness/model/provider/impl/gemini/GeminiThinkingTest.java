package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Gemini 思考过程、thoughtSignature 签名及多轮回放测试。 */
class GeminiThinkingTest {

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
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));

    ModelDescriptor model =
        new ModelDescriptor(
            "google-test",
            "gemini-2.5-pro",
            "gemini-2.5-pro",
            Set.of(ModelInputModality.TEXT),
            true,
            true);

    ModelVariant variant = new ModelVariant("default", null, PromptCacheRetention.NONE, null);
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

  /** 验证空文本 thought 的 thoughtSignature 保存在对应 thinking block 的 provider data 中。 */
  @Test
  void handlesEmptyThoughtWithSignature() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [
                { "text": "", "thought": true, "thoughtSignature": "valid_sig_empty_thought" },
                { "text": "Result after empty thought." }
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

    accumulator.handleEvent(chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals("", response.thinking());
    assertEquals("Result after empty thought.", response.text());

    ProviderThinkingBlock emptyThought = (ProviderThinkingBlock) response.blocks().get(0);
    assertEquals("", emptyThought.thinking());
    assertEquals("valid_sig_empty_thought", emptyThought.providerBlockData().signature());
    assertTrue(emptyThought.providerBlockData().thought());
  }

  /** 验证多个连续 reasoning parts 被累加，并保留签名。 */
  @Test
  void handlesMultipleReasoningParts() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    String chunk1 =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [
                { "text": "Step 1: analyze.", "thought": true },
                { "text": " Step 2: compute.", "thought": true, "thoughtSignature": "step2_sig" }
              ]
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
              "parts": [{ "text": "42" }]
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

    accumulator.handleEvent(chunk1);
    accumulator.handleEvent(chunk2);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals("Step 1: analyze. Step 2: compute.", response.thinking());
    assertEquals("42", response.text());
  }

  /** 验证 Gemini 3 并行工具调用：首个工具带 thoughtSignature，后续并行工具无签名；绝不伪造签名。 */
  @Test
  void handlesGemini3ParallelToolCalls_preservesFirstSignatureWithoutFabrication()
      throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    // Gemini 3 协议中，模型决定调用两个工具时，首个 part 可能携带 thoughtSignature，而第二个没有
    String chunk =
        """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [
                {
                  "functionCall": { "name": "tool_first", "args": { "q": 1 } },
                  "thoughtSignature": "first_call_signature"
                },
                {
                  "functionCall": { "name": "tool_second", "args": { "q": 2 } }
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

    accumulator.handleEvent(chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    List<ProviderToolCall> calls = response.toolCalls();
    assertEquals(2, calls.size());

    assertEquals(2, response.blocks().size());
    ProviderToolCallBlock firstCall = (ProviderToolCallBlock) response.blocks().get(0);
    ProviderToolCallBlock secondCall = (ProviderToolCallBlock) response.blocks().get(1);
    assertEquals("first_call_signature", firstCall.providerBlockData().signature());
    assertEquals("tool_first", firstCall.toolCall().name());
    assertEquals("tool_second", secondCall.toolCall().name());
    assertNull(secondCall.providerBlockData().signature());
  }

  /** 意图：空文本签名 part 是合法的原生事实，重复 part event 也必须保留原生边界与签名。 */
  @Test
  void retainsEmptyTextSignedPartInPlaceWithoutMerging() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "think", "thought": true, "thoughtSignature": "sig_1" },
              { "text": "", "thought": true, "thoughtSignature": "sig_empty" }
            ] }
          }]
        }
        """);
    accumulator.handleEvent(
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "think", "thought": true, "thoughtSignature": "sig_1" },
              { "text": "", "thought": true, "thoughtSignature": "sig_empty" },
              { "text": "answer" }
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
    assertEquals("thinkthink", completion.response().thinking());
    assertEquals("answer", completion.response().text());
    assertEquals(5, completion.response().blocks().size());
    assertEquals(
        "think", ((ProviderThinkingBlock) completion.response().blocks().get(0)).thinking());
    assertEquals(
        "sig_1",
        ((ProviderThinkingBlock) completion.response().blocks().get(0))
            .providerBlockData()
            .signature());
    assertEquals("answer", ((ProviderTextBlock) completion.response().blocks().get(4)).text());

    List<String> thinkingDeltas =
        emittedEvents.stream()
            .filter(e -> e instanceof ProviderStreamEvent.ThinkingDelta)
            .map(e -> ((ProviderStreamEvent.ThinkingDelta) e).text())
            .toList();
    assertEquals(List.of("think", "think"), thinkingDeltas);

    List<ProviderContentBlock> blocks = completion.response().blocks();
    assertEquals(5, blocks.size());
    ProviderThinkingBlock firstThought = (ProviderThinkingBlock) blocks.get(0);
    ProviderThinkingBlock emptyThought = (ProviderThinkingBlock) blocks.get(1);
    ProviderThinkingBlock repeatedThought = (ProviderThinkingBlock) blocks.get(2);
    ProviderThinkingBlock repeatedEmptyThought = (ProviderThinkingBlock) blocks.get(3);
    assertEquals("think", firstThought.thinking());
    assertEquals("sig_1", firstThought.providerBlockData().signature());
    assertEquals("", emptyThought.thinking());
    assertEquals("sig_empty", emptyThought.providerBlockData().signature());
    assertEquals("sig_1", repeatedThought.providerBlockData().signature());
    assertEquals("sig_empty", repeatedEmptyThought.providerBlockData().signature());
    assertEquals("answer", ((ProviderTextBlock) blocks.get(4)).text());
  }

  /** 意图：每个带 thoughtSignature 的事件都原位独立保留，签名精确、顺序不变、绝不跨 part 迁移。 */
  @Test
  void keepsDistinctSignedPartsAsSeparateVerbatimReplayParts() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "think A", "thought": true, "thoughtSignature": "sig_a" }
            ] }
          }]
        }
        """);
    // 相同签名 part 再次出现仍是独立事件，不能按内容相等去重。
    accumulator.handleEvent(
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "think A", "thought": true, "thoughtSignature": "sig_a" },
              { "text": " think B", "thought": true, "thoughtSignature": "sig_b" },
              { "text": "answer" }
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
    assertEquals("think Athink A think B", completion.response().thinking());
    assertEquals("answer", completion.response().text());

    List<ProviderContentBlock> blocks = completion.response().blocks();
    assertEquals(4, blocks.size(), "两个签名事件与文本 part 必须各自保留");
    ProviderThinkingBlock first = (ProviderThinkingBlock) blocks.get(0);
    ProviderThinkingBlock repeated = (ProviderThinkingBlock) blocks.get(1);
    ProviderThinkingBlock second = (ProviderThinkingBlock) blocks.get(2);
    assertEquals("think A", first.thinking());
    assertEquals("sig_a", first.providerBlockData().signature());
    assertTrue(first.providerBlockData().thought());
    assertEquals("think A", repeated.thinking());
    assertEquals("sig_a", repeated.providerBlockData().signature());
    assertEquals(" think B", second.thinking());
    assertEquals("sig_b", second.providerBlockData().signature());
    assertEquals("answer", ((ProviderTextBlock) blocks.get(3)).text());
    assertNull(((ProviderTextBlock) blocks.get(3)).providerBlockData().signature());
  }

  /** 意图：不同签名的 part event 绝不合并或覆盖签名；两份原生事实按原顺序各自保留。 */
  @Test
  void preservesSignaturesAcrossDistinctSignedEvents() throws Exception {
    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);

    accumulator.handleEvent(
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "think", "thought": true, "thoughtSignature": "sig_1" }
            ] }
          }]
        }
        """);
    accumulator.handleEvent(
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "think more", "thought": true, "thoughtSignature": "sig_2" }
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
    List<ProviderContentBlock> blocks = completion.response().blocks();
    assertEquals(2, blocks.size(), "带不同签名的累积形态绝不合并成单个 part");
    ProviderThinkingBlock first = (ProviderThinkingBlock) blocks.get(0);
    ProviderThinkingBlock second = (ProviderThinkingBlock) blocks.get(1);
    assertEquals("think", first.thinking());
    assertEquals("sig_1", first.providerBlockData().signature(), "本地签名绝不被上游覆盖");
    assertEquals("think more", second.thinking());
    assertEquals("sig_2", second.providerBlockData().signature());
  }

  /**
   * 意图：累积器冻结的原生 replay 必须真的可回放——签名与未知 union 成员原样上线，durable text/thinking 校验通过，
   * 而不是交付一个只能在结构上自洽、下一轮却会被拒绝的 payload。
   */
  @Test
  void replaysAccumulatorFrozenSignedAndUnknownPartsOnNextTurn() throws Exception {
    GeminiRequestEncoder encoder = new GeminiRequestEncoder();
    ModelVariant variant = new ModelVariant("default", null, PromptCacheRetention.NONE, null);

    GeminiStreamAccumulator accumulator = new GeminiStreamAccumulator(request, descriptor, bridge);
    accumulator.handleEvent(
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [
              { "text": "think", "thought": true, "thoughtSignature": "sig_stream" },
              { "text": "answer", "futurePartField": { "trace": "t-1" } },
              { "inlineData": { "mimeType": "image/png", "data": "QQ==" } }
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
    ProviderResponse response = completion.response();
    assertEquals("think", response.thinking());
    assertEquals("answer", response.text());
    ProviderRequest secondTurn =
        new ProviderRequest(
            request.model(),
            variant,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Round 1"))),
                new ProviderMessage(ProviderMessageRole.ASSISTANT, response.blocks()),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Round 2")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode secondTurnJson =
        MAPPER.readTree(encoder.encode(secondTurn, descriptor).bodyUtf8Bytes());
    ArrayNode modelParts = (ArrayNode) secondTurnJson.get("contents").get(1).get("parts");
    assertEquals(3, modelParts.size());
    assertEquals("sig_stream", modelParts.get(0).get("thoughtSignature").asString(""));
    assertTrue(modelParts.get(0).get("thought").asBoolean(false));
    assertEquals("t-1", modelParts.get(1).path("futurePartField").path("trace").asString(""));
    assertEquals("QQ==", modelParts.get(2).path("inlineData").path("data").asString(""));
  }
}

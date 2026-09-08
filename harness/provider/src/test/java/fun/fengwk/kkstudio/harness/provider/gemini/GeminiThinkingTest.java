package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Gemini 思考过程、thoughtSignature 签名及多轮回放测试。 */
class GeminiThinkingTest {

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
            "google-test", "gemini-2.5-pro", Set.of(ModelInputModality.TEXT), true, true, pricing);

    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    request =
        new ProviderRequest(model, variant, List.of(), List.of(), ProviderCacheControl.none());

    emittedEvents = new ArrayList<>();
    bridge = new GeminiStreamBridge(handler);
  }

  /** 验证空文本 thought 但带有 thoughtSignature 时，签名能正确记录在 ReplayState 中。 */
  @Test
  void handlesEmptyThoughtWithSignature() throws Exception {
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
                { "text": "", "thought": true, "thoughtSignature": "valid_sig_empty_thought" },
                { "text": "Result after empty thought." }
              ]
            },
            "finishReason": "STOP"
          }]
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals("", response.thinking());
    assertEquals("Result after empty thought.", response.text());

    assertNotNull(completion.replayState());
    JsonNode replayPayload = completion.replayState().payload();
    ArrayNode parts = (ArrayNode) replayPayload.get("parts");
    assertEquals("valid_sig_empty_thought", parts.get(0).get("thoughtSignature").asText());
  }

  /** 验证多个连续 reasoning parts 被累加，并保留签名。 */
  @Test
  void handlesMultipleReasoningParts() throws Exception {
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
          }]
        }
        """;

    accumulator.handleEvent("message", chunk1);
    accumulator.handleEvent("message", chunk2);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals("Step 1: analyze. Step 2: compute.", response.thinking());
    assertEquals("42", response.text());
  }

  /** 验证 Gemini 3 并行工具调用：首个工具带 thoughtSignature，后续并行工具无签名；绝不伪造签名。 */
  @Test
  void handlesGemini3ParallelToolCalls_preservesFirstSignatureWithoutFabrication()
      throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request,
            descriptor,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);

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
          }]
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    List<ProviderToolCall> calls = response.toolCalls();
    assertEquals(2, calls.size());

    assertNotNull(completion.replayState());
    JsonNode replayPayload = completion.replayState().payload();
    ArrayNode parts = (ArrayNode) replayPayload.get("parts");
    assertEquals(2, parts.size());

    // 第一个 part 保留原生 thoughtSignature
    assertEquals("first_call_signature", parts.get(0).get("thoughtSignature").asText());
    // 第二个 part 绝不伪造 thoughtSignature
    assertFalse(
        parts.get(1).has("thoughtSignature"),
        "Second parallel tool call must not fabricate thoughtSignature");
  }

  /** 验证多轮对话中，包含 thoughtSignature 的 ReplayState 经过编码器完整发送回服务端。 */
  @Test
  void preservesThoughtSignatureInReplayAcrossTurns() throws Exception {
    GeminiRequestEncoder encoder = new GeminiRequestEncoder();

    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ProviderRequest req1 =
        new ProviderRequest(
            request.model(),
            variant,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Round 1")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest enc1 = encoder.encode(req1, descriptor);
    String prefixHash = enc1.sourcePrefixHash();

    // 构造第一轮返回的 replayState
    var replayPayload = MAPPER.createObjectNode();
    replayPayload.put("role", "model");
    ArrayNode parts = replayPayload.putArray("parts");
    var p0 = parts.addObject();
    p0.put("text", "thinking round 1");
    p0.put("thought", true);
    p0.put("thoughtSignature", "sig_round_1");
    var p1 = parts.addObject();
    p1.put("text", "answer round 1");

    var replayState =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor.affinity("gemini-2.5-pro"),
            prefixHash,
            replayPayload);

    // 第二轮请求
    ProviderRequest req2 =
        new ProviderRequest(
            request.model(),
            variant,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Round 1"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderThinkingBlock("thinking round 1"),
                        new ProviderTextBlock("answer round 1")),
                    replayState),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Round 2")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest enc2 = encoder.encode(req2, descriptor);
    JsonNode req2Json = MAPPER.readTree(enc2.bodyUtf8Bytes());

    ArrayNode modelParts = (ArrayNode) req2Json.get("contents").get(1).get("parts");
    assertEquals("sig_round_1", modelParts.get(0).get("thoughtSignature").asText());
    assertEquals("answer round 1", modelParts.get(1).get("text").asText());
  }
}

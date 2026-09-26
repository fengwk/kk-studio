package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 测试意图：验证 provider 原生 assistant message 的累积与 replay 保真——delta 的字符串/对象/标量按规则合并，官方非规范化字段（audio、
 * function_call、未来字段）进入 replay 并可经次轮编码器原样回放，chunk 级 transport metadata 与 n&gt;1 的其他 choice 不混入， 而
 * known 字段与 durable 不一致时仍然失败；native-only 字段在 affinity/hash 失配时必须 fail closed。
 */
class OpenAiChatNativeReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OpenAiChatRequestEncoder encoder;
  private ProviderDescriptor descriptor;
  private ModelDescriptor modelDesc;
  private ModelVariant defaultVariant;
  private OpenAiChatStreamBridge bridge;

  @BeforeEach
  void setUp() {
    encoder = new OpenAiChatRequestEncoder();
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
            "openai",
            "gpt-4o-audio",
            "gpt-4o-audio",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            pricing);
    defaultVariant = new ModelVariant("default");
    bridge =
        new OpenAiChatStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {}
            });
  }

  @Test
  @DisplayName("audio / function_call / 未来字段无损进入 replay 并可在次轮原样回放")
  void replaysProviderNativeAssistantFieldsThroughNextTurn() throws Exception {
    ProviderMessage turn1User =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Say hi")));
    ProviderRequest turn1Req = request(turn1User);
    String sourcePrefixHash =
        encoder.encode(turn1Req, descriptor, OpenAiChatConfiguration.defaults()).sourcePrefixHash();

    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            turn1Req, descriptor, OpenAiChatConfiguration.defaults(), sourcePrefixHash, bridge);
    // chunk 级 transport metadata（id/object/created/model/service_tier/usage）不得进入 assistant message
    accumulator.handleData(
        """
        {
          "id": "c-audio",
          "object": "chat.completion.chunk",
          "created": 1758880000,
          "model": "gpt-4o-audio",
          "service_tier": "default",
          "choices": [{
            "index": 0,
            "delta": {
              "role": "assistant",
              "content": "Hello ",
              "audio": {"id": "audio_1", "data": "AAA", "transcript": "Hello "},
              "function_call": {"name": "legacy_lookup", "arguments": "{\\"q\\":"},
              "vendor_future_field": {"trace_id": "t-1", "attempt": 1}
            },
            "finish_reason": null
          }]
        }
        """);
    accumulator.handleData(
        """
        {
          "id": "c-audio",
          "choices": [{
            "index": 0,
            "delta": {
              "content": "world",
              "audio": {"data": "AAABBB", "transcript": "world", "expires_at": 1758889999},
              "function_call": {"arguments": "\\"hi\\"}"},
              "vendor_future_field": {"attempt": 2}
            },
            "finish_reason": "stop"
          }],
          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertEquals("Hello world", completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    JsonNode payload = completion.replayState().payload();
    // 字符串增量追加、object 递归合并、标量覆盖
    assertEquals("Hello world", payload.path("content").asText());
    assertEquals("audio_1", payload.path("audio").path("id").asText());
    assertEquals("AAAAAABBB", payload.path("audio").path("data").asText());
    assertEquals("Hello world", payload.path("audio").path("transcript").asText());
    assertEquals(1758889999L, payload.path("audio").path("expires_at").asLong());
    assertEquals("legacy_lookup", payload.path("function_call").path("name").asText());
    assertEquals("{\"q\":\"hi\"}", payload.path("function_call").path("arguments").asText());
    assertEquals("t-1", payload.path("vendor_future_field").path("trace_id").asText());
    assertEquals(2, payload.path("vendor_future_field").path("attempt").asInt());

    // chunk 级 transport metadata 不进入 assistant message
    for (String transportField :
        List.of("id", "object", "created", "model", "service_tier", "usage")) {
      assertFalse(payload.has(transportField), transportField);
    }

    // 次轮请求：原生字段与 known 字段一起原样回放到 wire
    ProviderMessage turn1Asst =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("Hello world")),
            completion.replayState());
    ProviderMessage turn2User =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Again")));
    JsonNode wireRoot =
        MAPPER.readTree(
            encoder
                .encode(
                    request(turn1User, turn1Asst, turn2User),
                    descriptor,
                    OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode wireAsst = wireRoot.path("messages").get(2);
    assertEquals("assistant", wireAsst.path("role").asText());
    assertEquals("Hello world", wireAsst.path("content").asText());
    assertEquals("audio_1", wireAsst.path("audio").path("id").asText());
    assertEquals("AAAAAABBB", wireAsst.path("audio").path("data").asText());
    assertEquals("legacy_lookup", wireAsst.path("function_call").path("name").asText());
    assertEquals("t-1", wireAsst.path("vendor_future_field").path("trace_id").asText());
  }

  @Test
  @DisplayName("only choices[0] 进入 normalized 与 replay，n>1 的其他 choice 不混入")
  void ignoresNonZeroChoiceDeltas() {
    ProviderRequest turn1Req =
        request(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi"))));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            turn1Req,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);
    // 一帧携带 n=2：只有 choices[0] 的 delta 与 finish_reason 被消费
    accumulator.handleData(
        """
        {
          "id": "c-n",
          "choices": [
            {"index": 0, "delta": {"role": "assistant", "content": "main"}, "finish_reason": null},
            {"index": 1, "delta": {"role": "assistant", "content": "other"}, "finish_reason": null}
          ]
        }
        """);
    accumulator.handleData(
        """
        {
          "id": "c-n",
          "choices": [
            {"index": 0, "delta": {"content": " answer"}, "finish_reason": "stop"},
            {"index": 1, "delta": {"content": "secondary"}, "finish_reason": "stop"}
          ]
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion.replayState());
    assertEquals("main answer", completion.response().text());
    JsonNode payload = completion.replayState().payload();
    assertEquals("main answer", payload.path("content").asText());
    assertFalse(payload.toString().contains("other"));
    assertFalse(payload.toString().contains("secondary"));
  }

  @Test
  @DisplayName("未知未来字段的 indexed array 片段按 index 合并，新 index 追加为独立槽位")
  void mergesFutureIndexedArrayFragmentsByIndex() {
    ProviderRequest turn1Req =
        request(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi"))));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            turn1Req,
            descriptor,
            OpenAiChatConfiguration.defaults(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            bridge);
    accumulator.handleData(
        """
        {
          "id": "c-steps",
          "choices": [{
            "index": 0,
            "delta": {
              "role": "assistant",
              "content": "done",
              "vendor_steps": [{"index": 0, "text": "a"}]
            },
            "finish_reason": null
          }]
        }
        """);
    accumulator.handleData(
        """
        {
          "id": "c-steps",
          "choices": [{
            "index": 0,
            "delta": {
              "vendor_steps": [
                {"index": 1, "text": "b"},
                {"index": 0, "text": "c", "state": {"phase": "second"}}
              ]
            },
            "finish_reason": "stop"
          }]
        }
        """);
    accumulator.handleData("[DONE]");

    JsonNode payload = accumulator.finish().replayState().payload();
    JsonNode steps = payload.path("vendor_steps");
    assertEquals(2, steps.size());
    assertEquals("ac", steps.get(0).path("text").asText());
    assertEquals("second", steps.get(0).path("state").path("phase").asText());
    assertEquals(1, steps.get(1).path("index").asInt());
    assertEquals("b", steps.get(1).path("text").asText());
  }

  @Test
  @DisplayName("replay 中 known 字段与 durable 不一致时即使携带未知字段也严格失败")
  void rejectsKnownMismatchEvenWithNativeFields() throws Exception {
    ProviderRequest turn1Req =
        request(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi"))));
    String sourcePrefixHash =
        encoder.encode(turn1Req, descriptor, OpenAiChatConfiguration.defaults()).sourcePrefixHash();

    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            turn1Req, descriptor, OpenAiChatConfiguration.defaults(), sourcePrefixHash, bridge);
    accumulator.handleData(
        """
        {"id":"c-tamper","choices":[{"index":0,"delta":{"role":"assistant","content":"honest"},
        "finish_reason":"stop"}]}
        """);
    accumulator.handleData("[DONE]");
    ProviderCompletion completion = accumulator.finish();

    // 篡改 known 字段但保留原生未知字段：affinity/hash 匹配也必须以 durable 一致性为准拒绝
    ObjectNode tampered = completion.replayState().payload().deepCopy();
    tampered.put("content", "tampered");
    tampered.put("vendor_future_field", "kept");
    ProviderReplayState tamperedState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity(modelDesc.modelId()),
            sourcePrefixHash,
            tampered);
    ProviderMessage tamperedAsst =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("honest")), tamperedState);
    ProviderRequest tamperedReq = request(turn1Req.messages().get(0), tamperedAsst);
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(tamperedReq, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());

    // 未篡改时同一 payload 可正常回放，证明失败来自 durable 不一致而非未知字段
    ProviderMessage honestAsst =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("honest")),
            completion.replayState());
    JsonNode honestRoot =
        MAPPER.readTree(
            encoder
                .encode(
                    request(turn1Req.messages().get(0), honestAsst),
                    descriptor,
                    OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode honestWire = honestRoot.path("messages").get(2);
    assertEquals("assistant", honestWire.path("role").asText());
    assertEquals("honest", honestWire.path("content").asText());
  }

  /**
   * 测试意图：native-only 字段（audio、废弃的 function_call、reasoning_content、refusal 区分、tool_calls 的额外嵌套字段）无法由
   * durable 语义等价重建，affinity 或 prefix hash 失配时必须 fail closed；只含 role/文本 content/现代 function
   * tool_calls 的 payload 仍回退语义编码。
   */
  @Test
  @DisplayName("native-only replay 字段失配时 fail closed，最小可重建 payload 仍回退语义编码")
  void failsClosedForNativeOnlyFieldsAndFallsBackForMinimalPayload() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    String mismatchedHash = "0".repeat(64);

    // 1. audio 属于 native-only：hash 失配时 fail closed
    ObjectNode audioPayload = MAPPER.createObjectNode();
    audioPayload.put("role", "assistant");
    audioPayload.put("content", "Hello");
    audioPayload.putObject("audio").put("id", "audio_1");
    assertNativeOnlyReplayRejected(
        user,
        List.of(new ProviderTextBlock("Hello")),
        audioPayload,
        modelAffinity(),
        mismatchedHash);

    // 2. 已废弃的 function_call 属于 native-only：affinity 失配时 fail closed
    ObjectNode legacyCallPayload = MAPPER.createObjectNode();
    legacyCallPayload.put("role", "assistant");
    legacyCallPayload.put("content", "Hello");
    legacyCallPayload.putObject("function_call").put("name", "legacy_lookup");
    assertNativeOnlyReplayRejected(
        user,
        List.of(new ProviderTextBlock("Hello")),
        legacyCallPayload,
        descriptor.affinity("another-model"),
        mismatchedHash);

    // 3. reasoning_content 属于 native-only：即使与 durable thinking 完全一致，hash 失配时也必须 fail closed
    ObjectNode reasoningPayload = MAPPER.createObjectNode();
    reasoningPayload.put("role", "assistant");
    reasoningPayload.put("content", "Hello");
    reasoningPayload.put("reasoning_content", "hidden chain");
    assertNativeOnlyReplayRejected(
        user,
        List.of(new ProviderThinkingBlock("hidden chain"), new ProviderTextBlock("Hello")),
        reasoningPayload,
        modelAffinity(),
        mismatchedHash);

    // 4. refusal 区分属于 native-only：affinity 失配时 fail closed
    ObjectNode refusalPayload = MAPPER.createObjectNode();
    refusalPayload.put("role", "assistant");
    refusalPayload.put("refusal", "I cannot help");
    assertNativeOnlyReplayRejected(
        user,
        List.of(new ProviderTextBlock("I cannot help")),
        refusalPayload,
        descriptor.affinity("another-model"),
        mismatchedHash);

    // 5. tool_calls 的额外嵌套字段属于 native-only：hash 失配时 fail closed
    ObjectNode extendedCallPayload = MAPPER.createObjectNode();
    extendedCallPayload.put("role", "assistant");
    ObjectNode extendedCall = extendedCallPayload.putArray("tool_calls").addObject();
    extendedCall.put("id", "c1").put("type", "function");
    ObjectNode extendedFn = extendedCall.putObject("function");
    extendedFn.put("name", "fn");
    extendedFn.put("arguments", "{}");
    extendedFn.put("vendorField", "v");
    assertNativeOnlyReplayRejected(
        user,
        List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
        extendedCallPayload,
        descriptor.affinity("another-model"),
        mismatchedHash);

    // 6. 最小可重建 payload（role + 文本 content + 现代 function tool_calls）：affinity/hash 失配都回退语义编码
    ObjectNode minimalPayload = MAPPER.createObjectNode();
    minimalPayload.put("role", "assistant");
    minimalPayload.put("content", "Hello");
    ArrayNode minimalCalls = minimalPayload.putArray("tool_calls");
    ObjectNode minimalCall = minimalCalls.addObject();
    minimalCall.put("id", "c1").put("type", "function");
    ObjectNode minimalFn = minimalCall.putObject("function");
    minimalFn.put("name", "fn");
    minimalFn.put("arguments", "{}");
    List<ProviderContentBlock> durableBlocks =
        List.of(
            new ProviderTextBlock("Hello"),
            new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}")));

    for (ProviderReplayState replayState :
        List.of(
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_CHAT,
                descriptor.affinity("another-model"),
                mismatchedHash,
                minimalPayload),
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_CHAT,
                modelAffinity(),
                mismatchedHash,
                minimalPayload))) {
      JsonNode wire =
          MAPPER.readTree(
              encoder
                  .encode(
                      request(user, assistant(durableBlocks, replayState)),
                      descriptor,
                      OpenAiChatConfiguration.defaults())
                  .bodyUtf8Bytes());
      JsonNode wireAsst = wire.path("messages").get(2);
      assertEquals("Hello", wireAsst.path("content").asText());
      assertEquals("c1", wireAsst.path("tool_calls").get(0).path("id").asText());
      assertEquals("fn", wireAsst.path("tool_calls").get(0).path("function").path("name").asText());
    }
  }

  private ProviderReplayAffinity modelAffinity() {
    return descriptor.affinity(modelDesc.modelId());
  }

  private ProviderMessage assistant(
      List<ProviderContentBlock> durableBlocks, ProviderReplayState replayState) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, durableBlocks, replayState);
  }

  /** 断言该 replay 因携带 native-only 字段而在 affinity/hash 失配时 fail closed。 */
  private void assertNativeOnlyReplayRejected(
      ProviderMessage user,
      List<ProviderContentBlock> durableBlocks,
      ObjectNode payload,
      ProviderReplayAffinity affinity,
      String sourcePrefixHash) {
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, affinity, sourcePrefixHash, payload);
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(user, assistant(durableBlocks, replayState)),
                    descriptor,
                    OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals(
        "native replay fields require matching affinity and source prefix hash",
        error.getMessage());
  }

  private ProviderRequest request(ProviderMessage... messages) {
    return new ProviderRequest(
        modelDesc,
        defaultVariant,
        1024,
        "Test system instruction.",
        List.of(messages),
        List.of(),
        ProviderCacheControl.none());
  }
}

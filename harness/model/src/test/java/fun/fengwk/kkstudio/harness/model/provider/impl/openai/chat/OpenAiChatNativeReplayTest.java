package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 测试意图：验证 provider 原生 assistant message 的累积与 replay 语义——delta 的字符串/对象/标量按规则合并，audio 仅回放 id，
 * function_call、未来字段在 affinity 匹配时经次轮编码器原样 opaque 回放（跨 system/date 前缀变化不丢失），affinity 失配时退回 语义编码，而
 * malformed payload 或 known 字段与 durable 不一致时无论 affinity 是否匹配都必须 INVALID_REQUEST。
 */
class OpenAiChatNativeReplayTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private OpenAiChatRequestEncoder encoder;
  private ProviderDescriptor descriptor;
  private ModelDescriptor modelDesc;
  private ModelVariant defaultVariant;
  private ProviderStreamBridge bridge;

  @BeforeEach
  void setUp() {
    encoder = new OpenAiChatRequestEncoder();
    descriptor =
        new ProviderDescriptor(
            "openai",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    modelDesc =
        new ModelDescriptor(
            "openai", "gpt-4o-audio", "gpt-4o-audio", Set.of(ModelInputModality.TEXT), true, false);
    defaultVariant = new ModelVariant("default", null, PromptCacheRetention.NONE, null);
    bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event) {}

              @Override
              public void onComplete(ProviderCompletion completion) {}

              @Override
              public void onError(ProviderException error) {}
            });
  }

  @Test
  void blockNativeRefusalReplaysAsRefusalField() throws Exception {
    ProviderBlockData refusalData =
        new ProviderBlockData(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity(modelDesc.modelId()),
            null,
            null,
            null,
            null,
            null,
            true,
            MAPPER.createObjectNode());
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("I cannot help", refusalData)));

    JsonNode message =
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(
                            new ProviderMessage(
                                ProviderMessageRole.USER,
                                List.of(new ProviderTextBlock("question"))),
                            assistant),
                        descriptor)
                    .bodyUtf8Bytes())
            .path("messages")
            .get(2);

    assertEquals("I cannot help", message.path("refusal").asString(""));
    assertFalse(message.has("content"));
  }

  @Test
  void crossModelBlockReplayDropsEmptyAndRedactedThinkingAndUsesPlainText() throws Exception {
    ProviderBlockData sourceData =
        new ProviderBlockData(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("old-model"),
            MAPPER.createObjectNode());
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("", sourceData),
                new ProviderRedactedThinkingBlock("redacted", sourceData),
                new ProviderThinkingBlock("readable thought", sourceData),
                new ProviderTextBlock("answer", sourceData)));

    JsonNode message =
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(
                            new ProviderMessage(
                                ProviderMessageRole.USER,
                                List.of(new ProviderTextBlock("question"))),
                            assistant),
                        descriptor)
                    .bodyUtf8Bytes())
            .path("messages")
            .get(2);

    assertEquals("readable thoughtanswer", message.path("content").asString(""));
    assertFalse(message.path("content").asString("").contains("<thinking>"));
  }

  @Test
  void replayAffinityIsScopedToProviderProtocolAndModel() {
    ProviderReplayAffinity affinity = descriptor.affinity("gpt-4o-audio");

    assertEquals(
        new ProviderReplayAffinity(ProviderType.OPENAI, "openai", "gpt-4o-audio"), affinity);
    assertNotEquals(
        affinity,
        new ProviderReplayAffinity(ProviderType.OPENAI_RESPONSES, "openai", "gpt-4o-audio"));
    assertNotEquals(
        affinity, new ProviderReplayAffinity(ProviderType.OPENAI, "other", "gpt-4o-audio"));
    assertNotEquals(
        affinity, new ProviderReplayAffinity(ProviderType.OPENAI, "openai", "other-model"));
  }

  @Test
  @DisplayName("audio 仅回放 id；function_call / 未来字段可在次轮原样回放")
  void replaysProviderNativeAssistantFieldsThroughNextTurn() throws Exception {
    ProviderMessage turn1User =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Say hi")));
    ProviderRequest turn1Req = request(turn1User);
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(turn1Req, descriptor, bridge);
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
              "annotations": [{"type": "url_citation", "url": "https://example.com"}],
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
    assertEquals(2, completion.response().blocks().size());
    ProviderOpaqueBlock messageFields = (ProviderOpaqueBlock) completion.response().blocks().get(1);
    assertEquals("openai_chat_message", messageFields.blockType());
    assertEquals(
        "t-1",
        messageFields
            .providerBlockData()
            .nativeFields()
            .path("vendor_future_field")
            .path("trace_id")
            .asString(""));
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    JsonNode messageNativeFields = messageFields.providerBlockData().nativeFields();
    // Per-block native fields retain assistant extensions and only the replayable audio id.
    assertEquals(MAPPER.readTree("{\"id\":\"audio_1\"}"), messageNativeFields.path("audio"));
    assertFalse(messageNativeFields.has("annotations"));
    assertEquals(
        "legacy_lookup", messageNativeFields.path("function_call").path("name").asString(""));
    assertEquals(
        "{\"q\":\"hi\"}", messageNativeFields.path("function_call").path("arguments").asString(""));
    assertEquals(
        "t-1", messageNativeFields.path("vendor_future_field").path("trace_id").asString(""));
    assertEquals(2, messageNativeFields.path("vendor_future_field").path("attempt").asInt(0));

    // Chunk-level transport metadata never reaches the owning assistant block.
    for (String transportField :
        List.of("id", "object", "created", "model", "service_tier", "usage")) {
      assertFalse(messageNativeFields.has(transportField), transportField);
    }

    // 次轮请求：原生字段与 known 字段一起原样回放到 wire
    ProviderMessage turn1Asst =
        new ProviderMessage(ProviderMessageRole.ASSISTANT, completion.response().blocks());
    ProviderMessage turn2User =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Again")));
    JsonNode wireRoot =
        MAPPER.readTree(
            encoder.encode(request(turn1User, turn1Asst, turn2User), descriptor).bodyUtf8Bytes());
    JsonNode wireAsst = wireRoot.path("messages").get(2);
    assertEquals("assistant", wireAsst.path("role").asString(""));
    assertEquals("Hello world", wireAsst.path("content").asString(""));
    assertEquals(MAPPER.readTree("{\"id\":\"audio_1\"}"), wireAsst.path("audio"));
    assertFalse(wireAsst.has("annotations"));
    assertEquals("legacy_lookup", wireAsst.path("function_call").path("name").asString(""));
    assertEquals("t-1", wireAsst.path("vendor_future_field").path("trace_id").asString(""));
  }

  /** 测试意图：缺失有效 audio id 的上游响应不能冻结。 */
  @Test
  void rejectsMalformedAudioResponse() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    for (String audio : List.of("{}", "{\"id\":\"  \"}", "\"audio_1\"")) {
      OpenAiChatStreamAccumulator accumulator =
          new OpenAiChatStreamAccumulator(request(user), descriptor, bridge);
      accumulator.handleData(
          "{\"choices\":[{\"delta\":{\"audio\":" + audio + "},\"finish_reason\":\"stop\"}]}");
      accumulator.handleData("[DONE]");
      ProviderException error = assertThrows(ProviderException.class, accumulator::finish);
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    }
  }

  /** 测试意图：custom-only 调用始终保持 raw/native-only，既不变成可执行 function，也不丢失次轮 wire。 */
  @Test
  void preservesCustomOnlyCallWithoutNormalizingIt() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(request(user), descriptor, bridge);
    accumulator.handleData(
        """
        {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"custom_1","type":"custom",
        "custom":{"name":"code","input":"print(1)"}}]},"finish_reason":"tool_calls"}]}
        """);
    accumulator.handleData("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    accumulator.handleData("[DONE]");
    ProviderCompletion completion = accumulator.finish();
    assertEquals(List.of(), completion.response().toolCalls());
    ProviderOpaqueBlock nativeMessage =
        (ProviderOpaqueBlock) completion.response().blocks().getLast();
    JsonNode nativeCall =
        nativeMessage.providerBlockData().nativeFields().path("tool_calls").get(0);
    assertEquals("custom_1", nativeCall.path("id").asString(""));
    assertEquals("print(1)", nativeCall.path("custom").path("input").asString(""));
    assertFalse(nativeCall.has("index"));
    JsonNode wire =
        MAPPER.readTree(
            encoder
                .encode(
                    request(
                        user,
                        new ProviderMessage(
                            ProviderMessageRole.ASSISTANT, completion.response().blocks())),
                    descriptor)
                .bodyUtf8Bytes());
    assertEquals(nativeCall, wire.path("messages").get(2).path("tool_calls").get(0));
  }

  /** 测试意图：混合调用保留原始顺序及 function 扩展成员，覆盖规范化字段并剔除所有 stream index。 */
  @Test
  void preservesFunctionExtrasAndCustomCallInOriginalOrder() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(request(user), descriptor, bridge);
    accumulator.handleData(
        """
        {"choices":[{"delta":{"tool_calls":[
        {"index":4,"id":"custom_1","type":"custom","custom":{"input":"x"}},
        {"index":7,"id":"fn_1","type":"function","future":"retained",
        "function":{"name":"lookup","arguments":"{","futureFn":{"value":1}}}
        ]},"finish_reason":null}]}
        """);
    accumulator.handleData(
        """
        {"choices":[{"delta":{"tool_calls":[{"index":7,
        "function":{"arguments":"\\"q\\":1}"}}]},"finish_reason":"tool_calls"}]}
        """);
    accumulator.handleData("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    accumulator.handleData("[DONE]");
    ProviderCompletion completion = accumulator.finish();
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals("{\"q\":1}", completion.response().toolCalls().get(0).argumentsJson());
    ProviderToolCallBlock nativeCall =
        completion.response().blocks().stream()
            .filter(ProviderToolCallBlock.class::isInstance)
            .map(ProviderToolCallBlock.class::cast)
            .findFirst()
            .orElseThrow();
    JsonNode fields = nativeCall.providerBlockData().nativeFields();
    assertEquals("retained", fields.path("future").asString(""));
    assertEquals(1, fields.path("function").path("futureFn").path("value").asInt(0));
    assertEquals("{\"q\":1}", nativeCall.toolCall().argumentsJson());
    assertFalse(fields.has("index"));
    ProviderMessage assistant =
        new ProviderMessage(ProviderMessageRole.ASSISTANT, completion.response().blocks());
    JsonNode wire =
        MAPPER.readTree(encoder.encode(request(user, assistant), descriptor).bodyUtf8Bytes());
    assertEquals(
        "retained",
        wire.path("messages").get(2).path("tool_calls").get(0).path("future").asString(""));
  }

  /** 测试意图：原生快照未包含已规范化 function 时，冻结仍追加标准调用而不丢失 durable 意图。 */
  @Test
  void appendsCanonicalFunctionCallWhenNativeEntryIsMissing() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(request(user), descriptor, bridge);
    accumulator.handleData(
        """
        {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"fn_1","type":"function",
        "function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":null}]}
        """);
    accumulator.handleData(
        "{\"choices\":[{\"delta\":{\"tool_calls\":[]},\"finish_reason\":\"tool_calls\"}]}");
    accumulator.handleData("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    accumulator.handleData("[DONE]");
    ProviderCompletion completion = accumulator.finish();
    assertEquals(1, completion.response().toolCalls().size());
    assertEquals("fn_1", completion.response().toolCalls().getFirst().id());
    ProviderToolCallBlock block = (ProviderToolCallBlock) completion.response().blocks().getFirst();
    assertEquals("OPENAI_CHAT", block.providerBlockData().protocol().name());
  }

  /** 测试意图：废弃的 finish_reason=function_call 仍被显式拒绝，不因保留原生 function_call 字段而开放执行。 */
  @Test
  void rejectsDeprecatedFunctionCallFinishReason() {
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            request(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            descriptor,
            bridge);
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleData(
                    "{\"choices\":[{\"delta\":{\"function_call\":{\"name\":\"old\"}},"
                        + "\"finish_reason\":\"function_call\"}]}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
  }

  @Test
  @DisplayName("only choices[0] 进入 normalized 与 replay，n>1 的其他 choice 不混入")
  void ignoresNonZeroChoiceDeltas() {
    ProviderRequest turn1Req =
        request(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi"))));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(turn1Req, descriptor, bridge);
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
    accumulator.handleData("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(
        ((ProviderTextBlock) completion.response().blocks().getFirst()).providerBlockData());
    assertEquals("main answer", completion.response().text());
    assertEquals(1, completion.response().blocks().size());
    assertFalse(completion.response().text().contains("other"));
    assertFalse(completion.response().text().contains("secondary"));
  }

  @Test
  @DisplayName("未知未来字段的 indexed array 片段按 index 合并，新 index 追加为独立槽位")
  void mergesFutureIndexedArrayFragmentsByIndex() {
    ProviderRequest turn1Req =
        request(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi"))));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(turn1Req, descriptor, bridge);
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
    accumulator.handleData("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    ProviderOpaqueBlock nativeMessage =
        (ProviderOpaqueBlock) completion.response().blocks().getLast();
    JsonNode steps = nativeMessage.providerBlockData().nativeFields().path("vendor_steps");
    assertEquals(2, steps.size());
    assertEquals("ac", steps.get(0).path("text").asString(""));
    assertEquals("second", steps.get(0).path("state").path("phase").asString(""));
    assertEquals(1, steps.get(1).path("index").asInt(0));
    assertEquals("b", steps.get(1).path("text").asString(""));
  }

  @Test
  @DisplayName("stream output stores native metadata on its owning assistant block")
  void storesNativeMetadataOnOwningBlock() throws Exception {
    ProviderRequest turn1Req =
        request(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi"))));
    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(turn1Req, descriptor, bridge);
    accumulator.handleData(
        """
        {"choices":[{"index":0,"delta":{"role":"assistant","content":"honest",
        "vendor_future_field":"kept"},"finish_reason":"stop"}]}
        """);
    accumulator.handleData("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    accumulator.handleData("[DONE]");
    ProviderCompletion completion = accumulator.finish();
    ProviderTextBlock text = (ProviderTextBlock) completion.response().blocks().getFirst();
    ProviderOpaqueBlock nativeMessage =
        (ProviderOpaqueBlock) completion.response().blocks().getLast();
    assertEquals("honest", text.text());
    assertEquals(
        "kept",
        nativeMessage.providerBlockData().nativeFields().path("vendor_future_field").asString(""));
    JsonNode wire =
        MAPPER.readTree(
            encoder
                .encode(
                    request(
                        turn1Req.messages().getFirst(),
                        new ProviderMessage(
                            ProviderMessageRole.ASSISTANT, completion.response().blocks())),
                    descriptor)
                .bodyUtf8Bytes());
    assertEquals("honest", wire.path("messages").get(2).path("content").asString(""));
    assertEquals("kept", wire.path("messages").get(2).path("vendor_future_field").asString(""));
  }

  /** 测试意图：无法由 durable 语义重建的原生字段在 affinity 失配时退回语义编码。 */
  @Test
  @DisplayName("affinity 失配时 native-only 字段退回语义编码")
  void fallsBackSemanticallyOnAffinityMismatch() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderReplayAffinity other = descriptor.affinity("another-model");

    // 1. audio 仅请求侧 id 引用属于 native 事实：affinity 失配时退回语义编码而不是外泄
    ObjectNode audioPayload = MAPPER.createObjectNode();
    audioPayload.put("role", "assistant");
    audioPayload.put("content", "Hello");
    audioPayload.putObject("audio").put("id", "audio_1");
    JsonNode audioAsst =
        assistantNode(wire(user, List.of(new ProviderTextBlock("Hello")), other, audioPayload));
    assertEquals("Hello", audioAsst.path("content").asString(""));
    assertFalse(audioAsst.has("audio"));

    // 2. 已废弃的 function_call 属于 native 事实：affinity 失配时丢弃
    ObjectNode legacyCallPayload = MAPPER.createObjectNode();
    legacyCallPayload.put("role", "assistant");
    legacyCallPayload.put("content", "Hello");
    legacyCallPayload.putObject("function_call").put("name", "legacy_lookup");
    JsonNode legacyAsst =
        assistantNode(
            wire(user, List.of(new ProviderTextBlock("Hello")), other, legacyCallPayload));
    assertEquals("Hello", legacyAsst.path("content").asString(""));
    assertFalse(legacyAsst.has("function_call"));

    // 3. reasoning_content 即使与 durable thinking 一致，也不跨 affinity 转移；可读思考改以带来源标记的普通文本保留
    ObjectNode reasoningPayload = MAPPER.createObjectNode();
    reasoningPayload.put("role", "assistant");
    reasoningPayload.put("content", "Hello");
    reasoningPayload.put("reasoning_content", "hidden chain");
    JsonNode reasoningAsst =
        assistantNode(
            wire(
                user,
                List.of(new ProviderThinkingBlock("hidden chain"), new ProviderTextBlock("Hello")),
                other,
                reasoningPayload));
    assertEquals("hidden chainHello", reasoningAsst.path("content").asString(""));
    assertFalse(reasoningAsst.has("reasoning_content"));

    // 4. refusal 属于 native 事实：affinity 失配时退回纯文本
    ObjectNode refusalPayload = MAPPER.createObjectNode();
    refusalPayload.put("role", "assistant");
    refusalPayload.put("refusal", "I cannot help");
    JsonNode refusalAsst =
        assistantNode(
            wire(user, List.of(new ProviderTextBlock("I cannot help")), other, refusalPayload));
    assertEquals("I cannot help", refusalAsst.path("content").asString(""));
    assertFalse(refusalAsst.has("refusal"));

    // 5. tool_calls 的额外嵌套字段属于 native 事实：affinity 失配时只保留 durable 标准调用
    ObjectNode extendedCallPayload = MAPPER.createObjectNode();
    extendedCallPayload.put("role", "assistant");
    ObjectNode extendedCall = extendedCallPayload.putArray("tool_calls").addObject();
    extendedCall.put("id", "c1").put("type", "function");
    ObjectNode extendedFn = extendedCall.putObject("function");
    extendedFn.put("name", "fn");
    extendedFn.put("arguments", "{}");
    extendedFn.put("vendorField", "v");
    JsonNode extendedAsst =
        assistantNode(
            wire(
                user,
                List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
                other,
                extendedCallPayload));
    assertFalse(extendedAsst.path("tool_calls").get(0).path("function").has("vendorField"));

    // 7. 最小可重建 payload：affinity 失配退回语义编码，affinity 匹配原样 opaque 回放
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
    for (ProviderReplayAffinity affinity : List.of(other, modelAffinity())) {
      JsonNode wireAsst = assistantNode(wire(user, durableBlocks, affinity, minimalPayload));
      assertEquals("Hello", wireAsst.path("content").asString(""));
      assertEquals("c1", wireAsst.path("tool_calls").get(0).path("id").asString(""));
      assertEquals(
          "fn", wireAsst.path("tool_calls").get(0).path("function").path("name").asString(""));
    }
  }

  /** 普通思考不绑定 system 日期或工具定义前缀：affinity 匹配时原样 opaque 回放且不修改 durable replay。 */
  @Test
  @DisplayName("affinity 匹配时跨 system/date 前缀变化仍原样 opaque 回放")
  void preservesPlainReasoningAcrossSystemPrefixChanges() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("role", "assistant");
    payload.put("content", "Hello");
    payload.put("reasoning_content", "先分析\nthen answer");
    ProviderMessage assistant =
        assistant(
            List.of(new ProviderThinkingBlock("先分析\nthen answer"), new ProviderTextBlock("Hello")),
            modelAffinity(),
            payload);
    ProviderToolDefinition tool =
        new ProviderToolDefinition("read", "Read a file", "{\"type\":\"object\"}");
    for (boolean changeSystem : List.of(true, false)) {
      String system = changeSystem ? "Today is 2026-10-07." : "Test system instruction.";
      ProviderRequest changed =
          new ProviderRequest(
              modelDesc,
              defaultVariant,
              1024,
              system,
              List.of(user, assistant),
              changeSystem ? List.of() : List.of(tool),
              ProviderCacheControl.none());
      JsonNode wire = MAPPER.readTree(encoder.encode(changed, descriptor).bodyUtf8Bytes());
      assertEquals(system, wire.at("/messages/0/content").asString(""));
      assertEquals(payload, wire.path("messages").get(2));
      ProviderThinkingBlock thinking = (ProviderThinkingBlock) assistant.contents().getFirst();
      assertEquals(modelAffinity(), thinking.providerBlockData().affinity());
    }
  }

  /** affinity 失配（不同 provider、连接 generation 或 wire 模型）时普通思考降级为带来源标记的语义文本，不跨边界转移原生字段。 */
  @Test
  @DisplayName("affinity 变化时普通思考降级为带标记的语义文本")
  void fallsBackPlainReasoningAcrossAffinityChanges() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("role", "assistant");
    payload.put("content", "Hello");
    payload.put("reasoning_content", "reasoning");
    List<ProviderContentBlock> durable =
        List.of(new ProviderThinkingBlock("reasoning"), new ProviderTextBlock("Hello"));
    ProviderReplayAffinity original = modelAffinity();
    for (ProviderReplayAffinity other :
        List.of(
            descriptor.affinity("another-model"),
            new ProviderReplayAffinity(
                original.providerType(), "other-provider", original.modelId()))) {
      JsonNode asst = assistantNode(wire(user, durable, other, payload));
      assertEquals("reasoningHello", asst.path("content").asString(""));
      assertFalse(asst.has("reasoning_content"));
    }
  }

  /** 测试意图：affinity 匹配时 block native 原生字段原样透传。 */
  @Test
  @DisplayName("affinity 匹配时 opaque 字段原样透传")
  void replaysOpaqueFieldsAtSameAffinity() throws Exception {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    List<ProviderContentBlock> contents =
        List.of(new ProviderThinkingBlock("reasoning"), new ProviderTextBlock("Hello"));

    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("role", "assistant");
    payload.put("content", "Hello");
    payload.put("reasoning_content", "reasoning");
    payload.putArray("reasoning_details").addObject().put("signature", "opaque");
    payload.put("vendor_future_field", "opaque");
    JsonNode asst = assistantNode(wire(user, contents, modelAffinity(), payload));
    assertEquals("opaque", asst.path("reasoning_details").get(0).path("signature").asString(""));
    assertEquals("opaque", asst.path("vendor_future_field").asString(""));
    assertEquals("reasoning", asst.path("reasoning_content").asString(""));
  }

  private ProviderReplayAffinity modelAffinity() {
    return descriptor.affinity(modelDesc.modelId());
  }

  private ProviderMessage assistant(
      List<ProviderContentBlock> durableBlocks,
      ProviderReplayAffinity affinity,
      ObjectNode payload) {
    List<ProviderContentBlock> blocks = new ArrayList<>();
    for (ProviderContentBlock block : durableBlocks) {
      ObjectNode nativeFields = MAPPER.createObjectNode();
      boolean refusal = false;
      if (block instanceof ProviderThinkingBlock && payload.has("reasoning_details")) {
        nativeFields.set("reasoning_details", payload.path("reasoning_details").deepCopy());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        for (JsonNode call : payload.path("tool_calls")) {
          if (toolCallBlock.toolCall().id().equals(call.path("id").asString(""))) {
            if (call.isObject()) {
              ((ObjectNode) call)
                  .properties().stream()
                      .filter(
                          field ->
                              !List.of("id", "type", "index", "function").contains(field.getKey()))
                      .forEach(
                          field -> nativeFields.set(field.getKey(), field.getValue().deepCopy()));
              JsonNode function = call.path("function");
              if (function.isObject()) {
                ObjectNode functionFields = MAPPER.createObjectNode();
                ((ObjectNode) function)
                    .properties().stream()
                        .filter(field -> !List.of("name", "arguments").contains(field.getKey()))
                        .forEach(
                            field ->
                                functionFields.set(field.getKey(), field.getValue().deepCopy()));
                if (!functionFields.isEmpty()) {
                  nativeFields.set("function", functionFields);
                }
              }
            }
            break;
          }
        }
      } else if (block instanceof ProviderTextBlock) {
        refusal = payload.path("refusal").asBoolean(false);
      }
      ProviderBlockData data =
          new ProviderBlockData(
              ProviderReplayFormat.OPENAI_CHAT,
              affinity,
              null,
              null,
              null,
              null,
              null,
              refusal,
              nativeFields);
      if (block instanceof ProviderTextBlock text) {
        blocks.add(new ProviderTextBlock(text.text(), data));
      } else if (block instanceof ProviderThinkingBlock thinking) {
        blocks.add(new ProviderThinkingBlock(thinking.thinking(), data));
      } else if (block instanceof ProviderToolCallBlock toolCall) {
        blocks.add(new ProviderToolCallBlock(toolCall.toolCall(), data));
      } else {
        throw new IllegalArgumentException("unsupported test block: " + block.getClass());
      }
    }

    ObjectNode messageFields = payload.deepCopy();
    for (String field :
        List.of(
            "role",
            "content",
            "refusal",
            "reasoning_content",
            "reasoning_details",
            "tool_calls",
            "annotations")) {
      messageFields.remove(field);
    }
    JsonNode audio = messageFields.path("audio");
    if (audio.isObject()) {
      ObjectNode audioId = MAPPER.createObjectNode();
      if (audio.path("id").isString()) {
        audioId.set("id", audio.path("id").deepCopy());
      }
      messageFields.set("audio", audioId);
    }
    blocks.add(
        new ProviderOpaqueBlock(
            "openai_chat_message",
            new ProviderBlockData(ProviderReplayFormat.OPENAI_CHAT, affinity, messageFields)));
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks);
  }

  private JsonNode wire(
      ProviderMessage user,
      List<ProviderContentBlock> durableBlocks,
      ProviderReplayAffinity affinity,
      ObjectNode payload)
      throws Exception {
    return MAPPER.readTree(
        encoder
            .encode(request(user, assistant(durableBlocks, affinity, payload)), descriptor)
            .bodyUtf8Bytes());
  }

  private static JsonNode assistantNode(JsonNode wire) {
    return wire.path("messages").get(2);
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

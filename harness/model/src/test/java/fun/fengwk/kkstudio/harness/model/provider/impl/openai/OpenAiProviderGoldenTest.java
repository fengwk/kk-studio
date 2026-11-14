package fun.fengwk.kkstudio.harness.model.provider.impl.openai;

import static fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiGoldenSupport.assertGolden;
import static fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiGoldenSupport.renderStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat.OpenAiChatGoldenHarness;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses.OpenAiResponsesGoldenHarness;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 守护 OpenAI 两套协议编码器与流式累积器的完整 wire/终态文本。
 *
 * <p>期望值由基准提交 {@code b79652fa4} 的原实现生成并固化在 {@code golden/} 资源中，因此任何字段写入顺序、判断顺序、错误文案或状态赋值
 * 时机的改变都会立即失配。覆盖面归并整理必须保持不变的对外行为：工具、缓存档位、reasoning 参数、native protocolOptions 合并与 assistant
 * 回放、以及流式增量与 replay 终态。
 */
class OpenAiProviderGoldenTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final ModelVariant PLAIN_VARIANT =
      new ModelVariant("default", null, PromptCacheRetention.NONE, null);

  private static final ProviderDescriptor CHAT_DESCRIPTOR =
      new ProviderDescriptor(
          "openai",
          ProviderType.OPENAI,
          "https://api.openai.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));

  private static final ProviderDescriptor RESPONSES_DESCRIPTOR =
      new ProviderDescriptor(
          "openai_test",
          ProviderType.OPENAI_RESPONSES,
          "https://api.openai.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)));

  private static final ModelDescriptor CHAT_MODEL =
      new ModelDescriptor(
          "openai",
          "gpt-4o",
          "gpt-4o",
          Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE),
          true,
          true);

  private static final ModelDescriptor RESPONSES_MODEL =
      new ModelDescriptor(
          "openai_responses",
          "gpt-5.4-mini",
          "gpt-5.4-mini",
          Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE),
          true,
          true);

  private static ModelVariant variant(
      String id, String effort, PromptCacheRetention retention, String protocolOptions) {
    return new ModelVariant(
        id,
        effort,
        retention,
        protocolOptions == null ? null : new ProviderProtocolOptions(protocolOptions));
  }

  private static ModelVariant plainVariant() {
    return PLAIN_VARIANT;
  }

  private static ProviderRequest chatRequest(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        CHAT_MODEL, variant, 1024, "You are helpful.", messages, tools, cacheControl);
  }

  private static ProviderRequest responsesRequest(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        RESPONSES_MODEL, variant, 1024, "You are helpful.", messages, tools, cacheControl);
  }

  private static ProviderMessage user(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderToolDefinition tool(String name) {
    return new ProviderToolDefinition(
        name,
        "Look things up.",
        "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}");
  }

  private static String chatEncoding(ProviderRequest request) {
    return new String(
        OpenAiChatGoldenHarness.encode(request, CHAT_DESCRIPTOR), StandardCharsets.UTF_8);
  }

  private static String responsesEncoding(ProviderRequest request) {
    return new String(
        OpenAiResponsesGoldenHarness.encode(request, RESPONSES_DESCRIPTOR), StandardCharsets.UTF_8);
  }

  private static String chatFailure(ProviderRequest request) {
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> OpenAiChatGoldenHarness.encode(request, CHAT_DESCRIPTOR));
    return error.kind().name() + "|" + error.getMessage();
  }

  private static String responsesFailure(ProviderRequest request) {
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> OpenAiResponsesGoldenHarness.encode(request, RESPONSES_DESCRIPTOR));
    return error.kind().name() + "|" + error.getMessage();
  }

  private static String chatStream(List<String> frames) {
    OpenAiChatGoldenHarness harness =
        new OpenAiChatGoldenHarness(
            chatRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.none()),
            CHAT_DESCRIPTOR);
    for (String frame : frames) {
      harness.handleData(frame);
    }
    ProviderCompletion completion = harness.finish();
    return renderStream(List.of(), harness.events(), completion.response());
  }

  private static String responsesStream(List<ObjectNode> frames) {
    OpenAiResponsesGoldenHarness harness =
        new OpenAiResponsesGoldenHarness(
            responsesRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.none()),
            RESPONSES_DESCRIPTOR);
    for (ObjectNode frame : frames) {
      harness.processEvent(frame);
    }
    ProviderCompletion completion = harness.finish();
    return renderStream(harness.protocolEvents(), harness.events(), completion.response());
  }

  private static ObjectNode frame(String json) {
    return (ObjectNode) MAPPER.readTree(json);
  }

  /** 纯文本请求：system 前导消息、默认 variant、无工具、无缓存。 */
  @Test
  void chatPlainRequest() {
    assertGolden(
        "chat-plain",
        chatEncoding(
            chatRequest(
                plainVariant(), List.of(user("Hello")), List.of(), ProviderCacheControl.none())));
  }

  /** 工具 + 三档缓存留存：守护 prompt_cache_key/retention 的写入位置与顺序。 */
  @Test
  void chatToolsWithCacheRetentions() {
    assertGolden(
        "chat-tools-cache-short",
        chatEncoding(
            chatRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.session(
                    PromptCacheRetention.SHORT, "11111111-1111-1111-1111-111111111111"))));
    assertGolden(
        "chat-tools-cache-long",
        chatEncoding(
            chatRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup"), tool("search")),
                ProviderCacheControl.session(
                    PromptCacheRetention.LONG, "22222222-2222-2222-2222-222222222222"))));
    assertGolden(
        "chat-tools-cache-none",
        chatEncoding(
            chatRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.none())));
  }

  /** reasoning 参数：声明 effort、显式 off、native 自带 thinking 与非推理模型。 */
  @Test
  void chatReasoningParameters() {
    assertGolden(
        "chat-reasoning-high",
        chatEncoding(
            chatRequest(
                variant("high", "high", PromptCacheRetention.NONE, null),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-reasoning-off",
        chatEncoding(
            chatRequest(
                variant("off", "off", PromptCacheRetention.NONE, null),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-reasoning-native-thinking",
        chatEncoding(
            chatRequest(
                variant(
                    "plain",
                    null,
                    PromptCacheRetention.NONE,
                    "{\"thinking\":{\"type\":\"enabled\"}}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-reasoning-conflict",
        chatFailure(
            chatRequest(
                variant(
                    "high", "high", PromptCacheRetention.NONE, "{\"reasoning_effort\":\"low\"}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** native protocolOptions：无损并入官方字段并合并 stream_options / tools 容器。 */
  @Test
  void chatNativeProtocolOptions() {
    assertGolden(
        "chat-native-merge",
        chatEncoding(
            chatRequest(
                variant(
                    "native",
                    null,
                    PromptCacheRetention.NONE,
                    "{\"temperature\":0.25,\"stream_options\":{\"chunk_include\":true},"
                        + "\"tools\":[],\"metadata\":{\"tenant\":\"t1\"}}"),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.session(
                    PromptCacheRetention.SHORT, "33333333-3333-3333-3333-333333333333"))));
    assertGolden(
        "chat-native-runtime-field",
        chatFailure(
            chatRequest(
                variant("native", null, PromptCacheRetention.NONE, "{\"messages\":[]}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-native-legacy-tools",
        chatFailure(
            chatRequest(
                variant("native", null, PromptCacheRetention.NONE, "{\"function_call\":{}}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-native-multi-choice",
        chatFailure(
            chatRequest(
                variant("native", null, PromptCacheRetention.NONE, "{\"n\":2}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-native-include-usage",
        chatFailure(
            chatRequest(
                variant(
                    "native",
                    null,
                    PromptCacheRetention.NONE,
                    "{\"stream_options\":{\"include_usage\":false}}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** 用户消息的多种内容块：parts 数组、音频 data URI 与视频拒绝。 */
  @Test
  void chatUserContentBlocks() {
    assertGolden(
        "chat-user-parts",
        chatEncoding(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.USER,
                        List.of(
                            new ProviderTextBlock("Describe"),
                            new ProviderImageBlock("image/png", "data:image/png;base64,AAAA"),
                            new ProviderTextBlock("and"),
                            new ProviderDocumentBlock("application/pdf", "JVBERi0xLjQK")))),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-user-audio",
        chatEncoding(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.USER,
                        List.of(
                            new ProviderTextBlock("Transcribe"),
                            new ProviderAudioBlock("audio/wav", "data:audio/wav;base64,BBBB")))),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-user-audio-url-before-video",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.USER,
                        List.of(
                            new ProviderAudioBlock("audio/wav", "https://e/a.wav"),
                            new ProviderVideoBlock("video/mp4", "https://e/v.mp4")))),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-user-video",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.USER,
                        List.of(
                            new ProviderTextBlock("Watch"),
                            new ProviderVideoBlock("video/mp4", "https://example.com/v.mp4")))),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** assistant 语义回退：文本、思考、JSON 诊断与工具调用合并到同一 wire message。 */
  @Test
  void chatAssistantSemanticFallback() {
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("reasoning"),
                new ProviderTextBlock("answer"),
                new ProviderJsonBlock("{\"note\":\"diag\"}"),
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "lookup", "{\"q\":\"x\"}"))));
    assertGolden(
        "chat-assistant-fallback",
        chatEncoding(
            chatRequest(
                plainVariant(),
                List.of(user("Hello"), assistant),
                List.of(tool("lookup")),
                ProviderCacheControl.none())));
  }

  /** assistant 原生回放：affinity 匹配时原样回放 native 字段与 audio id。 */
  @Test
  void chatAssistantNativeReplay() {
    ProviderBlockData affinityData =
        new ProviderBlockData(
            ProviderReplayFormat.OPENAI_CHAT,
            CHAT_DESCRIPTOR.affinity(CHAT_MODEL.modelId()),
            MAPPER.createObjectNode());
    ObjectNode messageFields =
        (ObjectNode)
            MAPPER.readTree(
                "{\"vendor_future_field\":{\"trace_id\":\"t-1\"},"
                    + "\"audio\":{\"id\":\"audio_1\"},"
                    + "\"function_call\":{\"name\":\"legacy\",\"arguments\":\"{}\"}}");
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderTextBlock("answer", affinityData),
                new ProviderOpaqueBlock(
                    "openai_chat_message",
                    new ProviderBlockData(
                        ProviderReplayFormat.OPENAI_CHAT,
                        CHAT_DESCRIPTOR.affinity(CHAT_MODEL.modelId()),
                        messageFields))));
    assertGolden(
        "chat-assistant-native-replay",
        chatEncoding(
            chatRequest(
                plainVariant(),
                List.of(user("Hello"), assistant),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** assistant 回放与 durable 矛盾时仍拒绝：错误文案与判断顺序被逐字守护。 */
  @Test
  void chatAssistantReplayMismatch() {
    ProviderBlockData affinityData =
        new ProviderBlockData(
            ProviderReplayFormat.OPENAI_CHAT,
            CHAT_DESCRIPTOR.affinity(CHAT_MODEL.modelId()),
            MAPPER.createObjectNode());
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderTextBlock("answer", affinityData),
                new ProviderOpaqueBlock(
                    "openai_chat_message",
                    new ProviderBlockData(
                        ProviderReplayFormat.OPENAI_CHAT,
                        CHAT_DESCRIPTOR.affinity(CHAT_MODEL.modelId()),
                        MAPPER.readTree("{\"refusal\":\"extra\"}")))));
    assertGolden(
        "chat-assistant-replay-mismatch",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(user("Hello"), assistant),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** 用户/工具消息的角色约束与非法块：错误文案与判断顺序被逐字守护。 */
  @Test
  void chatMessageShapeErrors() {
    assertGolden(
        "chat-mid-system-message",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("mid")))),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-tool-result-unsupported-block",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.TOOL,
                        List.of(
                            new ProviderToolResultBlock(
                                "call_1",
                                "lookup",
                                List.of(new ProviderImageBlock("image/png", "https://e/i.png")),
                                false,
                                "{}")))),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-user-audio-url",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.USER,
                        List.of(
                            new ProviderTextBlock("Transcribe"),
                            new ProviderAudioBlock("audio/wav", "https://e/a.wav")))),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-user-document-type",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.USER,
                        List.of(new ProviderDocumentBlock("text/plain", "aGVsbG8=")))),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** assistant 回放 payload 的结构错误：每类拒绝的文案与顺序被守护。 */
  @Test
  void chatReplayPayloadErrors() {
    Map<String, String> payloads = new LinkedHashMap<>();
    payloads.put("annotations-only", "{\"annotations\":[]}");
    payloads.put("blank-audio-id", "{\"audio\":{\"id\":\"\"}}");
    payloads.put("non-string-refusal", "{\"refusal\":12}");
    payloads.put("non-array-reasoning-details", "{\"reasoning_details\":5}");
    payloads.put(
        "array-arguments",
        "{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
            + "{\"name\":\"f\",\"arguments\":\"[]\"}}]}");
    for (Map.Entry<String, String> payload : payloads.entrySet()) {
      ProviderBlockData affinityData =
          new ProviderBlockData(
              ProviderReplayFormat.OPENAI_CHAT,
              CHAT_DESCRIPTOR.affinity(CHAT_MODEL.modelId()),
              MAPPER.createObjectNode());
      ProviderMessage assistant =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(
                  new ProviderTextBlock("answer", affinityData),
                  new ProviderOpaqueBlock(
                      "openai_chat_message",
                      new ProviderBlockData(
                          ProviderReplayFormat.OPENAI_CHAT,
                          CHAT_DESCRIPTOR.affinity(CHAT_MODEL.modelId()),
                          MAPPER.readTree(payload.getValue())))));
      assertGolden(
          "chat-replay-payload-error-" + payload.getKey(),
          chatFailure(
              chatRequest(
                  plainVariant(),
                  List.of(user("Hello"), assistant),
                  List.of(),
                  ProviderCacheControl.none())));
    }
  }

  /** native tools 非空与 n=1 的组合：判断顺序被守护。 */
  @Test
  void chatNativeToolsAndChoice() {
    assertGolden(
        "chat-native-tools-nonempty",
        chatFailure(
            chatRequest(
                variant(
                    "native",
                    null,
                    PromptCacheRetention.NONE,
                    "{\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"x\"}}]}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "chat-native-single-choice",
        chatEncoding(
            chatRequest(
                variant("native", null, PromptCacheRetention.NONE, "{\"n\":1}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** 非推理模型声明 effort：不下发任何推理字段。 */
  @Test
  void chatNonReasoningModelIgnoresEffort() {
    ProviderRequest request =
        new ProviderRequest(
            new ModelDescriptor(
                "openai",
                "gpt-4o-mini",
                "gpt-4o-mini",
                Set.of(ModelInputModality.TEXT),
                true,
                false),
            variant("high", "high", PromptCacheRetention.NONE, null),
            1024,
            "You are helpful.",
            List.of(user("Hello")),
            List.of(),
            ProviderCacheControl.none());
    assertGolden("chat-non-reasoning-effort", chatEncoding(request));
  }

  /** 工具 schema 非法时在合并 tools 阶段失败。 */
  @Test
  void chatInvalidToolSchema() {
    ProviderToolDefinition bad =
        new ProviderToolDefinition("broken", "desc", "[\"not-an-object\"]");
    assertGolden(
        "chat-invalid-tool-schema",
        chatFailure(
            chatRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(bad),
                ProviderCacheControl.none())));
  }

  /** 基础请求：model/stream/store/instructions 与输出预算。 */
  @Test
  void responsesPlainRequest() {
    assertGolden(
        "responses-plain",
        responsesEncoding(
            responsesRequest(
                plainVariant(), List.of(user("Hello")), List.of(), ProviderCacheControl.none())));
  }

  /** reasoning 与 include 合并：auto summary、encrypted content、原生子字段与去重。 */
  @Test
  void responsesReasoningMerge() {
    assertGolden(
        "responses-reasoning-high",
        responsesEncoding(
            responsesRequest(
                variant("high", "high", PromptCacheRetention.NONE, null),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "responses-reasoning-off",
        responsesEncoding(
            responsesRequest(
                variant("off", "off", PromptCacheRetention.NONE, null),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "responses-reasoning-native",
        responsesEncoding(
            responsesRequest(
                variant(
                    "native",
                    "low",
                    PromptCacheRetention.NONE,
                    "{\"reasoning\":{\"summary\":\"concise\",\"verbosity\":\"low\"},"
                        + "\"include\":[\"message.output_text.logprobs\","
                        + "\"message.output_text.logprobs\"]}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** hosted 工具保留、runtime 工具追加，缓存档位写入 cache 字段。 */
  @Test
  void responsesToolsWithHostedAndCache() {
    assertGolden(
        "responses-tools-cache",
        responsesEncoding(
            responsesRequest(
                variant(
                    "native",
                    null,
                    PromptCacheRetention.NONE,
                    "{\"tools\":[{\"type\":\"web_search\"},"
                        + "{\"type\":\"mcp\",\"require_approval\":\"never\",\"server_label\":\"s\"}],"
                        + "\"background\":false,\"temperature\":0.4}"),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.session(
                    PromptCacheRetention.LONG, "44444444-4444-4444-4444-444444444444"))));
    assertGolden(
        "responses-cache-short",
        responsesEncoding(
            responsesRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.session(
                    PromptCacheRetention.SHORT, "55555555-5555-5555-5555-555555555555"))));
  }

  /** native 冲突与非法容器：错误文案与判断顺序被守护。 */
  @Test
  void responsesNativeConflicts() {
    assertGolden(
        "responses-stateful-field",
        responsesFailure(
            responsesRequest(
                variant(
                    "native", null, PromptCacheRetention.NONE, "{\"previous_response_id\":\"r\"}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "responses-runtime-effort-conflict",
        responsesFailure(
            responsesRequest(
                variant(
                    "native",
                    "high",
                    PromptCacheRetention.NONE,
                    "{\"reasoning\":{\"effort\":\"low\"}}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "responses-unsupported-tool",
        responsesFailure(
            responsesRequest(
                variant(
                    "native",
                    null,
                    PromptCacheRetention.NONE,
                    "{\"tools\":[{\"type\":\"function\",\"name\":\"lookup\"}]}"),
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
    assertGolden(
        "responses-output-budget",
        responsesFailure(
            new ProviderRequest(
                RESPONSES_MODEL,
                plainVariant(),
                8,
                "You are helpful.",
                List.of(user("Hello")),
                List.of(),
                ProviderCacheControl.none())));
  }

  /** 用户内容块与工具结果：input_text/input_image/input_file 与 function_call_output。 */
  @Test
  void responsesUserAndToolResults() {
    assertGolden(
        "responses-user-parts",
        responsesEncoding(
            responsesRequest(
                plainVariant(),
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.USER,
                        List.of(
                            new ProviderTextBlock("Describe"),
                            new ProviderImageBlock("image/png", "https://example.com/i.png"),
                            new ProviderDocumentBlock(
                                "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK"))),
                    new ProviderMessage(
                        ProviderMessageRole.TOOL,
                        List.of(
                            new ProviderToolResultBlock(
                                "call_1",
                                "lookup",
                                List.of(
                                    new ProviderTextBlock("result"),
                                    new ProviderJsonBlock("{\"n\":1}"),
                                    new ProviderImageBlock(
                                        "image/png", "https://example.com/r.png")),
                                false,
                                "{}")))),
                List.of(tool("lookup")),
                ProviderCacheControl.none())));
  }

  /** assistant 语义回退：文本/思考/JSON 合并，工具调用成为独立 function_call item。 */
  @Test
  void responsesAssistantSemanticFallback() {
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("reasoning"),
                new ProviderTextBlock("answer"),
                new ProviderJsonBlock("{\"note\":\"diag\"}"),
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "lookup", "{\"q\":\"x\"}")),
                new ProviderTextBlock("tail")));
    assertGolden(
        "responses-assistant-fallback",
        responsesEncoding(
            responsesRequest(
                plainVariant(),
                List.of(user("Hello"), assistant),
                List.of(tool("lookup")),
                ProviderCacheControl.none())));
  }

  /** assistant 原生回放：message/reasoning/function_call 的完整 item 原样带回。 */
  @Test
  void responsesAssistantNativeReplay() {
    ProviderReplayAffinity affinity = RESPONSES_DESCRIPTOR.affinity(RESPONSES_MODEL.modelId());
    ProviderBlockData messageItem =
        new ProviderBlockData(
            ProviderReplayFormat.OPENAI_RESPONSES,
            affinity,
            null,
            null,
            "msg_1",
            "message",
            "output_text",
            false,
            null,
            true,
            MAPPER.readTree(
                "{\"itemFields\":{\"status\":\"completed\",\"role\":\"assistant\"},"
                    + "\"contentFields\":{\"annotations\":[{\"type\":\"url_citation\"}]}}"));
    ProviderBlockData reasoningItem =
        new ProviderBlockData(
            ProviderReplayFormat.OPENAI_RESPONSES,
            affinity,
            null,
            "enc_1",
            "rs_1",
            "reasoning",
            "summary_text",
            false,
            null,
            true,
            MAPPER.readTree("{\"itemFields\":{\"status\":\"completed\"}}"));
    ProviderBlockData functionCallItem =
        new ProviderBlockData(
            ProviderReplayFormat.OPENAI_RESPONSES,
            affinity,
            null,
            null,
            "fc_1",
            "function_call",
            null,
            false,
            null,
            true,
            MAPPER.readTree("{\"itemFields\":{\"status\":\"completed\"}}"));
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("thought", reasoningItem),
                new ProviderTextBlock("answer", messageItem),
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "lookup", "{\"q\":\"x\"}"), functionCallItem)));
    assertGolden(
        "responses-assistant-native-replay",
        responsesEncoding(
            responsesRequest(
                plainVariant(),
                List.of(user("Hello"), assistant),
                List.of(tool("lookup")),
                ProviderCacheControl.none())));
  }

  /** 文本 + 工具调用 + usage 的完整流：增量顺序、replay metadata 与用量归一化。 */
  @Test
  void chatStreamTextAndToolCall() {
    assertGolden(
        "chat-stream-text-tool",
        chatStream(
            List.of(
                "{\"id\":\"c-1\",\"service_tier\":\"default\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"id\":\"call_1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
                    + "\"total_tokens\":15,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":2},"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":1}}}",
                "[DONE]")));
  }

  /** reasoning + refusal + native 扩展字段的流。 */
  @Test
  void chatStreamReasoningAndNativeFields() {
    assertGolden(
        "chat-stream-reasoning",
        chatStream(
            List.of(
                "{\"id\":\"c-2\",\"choices\":[{\"index\":0,\"delta\":"
                    + "{\"reasoning_content\":\"think\","
                    + "\"reasoning_details\":[{\"type\":\"summary\"}],"
                    + "\"vendor_future_field\":{\"trace_id\":\"t-1\"}}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"answer\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"nope\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                "[DONE]")));
  }

  private static String chatStreamFailure(List<String> frames) {
    OpenAiChatGoldenHarness harness =
        new OpenAiChatGoldenHarness(
            chatRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.none()),
            CHAT_DESCRIPTOR);
    for (String frame : frames) {
      harness.handleData(frame);
    }
    ProviderException error = assertThrows(ProviderException.class, harness::finish);
    return error.kind().name() + "|" + error.getMessage();
  }

  private static String chatFrameFailure(List<String> frames) {
    OpenAiChatGoldenHarness harness =
        new OpenAiChatGoldenHarness(
            chatRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.none()),
            CHAT_DESCRIPTOR);
    ProviderException error = null;
    for (String frame : frames) {
      try {
        harness.handleData(frame);
      } catch (ProviderException thrown) {
        error = thrown;
        break;
      }
    }
    if (error == null) {
      error = assertThrows(ProviderException.class, harness::finish);
    }
    return error.kind().name() + "|" + error.getMessage();
  }

  /** 流式累积器的错误路径：文案与判断顺序逐字守护。 */
  @Test
  void chatStreamErrorPaths() {
    assertGolden(
        "chat-stream-after-done",
        chatFrameFailure(
            List.of("[DONE]", "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}")));
    assertGolden(
        "chat-stream-non-string-finish-reason",
        chatFrameFailure(List.of("{\"choices\":[{\"index\":0,\"finish_reason\":5}]}")));
    assertGolden(
        "chat-stream-unsupported-finish-reason",
        chatFrameFailure(List.of("{\"choices\":[{\"index\":0,\"finish_reason\":\"weird\"}]}")));
    assertGolden(
        "chat-stream-conflicting-tail",
        chatFrameFailure(
            List.of(
                "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[{\"index\":1,\"finish_reason\":\"stop\"}]}")));
    assertGolden(
        "chat-stream-semantic-tail",
        chatFrameFailure(
            List.of(
                "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"late\"}}]}")));
    assertGolden(
        "chat-stream-invalid-choice-index",
        chatFrameFailure(List.of("{\"choices\":[{\"index\":-1,\"finish_reason\":\"stop\"}]}")));
    assertGolden(
        "chat-stream-tool-function-not-object",
        chatFrameFailure(
            List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"f\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":5}]}}]}")));
    assertGolden(
        "chat-stream-incomplete-tool-on-complete",
        chatStreamFailure(
            List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"f\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}]}",
                "{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                "[DONE]")));
    assertGolden(
        "chat-stream-missing-usage",
        chatStreamFailure(
            List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},"
                    + "\"finish_reason\":\"stop\"}]}",
                "[DONE]")));
    assertGolden(
        "chat-stream-missing-done",
        chatStreamFailure(
            List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},"
                    + "\"finish_reason\":\"stop\"}]}",
                "{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")));
    assertGolden(
        "chat-stream-invalid-usage-field",
        chatFrameFailure(List.of("{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":1}}")));
    assertGolden("chat-stream-usage-not-object", chatFrameFailure(List.of("{\"usage\":[]}")));
  }

  /** LENGTH 截断与 FILTERED 的终态：诊断与工具清空行为被守护。 */
  @Test
  void chatStreamLengthAndFiltered() {
    assertGolden(
        "chat-stream-length-truncated",
        chatStream(
            List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                    + "\"arguments\":\"{\\\"q\\\":\"}}]},\"finish_reason\":\"length\"}]}",
                "{\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}",
                "[DONE]")));
    assertGolden(
        "chat-stream-filtered",
        chatStream(
            List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"blocked\","
                    + "\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},"
                    + "\"finish_reason\":\"content_filter\"}]}",
                "{\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}",
                "[DONE]")));
  }

  /** 编码器矩阵：tools × 缓存档位 × reasoning × native 选项，逐格守护完整请求体字节。 */
  @Test
  void chatEncoderMatrix() {
    List<List<ProviderToolDefinition>> toolSets =
        List.of(List.of(), List.of(tool("lookup")), List.of(tool("a"), tool("b")));
    List<ProviderCacheControl> caches =
        List.of(
            ProviderCacheControl.none(),
            ProviderCacheControl.session(
                PromptCacheRetention.SHORT, "66666666-6666-6666-6666-666666666666"),
            ProviderCacheControl.session(
                PromptCacheRetention.LONG, "77777777-7777-7777-7777-777777777777"));
    List<ModelVariant> variants =
        List.of(
            plainVariant(),
            variant("high", "high", PromptCacheRetention.NONE, null),
            variant("off", "off", PromptCacheRetention.NONE, null),
            variant("native", null, PromptCacheRetention.NONE, "{\"temperature\":0.1}"));
    for (int t = 0; t < toolSets.size(); t++) {
      for (int c = 0; c < caches.size(); c++) {
        for (int v = 0; v < variants.size(); v++) {
          assertGolden(
              "chat-matrix-" + t + "-" + c + "-" + v,
              chatEncoding(
                  chatRequest(
                      variants.get(v), List.of(user("Hello")), toolSets.get(t), caches.get(c))));
        }
      }
    }
  }

  /** Responses 编码器矩阵：tools × 缓存档位 × reasoning × native 选项。 */
  @Test
  void responsesEncoderMatrix() {
    List<List<ProviderToolDefinition>> toolSets = List.of(List.of(), List.of(tool("lookup")));
    List<ProviderCacheControl> caches =
        List.of(
            ProviderCacheControl.none(),
            ProviderCacheControl.session(
                PromptCacheRetention.SHORT, "88888888-8888-8888-8888-888888888888"),
            ProviderCacheControl.session(
                PromptCacheRetention.LONG, "99999999-9999-9999-9999-999999999999"));
    List<ModelVariant> variants =
        List.of(
            plainVariant(),
            variant("high", "high", PromptCacheRetention.NONE, null),
            variant("off", "off", PromptCacheRetention.NONE, null),
            variant(
                "native",
                null,
                PromptCacheRetention.NONE,
                "{\"include\":[\"message.output_text.logprobs\"]}"));
    for (int t = 0; t < toolSets.size(); t++) {
      for (int c = 0; c < caches.size(); c++) {
        for (int v = 0; v < variants.size(); v++) {
          assertGolden(
              "responses-matrix-" + t + "-" + c + "-" + v,
              responsesEncoding(
                  responsesRequest(
                      variants.get(v), List.of(user("Hello")), toolSets.get(t), caches.get(c))));
        }
      }
    }
  }

  /** 文本/推理增量 + function_call + 终态 output 的完整 Responses 流。 */
  @Test
  void responsesStreamWithToolCall() {
    assertGolden(
        "responses-stream-tool",
        responsesStream(
            List.of(
                frame(
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
                        + "\"service_tier\":\"default\",\"usage\":{\"input_tokens\":1,"
                        + "\"output_tokens\":0}}}"),
                frame(
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"Hel\","
                        + "\"output_index\":1}"),
                frame("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"think\"}"),
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":2,\"item\":"
                        + "{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
                        + "\"name\":\"lookup\"}}"),
                frame(
                    "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\","
                        + "\"delta\":\"{\\\"q\\\":\"}"),
                frame(
                    "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}"),
                frame(
                    "{\"type\":\"response.output_item.done\",\"item\":{\"id\":\"fc_1\","
                        + "\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}"),
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
                        + "\"service_tier\":\"default\",\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                        + "\"total_tokens\":15,"
                        + "\"input_tokens_details\":{\"cached_tokens\":2},"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":1}},\"output\":["
                        + "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
                        + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"think\"}],"
                        + "\"encrypted_content\":\"enc_1\"},"
                        + "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\","
                        + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
                        + "\"text\":\"Hello\",\"annotations\":[{\"type\":\"url_citation\"}]}]},"
                        + "{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\","
                        + "\"call_id\":\"call_1\",\"name\":\"lookup\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}]}}"))));
  }

  /** incomplete(LENGTH) 与未知官方 item 的不透明保留。 */
  @Test
  void responsesStreamIncompleteWithOpaqueItem() {
    assertGolden(
        "responses-stream-incomplete",
        responsesStream(
            List.of(
                frame(
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_2\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                        + "{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\","
                        + "\"action\":{\"query\":\"Paris\"}}}"),
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":"
                        + "{\"id\":\"fc_2\",\"type\":\"function_call\",\"call_id\":\"call_2\","
                        + "\"name\":\"lookup\"}}"),
                frame(
                    "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_2\","
                        + "\"delta\":\"{\\\"q\\\":\\\"P\"}"),
                frame(
                    "{\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_2\","
                        + "\"status\":\"incomplete\",\"incomplete_details\":"
                        + "{\"reason\":\"max_output_tokens\"},"
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3},\"output\":["
                        + "{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\","
                        + "\"action\":{\"query\":\"Paris\"}},"
                        + "{\"id\":\"fc_2\",\"type\":\"function_call\",\"status\":\"incomplete\","
                        + "\"call_id\":\"call_2\",\"name\":\"lookup\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"P\"}]}}"))));
  }

  /** 工具调用只在 output_item.done / 终态 output 出现，以及 delta 先于 added 的乱序帧。 */
  @Test
  void responsesStreamToolDiscoveryOrder() {
    assertGolden(
        "responses-stream-tool-done-only",
        responsesStream(
            List.of(
                frame(
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_3\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
                frame(
                    "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_9\","
                        + "\"delta\":\"ignored\"}"),
                frame(
                    "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
                        + "{\"id\":\"fc_3\",\"type\":\"function_call\","
                        + "\"call_id\":\"call_3\",\"name\":\"lookup\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}"),
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_3\","
                        + "\"usage\":{\"input_tokens\":4,\"output_tokens\":2},\"output\":["
                        + "{\"id\":\"fc_3\",\"type\":\"function_call\",\"status\":\"completed\","
                        + "\"call_id\":\"call_3\",\"name\":\"lookup\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}]}}"))));
    assertGolden(
        "responses-stream-tool-terminal-only",
        responsesStream(
            List.of(
                frame(
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_4\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_4\","
                        + "\"usage\":{\"input_tokens\":4,\"output_tokens\":2},\"output\":["
                        + "{\"type\":\"function_call\",\"status\":\"completed\","
                        + "\"call_id\":\"call_4\",\"name\":\"lookup\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"y\\\"}\"}]}}"))));
    assertGolden(
        "responses-stream-tool-truncated-identity",
        responsesStream(
            List.of(
                frame(
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_5\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                        + "{\"id\":\"fc_5\",\"type\":\"function_call\","
                        + "\"call_id\":\"call_5\"}}"),
                frame(
                    "{\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_5\","
                        + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                        + "\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}}"))));
    assertGolden(
        "responses-stream-tool-duplicate-added",
        responsesStream(
            List.of(
                frame(
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_6\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                        + "{\"id\":\"fc_6\",\"type\":\"function_call\","
                        + "\"call_id\":\"call_6\",\"name\":\"lookup\"}}"),
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                        + "{\"id\":\"fc_6\",\"type\":\"function_call\","
                        + "\"call_id\":\"call_6\",\"name\":\"lookup\"}}"),
                frame(
                    "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_6\","
                        + "\"arguments\":\"{\\\"q\\\":\\\"z\\\"}\"}"),
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_6\","
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}}"))));
  }

  private static String responsesFailureOf(List<ObjectNode> frames) {
    OpenAiResponsesGoldenHarness harness =
        new OpenAiResponsesGoldenHarness(
            responsesRequest(
                plainVariant(),
                List.of(user("Hello")),
                List.of(tool("lookup")),
                ProviderCacheControl.none()),
            RESPONSES_DESCRIPTOR);
    ProviderException error = null;
    for (ObjectNode frame : frames) {
      try {
        harness.processEvent(frame);
      } catch (ProviderException thrown) {
        error = thrown;
        break;
      }
    }
    if (error == null) {
      error = assertThrows(ProviderException.class, harness::finish);
    }
    return error.kind().name() + "|" + error.getMessage();
  }

  /** Responses 流式累积的错误路径：用量、终态与工具完整性。 */
  @Test
  void responsesStreamErrorPaths() {
    assertGolden(
        "responses-stream-missing-usage",
        responsesFailureOf(
            List.of(
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\","
                        + "\"status\":\"completed\",\"output\":[]}}"))));
    assertGolden(
        "responses-stream-incomplete-usage",
        responsesFailureOf(
            List.of(
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\","
                        + "\"status\":\"completed\",\"usage\":{\"input_tokens\":1},"
                        + "\"output\":[]}}"))));
    assertGolden(
        "responses-stream-output-not-array",
        responsesFailureOf(
            List.of(
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\","
                        + "\"status\":\"completed\",\"usage\":{\"input_tokens\":1,"
                        + "\"output_tokens\":1},\"output\":{}}}"))));
    assertGolden(
        "responses-stream-premature-eof",
        responsesFailureOf(List.of(frame("{\"type\":\"response.created\",\"response\":{}}"))));
    assertGolden(
        "responses-stream-failed-event",
        responsesFailureOf(
            List.of(
                frame(
                    "{\"type\":\"response.failed\",\"response\":{\"error\":"
                        + "{\"code\":\"server_error\",\"message\":\"boom\"}}}"))));
    assertGolden(
        "responses-stream-duplicate-item-identity",
        responsesFailureOf(
            List.of(
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                        + "{\"id\":\"fc\",\"type\":\"function_call\",\"call_id\":\"c1\","
                        + "\"name\":\"lookup\"}}"),
                frame(
                    "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                        + "{\"id\":\"fc\",\"type\":\"function_call\",\"call_id\":\"c2\","
                        + "\"name\":\"lookup\"}}"))));
    assertGolden(
        "responses-stream-complete-missing-arguments",
        responsesFailureOf(
            List.of(
                frame(
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\","
                        + "\"status\":\"completed\",\"usage\":{\"input_tokens\":1,"
                        + "\"output_tokens\":1},\"output\":[{\"id\":\"fc\","
                        + "\"type\":\"function_call\",\"status\":\"completed\","
                        + "\"call_id\":\"c1\",\"name\":\"lookup\"}]}}"))));
    assertGolden(
        "responses-stream-invalid-reasoning-content",
        responsesFailureOf(
            List.of(
                frame(
                    "{\"type\":\"response.output_item.done\",\"item\":"
                        + "{\"id\":\"rs\",\"type\":\"reasoning\",\"content\":\"bad\"}}"))));
  }

  /** 终止后语义事件必须拒绝：错误文案被守护。 */
  @Test
  void responsesStreamRejectsSemanticEventAfterTerminal() {
    OpenAiResponsesGoldenHarness harness =
        new OpenAiResponsesGoldenHarness(
            responsesRequest(
                plainVariant(), List.of(user("Hello")), List.of(), ProviderCacheControl.none()),
            RESPONSES_DESCRIPTOR);
    harness.processEvent(
        frame(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_3\","
                + "\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1},"
                + "\"output\":[]}}"));
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                harness.processEvent(
                    frame("{\"type\":\"response.output_text.delta\",\"delta\":\"x\"}")));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertGolden("responses-stream-after-terminal", error.getMessage());
  }
}

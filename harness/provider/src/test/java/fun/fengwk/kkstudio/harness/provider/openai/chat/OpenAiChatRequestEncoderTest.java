package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.harness.provider.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.provider.RequestBodySizeGuard;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 测试意图：全面验证 OpenAI Chat 请求编码器（Golden 结构、输出预算、推理、Tools、Messages、Replay、媒体、留存档位映射）。 */
class OpenAiChatRequestEncoderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private OpenAiChatRequestEncoder encoder;
  private ProviderDescriptor descriptor;
  private ModelDescriptor modelDesc;
  private ModelVariant defaultVariant;

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
            "openai", "gpt-4o", "gpt-4o", Set.of(ModelInputModality.TEXT), true, false);
    defaultVariant = new ModelVariant("default");
  }

  @Test
  @DisplayName("标准请求 Golden 结构：model, stream=true, stream_options.include_usage=true")
  void testGoldenRequestStructure() throws Exception {
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());

    OpenAiChatEncodedRequest encoded =
        encoder.encode(request, descriptor, OpenAiChatConfiguration.defaults());
    assertNotNull(encoded.bodyUtf8Bytes());

    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals("gpt-4o", root.path("model").asText());
    assertTrue(root.path("stream").asBoolean());
    assertTrue(root.path("stream_options").path("include_usage").asBoolean());

    ArrayNode messages = (ArrayNode) root.path("messages");
    // 唯一的系统指令合成为前导 system message，会话消息紧随其后
    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).path("role").asText());
    assertEquals("Test system instruction.", messages.get(0).path("content").asText());
    assertEquals("user", messages.get(1).path("role").asText());
    assertEquals("Hello", messages.get(1).path("content").asText());
  }

  @Test
  @DisplayName("通过配置覆盖 openAiChatIncludeUsage 为 false")
  void testDisableIncludeUsage() throws Exception {
    OpenAiChatConfiguration config =
        new OpenAiChatConfiguration(false, true, PromptCacheRetention.NONE);
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());

    OpenAiChatEncodedRequest encoded = encoder.encode(request, descriptor, config);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertFalse(root.path("stream_options").path("include_usage").asBoolean());
  }

  @Test
  @DisplayName("STANDARD 格式下 off 映射为 none、null 不发送推理字段")
  void testReasoningEffortMapping() throws Exception {
    // STANDARD 与 DEEPSEEK 共用同一门控：非 reasoning 模型不发送任何推理字段
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "openai",
            "gpt-4o-reasoning",
            "gpt-4o-reasoning",
            Set.of(ModelInputModality.TEXT),
            true,
            true);
    ModelVariant variantWithReasoning = new ModelVariant("v1", "high");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Solve this")));
    ProviderRequest req1 =
        new ProviderRequest(
            reasoningModel,
            variantWithReasoning,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root1 =
        MAPPER.readTree(
            encoder.encode(req1, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    assertEquals("high", root1.path("reasoning_effort").asText());

    ModelVariant variantOff = new ModelVariant("v2", "off");
    ProviderRequest req2 =
        new ProviderRequest(
            reasoningModel,
            variantOff,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root2 =
        MAPPER.readTree(
            encoder.encode(req2, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    assertEquals("none", root2.path("reasoning_effort").asText());

    ProviderRequest req3 =
        new ProviderRequest(
            modelDesc,
            variantWithReasoning,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root3 =
        MAPPER.readTree(
            encoder.encode(req3, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    assertFalse(root3.has("reasoning_effort"));
  }

  @Test
  @DisplayName("DEEPSEEK thinking format 与 STANDARD 格式编码对比测试")
  void testDeepSeekThinkingFormatEncoding() throws Exception {
    ModelDescriptor reasoningModel =
        new ModelDescriptor(
            "deepseek",
            "deepseek-reasoner",
            "deepseek-reasoner",
            Set.of(ModelInputModality.TEXT),
            true,
            true);
    OpenAiChatConfiguration deepseekConfig =
        OpenAiChatConfiguration.parse("{\"openAiChatThinkingFormat\":\"DEEPSEEK\"}");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Solve math")));

    // 1. DEEPSEEK 格式下 reasoning=true 且 effort="low" -> thinking:{type:"enabled"} 且
    // reasoning_effort:"low"
    ModelVariant variantLow = new ModelVariant("v1", "low");
    ProviderRequest reqLow =
        new ProviderRequest(
            reasoningModel,
            variantLow,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootLow =
        MAPPER.readTree(encoder.encode(reqLow, descriptor, deepseekConfig).bodyUtf8Bytes());
    assertEquals("enabled", rootLow.path("thinking").path("type").asText());
    assertEquals("low", rootLow.path("reasoning_effort").asText());

    // 2. DEEPSEEK 格式下 reasoning=true 且 effort="off" -> 显式 thinking:{type:"disabled"}
    // 且不发送 reasoning_effort（off 是显式关闭，不是某个强度值）
    ModelVariant variantOff = new ModelVariant("v2", "off");
    ProviderRequest reqOff =
        new ProviderRequest(
            reasoningModel,
            variantOff,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootOff =
        MAPPER.readTree(encoder.encode(reqOff, descriptor, deepseekConfig).bodyUtf8Bytes());
    assertEquals("disabled", rootOff.path("thinking").path("type").asText());
    assertFalse(rootOff.has("reasoning_effort"));

    // 3. DEEPSEEK 格式下 reasoning=true 且 variant.reasoningEffort 为 null -> 不覆盖协议默认：
    // thinking 与 reasoning_effort 都不发送
    ProviderRequest reqDefaultVariant =
        new ProviderRequest(
            reasoningModel,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootDefaultVariant =
        MAPPER.readTree(
            encoder.encode(reqDefaultVariant, descriptor, deepseekConfig).bodyUtf8Bytes());
    assertFalse(rootDefaultVariant.has("thinking"));
    assertFalse(rootDefaultVariant.has("reasoning_effort"));

    // 4. DEEPSEEK 格式下 reasoning=false 且 effort="low" -> 均不生成 thinking 和 reasoning_effort
    ProviderRequest reqNonReasoningWithEffort =
        new ProviderRequest(
            modelDesc,
            variantLow,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootNonReasoningWithEffort =
        MAPPER.readTree(
            encoder.encode(reqNonReasoningWithEffort, descriptor, deepseekConfig).bodyUtf8Bytes());
    assertFalse(rootNonReasoningWithEffort.has("thinking"));
    assertFalse(rootNonReasoningWithEffort.has("reasoning_effort"));

    // 5. DEEPSEEK 格式下 reasoning=false 且 effort=null -> 均不生成 thinking 和 reasoning_effort
    ProviderRequest reqNonReasoningNull =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootNonReasoningNull =
        MAPPER.readTree(
            encoder.encode(reqNonReasoningNull, descriptor, deepseekConfig).bodyUtf8Bytes());
    assertFalse(rootNonReasoningNull.has("thinking"));
    assertFalse(rootNonReasoningNull.has("reasoning_effort"));

    // 6. STANDARD 格式保持标准编码：有 effort 时仅出 reasoning_effort，不出 thinking
    OpenAiChatConfiguration standardConfig = OpenAiChatConfiguration.defaults();
    JsonNode rootStandard =
        MAPPER.readTree(encoder.encode(reqLow, descriptor, standardConfig).bodyUtf8Bytes());
    assertFalse(rootStandard.has("thinking"));
    assertEquals("low", rootStandard.path("reasoning_effort").asText());

    // 7. STANDARD 格式下 off 映射为协议关闭值 none
    JsonNode rootStandardOff =
        MAPPER.readTree(encoder.encode(reqOff, descriptor, standardConfig).bodyUtf8Bytes());
    assertFalse(rootStandardOff.has("thinking"));
    assertEquals("none", rootStandardOff.path("reasoning_effort").asText());

    // 8. STANDARD 格式下 null effort 不发送任何推理字段
    JsonNode rootStandardDefault =
        MAPPER.readTree(
            encoder.encode(reqDefaultVariant, descriptor, standardConfig).bodyUtf8Bytes());
    assertFalse(rootStandardDefault.has("reasoning_effort"));
  }

  @Test
  @DisplayName("输出预算 max_tokens 正确映射到 JSON")
  void testOutputBudgetParameter() throws Exception {
    ModelVariant variant = new ModelVariant("v");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            variant,
            100,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(request, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals(100, root.path("max_tokens").asInt());
  }

  @Test
  @DisplayName("Tools 与 Tool Definition 正确编码")
  void testToolsEncoding() throws Exception {
    ProviderToolDefinition tool =
        new ProviderToolDefinition(
            "get_weather",
            "Get current weather",
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Weather?")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(tool),
            ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(request, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    ArrayNode tools = (ArrayNode) root.path("tools");
    assertEquals(1, tools.size());
    assertEquals("function", tools.get(0).path("type").asText());
    JsonNode fn = tools.get(0).path("function");
    assertEquals("get_weather", fn.path("name").asText());
    assertEquals("Get current weather", fn.path("description").asText());
    assertTrue(fn.path("parameters").isObject());
  }

  @Test
  @DisplayName("TOOL 消息与 ProviderToolResultBlock 编码为 role:tool 和 tool_call_id")
  void testToolMessageEncoding() throws Exception {
    ProviderToolResultBlock resultBlock =
        new ProviderToolResultBlock(
            "call_123",
            "get_weather",
            List.of(new ProviderTextBlock("{\"temp\":25}")),
            false,
            "{}");
    ProviderMessage toolMsg = new ProviderMessage(ProviderMessageRole.TOOL, List.of(resultBlock));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(toolMsg),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(request, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    ArrayNode messages = (ArrayNode) root.path("messages");
    assertEquals(2, messages.size());
    assertEquals("tool", messages.get(1).path("role").asText());
    assertEquals("call_123", messages.get(1).path("tool_call_id").asText());
    assertEquals("{\"temp\":25}", messages.get(1).path("content").asText());
  }

  @Test
  @DisplayName("按 Chat Completions 原生 schema 编码用户媒体")
  void testMediaEncoding() throws Exception {
    ProviderMessage userImg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderImageBlock("image/jpeg", "https://example.com/a.jpg")));
    ProviderRequest reqImg =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userImg),
            List.of(),
            ProviderCacheControl.none());

    JsonNode rootImg =
        MAPPER.readTree(
            encoder.encode(reqImg, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) rootImg.path("messages").get(1).path("content");
    assertEquals("image_url", parts.get(0).path("type").asText());
    assertEquals("https://example.com/a.jpg", parts.get(0).path("image_url").path("url").asText());

    // 2b. base64 data URI 图片必须逐字节保留完整 data URI 作为 image_url.url（不重编码、不丢载荷）
    String base64Png =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    ProviderMessage userImgBase64 =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderImageBlock("image/png", base64Png)));
    ProviderRequest reqImgBase64 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userImgBase64),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootImgBase64 =
        MAPPER.readTree(
            encoder
                .encode(reqImgBase64, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    ArrayNode imgBase64Parts = (ArrayNode) rootImgBase64.path("messages").get(1).path("content");
    assertEquals("image_url", imgBase64Parts.get(0).path("type").asText());
    assertEquals(base64Png, imgBase64Parts.get(0).path("image_url").path("url").asText());
    assertFalse(imgBase64Parts.get(0).path("image_url").path("url").asText().contains(" "));

    // 3. 音频处理：URL 必须拒绝
    ProviderMessage userAudioUrl =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/wav", "https://example.com/a.wav")));
    ProviderRequest reqAudioUrl =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userAudioUrl),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqAudioUrl, descriptor, OpenAiChatConfiguration.defaults()));

    // 4. 音频处理：base64 允许编码为 input_audio
    ProviderMessage userAudioBase64 =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/wav", "data:audio/wav;base64,UklGRg==")));
    ProviderRequest reqAudioBase64 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userAudioBase64),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootAudio =
        MAPPER.readTree(
            encoder
                .encode(reqAudioBase64, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    ArrayNode audioParts = (ArrayNode) rootAudio.path("messages").get(1).path("content");
    assertEquals("input_audio", audioParts.get(0).path("type").asText());
    assertEquals("UklGRg==", audioParts.get(0).path("input_audio").path("data").asText());
    assertEquals("wav", audioParts.get(0).path("input_audio").path("format").asText());

    // 5. PDF 编码
    ProviderMessage userPdf =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(
                new ProviderDocumentBlock(
                    "application/pdf", "data:application/pdf;base64,JVBERi==")));
    ProviderRequest reqPdf =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userPdf),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootPdf =
        MAPPER.readTree(
            encoder.encode(reqPdf, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    ArrayNode pdfParts = (ArrayNode) rootPdf.path("messages").get(1).path("content");
    assertEquals("file", pdfParts.get(0).path("type").asText());
    assertEquals(
        "data:application/pdf;base64,JVBERi==",
        pdfParts.get(0).path("file").path("file_data").asText());

    // 6. VIDEO 始终拒绝
    ProviderMessage userVideo =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderVideoBlock("video/mp4", "https://example.com/v.mp4")));
    ProviderRequest reqVideo =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userVideo),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqVideo, descriptor, OpenAiChatConfiguration.defaults()));
  }

  @Test
  @DisplayName("ASSISTANT ReplayState 合法原位回放白名单对象；同 format 但 payload 非法应拒绝；不可回放时 fallback")
  void testAssistantReplayAndFallback() throws Exception {
    ProviderMessage userMsg1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Tell joke")));

    // 1. 合法 replay：仅包含白名单字段且匹配 durable
    ObjectNode validPayload = MAPPER.createObjectNode();
    validPayload.put("role", "assistant");
    validPayload.put("content", "Why did chicken cross road?");
    validPayload.put("reasoning_content", "A classic joke is appropriate.");

    ProviderReplayState validReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), validPayload);

    ProviderMessage validAsstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("A classic joke is appropriate."),
                new ProviderTextBlock("Why did chicken cross road?")),
            validReplayState);
    ProviderMessage userMsg2 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Why?")));

    ProviderRequest turn2Req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg1, validAsstMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(turn2Req, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    ArrayNode messages = (ArrayNode) root.path("messages");
    assertEquals(4, messages.size());

    JsonNode replayedAsst = messages.get(2);
    assertEquals("assistant", replayedAsst.path("role").asText());
    assertEquals("Why did chicken cross road?", replayedAsst.path("content").asText());
    assertEquals("A classic joke is appropriate.", replayedAsst.path("reasoning_content").asText());

    // 2. 同 format 且 payload 携带 provider 原生未知字段：不在白名单内的合法 assistant 字段必须原样透传
    ObjectNode extendedPayload = validPayload.deepCopy();
    extendedPayload.put("vendor_future_field", "kept");
    extendedPayload.putObject("audio").put("id", "audio_1");
    ProviderReplayState extendedReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), extendedPayload);
    ProviderMessage extendedAsstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("A classic joke is appropriate."),
                new ProviderTextBlock("Why did chicken cross road?")),
            extendedReplayState);
    ProviderRequest extendedReq =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg1, extendedAsstMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());
    JsonNode extendedRoot =
        MAPPER.readTree(
            encoder
                .encode(extendedReq, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode extendedAsst = extendedRoot.path("messages").get(2);
    assertEquals("Why did chicken cross road?", extendedAsst.path("content").asText());
    assertEquals("kept", extendedAsst.path("vendor_future_field").asText());
    assertEquals(MAPPER.readTree("{\"id\":\"audio_1\"}"), extendedAsst.path("audio"));

    // 2b. 同 format 但 payload 的 known 字段形态损坏（content 非文本）必须抛出 ProviderException 拒绝
    ObjectNode illegalPayload = MAPPER.createObjectNode();
    illegalPayload.put("role", "assistant");
    illegalPayload.put("content", 42);
    ProviderReplayState illegalReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), illegalPayload);
    ProviderMessage illegalAsstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("A classic joke is appropriate."),
                new ProviderTextBlock("Why did chicken cross road?")),
            illegalReplayState);
    ProviderRequest illegalReq =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg1, illegalAsstMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(illegalReq, descriptor, OpenAiChatConfiguration.defaults()));

    // 3. affinity 失配（不同 wire 模型）且 payload 合法一致时，退回语义 fallback
    ObjectNode fallbackPayload = MAPPER.createObjectNode();
    fallbackPayload.put("role", "assistant");
    fallbackPayload.put("content", "Fallback answer");
    ProviderReplayState mismatchedAffinityReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o-mini"), fallbackPayload);
    ProviderMessage fallbackAsstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("Fallback answer")),
            mismatchedAffinityReplayState);
    ProviderRequest fallbackReq =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg1, fallbackAsstMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());
    JsonNode fallbackRoot =
        MAPPER.readTree(
            encoder
                .encode(fallbackReq, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode fallbackAsstNode = fallbackRoot.path("messages").get(2);
    assertEquals("assistant", fallbackAsstNode.path("role").asText());
    assertEquals("Fallback answer", fallbackAsstNode.path("content").asText());
    assertFalse(fallbackAsstNode.has("reasoning_content"));
  }

  /** 验证 refusal replay 与 durable 可见文本一致时原位回放，不一致时严格拒绝。 */
  @Test
  void testAssistantRefusalReplay() throws Exception {
    ProviderMessage firstUser =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("unsafe request")));
    OpenAiChatEncodedRequest firstRequest =
        encoder.encode(
            new ProviderRequest(
                modelDesc,
                defaultVariant,
                1024,
                "Test system instruction.",
                List.of(firstUser),
                List.of(),
                ProviderCacheControl.none()),
            descriptor,
            OpenAiChatConfiguration.defaults());

    ObjectNode replayPayload = MAPPER.createObjectNode();
    replayPayload.put("role", "assistant");
    replayPayload.put("refusal", "I cannot help with that.");
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity(modelDesc.modelId()),
            replayPayload);
    ProviderMessage refusal =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("I cannot help with that.")),
            replayState);
    ProviderMessage nextUser =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Why?")));

    ProviderRequest nextRequest =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(firstUser, refusal, nextUser),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(nextRequest, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());

    JsonNode replayedRefusal = root.path("messages").get(2);
    assertEquals("I cannot help with that.", replayedRefusal.path("refusal").asText());
    assertFalse(replayedRefusal.has("content"));

    ProviderMessage mismatchedRefusal =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("different text")),
            replayState);
    ProviderRequest invalidRequest =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(firstUser, mismatchedRefusal, nextUser),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(invalidRequest, descriptor, OpenAiChatConfiguration.defaults()));
  }

  @Test
  @DisplayName("Prompt Cache 留存档位映射：NONE 不发，SHORT/LONG 发 key + retention")
  void testPromptCacheRetentionMapping() throws Exception {
    ProviderMessage userMsg =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("User prompt")));

    // 1. NONE: 不发任何 cache hint
    OpenAiChatConfiguration configNone =
        new OpenAiChatConfiguration(true, true, PromptCacheRetention.NONE);
    ProviderRequest reqNone =
        cacheRequest("Test system instruction.", List.of(userMsg), ProviderCacheControl.none());
    JsonNode rootNone =
        MAPPER.readTree(encoder.encode(reqNone, descriptor, configNone).bodyUtf8Bytes());
    assertFalse(rootNone.has("prompt_cache_key"));
    assertFalse(rootNone.has("prompt_cache_retention"));
    assertFalse(rootNone.has("prompt_cache_options"));

    // 2. SHORT: key 直接使用运行时会话 key，retention=in_memory
    OpenAiChatConfiguration configShort =
        new OpenAiChatConfiguration(true, true, PromptCacheRetention.SHORT);
    ProviderCacheControl cacheControlShort =
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "my-key-short");
    ProviderRequest reqShort =
        cacheRequest("Test system instruction.", List.of(userMsg), cacheControlShort);
    JsonNode rootShort =
        MAPPER.readTree(encoder.encode(reqShort, descriptor, configShort).bodyUtf8Bytes());
    assertEquals("my-key-short", rootShort.path("prompt_cache_key").asText());
    assertEquals("in_memory", rootShort.path("prompt_cache_retention").asText());
    assertFalse(rootShort.has("prompt_cache_options"));
    // 不再改写 content 形态或注入 breakpoint
    assertEquals("User prompt", rootShort.path("messages").get(1).path("content").asText());

    // 3. LONG: retention=24h
    OpenAiChatConfiguration configLong =
        new OpenAiChatConfiguration(true, true, PromptCacheRetention.LONG);
    ProviderCacheControl cacheControlLong =
        ProviderCacheControl.session(PromptCacheRetention.LONG, "my-key-long");
    ProviderRequest reqLong =
        cacheRequest("Test system instruction.", List.of(userMsg), cacheControlLong);
    JsonNode rootLong =
        MAPPER.readTree(encoder.encode(reqLong, descriptor, configLong).bodyUtf8Bytes());
    assertEquals("my-key-long", rootLong.path("prompt_cache_key").asText());
    assertEquals("24h", rootLong.path("prompt_cache_retention").asText());

    // 4. NONE retention: 即便配置为 LONG，cacheControl=NONE 也不发 cache 字段
    ProviderRequest reqLongNone =
        cacheRequest("Test system instruction.", List.of(userMsg), ProviderCacheControl.none());
    JsonNode rootLongNone =
        MAPPER.readTree(encoder.encode(reqLongNone, descriptor, configLong).bodyUtf8Bytes());
    assertFalse(rootLongNone.has("prompt_cache_key"));
    assertFalse(rootLongNone.has("prompt_cache_retention"));
  }

  @Test
  @DisplayName("各种边缘消息角色与非法块校验")
  void testEdgeCasesAndIllegalBlocks() throws Exception {
    // 1. ASSISTANT 包含 ThinkingBlock（回退为带来源标记的普通文本）与 ToolCall
    ProviderMessage asstWithThinking =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("internal thought"),
                new ProviderTextBlock("answer text"),
                new ProviderToolCallBlock(new ProviderToolCall("call_1", "calc", "{}"))));
    ProviderRequest reqAsstThinking =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(asstWithThinking),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootAsstThinking =
        MAPPER.readTree(
            encoder
                .encode(reqAsstThinking, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode asstNode = rootAsstThinking.path("messages").get(1);
    assertEquals(
        "<thinking>\ninternal thought\n</thinking>\nanswer text",
        asstNode.path("content").asText());
    assertEquals(1, asstNode.path("tool_calls").size());

    // 2. ASSISTANT 包含非法块（如 ImageBlock）抛异常
    ProviderMessage asstWithImage =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderImageBlock("image/png", "data:image/png;base64,123")));
    ProviderRequest reqAsstImage =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(asstWithImage),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqAsstImage, descriptor, OpenAiChatConfiguration.defaults()));

    // 3. TOOL 消息包含 ProviderJsonBlock
    ProviderToolResultBlock resultWithJson =
        new ProviderToolResultBlock(
            "call_json", "calc", List.of(new ProviderJsonBlock("{\"ans\":42}")), false, "{}");
    ProviderMessage toolJsonMsg =
        new ProviderMessage(ProviderMessageRole.TOOL, List.of(resultWithJson));
    ProviderRequest reqToolJson =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(toolJsonMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootToolJson =
        MAPPER.readTree(
            encoder
                .encode(reqToolJson, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals("{\"ans\":42}", rootToolJson.path("messages").get(1).path("content").asText());

    // 4. 不支持的音频格式抛异常
    OpenAiChatConfiguration audioConfig =
        new OpenAiChatConfiguration(true, true, PromptCacheRetention.NONE);
    ProviderMessage audioFlac =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/flac", "data:audio/flac;base64,123")));
    ProviderRequest reqFlac =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(audioFlac),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqFlac, descriptor, audioConfig));

    // 7. 非法 data URI 音频数据
    ProviderMessage audioBadUri =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/wav", "data:audio/wav-without-comma")));
    ProviderRequest reqBadAudio =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(audioBadUri),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class, () -> encoder.encode(reqBadAudio, descriptor, audioConfig));
  }

  @Test
  @DisplayName("Replay payload 详细非法分支拒绝与带 tool_calls 合法回放")
  void testReplayPayloadValidationDetailed() throws Exception {
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderRequest req1 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1),
            List.of(),
            ProviderCacheControl.none());
    // 1. payload role 不是 assistant
    ObjectNode roleUserPayload = MAPPER.createObjectNode();
    roleUserPayload.put("role", "user");
    roleUserPayload.put("content", "Hi");
    ProviderReplayState badRoleState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), roleUserPayload);
    ProviderMessage badRoleMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("Hi")), badRoleState);
    ProviderRequest reqBadRole =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, badRoleMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqBadRole, descriptor, OpenAiChatConfiguration.defaults()));

    // 2. payload 文本与 durable 不匹配
    ObjectNode mismatchTextPayload = MAPPER.createObjectNode();
    mismatchTextPayload.put("role", "assistant");
    mismatchTextPayload.put("content", "different text");
    ProviderReplayState mismatchTextState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), mismatchTextPayload);
    ProviderMessage mismatchTextMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("actual text")),
            mismatchTextState);
    ProviderRequest reqMismatchText =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, mismatchTextMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqMismatchText, descriptor, OpenAiChatConfiguration.defaults()));

    // 3. payload tool_calls 格式损坏（缺少 function）
    ObjectNode badToolPayload = MAPPER.createObjectNode();
    badToolPayload.put("role", "assistant");
    badToolPayload.put("content", "");
    ArrayNode badCalls = badToolPayload.putArray("tool_calls");
    ObjectNode callWithoutFn = badCalls.addObject();
    callWithoutFn.put("id", "c1");
    callWithoutFn.put("type", "function");
    // missing function node
    ProviderReplayState badToolState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), badToolPayload);
    ProviderMessage badToolMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            badToolState);
    ProviderRequest reqBadTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, badToolMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqBadTool, descriptor, OpenAiChatConfiguration.defaults()));

    // 4. 合法带 tool_calls 的原位回放
    ObjectNode validToolPayload = MAPPER.createObjectNode();
    validToolPayload.put("role", "assistant");
    validToolPayload.put("content", "");
    ArrayNode validCalls = validToolPayload.putArray("tool_calls");
    ObjectNode validCall = validCalls.addObject();
    validCall.put("id", "c1");
    validCall.put("type", "function");
    ObjectNode fn = validCall.putObject("function");
    fn.put("name", "calc");
    fn.put("arguments", "{\"x\":1}");
    ProviderReplayState validToolState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), validToolPayload);
    ProviderMessage validToolMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{\"x\":1}"))),
            validToolState);
    ProviderRequest reqValidTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, validToolMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootValidTool =
        MAPPER.readTree(
            encoder
                .encode(reqValidTool, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals(3, rootValidTool.path("messages").size());
    assertEquals(
        "c1", rootValidTool.path("messages").get(2).path("tool_calls").get(0).path("id").asText());

    // 5. payload role 不是 text
    ObjectNode badRoleTypePayload = MAPPER.createObjectNode();
    badRoleTypePayload.put("role", 123);
    ProviderReplayState badRoleTypeState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), badRoleTypePayload);
    ProviderMessage badRoleTypeMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("Hi")), badRoleTypeState);
    ProviderRequest reqBadRoleType =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, badRoleTypeMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqBadRoleType, descriptor, OpenAiChatConfiguration.defaults()));

    // 7. payload tool_calls 包含非 object 元素
    ObjectNode nonObjToolPayload = MAPPER.createObjectNode();
    nonObjToolPayload.put("role", "assistant");
    nonObjToolPayload.put("content", "");
    nonObjToolPayload.putArray("tool_calls").add("string-element");
    ProviderReplayState nonObjToolState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), nonObjToolPayload);
    ProviderMessage nonObjToolMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))),
            nonObjToolState);
    ProviderRequest reqNonObjTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, nonObjToolMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqNonObjTool, descriptor, OpenAiChatConfiguration.defaults()));
  }

  @Test
  @DisplayName("Tool 损坏 schema、MP3 音频、PDF 格式及末尾 tool-calls-only assistant 的缓存编码")
  void testSchemaAudioPdfAndBreakpointBranches() throws Exception {
    // 1. Tool inputSchemaJson 不是合法 JSON
    ProviderToolDefinition badJsonTool =
        new ProviderToolDefinition("bad_tool", "desc", "not-json-at-all");
    ProviderRequest reqBadJsonTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(badJsonTool),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqBadJsonTool, descriptor, OpenAiChatConfiguration.defaults()));

    // 2. Tool inputSchemaJson 不是 JSON object (如 "123")
    ProviderToolDefinition nonObjTool = new ProviderToolDefinition("non_obj_tool", "desc", "123");
    ProviderRequest reqNonObjJsonTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(nonObjTool),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqNonObjJsonTool, descriptor, OpenAiChatConfiguration.defaults()));

    // 3. MP3 音频格式识别
    OpenAiChatConfiguration audioConfig =
        new OpenAiChatConfiguration(true, true, PromptCacheRetention.NONE);
    ProviderMessage mp3Msg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/mp3", "data:audio/mp3;base64,AAA=")));
    ProviderRequest reqMp3 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(mp3Msg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootMp3 =
        MAPPER.readTree(encoder.encode(reqMp3, descriptor, audioConfig).bodyUtf8Bytes());
    assertEquals(
        "mp3",
        rootMp3
            .path("messages")
            .get(1)
            .path("content")
            .get(0)
            .path("input_audio")
            .path("format")
            .asText());

    // 4. PDF 非法 mediaType
    OpenAiChatConfiguration pdfConfig =
        new OpenAiChatConfiguration(true, true, PromptCacheRetention.NONE);
    ProviderMessage badDocMsg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(
                new ProviderDocumentBlock(
                    "application/msword", "data:application/msword;base64,AAA=")));
    ProviderRequest reqBadDoc =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(badDocMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqBadDoc, descriptor, pdfConfig));

    // 5. PDF 纯 base64 自动补充 data:application/pdf;base64, 前缀
    ProviderMessage rawPdfMsg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderDocumentBlock("application/pdf", "JVBERi0xLjQK")));
    ProviderRequest reqRawPdf =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(rawPdfMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootRawPdf =
        MAPPER.readTree(encoder.encode(reqRawPdf, descriptor, pdfConfig).bodyUtf8Bytes());
    assertEquals(
        "data:application/pdf;base64,JVBERi0xLjQK",
        rootRawPdf
            .path("messages")
            .get(1)
            .path("content")
            .get(0)
            .path("file")
            .path("file_data")
            .asText());

    // 6. tool-call-only assistant 不制造 content；启用缓存时也不改写消息内容或注入 breakpoint
    OpenAiChatConfiguration configShort =
        new OpenAiChatConfiguration(true, true, PromptCacheRetention.SHORT);
    ProviderCacheControl cacheControlShort =
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "session-key");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("calc")));
    ProviderMessage asstOnlyTools =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c_last", "calc", "{}"))));
    ProviderRequest reqLastToolBreak =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg, asstOnlyTools),
            List.of(),
            cacheControlShort);
    JsonNode rootBreak =
        MAPPER.readTree(encoder.encode(reqLastToolBreak, descriptor, configShort).bodyUtf8Bytes());
    assertEquals("session-key", rootBreak.path("prompt_cache_key").asText());
    assertEquals("in_memory", rootBreak.path("prompt_cache_retention").asText());
    ArrayNode messages = (ArrayNode) rootBreak.path("messages");
    // 0 号是合成的系统指令消息，user 消息紧随其后
    JsonNode userNode = messages.get(1);
    assertEquals("calc", userNode.path("content").asText());
    assertFalse(messages.get(2).has("content"));
  }

  @Test
  @DisplayName("TOOL 消息多块按原顺序完整串联且拒绝未知块")
  void testToolMessageMultiBlockAndUnsupportedBlocks() throws Exception {
    // 测试意图：验证 TOOL message 的唯一 ProviderToolResultBlock 内，按原顺序完整处理任意数量的 Text 与 Json 块，
    // JSON 不得丢弃，多块采用确定性文本串联；遇到任何其他类型块（如 Image）必须抛出 ProviderErrorKind.INVALID_REQUEST。
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("call tool")));

    // 1. 多个 Text 和 Json 块顺序拼接
    ProviderToolResultBlock multiBlockResult =
        new ProviderToolResultBlock(
            "call_multi",
            "calc",
            List.of(
                new ProviderTextBlock("Result: "),
                new ProviderJsonBlock("{\"score\":99}"),
                new ProviderTextBlock(" done")),
            false,
            "{}");
    ProviderMessage toolMsg =
        new ProviderMessage(ProviderMessageRole.TOOL, List.of(multiBlockResult));
    ProviderRequest reqMulti =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg, toolMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootMulti =
        MAPPER.readTree(
            encoder
                .encode(reqMulti, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals(
        "Result: {\"score\":99} done", rootMulti.path("messages").get(2).path("content").asText());

    // 2. 包含非法块（例如 ProviderImageBlock）必须抛出 INVALID_REQUEST
    ProviderToolResultBlock badBlockResult =
        new ProviderToolResultBlock(
            "call_bad",
            "calc",
            List.of(
                new ProviderTextBlock("Result: "),
                new ProviderImageBlock("image/png", "http://example.com/img.png")),
            false,
            "{}");
    ProviderMessage badToolMsg =
        new ProviderMessage(ProviderMessageRole.TOOL, List.of(badBlockResult));
    ProviderRequest reqBadTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg, badToolMsg),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(reqBadTool, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }

  /**
   * 测试意图：Chat Completions 的 tool message 只承载文本/JSON，任何媒体块（图片、PDF、音频、视频）与其他块类型都必须以 INVALID_REQUEST
   * 明确失败；该参数化用例把每个块类型映射到预期 wire 文本或预期失败，锁定能力声明与实际编码一致。
   */
  @ParameterizedTest(name = "chat tool result block: {0}")
  @MethodSource("toolResultBlockCases")
  void testToolResultBlockModalityMatrix(
      String label, ProviderContentBlock block, String expectedContent) throws Exception {
    ProviderMessage toolMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(new ProviderToolResultBlock("call_1", "tool", List.of(block), false, "{}")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(toolMsg),
            List.of(),
            ProviderCacheControl.none());

    if (expectedContent == null) {
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encoder.encode(request, descriptor, OpenAiChatConfiguration.defaults()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNotNull(ex.getMessage());
      return;
    }

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(request, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode wire = root.path("messages").get(1);
    assertEquals("tool", wire.path("role").asText());
    assertEquals("call_1", wire.path("tool_call_id").asText());
    assertEquals(expectedContent, wire.path("content").asText());
  }

  private static Stream<Arguments> toolResultBlockCases() {
    return Stream.of(
        Arguments.of("text", new ProviderTextBlock("Result text"), "Result text"),
        Arguments.of("json", new ProviderJsonBlock("{\"ans\":1}"), "{\"ans\":1}"),
        Arguments.of(
            "image-rejected",
            new ProviderImageBlock("image/png", "data:image/png;base64,iVBORw0KGgo="),
            null),
        Arguments.of(
            "pdf-rejected",
            new ProviderDocumentBlock(
                "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK"),
            null),
        Arguments.of(
            "audio-rejected",
            new ProviderAudioBlock("audio/wav", "data:audio/wav;base64,UklGRg=="),
            null),
        Arguments.of(
            "video-rejected",
            new ProviderVideoBlock("video/mp4", "data:video/mp4;base64,AAAAIGZ0eXA="),
            null),
        Arguments.of("thinking-rejected", new ProviderThinkingBlock("internal"), null));
  }

  @Test
  @DisplayName("同 format payload 即使 affinity 失配也必须先严格校验 shape 与 durable 一致性")
  void testReplayValidationBeforeAffinityCheck() throws Exception {
    // 测试意图：验证同 OPENAI_CHAT format 时，即使 affinity 失配，
    // 也必须先严格校验 payload 的 shape 与 durable 一致性，损坏时必须抛出 INVALID_REQUEST，严禁静默 fallback。
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    // 1. affinity 失配且 role 非法（非 assistant）-> 必须抛出 INVALID_REQUEST
    ObjectNode badRolePayload = MAPPER.createObjectNode();
    badRolePayload.put("role", "system");
    badRolePayload.put("content", "text");
    ProviderReplayState badRoleState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o-mini"), badRolePayload);
    ProviderMessage badRoleMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("text")), badRoleState);
    ProviderRequest reqBadRole =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, badRoleMsg),
            List.of(),
            ProviderCacheControl.none());
    ProviderException exRole =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(reqBadRole, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exRole.kind());

    // 2. affinity 失配且 known 字段 content 形态非法（非文本）-> 必须抛出 INVALID_REQUEST
    // 未知字段本身允许透传，但 known 字段的 shape 校验绝不因此放宽
    ObjectNode illegalContentPayload = MAPPER.createObjectNode();
    illegalContentPayload.put("role", "assistant");
    illegalContentPayload.put("content", 123);
    illegalContentPayload.put("vendor_future_field", "value");
    ProviderReplayState illegalContentState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o-mini"),
            illegalContentPayload);
    ProviderMessage illegalContentMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("text")),
            illegalContentState);
    ProviderRequest reqIllegalContent =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, illegalContentMsg),
            List.of(),
            ProviderCacheControl.none());
    ProviderException exContent =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(reqIllegalContent, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exContent.kind());

    // 3. affinity 失配且 reasoning_content 与 durable ProviderThinkingBlock 不一致 -> 必须抛出 INVALID_REQUEST
    ObjectNode mismatchThinkingPayload = MAPPER.createObjectNode();
    mismatchThinkingPayload.put("role", "assistant");
    mismatchThinkingPayload.put("content", "text");
    mismatchThinkingPayload.put("reasoning_content", "fabricated reasoning");
    ProviderReplayState mismatchThinkingState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o-mini"),
            mismatchThinkingPayload);
    // durable 中没有 ProviderThinkingBlock
    ProviderMessage mismatchThinkingMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("text")),
            mismatchThinkingState);
    ProviderRequest reqMismatchThinking =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, mismatchThinkingMsg),
            List.of(),
            ProviderCacheControl.none());
    ProviderException exThinking =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    reqMismatchThinking, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exThinking.kind());

    // 4. affinity 失配但 payload 完全合法且与 durable 一致 -> 允许降级为语义 fallback
    ObjectNode validFallbackPayload = MAPPER.createObjectNode();
    validFallbackPayload.put("role", "assistant");
    validFallbackPayload.put("content", "text");
    ProviderReplayState validFallbackState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o-mini"),
            validFallbackPayload);
    ProviderMessage validFallbackMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("text")),
            validFallbackState);
    ProviderRequest reqValidFallback =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, validFallbackMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootFallback =
        MAPPER.readTree(
            encoder
                .encode(reqValidFallback, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals("assistant", rootFallback.path("messages").get(2).path("role").asText());
    assertEquals("text", rootFallback.path("messages").get(2).path("content").asText());
  }

  @Test
  @DisplayName("tool_calls 损坏子结构校验并阻止构造器抛裸 IllegalArgumentException")
  void testReplayToolCallsShapeValidationPreventsRawIllegalArgumentException() {
    // 测试意图：验证 tool_calls 各种损坏形态（非 array、缺少 id、name/arguments 为空等）均被严格校验抛出
    // ProviderErrorKind.INVALID_REQUEST，避免 ProviderToolCall 构造器抛出裸的 IllegalArgumentException。
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    // 1. tool_calls 为非 array
    ObjectNode nonArrPayload = MAPPER.createObjectNode();
    nonArrPayload.put("role", "assistant");
    nonArrPayload.put("tool_calls", "not-array");
    ProviderReplayState state1 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), nonArrPayload);
    ProviderMessage msg1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            state1);
    ProviderRequest req1 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, msg1),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req1, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. tool call 的 id 为 blank
    ObjectNode blankIdPayload = MAPPER.createObjectNode();
    blankIdPayload.put("role", "assistant");
    ArrayNode calls2 = blankIdPayload.putArray("tool_calls");
    ObjectNode call2 = calls2.addObject();
    call2.put("id", "   ");
    call2.put("type", "function");
    ObjectNode fn2 = call2.putObject("function");
    fn2.put("name", "fn");
    fn2.put("arguments", "{}");
    ProviderReplayState state2 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), blankIdPayload);
    ProviderMessage msg2 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            state2);
    ProviderRequest req2 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, msg2),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req2, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());

    // 3. tool call 的 function arguments 为 blank（若直接构造 ProviderToolCall 会抛 IllegalArgumentException）
    ObjectNode blankArgsPayload = MAPPER.createObjectNode();
    blankArgsPayload.put("role", "assistant");
    ArrayNode calls3 = blankArgsPayload.putArray("tool_calls");
    ObjectNode call3 = calls3.addObject();
    call3.put("id", "c1");
    call3.put("type", "function");
    ObjectNode fn3 = call3.putObject("function");
    fn3.put("name", "fn");
    fn3.put("arguments", "   ");
    ProviderReplayState state3 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), blankArgsPayload);
    ProviderMessage msg3 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            state3);
    ProviderRequest req3 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, msg3),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex3 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req3, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());

    // 4. tool call 的 function name 为 blank
    ObjectNode blankNamePayload = MAPPER.createObjectNode();
    blankNamePayload.put("role", "assistant");
    ArrayNode calls4 = blankNamePayload.putArray("tool_calls");
    ObjectNode call4 = calls4.addObject();
    call4.put("id", "c1");
    call4.put("type", "function");
    ObjectNode fn4 = call4.putObject("function");
    fn4.put("name", "   ");
    fn4.put("arguments", "{}");
    ProviderReplayState state4 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), blankNamePayload);
    ProviderMessage msg4 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            state4);
    ProviderRequest req4 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, msg4),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex4 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req4, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex4.kind());
  }

  @Test
  @DisplayName("非 OPENAI_CHAT format 的 replay 自动进行 semantic fallback")
  void testReplayNonOpenAiChatFormatSemanticFallback() throws Exception {
    // 测试意图：验证非 OPENAI_CHAT format（如 ANTHROPIC_MESSAGES）直接走语义回退，不校验 OPENAI_CHAT payload
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ObjectNode anthropicPayload = MAPPER.createObjectNode();
    anthropicPayload.put("type", "message");
    ProviderReplayState anthropicState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            anthropicPayload);
    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("think dropped"),
                new ProviderTextBlock("fallback result"),
                new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{\"a\":1}"))),
            anthropicState);
    ProviderRequest req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, asstMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root =
        MAPPER.readTree(
            encoder.encode(req, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    JsonNode wireAsst = root.path("messages").get(2);
    assertEquals("assistant", wireAsst.path("role").asText());
    // 可读思考以带来源标记的普通文本保留，与 final text 明确分隔。
    assertEquals(
        "<thinking>\nthink dropped\n</thinking>\nfallback result",
        wireAsst.path("content").asText());
    assertEquals("c1", wireAsst.path("tool_calls").get(0).path("id").asText());
    assertFalse(wireAsst.has("reasoning_content"));
  }

  @Test
  @DisplayName("Responses 空 reasoning 占位符 replay 跨格式交接：Chat 语义回退且不泄漏原生结构")
  void testResponsesEmptyPlaceholderReplayCrossFormatHandoff() throws Exception {
    // 测试意图：MiniMax Responses 网关产出的不完整 replay（空 reasoning 占位符、无密文、无可用摘要）在交接给
    // Chat 编码器时，必须整体跨格式回退为语义消息——既不抛出 INVALID_REQUEST，也不把 Responses 原生结构写到 Chat 线路上。
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ObjectNode responsesPayload = MAPPER.createObjectNode();
    ArrayNode responsesOutput = responsesPayload.putArray("output");
    responsesOutput.addObject().put("type", "reasoning").putArray("summary");
    ProviderReplayState responsesState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            descriptor.affinity("MiniMax-M2"),
            responsesPayload);
    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("weigh tradeoffs"),
                new ProviderTextBlock("fallback result"),
                new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{\"a\":1}"))),
            responsesState);
    ProviderRequest req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, asstMsg),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder.encode(req, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    JsonNode wireAsst = root.path("messages").get(2);
    assertEquals("assistant", wireAsst.path("role").asText());
    assertEquals(
        "<thinking>\nweigh tradeoffs\n</thinking>\nfallback result",
        wireAsst.path("content").asText());
    assertEquals("c1", wireAsst.path("tool_calls").get(0).path("id").asText());
    assertFalse(wireAsst.has("reasoning_content"));
    assertFalse(
        wireAsst.has("reasoning"), "Responses-native structural fields must never leak into Chat");
  }

  @Test
  @DisplayName("tool_calls type=function 强校验与原生额外字段保留（即使 affinity 失配）")
  void testToolCallsTypeRequirementAndNativeFieldPassthrough() throws Exception {
    // 测试意图：tool_calls 每个 function 调用必须完整声明 id/type/function{name,arguments}，缺失或非法 type 必须
    // INVALID_REQUEST；而 vendor 额外嵌套字段属于原生事实，affinity 匹配时必须原样保留，不得因此拒绝或丢弃。
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));

    // 1. 缺少 type 字段 -> INVALID_REQUEST
    ObjectNode missingTypePayload = MAPPER.createObjectNode();
    missingTypePayload.put("role", "assistant");
    ObjectNode call1 = missingTypePayload.putArray("tool_calls").addObject();
    call1.put("id", "c1");
    ObjectNode fn1 = call1.putObject("function");
    fn1.put("name", "fn");
    fn1.put("arguments", "{}");
    ProviderReplayState state1 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o-mini"),
            missingTypePayload);
    ProviderMessage msg1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            state1);
    ProviderException ex1 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    new ProviderRequest(
                        modelDesc,
                        defaultVariant,
                        1024,
                        "Test system instruction.",
                        List.of(user1, msg1),
                        List.of(),
                        ProviderCacheControl.none()),
                    descriptor,
                    OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. type 非 "function"（custom）无法与 durable function 调用对齐 -> INVALID_REQUEST
    ObjectNode badTypePayload = MAPPER.createObjectNode();
    badTypePayload.put("role", "assistant");
    ObjectNode call2 = badTypePayload.putArray("tool_calls").addObject();
    call2.put("id", "c1");
    call2.put("type", "custom");
    ObjectNode fn2 = call2.putObject("function");
    fn2.put("name", "fn");
    fn2.put("arguments", "{}");
    ProviderReplayState state2 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o-mini"), badTypePayload);
    ProviderMessage msg2 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            state2);
    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    new ProviderRequest(
                        modelDesc,
                        defaultVariant,
                        1024,
                        "Test system instruction.",
                        List.of(user1, msg2),
                        List.of(),
                        ProviderCacheControl.none()),
                    descriptor,
                    OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());

    // 3. vendor 额外嵌套字段（call 级与 function 级）属于原生事实：affinity 匹配时原样保留
    ObjectNode extraPayload = MAPPER.createObjectNode();
    extraPayload.put("role", "assistant");
    ObjectNode call3 = extraPayload.putArray("tool_calls").addObject();
    call3.put("id", "c1").put("type", "function").put("extra_call_field", "value");
    ObjectNode fn3 = call3.putObject("function");
    fn3.put("name", "fn");
    fn3.put("arguments", "{}");
    fn3.put("extra_fn_field", "value");
    ProviderReplayState state3 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), extraPayload);
    ProviderMessage msg3 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            state3);
    JsonNode wire =
        MAPPER.readTree(
            encoder
                .encode(
                    new ProviderRequest(
                        modelDesc,
                        defaultVariant,
                        1024,
                        "Test system instruction.",
                        List.of(user1, msg3),
                        List.of(),
                        ProviderCacheControl.none()),
                    descriptor,
                    OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode wireCall = wire.path("messages").get(2).path("tool_calls").get(0);
    assertEquals("value", wireCall.path("extra_call_field").asText());
    assertEquals("value", wireCall.path("function").path("extra_fn_field").asText());
  }

  @Test
  @DisplayName("reasoning_details 为标量时拒绝抛出 INVALID_REQUEST")
  void testRejectScalarReasoningDetails() {
    // 测试意图：验证 reasoning_details 仅支持 object 或 array（允许不透明内容），若为 scalar（如 string 或 int）必须抛出
    // INVALID_REQUEST。
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    // 1. string 标量
    ObjectNode scalarStringPayload = MAPPER.createObjectNode();
    scalarStringPayload.put("role", "assistant");
    scalarStringPayload.put("content", "text");
    scalarStringPayload.put("reasoning_details", "scalar-string");
    ProviderReplayState state1 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), scalarStringPayload);
    ProviderMessage msg1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("text")), state1);
    ProviderRequest req1 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, msg1),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req1, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. int 标量
    ObjectNode scalarIntPayload = MAPPER.createObjectNode();
    scalarIntPayload.put("role", "assistant");
    scalarIntPayload.put("content", "text");
    scalarIntPayload.put("reasoning_details", 12345);
    ProviderReplayState state2 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), scalarIntPayload);
    ProviderMessage msg2 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("text")), state2);
    ProviderRequest req2 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(user1, msg2),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req2, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
  }

  @Test
  @DisplayName("Accumulator 生成包含 array reasoning_details 的 replay 并在次轮被 Encoder 成功回放")
  void testAccumulatorToReplayStateToNextTurnEncoderWithArrayReasoningDetails() throws Exception {
    // 测试意图：端到端验证 Accumulator 聚合流式多段 reasoning_details 数组，生成的 ProviderReplayState
    // 携带 ArrayNode reasoning_details，在次轮请求中作为 ASSISTANT 历史消息被 OpenAiChatRequestEncoder 成功编码，
    // 并且 wire JSON 保留完整的 reasoning_details 数组，验证数组类型 round-trip。
    ProviderMessage turn1User =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")));
    ProviderRequest turn1Req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(turn1User),
            List.of(),
            ProviderCacheControl.none());

    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {}
            });

    OpenAiChatStreamAccumulator accumulator =
        new OpenAiChatStreamAccumulator(
            turn1Req, descriptor, OpenAiChatConfiguration.defaults(), bridge);

    accumulator.handleData(
        """
        {
          "id": "c_arr",
          "choices": [{
            "index": 0,
            "delta": {
              "role": "assistant",
              "reasoning_content": "Solving...",
              "reasoning_details": [{"step": 1, "status": "thinking"}]
            }
          }]
        }
        """);
    accumulator.handleData(
        """
        {
          "id": "c_arr",
          "choices": [{
            "index": 0,
            "delta": {
              "content": "Result 42",
              "reasoning_details": [{"step": 2, "status": "completed"}]
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": 10,
            "completion_tokens": 5,
            "total_tokens": 15
          }
        }
        """);
    accumulator.handleData("[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion.replayState());
    assertTrue(completion.replayState().payload().path("reasoning_details").isArray());
    assertEquals(2, completion.replayState().payload().path("reasoning_details").size());

    // 次轮请求：包含 turn1User、turn1Asst (使用 completion 生成的文本、思考和 replayState) 以及 turn2User
    ProviderMessage turn1Asst =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock(completion.response().thinking()),
                new ProviderTextBlock(completion.response().text())),
            completion.replayState());
    ProviderMessage turn2User =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("Explain more")));

    ProviderRequest turn2Req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(turn1User, turn1Asst, turn2User),
            List.of(),
            ProviderCacheControl.none());

    OpenAiChatEncodedRequest encodedTurn2 =
        encoder.encode(turn2Req, descriptor, OpenAiChatConfiguration.defaults());
    JsonNode wireRoot = MAPPER.readTree(encodedTurn2.bodyUtf8Bytes());
    ArrayNode wireMessages = (ArrayNode) wireRoot.path("messages");
    assertEquals(4, wireMessages.size());

    JsonNode wireAsst = wireMessages.get(2);
    assertEquals("assistant", wireAsst.path("role").asText());
    assertEquals("Result 42", wireAsst.path("content").asText());
    assertEquals("Solving...", wireAsst.path("reasoning_content").asText());
    assertTrue(wireAsst.path("reasoning_details").isArray());
    assertEquals(2, wireAsst.path("reasoning_details").size());
    assertEquals(1, wireAsst.path("reasoning_details").get(0).path("step").asInt());
    assertEquals("completed", wireAsst.path("reasoning_details").get(1).path("status").asText());
  }

  @Test
  @DisplayName(
      "工具参数为非 Object（畸形、数组、标量、JSON null）在 replay 与 fallback 均严格抛出 INVALID_REQUEST 且无原始参数泄露")
  void testToolArgumentsStrictObjectValidationInReplayAndFallback() {
    ProviderMessage user1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    List<String> invalidArgs =
        List.of("12345", "\"scalar_string\"", "true", "[1, 2, 3]", "{\"unclosed\":", "null");

    for (String badArg : invalidArgs) {
      // 1. Semantic fallback: durable 中的 toolCall.argumentsJson 为非 Object
      ProviderMessage fallbackMsg =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))),
              null);
      ProviderRequest fallbackReq =
          new ProviderRequest(
              modelDesc,
              defaultVariant,
              1024,
              "Test system instruction.",
              List.of(user1, fallbackMsg),
              List.of(),
              ProviderCacheControl.none());
      ProviderException exFallback =
          assertThrows(
              ProviderException.class,
              () -> encoder.encode(fallbackReq, descriptor, OpenAiChatConfiguration.defaults()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exFallback.kind());
      assertNull(exFallback.getCause());
      assertFalse(exFallback.getMessage().contains(badArg));

      // 2. Replay payload: tool_calls function.arguments 为非 Object（即使 affinity 失配也必须严格拦截）
      ObjectNode badReplayPayload = MAPPER.createObjectNode();
      badReplayPayload.put("role", "assistant");
      ArrayNode tcArr = badReplayPayload.putArray("tool_calls");
      ObjectNode tcObj = tcArr.addObject();
      tcObj.put("id", "c1").put("type", "function");
      tcObj.putObject("function").put("name", "calc").put("arguments", badArg);

      ProviderReplayState badReplayState =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), badReplayPayload);
      ProviderMessage replayMsg =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))),
              badReplayState);
      ProviderRequest replayReq =
          new ProviderRequest(
              modelDesc,
              defaultVariant,
              1024,
              "Test system instruction.",
              List.of(user1, replayMsg),
              List.of(),
              ProviderCacheControl.none());
      ProviderException exReplay =
          assertThrows(
              ProviderException.class,
              () -> encoder.encode(replayReq, descriptor, OpenAiChatConfiguration.defaults()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exReplay.kind());
      assertNull(exReplay.getCause());
      assertFalse(exReplay.getMessage().contains(badArg));

      // 3. Replay durable: durable 中的 toolCall.argumentsJson 为非 Object
      ObjectNode validPayload = MAPPER.createObjectNode();
      validPayload.put("role", "assistant");
      ArrayNode validTcArr = validPayload.putArray("tool_calls");
      ObjectNode validTc = validTcArr.addObject();
      validTc.put("id", "c1").put("type", "function");
      validTc.putObject("function").put("name", "calc").put("arguments", "{}");

      ProviderReplayState replayStateWithBadDurable =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), validPayload);
      ProviderMessage msgWithBadDurable =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))),
              replayStateWithBadDurable);
      ProviderRequest reqWithBadDurable =
          new ProviderRequest(
              modelDesc,
              defaultVariant,
              1024,
              "Test system instruction.",
              List.of(user1, msgWithBadDurable),
              List.of(),
              ProviderCacheControl.none());
      ProviderException exDurable =
          assertThrows(
              ProviderException.class,
              () ->
                  encoder.encode(
                      reqWithBadDurable, descriptor, OpenAiChatConfiguration.defaults()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exDurable.kind());
      assertNull(exDurable.getCause());
      assertFalse(exDurable.getMessage().contains(badArg));
    }
  }

  @Test
  @DisplayName("最终 UTF-8 请求体应用上限：使用可注入小阈值覆盖调用点边界，不进行昂贵的大内存分配")
  void testFinalBodySizeGuardEnforcedAtCallSite() {
    // 测试意图：证明 OpenAiChatRequestEncoder.encode 在序列化完成后确实调用应用上限守卫。
    // 通过在正常请求上先用默认阈值成功编码得到真实字节长度，再用该长度作为精确阈值验证边界通过/超限拒绝。
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("size guard")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());

    int actualBytes =
        new OpenAiChatRequestEncoder()
            .encode(request, descriptor, OpenAiChatConfiguration.defaults())
            .bodyUtf8Bytes()
            .length;

    // 恰好等于真实长度：通过
    OpenAiChatEncodedRequest atLimit =
        new OpenAiChatRequestEncoder(new RequestBodySizeGuard(actualBytes))
            .encode(request, descriptor, OpenAiChatConfiguration.defaults());
    assertEquals(actualBytes, atLimit.bodyUtf8Bytes().length);

    // 比真实长度少 1 字节：调用点必须拒绝，且错误消息不泄露请求体内容
    RequestBodySizeGuard tiny = new RequestBodySizeGuard(actualBytes - 1);
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                new OpenAiChatRequestEncoder(tiny)
                    .encode(request, descriptor, OpenAiChatConfiguration.defaults()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("request body exceeds"));
    assertFalse(ex.getMessage().contains("size guard"));
  }

  private ProviderRequest cacheRequest(
      String system, List<ProviderMessage> history, ProviderCacheControl control) {
    return new ProviderRequest(
        modelDesc, defaultVariant, 1024, system, history, List.of(), control);
  }

  @Test
  @DisplayName("合法 replay 的 tool_calls 必须原位保留 type/function.name/function.arguments，而非仅保留 id")
  void testValidReplayPreservesFullToolCallShape() throws Exception {
    // 意图：affinity 匹配时原样回放 opaque payload；原生 reasoning_content 区分回放与语义 fallback，并校验工具参数。
    ProviderMessage turn1User =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("call it")));
    ProviderRequest turn1Req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(turn1User),
            List.of(),
            ProviderCacheControl.none());
    ObjectNode replayPayload = MAPPER.createObjectNode();
    replayPayload.put("role", "assistant");
    replayPayload.putNull("content");
    replayPayload.put("reasoning_content", "native reasoning");
    ArrayNode replayCalls = replayPayload.putArray("tool_calls");
    ObjectNode replayCall = replayCalls.addObject();
    replayCall.put("id", "call_replay_1");
    replayCall.put("type", "function");
    replayCall
        .putObject("function")
        .put("name", "weather_query")
        .put("arguments", "{\"city\":\"Berlin\"}");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), replayPayload);

    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("native reasoning"),
                new ProviderToolCallBlock(
                    new ProviderToolCall(
                        "call_replay_1", "weather_query", "{\"city\":\"Berlin\"}"))),
            replayState);
    ProviderRequest turn2Req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "Test system instruction.",
            List.of(turn1User, assistantMsg),
            List.of(),
            ProviderCacheControl.none());

    JsonNode wire =
        MAPPER.readTree(
            encoder
                .encode(turn2Req, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode wireCalls = wire.path("messages").get(2).path("tool_calls");

    assertEquals(
        "native reasoning", wire.path("messages").get(2).path("reasoning_content").asText());
    assertEquals(1, wireCalls.size());
    assertEquals("call_replay_1", wireCalls.get(0).path("id").asText());
    assertEquals("function", wireCalls.get(0).path("type").asText());
    assertEquals("weather_query", wireCalls.get(0).path("function").path("name").asText());
    assertEquals(
        "{\"city\":\"Berlin\"}", wireCalls.get(0).path("function").path("arguments").asText());
  }
}

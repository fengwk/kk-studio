package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 测试意图：全面验证 OpenAI Chat 请求编码器（Golden 结构、Sampling、Tools、Messages、Replay、媒体、三种缓存模式）。 */
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
    defaultVariant = new ModelVariant("default", null, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("标准请求 Golden 结构：model, stream=true, stream_options.include_usage=true")
  void testGoldenRequestStructure() throws Exception {
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(userMsg), List.of(), ProviderCacheControl.none());

    OpenAiChatEncodedRequest encoded =
        encoder.encode(request, descriptor, OpenAiChatConfiguration.defaults());
    assertNotNull(encoded.sourcePrefixHash());

    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals("gpt-4o", root.path("model").asText());
    assertTrue(root.path("stream").asBoolean());
    assertTrue(root.path("stream_options").path("include_usage").asBoolean());

    ArrayNode messages = (ArrayNode) root.path("messages");
    assertEquals(1, messages.size());
    assertEquals("user", messages.get(0).path("role").asText());
    assertEquals("Hello", messages.get(0).path("content").asText());
  }

  @Test
  @DisplayName("通过配置覆盖 openAiChatIncludeUsage 为 false")
  void testDisableIncludeUsage() throws Exception {
    OpenAiChatConfiguration config =
        new OpenAiChatConfiguration(
            false, true, Set.of(), OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(userMsg), List.of(), ProviderCacheControl.none());

    OpenAiChatEncodedRequest encoded = encoder.encode(request, descriptor, config);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertFalse(root.path("stream_options").path("include_usage").asBoolean());
  }

  @Test
  @DisplayName("Reasoning effort 仅在非空时映射，空时不发")
  void testReasoningEffortMapping() throws Exception {
    ModelVariant variantWithReasoning =
        new ModelVariant("v1", null, null, null, null, null, null, null, "high");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Solve this")));
    ProviderRequest req1 =
        new ProviderRequest(
            modelDesc,
            variantWithReasoning,
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root1 =
        MAPPER.readTree(
            encoder.encode(req1, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    assertEquals("high", root1.path("reasoning_effort").asText());

    ModelVariant variantOff =
        new ModelVariant("v2", null, null, null, null, null, null, null, "off");
    ProviderRequest req2 =
        new ProviderRequest(
            modelDesc, variantOff, List.of(userMsg), List.of(), ProviderCacheControl.none());
    JsonNode root2 =
        MAPPER.readTree(
            encoder.encode(req2, descriptor, OpenAiChatConfiguration.defaults()).bodyUtf8Bytes());
    assertFalse(root2.has("reasoning_effort"));
  }

  @Test
  @DisplayName("TopK 不受支持时严格拒绝抛出 INVALID_REQUEST")
  void testRejectTopK() {
    ModelVariant variant = new ModelVariant("v", null, null, null, 10, null, null, null, null);
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg), List.of(), ProviderCacheControl.none());

    assertThrows(
        ProviderException.class,
        () -> encoder.encode(request, descriptor, OpenAiChatConfiguration.defaults()));
  }

  @Test
  @DisplayName("Sampling 参数正确映射到 JSON")
  void testSamplingParameters() throws Exception {
    ModelVariant variant =
        new ModelVariant("v", 100, 0.7, 0.9, null, 0.5, 0.5, List.of("STOP_1", "STOP_2"), null);
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderRequest request =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg), List.of(), ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(request, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals(100, root.path("max_tokens").asInt());
    assertEquals(0.7, root.path("temperature").asDouble());
    assertEquals(0.9, root.path("top_p").asDouble());
    assertEquals(0.5, root.path("frequency_penalty").asDouble());
    assertEquals(0.5, root.path("presence_penalty").asDouble());
    assertEquals(2, root.path("stop").size());
    assertEquals("STOP_1", root.path("stop").get(0).asText());
    assertEquals("STOP_2", root.path("stop").get(1).asText());
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
            modelDesc, defaultVariant, List.of(toolMsg), List.of(), ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(request, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    ArrayNode messages = (ArrayNode) root.path("messages");
    assertEquals(1, messages.size());
    assertEquals("tool", messages.get(0).path("role").asText());
    assertEquals("call_123", messages.get(0).path("tool_call_id").asText());
    assertEquals("{\"temp\":25}", messages.get(0).path("content").asText());
  }

  @Test
  @DisplayName("媒体能力控制：未配置媒体时拒绝，配置后编码")
  void testMediaEncodingAndPermissions() throws Exception {
    ProviderMessage userImg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderImageBlock("image/jpeg", "https://example.com/a.jpg")));
    ProviderRequest reqImg =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(userImg), List.of(), ProviderCacheControl.none());

    // 1. 未配置 IMAGE，拒绝
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqImg, descriptor, OpenAiChatConfiguration.defaults()));

    // 2. 配置 IMAGE，允许
    OpenAiChatConfiguration configImg =
        new OpenAiChatConfiguration(
            true,
            true,
            Set.of(OpenAiChatConfiguration.MediaType.IMAGE),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    JsonNode rootImg =
        MAPPER.readTree(encoder.encode(reqImg, descriptor, configImg).bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) rootImg.path("messages").get(0).path("content");
    assertEquals("image_url", parts.get(0).path("type").asText());
    assertEquals("https://example.com/a.jpg", parts.get(0).path("image_url").path("url").asText());

    // 3. 音频处理：URL 必须拒绝
    ProviderMessage userAudioUrl =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/wav", "https://example.com/a.wav")));
    ProviderRequest reqAudioUrl =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(userAudioUrl),
            List.of(),
            ProviderCacheControl.none());
    OpenAiChatConfiguration configAudio =
        new OpenAiChatConfiguration(
            true,
            true,
            Set.of(OpenAiChatConfiguration.MediaType.AUDIO),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    assertThrows(
        ProviderException.class, () -> encoder.encode(reqAudioUrl, descriptor, configAudio));

    // 4. 音频处理：base64 允许编码为 input_audio
    ProviderMessage userAudioBase64 =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/wav", "data:audio/wav;base64,UklGRg==")));
    ProviderRequest reqAudioBase64 =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(userAudioBase64),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootAudio =
        MAPPER.readTree(encoder.encode(reqAudioBase64, descriptor, configAudio).bodyUtf8Bytes());
    ArrayNode audioParts = (ArrayNode) rootAudio.path("messages").get(0).path("content");
    assertEquals("input_audio", audioParts.get(0).path("type").asText());
    assertEquals("UklGRg==", audioParts.get(0).path("input_audio").path("data").asText());
    assertEquals("wav", audioParts.get(0).path("input_audio").path("format").asText());

    // 5. PDF 编码与未配置拒绝
    ProviderMessage userPdf =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(
                new ProviderDocumentBlock(
                    "application/pdf", "data:application/pdf;base64,JVBERi==")));
    ProviderRequest reqPdf =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(userPdf), List.of(), ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqPdf, descriptor, OpenAiChatConfiguration.defaults()));

    OpenAiChatConfiguration configPdf =
        new OpenAiChatConfiguration(
            true,
            true,
            Set.of(OpenAiChatConfiguration.MediaType.PDF),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    JsonNode rootPdf =
        MAPPER.readTree(encoder.encode(reqPdf, descriptor, configPdf).bodyUtf8Bytes());
    ArrayNode pdfParts = (ArrayNode) rootPdf.path("messages").get(0).path("content");
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
            modelDesc, defaultVariant, List.of(userVideo), List.of(), ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqVideo, descriptor, OpenAiChatConfiguration.defaults()));
  }

  @Test
  @DisplayName("ASSISTANT ReplayState 合法原位回放白名单对象；同 format 但 payload 非法应拒绝；不可回放时 fallback")
  void testAssistantReplayAndFallback() throws Exception {
    ProviderMessage userMsg1 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Tell joke")));

    // 先编码一次以获取一致的前缀 hash
    ProviderRequest turn1Req =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(userMsg1), List.of(), ProviderCacheControl.none());
    OpenAiChatEncodedRequest turn1Encoded =
        encoder.encode(turn1Req, descriptor, OpenAiChatConfiguration.defaults());

    // 1. 合法 replay：仅包含白名单字段且匹配 durable
    ObjectNode validPayload = MAPPER.createObjectNode();
    validPayload.put("role", "assistant");
    validPayload.put("content", "Why did chicken cross road?");
    validPayload.put("reasoning_content", "A classic joke is appropriate.");

    ProviderReplayState validReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o"),
            turn1Encoded.sourcePrefixHash(),
            validPayload);

    ProviderMessage validAsstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("Why did chicken cross road?")),
            validReplayState);
    ProviderMessage userMsg2 =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Why?")));

    ProviderRequest turn2Req =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(userMsg1, validAsstMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(turn2Req, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    ArrayNode messages = (ArrayNode) root.path("messages");
    assertEquals(3, messages.size());

    JsonNode replayedAsst = messages.get(1);
    assertEquals("assistant", replayedAsst.path("role").asText());
    assertEquals("Why did chicken cross road?", replayedAsst.path("content").asText());
    assertEquals("A classic joke is appropriate.", replayedAsst.path("reasoning_content").asText());

    // 2. 同 format 但 payload 非法（包含未知字段），必须抛出 ProviderException 拒绝
    ObjectNode illegalPayload = MAPPER.createObjectNode();
    illegalPayload.put("role", "assistant");
    illegalPayload.put("content", "Why did chicken cross road?");
    illegalPayload.put("unknown_forbidden_field", "evil");
    ProviderReplayState illegalReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o"),
            turn1Encoded.sourcePrefixHash(),
            illegalPayload);
    ProviderMessage illegalAsstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("Why did chicken cross road?")),
            illegalReplayState);
    ProviderRequest illegalReq =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(userMsg1, illegalAsstMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(illegalReq, descriptor, OpenAiChatConfiguration.defaults()));

    // 3. runtime 判定不可回放（前缀 hash 不匹配），语义 fallback
    ProviderReplayState mismatchedHashReplayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o"),
            "0".repeat(64),
            validPayload);
    ProviderMessage fallbackAsstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("Fallback answer")),
            mismatchedHashReplayState);
    ProviderRequest fallbackReq =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(userMsg1, fallbackAsstMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());
    JsonNode fallbackRoot =
        MAPPER.readTree(
            encoder
                .encode(fallbackReq, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode fallbackAsstNode = fallbackRoot.path("messages").get(1);
    assertEquals("assistant", fallbackAsstNode.path("role").asText());
    assertEquals("Fallback answer", fallbackAsstNode.path("content").asText());
    assertFalse(fallbackAsstNode.has("reasoning_content"));
  }

  @Test
  @DisplayName("三种 Prompt Cache 模式验证：AUTOMATIC, LEGACY, GPT_5_6_EXPLICIT")
  void testPromptCacheModes() throws Exception {
    ProviderMessage sysMsg =
        new ProviderMessage(
            ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("System prompt")));
    ProviderMessage userMsg =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("User prompt")));

    // 1. AUTOMATIC: 不发 hint
    OpenAiChatConfiguration configAuto =
        new OpenAiChatConfiguration(
            true, true, Set.of(), OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    ProviderRequest reqAuto =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(sysMsg, userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootAuto =
        MAPPER.readTree(encoder.encode(reqAuto, descriptor, configAuto).bodyUtf8Bytes());
    assertFalse(rootAuto.has("prompt_cache_key"));
    assertFalse(rootAuto.has("prompt_cache_options"));

    // 2. LEGACY: 非 NONE 发 key + retention
    OpenAiChatConfiguration configLegacy =
        new OpenAiChatConfiguration(
            true, true, Set.of(), OpenAiChatConfiguration.PromptCacheMode.LEGACY);
    ProviderCacheControl cacheControlLegacy =
        ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "my-key-legacy");
    ProviderRequest reqLegacy =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(sysMsg, userMsg), List.of(), cacheControlLegacy);
    JsonNode rootLegacy =
        MAPPER.readTree(encoder.encode(reqLegacy, descriptor, configLegacy).bodyUtf8Bytes());
    assertEquals("my-key-legacy", rootLegacy.path("prompt_cache_key").asText());
    assertEquals("in_memory", rootLegacy.path("prompt_cache_retention").asText());

    // 3. GPT_5_6_EXPLICIT: 始终发 options；非 NONE 打 SYSTEM/CONVERSATION breakpoints
    OpenAiChatConfiguration configGpt =
        new OpenAiChatConfiguration(
            true, true, Set.of(), OpenAiChatConfiguration.PromptCacheMode.GPT_5_6_EXPLICIT);
    ProviderCacheControl cacheControlGpt =
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT,
            "my-key-gpt",
            EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.CONVERSATION));
    ProviderRequest reqGpt =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(sysMsg, userMsg), List.of(), cacheControlGpt);
    JsonNode rootGpt =
        MAPPER.readTree(encoder.encode(reqGpt, descriptor, configGpt).bodyUtf8Bytes());
    assertEquals("my-key-gpt", rootGpt.path("prompt_cache_key").asText());
    assertEquals("explicit", rootGpt.path("prompt_cache_options").path("mode").asText());
    assertEquals("30m", rootGpt.path("prompt_cache_options").path("ttl").asText());

    ArrayNode gptMessages = (ArrayNode) rootGpt.path("messages");
    // SYSTEM breakpoint
    ArrayNode sysContentParts = (ArrayNode) gptMessages.get(0).path("content");
    assertTrue(sysContentParts.get(0).path("prompt_cache_breakpoint").asBoolean());

    // CONVERSATION breakpoint
    ArrayNode userContentParts = (ArrayNode) gptMessages.get(1).path("content");
    assertTrue(userContentParts.get(0).path("prompt_cache_breakpoint").asBoolean());

    // 4. GPT_5_6_EXPLICIT with retention NONE: 仍发 explicit options，但不发 key 和 breakpoint 以禁用缓存
    ProviderRequest reqGptNone =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(sysMsg, userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootGptNone =
        MAPPER.readTree(encoder.encode(reqGptNone, descriptor, configGpt).bodyUtf8Bytes());
    assertFalse(rootGptNone.has("prompt_cache_key"));
    assertEquals("explicit", rootGptNone.path("prompt_cache_options").path("mode").asText());
    assertEquals("system", rootGptNone.path("messages").get(0).path("role").asText());
    assertEquals("System prompt", rootGptNone.path("messages").get(0).path("content").asText());

    // 5. LEGACY with LONG retention -> 24h
    ProviderCacheControl cacheControlLong =
        ProviderCacheControl.affinity(PromptCacheRetention.LONG, "my-key-long");
    ProviderRequest reqLegacyLong =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(sysMsg, userMsg), List.of(), cacheControlLong);
    JsonNode rootLegacyLong =
        MAPPER.readTree(encoder.encode(reqLegacyLong, descriptor, configLegacy).bodyUtf8Bytes());
    assertEquals("24h", rootLegacyLong.path("prompt_cache_retention").asText());
  }

  @Test
  @DisplayName("各种边缘消息角色与非法块校验")
  void testEdgeCasesAndIllegalBlocks() throws Exception {
    // 1. ASSISTANT 包含 ThinkingBlock（fallback 时静默丢弃）与 ToolCall
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
            List.of(asstWithThinking),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootAsstThinking =
        MAPPER.readTree(
            encoder
                .encode(reqAsstThinking, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    JsonNode asstNode = rootAsstThinking.path("messages").get(0);
    assertEquals("answer text", asstNode.path("content").asText());
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
            List.of(asstWithImage),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqAsstImage, descriptor, OpenAiChatConfiguration.defaults()));

    // 3. SYSTEM 包含非 TextBlock 抛异常
    ProviderMessage sysWithImage =
        new ProviderMessage(
            ProviderMessageRole.SYSTEM,
            List.of(new ProviderImageBlock("image/png", "data:image/png;base64,123")));
    ProviderRequest reqSysImage =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(sysWithImage),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqSysImage, descriptor, OpenAiChatConfiguration.defaults()));

    // 4. TOOL 消息包含 ProviderJsonBlock
    ProviderToolResultBlock resultWithJson =
        new ProviderToolResultBlock(
            "call_json", "calc", List.of(new ProviderJsonBlock("{\"ans\":42}")), false, "{}");
    ProviderMessage toolJsonMsg =
        new ProviderMessage(ProviderMessageRole.TOOL, List.of(resultWithJson));
    ProviderRequest reqToolJson =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(toolJsonMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootToolJson =
        MAPPER.readTree(
            encoder
                .encode(reqToolJson, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals("{\"ans\":42}", rootToolJson.path("messages").get(0).path("content").asText());

    // 6. 不支持的音频格式抛异常
    OpenAiChatConfiguration audioConfig =
        new OpenAiChatConfiguration(
            true,
            true,
            Set.of(OpenAiChatConfiguration.MediaType.AUDIO),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    ProviderMessage audioFlac =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/flac", "data:audio/flac;base64,123")));
    ProviderRequest reqFlac =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(audioFlac), List.of(), ProviderCacheControl.none());
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
            modelDesc, defaultVariant, List.of(user1), List.of(), ProviderCacheControl.none());
    String hash =
        encoder.encode(req1, descriptor, OpenAiChatConfiguration.defaults()).sourcePrefixHash();

    // 1. payload role 不是 assistant
    ObjectNode roleUserPayload = MAPPER.createObjectNode();
    roleUserPayload.put("role", "user");
    roleUserPayload.put("content", "Hi");
    ProviderReplayState badRoleState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), hash, roleUserPayload);
    ProviderMessage badRoleMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("Hi")), badRoleState);
    ProviderRequest reqBadRole =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
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
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o"),
            hash,
            mismatchTextPayload);
    ProviderMessage mismatchTextMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("actual text")),
            mismatchTextState);
    ProviderRequest reqMismatchText =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
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
    // missing function node
    ProviderReplayState badToolState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT, descriptor.affinity("gpt-4o"), hash, badToolPayload);
    ProviderMessage badToolMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
            badToolState);
    ProviderRequest reqBadTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
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
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o"),
            hash,
            validToolPayload);
    ProviderMessage validToolMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{\"x\":1}"))),
            validToolState);
    ProviderRequest reqValidTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(user1, validToolMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode rootValidTool =
        MAPPER.readTree(
            encoder
                .encode(reqValidTool, descriptor, OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals(2, rootValidTool.path("messages").size());
    assertEquals(
        "c1", rootValidTool.path("messages").get(1).path("tool_calls").get(0).path("id").asText());

    // 5. payload role 不是 text
    ObjectNode badRoleTypePayload = MAPPER.createObjectNode();
    badRoleTypePayload.put("role", 123);
    ProviderReplayState badRoleTypeState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o"),
            hash,
            badRoleTypePayload);
    ProviderMessage badRoleTypeMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("Hi")), badRoleTypeState);
    ProviderRequest reqBadRoleType =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
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
            ProviderReplayFormat.OPENAI_CHAT,
            descriptor.affinity("gpt-4o"),
            hash,
            nonObjToolPayload);
    ProviderMessage nonObjToolMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))),
            nonObjToolState);
    ProviderRequest reqNonObjTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            List.of(user1, nonObjToolMsg),
            List.of(),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqNonObjTool, descriptor, OpenAiChatConfiguration.defaults()));
  }

  @Test
  @DisplayName("Tool 损坏 schema、MP3 音频、PDF 格式及末尾带 tool_calls 的断点覆盖")
  void testSchemaAudioPdfAndBreakpointBranches() throws Exception {
    // 1. Tool inputSchemaJson 不是合法 JSON
    ProviderToolDefinition badJsonTool =
        new ProviderToolDefinition("bad_tool", "desc", "not-json-at-all");
    ProviderRequest reqBadJsonTool =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
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
        new OpenAiChatConfiguration(
            true,
            true,
            Set.of(OpenAiChatConfiguration.MediaType.AUDIO),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    ProviderMessage mp3Msg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderAudioBlock("audio/mp3", "data:audio/mp3;base64,AAA=")));
    ProviderRequest reqMp3 =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(mp3Msg), List.of(), ProviderCacheControl.none());
    JsonNode rootMp3 =
        MAPPER.readTree(encoder.encode(reqMp3, descriptor, audioConfig).bodyUtf8Bytes());
    assertEquals(
        "mp3",
        rootMp3
            .path("messages")
            .get(0)
            .path("content")
            .get(0)
            .path("input_audio")
            .path("format")
            .asText());

    // 4. PDF 非法 mediaType
    OpenAiChatConfiguration pdfConfig =
        new OpenAiChatConfiguration(
            true,
            true,
            Set.of(OpenAiChatConfiguration.MediaType.PDF),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    ProviderMessage badDocMsg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(
                new ProviderDocumentBlock(
                    "application/msword", "data:application/msword;base64,AAA=")));
    ProviderRequest reqBadDoc =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(badDocMsg), List.of(), ProviderCacheControl.none());
    assertThrows(ProviderException.class, () -> encoder.encode(reqBadDoc, descriptor, pdfConfig));

    // 5. PDF 纯 base64 自动补充 data:application/pdf;base64, 前缀
    ProviderMessage rawPdfMsg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderDocumentBlock("application/pdf", "JVBERi0xLjQK")));
    ProviderRequest reqRawPdf =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(rawPdfMsg), List.of(), ProviderCacheControl.none());
    JsonNode rootRawPdf =
        MAPPER.readTree(encoder.encode(reqRawPdf, descriptor, pdfConfig).bodyUtf8Bytes());
    assertEquals(
        "data:application/pdf;base64,JVBERi0xLjQK",
        rootRawPdf
            .path("messages")
            .get(0)
            .path("content")
            .get(0)
            .path("file")
            .path("file_data")
            .asText());

    // 6. 最后一条消息是纯 tool_calls 的 assistant 消息并在上面打 conversation breakpoint
    OpenAiChatConfiguration configGpt =
        new OpenAiChatConfiguration(
            true, true, Set.of(), OpenAiChatConfiguration.PromptCacheMode.GPT_5_6_EXPLICIT);
    ProviderCacheControl cacheControlGpt =
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT,
            "key-asst-break",
            EnumSet.of(PromptCacheBreakpoint.CONVERSATION));
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("calc")));
    ProviderMessage asstOnlyTools =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c_last", "calc", "{}"))));
    ProviderRequest reqLastToolBreak =
        new ProviderRequest(
            modelDesc, defaultVariant, List.of(userMsg, asstOnlyTools), List.of(), cacheControlGpt);
    JsonNode rootBreak =
        MAPPER.readTree(encoder.encode(reqLastToolBreak, descriptor, configGpt).bodyUtf8Bytes());
    ArrayNode messages = (ArrayNode) rootBreak.path("messages");
    JsonNode userNode = messages.get(0);
    assertTrue(userNode.path("content").get(0).path("prompt_cache_breakpoint").asBoolean());
  }
}

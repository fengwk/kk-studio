package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 测试意图：全面验证 OpenAI Chat 请求编码器（Golden 结构、输出预算、推理、Tools、Messages、Replay、媒体、留存档位映射）。 */
class OpenAiChatRequestEncoderTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
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
    defaultVariant = new ModelVariant("default", null, PromptCacheRetention.NONE, null);
  }

  /** 内部历史 effort 不改变 OpenAI Chat 的任何 wire 字段。 */
  @Test
  void ignoresAssistantHistoryEffortOnWire() {
    List<ProviderMessage> plain =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("answer"))));
    List<ProviderMessage> tagged =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("answer")), "max"));
    ProviderRequest first =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "system",
            plain,
            List.of(),
            ProviderCacheControl.none());
    ProviderRequest second =
        new ProviderRequest(
            modelDesc,
            defaultVariant,
            1024,
            "system",
            tagged,
            List.of(),
            ProviderCacheControl.none());
    assertEquals(
        MAPPER.readTree(encoder.encode(first, descriptor).bodyUtf8Bytes()),
        MAPPER.readTree(encoder.encode(second, descriptor).bodyUtf8Bytes()));
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

    OpenAiChatEncodedRequest encoded = encoder.encode(request, descriptor);
    assertNotNull(encoded.bodyUtf8Bytes());

    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals("gpt-4o", root.path("model").asString(""));
    assertTrue(root.path("stream").asBoolean(false));
    assertTrue(root.path("stream_options").path("include_usage").asBoolean(false));

    ArrayNode messages = (ArrayNode) root.path("messages");
    // 唯一的系统指令合成为前导 system message，会话消息紧随其后
    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).path("role").asString(""));
    assertEquals("Test system instruction.", messages.get(0).path("content").asString(""));
    assertEquals("user", messages.get(1).path("role").asString(""));
    assertEquals("Hello", messages.get(1).path("content").asString(""));
  }

  @Test
  @DisplayName("始终请求 usage")
  void alwaysRequestsUsage() throws Exception {
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

    OpenAiChatEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertTrue(root.path("stream_options").path("include_usage").asBoolean(false));
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
    ModelVariant variantWithReasoning =
        new ModelVariant("v1", "high", PromptCacheRetention.NONE, null);
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

    JsonNode root1 = MAPPER.readTree(encoder.encode(req1, descriptor).bodyUtf8Bytes());
    assertEquals("high", root1.path("reasoning_effort").asString(""));

    // 手工配置的大小写是厂商原生值，编码时原样下发（如 HIGH 不被小写化）
    for (String effort : List.of("HIGH", "HiGh")) {
      ProviderRequest casedReq =
          new ProviderRequest(
              reasoningModel,
              new ModelVariant("v-cased", effort, PromptCacheRetention.NONE, null),
              1024,
              "Test system instruction.",
              List.of(userMsg),
              List.of(),
              ProviderCacheControl.none());
      JsonNode casedRoot = MAPPER.readTree(encoder.encode(casedReq, descriptor).bodyUtf8Bytes());
      assertEquals(effort, casedRoot.path("reasoning_effort").asString(""));
    }

    ModelVariant variantOff = new ModelVariant("v2", "off", PromptCacheRetention.NONE, null);
    ProviderRequest req2 =
        new ProviderRequest(
            reasoningModel,
            variantOff,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root2 = MAPPER.readTree(encoder.encode(req2, descriptor).bodyUtf8Bytes());
    assertEquals("none", root2.path("reasoning_effort").asString(""));

    ProviderRequest req3 =
        new ProviderRequest(
            modelDesc,
            variantWithReasoning,
            1024,
            "Test system instruction.",
            List.of(userMsg),
            List.of(),
            ProviderCacheControl.none());
    JsonNode root3 = MAPPER.readTree(encoder.encode(req3, descriptor).bodyUtf8Bytes());
    assertFalse(root3.has("reasoning_effort"));
  }

  @Test
  @DisplayName("输出预算 max_tokens 正确映射到 JSON")
  void testOutputBudgetParameter() throws Exception {
    ModelVariant variant = new ModelVariant("v", null, PromptCacheRetention.NONE, null);
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

    JsonNode root = MAPPER.readTree(encoder.encode(request, descriptor).bodyUtf8Bytes());
    assertEquals(100, root.path("max_tokens").asInt(0));
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

    JsonNode root = MAPPER.readTree(encoder.encode(request, descriptor).bodyUtf8Bytes());
    ArrayNode tools = (ArrayNode) root.path("tools");
    assertEquals(1, tools.size());
    assertEquals("function", tools.get(0).path("type").asString(""));
    JsonNode fn = tools.get(0).path("function");
    assertEquals("get_weather", fn.path("name").asString(""));
    assertEquals("Get current weather", fn.path("description").asString(""));
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

    JsonNode root = MAPPER.readTree(encoder.encode(request, descriptor).bodyUtf8Bytes());
    ArrayNode messages = (ArrayNode) root.path("messages");
    assertEquals(2, messages.size());
    assertEquals("tool", messages.get(1).path("role").asString(""));
    assertEquals("call_123", messages.get(1).path("tool_call_id").asString(""));
    assertEquals("{\"temp\":25}", messages.get(1).path("content").asString(""));
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

    JsonNode rootImg = MAPPER.readTree(encoder.encode(reqImg, descriptor).bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) rootImg.path("messages").get(1).path("content");
    assertEquals("image_url", parts.get(0).path("type").asString(""));
    assertEquals(
        "https://example.com/a.jpg", parts.get(0).path("image_url").path("url").asString(""));

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
        MAPPER.readTree(encoder.encode(reqImgBase64, descriptor).bodyUtf8Bytes());
    ArrayNode imgBase64Parts = (ArrayNode) rootImgBase64.path("messages").get(1).path("content");
    assertEquals("image_url", imgBase64Parts.get(0).path("type").asString(""));
    assertEquals(base64Png, imgBase64Parts.get(0).path("image_url").path("url").asString(""));
    assertFalse(imgBase64Parts.get(0).path("image_url").path("url").asString("").contains(" "));

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
    assertThrows(ProviderException.class, () -> encoder.encode(reqAudioUrl, descriptor));

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
        MAPPER.readTree(encoder.encode(reqAudioBase64, descriptor).bodyUtf8Bytes());
    ArrayNode audioParts = (ArrayNode) rootAudio.path("messages").get(1).path("content");
    assertEquals("input_audio", audioParts.get(0).path("type").asString(""));
    assertEquals("UklGRg==", audioParts.get(0).path("input_audio").path("data").asString(""));
    assertEquals("wav", audioParts.get(0).path("input_audio").path("format").asString(""));

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
    JsonNode rootPdf = MAPPER.readTree(encoder.encode(reqPdf, descriptor).bodyUtf8Bytes());
    ArrayNode pdfParts = (ArrayNode) rootPdf.path("messages").get(1).path("content");
    assertEquals("file", pdfParts.get(0).path("type").asString(""));
    assertEquals(
        "data:application/pdf;base64,JVBERi==",
        pdfParts.get(0).path("file").path("file_data").asString(""));

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
    assertThrows(ProviderException.class, () -> encoder.encode(reqVideo, descriptor));
  }

  @Test
  @DisplayName("Prompt Cache 留存档位映射：NONE 不发，SHORT/LONG 发 key + retention")
  void testPromptCacheRetentionMapping() throws Exception {
    ProviderMessage userMsg =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("User prompt")));

    // 1. NONE: 不发任何 cache hint
    ProviderRequest reqNone =
        cacheRequest("Test system instruction.", List.of(userMsg), ProviderCacheControl.none());
    JsonNode rootNone = MAPPER.readTree(encoder.encode(reqNone, descriptor).bodyUtf8Bytes());
    assertFalse(rootNone.has("prompt_cache_key"));
    assertFalse(rootNone.has("prompt_cache_retention"));
    assertFalse(rootNone.has("prompt_cache_options"));

    // 2. SHORT: key 直接使用运行时会话 key，retention=in_memory
    ProviderCacheControl cacheControlShort =
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "my-key-short");
    ProviderRequest reqShort =
        cacheRequest("Test system instruction.", List.of(userMsg), cacheControlShort);
    JsonNode rootShort = MAPPER.readTree(encoder.encode(reqShort, descriptor).bodyUtf8Bytes());
    assertEquals("my-key-short", rootShort.path("prompt_cache_key").asString(""));
    assertEquals("in_memory", rootShort.path("prompt_cache_retention").asString(""));
    assertFalse(rootShort.has("prompt_cache_options"));
    // 不再改写 content 形态或注入 breakpoint
    assertEquals("User prompt", rootShort.path("messages").get(1).path("content").asString(""));

    // 3. LONG: retention=24h
    ProviderCacheControl cacheControlLong =
        ProviderCacheControl.session(PromptCacheRetention.LONG, "my-key-long");
    ProviderRequest reqLong =
        cacheRequest("Test system instruction.", List.of(userMsg), cacheControlLong);
    JsonNode rootLong = MAPPER.readTree(encoder.encode(reqLong, descriptor).bodyUtf8Bytes());
    assertEquals("my-key-long", rootLong.path("prompt_cache_key").asString(""));
    assertEquals("24h", rootLong.path("prompt_cache_retention").asString(""));
  }

  @Test
  @DisplayName("各种边缘消息角色与非法块校验")
  void testEdgeCasesAndIllegalBlocks() throws Exception {
    // 1. ASSISTANT 的可读 ThinkingBlock 退化为普通正文文本，并保留 ToolCall
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
        MAPPER.readTree(encoder.encode(reqAsstThinking, descriptor).bodyUtf8Bytes());
    JsonNode asstNode = rootAsstThinking.path("messages").get(1);
    assertEquals("internal thoughtanswer text", asstNode.path("content").asString(""));
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
    assertThrows(ProviderException.class, () -> encoder.encode(reqAsstImage, descriptor));

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
        MAPPER.readTree(encoder.encode(reqToolJson, descriptor).bodyUtf8Bytes());
    assertEquals("{\"ans\":42}", rootToolJson.path("messages").get(1).path("content").asString(""));

    // 4. 不支持的音频格式抛异常
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
    assertThrows(ProviderException.class, () -> encoder.encode(reqFlac, descriptor));

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
    assertThrows(ProviderException.class, () -> encoder.encode(reqBadAudio, descriptor));
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
    assertThrows(ProviderException.class, () -> encoder.encode(reqBadJsonTool, descriptor));

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
    assertThrows(ProviderException.class, () -> encoder.encode(reqNonObjJsonTool, descriptor));

    // 3. MP3 音频格式识别
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
    JsonNode rootMp3 = MAPPER.readTree(encoder.encode(reqMp3, descriptor).bodyUtf8Bytes());
    assertEquals(
        "mp3",
        rootMp3
            .path("messages")
            .get(1)
            .path("content")
            .get(0)
            .path("input_audio")
            .path("format")
            .asString(""));

    // 4. PDF 非法 mediaType
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
    assertThrows(ProviderException.class, () -> encoder.encode(reqBadDoc, descriptor));

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
    JsonNode rootRawPdf = MAPPER.readTree(encoder.encode(reqRawPdf, descriptor).bodyUtf8Bytes());
    assertEquals(
        "data:application/pdf;base64,JVBERi0xLjQK",
        rootRawPdf
            .path("messages")
            .get(1)
            .path("content")
            .get(0)
            .path("file")
            .path("file_data")
            .asString(""));

    // 6. tool-call-only assistant 不制造 content；启用缓存时也不改写消息内容或注入 breakpoint
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
        MAPPER.readTree(encoder.encode(reqLastToolBreak, descriptor).bodyUtf8Bytes());
    assertEquals("session-key", rootBreak.path("prompt_cache_key").asString(""));
    assertEquals("in_memory", rootBreak.path("prompt_cache_retention").asString(""));
    ArrayNode messages = (ArrayNode) rootBreak.path("messages");
    // 0 号是合成的系统指令消息，user 消息紧随其后
    JsonNode userNode = messages.get(1);
    assertEquals("calc", userNode.path("content").asString(""));
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
    JsonNode rootMulti = MAPPER.readTree(encoder.encode(reqMulti, descriptor).bodyUtf8Bytes());
    assertEquals(
        "Result: {\"score\":99} done",
        rootMulti.path("messages").get(2).path("content").asString(""));

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
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadTool, descriptor));
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
          assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNotNull(ex.getMessage());
      return;
    }

    JsonNode root = MAPPER.readTree(encoder.encode(request, descriptor).bodyUtf8Bytes());
    JsonNode wire = root.path("messages").get(1);
    assertEquals("tool", wire.path("role").asString(""));
    assertEquals("call_1", wire.path("tool_call_id").asString(""));
    assertEquals(expectedContent, wire.path("content").asString(""));
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
  @DisplayName("非 Object tool arguments 在语义编码中拒绝且不泄露原始参数")
  void testToolArgumentsStrictObjectValidationInSemanticFallback() {
    ProviderMessage user =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    List<String> invalidArgs =
        List.of("12345", "\"scalar_string\"", "true", "[1, 2, 3]", "{\"unclosed\":", "null");

    for (String badArg : invalidArgs) {
      ProviderMessage assistant =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))));
      ProviderRequest request =
          new ProviderRequest(
              modelDesc,
              defaultVariant,
              1024,
              "Test system instruction.",
              List.of(user, assistant),
              List.of(),
              ProviderCacheControl.none());
      ProviderException error =
          assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertNull(error.getCause());
      assertFalse(error.getMessage().contains(badArg));
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
        new OpenAiChatRequestEncoder().encode(request, descriptor).bodyUtf8Bytes().length;

    // 恰好等于真实长度：通过
    OpenAiChatEncodedRequest atLimit =
        new OpenAiChatRequestEncoder(new RequestBodySizeGuard(actualBytes))
            .encode(request, descriptor);
    assertEquals(actualBytes, atLimit.bodyUtf8Bytes().length);

    // 比真实长度少 1 字节：调用点必须拒绝，且错误消息不泄露请求体内容
    RequestBodySizeGuard tiny = new RequestBodySizeGuard(actualBytes - 1);
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> new OpenAiChatRequestEncoder(tiny).encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("request body exceeds"));
    assertFalse(ex.getMessage().contains("size guard"));
  }

  private ProviderRequest cacheRequest(
      String system, List<ProviderMessage> history, ProviderCacheControl control) {
    return new ProviderRequest(
        modelDesc, defaultVariant, 1024, system, history, List.of(), control);
  }
}

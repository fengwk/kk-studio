package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.RequestBodySizeGuard;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 OpenAI Responses 请求编码、参数映射、媒体支持、回放与缓存控制规则。 */
class OpenAiResponsesRequestEncoderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default");

  private final OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ModelDescriptor createModel() {
    return new ModelDescriptor(
        "openai_test",
        "gpt-5.4-mini",
        "gpt-5.4-mini",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        pricing());
  }

  /** 非推理模型：保留既有 system message 行为。 */
  private ModelDescriptor nonReasoningModel() {
    return new ModelDescriptor(
        "openai_test",
        "gpt-4.1",
        "gpt-4.1",
        Set.of(ModelInputModality.TEXT),
        true,
        false,
        pricing());
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        BigDecimal.ONE,
        "v1",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ONE);
  }

  private ProviderRequest request(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return request(createModel(), variant, messages, tools, cacheControl);
  }

  private static ProviderRequest request(
      ModelDescriptor model,
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return request(model, variant, 1024, messages, tools, cacheControl);
  }

  private static ProviderRequest request(
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        model,
        variant != null ? variant : DEFAULT_VARIANT,
        outputTokens,
        "Test system instruction.",
        messages != null ? messages : List.of(),
        tools != null ? tools : List.of(),
        cacheControl != null ? cacheControl : ProviderCacheControl.none());
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    return request(null, messages, List.of(), null);
  }

  private ProviderRequest request(
      List<ProviderMessage> messages, List<ProviderToolDefinition> tools) {
    return request(null, messages, tools, null);
  }

  private ProviderRequest request(
      List<ProviderMessage> messages, ProviderCacheControl cacheControl) {
    return request(null, messages, List.of(), cacheControl);
  }

  private ProviderRequest request(ModelVariant variant) {
    return request(variant, List.of(), List.of(), null);
  }

  /** 验证基本请求字段：stream=true, store=false, 绝不发送 previous_response_id。 */
  @Test
  void test_basicFieldsAndNoPreviousResponseId() throws Exception {
    ProviderRequest request =
        request(
            new ModelVariant("v1", "low"),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
            List.of(),
            ProviderCacheControl.none());

    OpenAiResponsesEncodedRequest encoded =
        encoder.encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertEquals("gpt-5.4-mini", root.path("model").asText());
    assertTrue(root.path("stream").asBoolean());
    assertFalse(root.path("store").asBoolean());
    assertFalse(root.has("previous_response_id"));
    assertEquals(1024, root.path("max_output_tokens").asInt());
    assertFalse(root.has("temperature"));
    assertFalse(root.has("top_p"));
    assertFalse(root.has("top_k"));
    assertFalse(root.has("stop_sequences"));
    assertEquals("low", root.path("reasoning").path("effort").asText());
    assertEquals("auto", root.path("reasoning").path("summary").asText());
    assertEquals("reasoning.encrypted_content", root.path("include").get(0).asText());
  }

  @Test
  void test_reasoningEffortOffAndEnabledEncodings() throws Exception {
    // 1. effort = "none" -> emit reasoning:{effort:"none"} only; omit summary and omit include
    ProviderRequest reqOff = request(new ModelVariant("v1", "off"));
    JsonNode rootOff =
        MAPPER.readTree(
            encoder
                .encode(reqOff, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
                .bodyUtf8Bytes());
    assertEquals("none", rootOff.path("reasoning").path("effort").asText());
    assertFalse(rootOff.path("reasoning").has("summary"));
    assertFalse(rootOff.has("include"));

    // 2. 厂商定义值原样下发，并附带 summary 与 encrypted content
    // include:["reasoning.encrypted_content"]
    ProviderRequest reqEnabled = request(new ModelVariant("v1", "xhigh"));
    JsonNode rootEnabled =
        MAPPER.readTree(
            encoder
                .encode(reqEnabled, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
                .bodyUtf8Bytes());
    assertEquals("xhigh", rootEnabled.path("reasoning").path("effort").asText());
    assertEquals("auto", rootEnabled.path("reasoning").path("summary").asText());
    assertEquals(1, rootEnabled.path("include").size());
    assertEquals("reasoning.encrypted_content", rootEnabled.path("include").get(0).asText());

    // 3. effort = null -> omit reasoning and include
    ProviderRequest reqNull = request(new ModelVariant("v1"));
    JsonNode rootNull =
        MAPPER.readTree(
            encoder
                .encode(reqNull, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
                .bodyUtf8Bytes());
    assertFalse(rootNull.has("reasoning"));
    assertFalse(rootNull.has("include"));
  }

  /** wire 根字段 model 始终取 ModelDescriptor.modelId，与逻辑名相互独立。 */
  @Test
  void test_wireModelUsesDescriptorModelId() throws Exception {
    ModelDescriptor logicalModel =
        new ModelDescriptor(
            "openai_test",
            "logical-name",
            "wire-model-id",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing());
    ProviderRequest request = request(logicalModel, DEFAULT_VARIANT, List.of(), List.of(), null);

    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
                .bodyUtf8Bytes());

    assertEquals("wire-model-id", root.path("model").asText());
    assertFalse(root.toString().contains("logical-name"));
  }

  /** 验证工具声明映射：strict 工具携带归一化参数与解析后的 JSON schema。 */
  @Test
  void test_toolDefinitionEncoding() throws Exception {
    ProviderToolDefinition tool =
        new ProviderToolDefinition(
            "lookupWeather",
            "Lookup weather by city",
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
    ProviderRequest request =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("weather?")))),
            List.of(tool));

    OpenAiResponsesEncodedRequest encoded =
        encoder.encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode tools = root.get("tools");
    assertNotNull(tools);
    assertEquals(1, tools.size());
    JsonNode t = tools.get(0);
    assertEquals("function", t.path("type").asText());
    assertEquals("lookupWeather", t.path("name").asText());
    assertEquals("Lookup weather by city", t.path("description").asText());
    assertEquals("object", t.path("parameters").path("type").asText());
    assertTrue(t.path("strict").asBoolean());
    assertEquals(List.of("city"), requiredOf(t.path("parameters")));
    assertFalse(t.path("parameters").path("additionalProperties").asBoolean());
  }

  /** 验证系统指令角色：推理模型编码为 developer，非推理模型保持 system。 */
  @Test
  void test_systemMessageRoleFollowsModelReasoning() throws Exception {
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("system rules"))),
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("user prompt"))));

    JsonNode reasoningRoot = encodedRoot(request(messages));
    assertEquals("developer", reasoningRoot.get("input").get(0).path("role").asText());
    assertEquals(
        "input_text",
        reasoningRoot.get("input").get(0).path("content").get(0).path("type").asText());
    assertEquals(
        "system rules",
        reasoningRoot.get("input").get(0).path("content").get(0).path("text").asText());

    JsonNode plainRoot =
        encodedRoot(request(nonReasoningModel(), DEFAULT_VARIANT, messages, List.of(), null));
    assertEquals("system", plainRoot.get("input").get(0).path("role").asText());
  }

  /** 验证 max_output_tokens 下限：过小的冻结预算明确拒绝，合法预算原样下发。 */
  @Test
  void test_maxOutputTokensMinimumValidation() throws Exception {
    ProviderException one = assertThrows(ProviderException.class, () -> encodedMaxOutputTokens(1));
    ProviderException fifteen =
        assertThrows(ProviderException.class, () -> encodedMaxOutputTokens(15));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, one.kind());
    assertEquals("max_output_tokens must be at least 16", one.getMessage());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, fifteen.kind());
    assertEquals(16, encodedMaxOutputTokens(16));
    assertEquals(1000, encodedMaxOutputTokens(1000));
  }

  /**
   * 验证 strict 工具 schema 归一化：object 节点显式全量 required 与 additionalProperties=false；只有源 schema 确实把属性
   * 排除在 required 之外（属性本身允许缺省）时才用 anyOf+null 保留可缺省语义；本来就必填或本来就允许 null 的属性保持原 schema；嵌套 object 与
   * array items 同样收敛，且绝不改写共享 schema。
   */
  @Test
  void test_strictToolSchemaNormalization() throws Exception {
    String schema =
        """
        {"type":"object",
         "properties":{
           "query":{"type":"string"},
           "limit":{"type":"integer"},
           "nullable":{"type":["string","null"]},
           "filter":{"type":"object","additionalProperties":true,
                     "properties":{"tag":{"type":"string"},"exact":{"type":"boolean"}},
                     "required":["exact"]},
           "meta":{"type":"object","properties":{"note":{"type":"string"}}},
           "tags":{"type":"array",
                   "items":{"type":"object","additionalProperties":false,
                            "properties":{"name":{"type":"string"}},"required":["name"]}}},
         "required":["query","filter","tags"],
         "additionalProperties":true}
        """;
    ProviderToolDefinition tool = new ProviderToolDefinition("search", "search", schema);

    JsonNode parameters = encodedTools(tool).get(0).path("parameters");

    assertTrue(encodedTools(tool).get(0).path("strict").asBoolean());
    assertEquals("object", parameters.path("type").asText());
    assertEquals(
        List.of("query", "limit", "nullable", "filter", "meta", "tags"), requiredOf(parameters));
    assertFalse(parameters.path("additionalProperties").asBoolean());

    JsonNode properties = parameters.path("properties");
    // 已声明必填的属性保持原 schema。
    assertEquals("string", properties.path("query").path("type").asText());
    assertFalse(properties.path("query").has("anyOf"));
    // 未声明必填的标量属性改写为任何非 null 值或 null。
    assertEquals("integer", properties.path("limit").path("anyOf").get(0).path("type").asText());
    assertEquals("null", properties.path("limit").path("anyOf").get(1).path("type").asText());
    // 已经允许 null 的属性不再重复包装。
    assertEquals("string", properties.path("nullable").path("type").get(0).asText());
    assertFalse(properties.path("nullable").has("anyOf"));
    // 已声明必填的嵌套 object：全量 required + additionalProperties=false，可缺省属性用 anyOf+null 保留语义。
    JsonNode filter = properties.path("filter");
    assertEquals(List.of("tag", "exact"), requiredOf(filter));
    assertFalse(filter.path("additionalProperties").asBoolean());
    assertEquals(
        "string", filter.path("properties").path("tag").path("anyOf").get(0).path("type").asText());
    assertEquals(
        "null", filter.path("properties").path("tag").path("anyOf").get(1).path("type").asText());
    assertEquals("boolean", filter.path("properties").path("exact").path("type").asText());
    // 可选 object 属性整体被 anyOf+null 包装，其内部 schema 同样完成归一化。
    JsonNode meta = properties.path("meta").path("anyOf").get(0);
    assertEquals("object", meta.path("type").asText());
    assertEquals(List.of("note"), requiredOf(meta));
    assertFalse(meta.path("additionalProperties").asBoolean());
    assertEquals(
        "string", meta.path("properties").path("note").path("anyOf").get(0).path("type").asText());
    assertEquals("null", properties.path("meta").path("anyOf").get(1).path("type").asText());
    // array items 内的 object schema 同样归一化。
    JsonNode items = properties.path("tags").path("items");
    assertEquals(List.of("name"), requiredOf(items));
    assertFalse(items.path("additionalProperties").asBoolean());

    // 共享输入模型不被改写，重复编码结果稳定。
    assertEquals(MAPPER.readTree(schema), MAPPER.readTree(tool.inputSchemaJson()));
    assertEquals(encodedTools(tool), encodedTools(tool));
  }

  /** 验证 prompt_cache_key 直接取自 runtime 派生的 cache affinity identity：稳定、可复现、不同 session 不同。 */
  @Test
  void test_promptCacheKeyComesFromRuntimeAffinityIdentity() throws Exception {
    UUID sessionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("system rules"))),
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("user prompt"))));
    ProviderCacheControl cacheControl = runtimeAffinityCacheControl(sessionId, request(messages));
    assertTrue(cacheControl.affinityKey().startsWith("pc2-"));

    ProviderRequest cachedRequest =
        new ProviderRequest(
            createModel(),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            messages,
            List.of(),
            cacheControl);
    JsonNode first =
        MAPPER.readTree(
            encoder
                .encode(
                    cachedRequest,
                    createDescriptor(),
                    new OpenAiResponsesConfig(OpenAiPromptCacheMode.LEGACY))
                .bodyUtf8Bytes());
    JsonNode second =
        MAPPER.readTree(
            encoder
                .encode(
                    cachedRequest,
                    createDescriptor(),
                    new OpenAiResponsesConfig(OpenAiPromptCacheMode.LEGACY))
                .bodyUtf8Bytes());

    // 编码器只透出 runtime identity，绝不自造 per-attempt key。
    assertEquals(cacheControl.affinityKey(), first.path("prompt_cache_key").asText());
    assertEquals(first.path("prompt_cache_key").asText(), second.path("prompt_cache_key").asText());

    ProviderCacheControl otherSession =
        runtimeAffinityCacheControl(
            UUID.fromString("44444444-4444-4444-4444-444444444444"), request(messages));
    assertNotEquals(otherSession.affinityKey(), first.path("prompt_cache_key").asText());

    // runtime 未启用缓存时不得凭空发送 key。
    JsonNode none =
        MAPPER.readTree(
            encoder
                .encode(
                    request(messages),
                    createDescriptor(),
                    new OpenAiResponsesConfig(OpenAiPromptCacheMode.LEGACY))
                .bodyUtf8Bytes());
    assertFalse(none.has("prompt_cache_key"));
  }

  /** 经 runtime 的 finalizer 派生 affinity cacheControl，模拟真实请求物化路径。 */
  private static ProviderCacheControl runtimeAffinityCacheControl(
      UUID sessionId, ProviderRequest base) {
    PromptCacheCapability capability =
        PromptCacheCapability.affinity(
            Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG));
    return new PromptCacheRequestFinalizer(
            sessionId, UUID.fromString("33333333-3333-3333-3333-333333333333"))
        .apply(base, PromptCachePolicy.affinityShort(capability))
        .cacheControl();
  }

  private JsonNode encodedRoot(ProviderRequest request) throws Exception {
    return MAPPER.readTree(
        encoder
            .encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
            .bodyUtf8Bytes());
  }

  private int encodedMaxOutputTokens(int maxOutputTokens) throws Exception {
    JsonNode root =
        encodedRoot(request(createModel(), DEFAULT_VARIANT, maxOutputTokens, null, null, null));
    return root.path("max_output_tokens").asInt();
  }

  private ArrayNode encodedTools(ProviderToolDefinition tool) throws Exception {
    JsonNode root = encodedRoot(request(List.of(), List.of(tool)));
    return (ArrayNode) root.get("tools");
  }

  private static List<String> requiredOf(JsonNode objectSchema) {
    List<String> required = new ArrayList<>();
    objectSchema.path("required").forEach(node -> required.add(node.asText()));
    return required;
  }

  /** 验证多模态输入（图片 URL、图片 data URI、PDF URL、PDF data URI）正确映射。 */
  @Test
  void test_multimodalEncoding_imageAndPdf() throws Exception {
    String dataImage = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==";
    String dataPdf = "data:application/pdf;base64,JVBERi0xLjQK";

    ProviderRequest request =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(
                        new ProviderTextBlock("Check files"),
                        new ProviderImageBlock("image/jpeg", "https://example.com/img.jpg"),
                        new ProviderImageBlock("image/png", dataImage),
                        new ProviderDocumentBlock("application/pdf", "https://example.com/doc.pdf"),
                        new ProviderDocumentBlock("application/pdf", dataPdf)))));

    OpenAiResponsesEncodedRequest encoded =
        encoder.encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode contents = root.get("input").get(0).get("content");
    assertEquals(5, contents.size());

    assertEquals("input_text", contents.get(0).path("type").asText());
    assertEquals("input_image", contents.get(1).path("type").asText());
    assertEquals("https://example.com/img.jpg", contents.get(1).path("image_url").asText());
    assertEquals("input_image", contents.get(2).path("type").asText());
    assertEquals(dataImage, contents.get(2).path("image_url").asText());
    assertEquals("input_file", contents.get(3).path("type").asText());
    assertEquals("https://example.com/doc.pdf", contents.get(3).path("file_url").asText());
    assertEquals("input_file", contents.get(4).path("type").asText());
    assertEquals(dataPdf, contents.get(4).path("file_data").asText());
    assertEquals("document.pdf", contents.get(4).path("filename").asText());
  }

  /** 验证不支持的媒体类型（音频、视频、不支持的图片格式、非法 URI）被立即拒绝。 */
  @Test
  void test_rejectsUnsupportedMedia() {
    // 音频
    ProviderRequest reqAudio =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderAudioBlock("audio/mp3", "https://ex.com/a.mp3")))));
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqAudio, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));

    // 视频
    ProviderRequest reqVideo =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderVideoBlock("video/mp4", "https://ex.com/v.mp4")))));
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqVideo, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));

    // 不支持图片格式
    ProviderRequest reqTiff =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/tiff", "https://ex.com/i.tiff")))));
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqTiff, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));

    // 非法 URI scheme
    ProviderRequest reqFtp =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/png", "ftp://ex.com/i.png")))));
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(reqFtp, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));
  }

  /** 验证 AUTOMATIC、LEGACY 与 GPT_5_6_EXPLICIT 三种缓存模式下的 wire 映射行为。 */
  @Test
  void test_promptCacheModesWireMapping() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    ProviderCacheControl cacheControl =
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT,
            "aff_key_999",
            Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.CONVERSATION));

    ProviderRequest request =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("system rules"))),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("user prompt")))),
            cacheControl);

    // 1. AUTOMATIC: 不发送任何 cache hint，即使 cacheControl 含有 key/retention 也防御性忽略
    OpenAiResponsesEncodedRequest encAuto =
        encoder.encode(request, desc, new OpenAiResponsesConfig(OpenAiPromptCacheMode.AUTOMATIC));
    JsonNode rootAuto = MAPPER.readTree(encAuto.bodyUtf8Bytes());
    assertFalse(rootAuto.has("prompt_cache_key"));
    assertFalse(rootAuto.has("prompt_cache_retention"));
    assertFalse(rootAuto.has("prompt_cache_options"));
    assertFalse(rootAuto.get("input").get(0).get("content").get(0).has("prompt_cache_breakpoint"));

    // 1b. AUTOMATIC + retention NONE: 完全不发 cache hint，明确表达禁用
    ProviderRequest reqAutoNone =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")))));
    JsonNode rootAutoNone =
        MAPPER.readTree(
            encoder
                .encode(
                    reqAutoNone, desc, new OpenAiResponsesConfig(OpenAiPromptCacheMode.AUTOMATIC))
                .bodyUtf8Bytes());
    assertFalse(rootAutoNone.has("prompt_cache_key"));
    assertFalse(rootAutoNone.has("prompt_cache_retention"));
    assertFalse(rootAutoNone.has("prompt_cache_options"));

    // 2. LEGACY: 发送 key 与 retention
    OpenAiResponsesEncodedRequest encLegacy =
        encoder.encode(request, desc, new OpenAiResponsesConfig(OpenAiPromptCacheMode.LEGACY));
    JsonNode rootLegacy = MAPPER.readTree(encLegacy.bodyUtf8Bytes());
    assertEquals("aff_key_999", rootLegacy.path("prompt_cache_key").asText());
    assertEquals("in_memory", rootLegacy.path("prompt_cache_retention").asText());
    assertFalse(rootLegacy.has("prompt_cache_options"));

    // 3. GPT_5_6_EXPLICIT: 非 NONE 发送 options + key，并在 SYSTEM 与 CONVERSATION 打 breakpoint marker
    OpenAiResponsesEncodedRequest encExplicit =
        encoder.encode(
            request, desc, new OpenAiResponsesConfig(OpenAiPromptCacheMode.GPT_5_6_EXPLICIT));
    JsonNode rootExplicit = MAPPER.readTree(encExplicit.bodyUtf8Bytes());
    assertEquals("aff_key_999", rootExplicit.path("prompt_cache_key").asText());
    assertEquals("explicit", rootExplicit.path("prompt_cache_options").path("mode").asText());
    assertEquals("30m", rootExplicit.path("prompt_cache_options").path("ttl").asText());

    JsonNode sysBlock = rootExplicit.get("input").get(0).get("content").get(0);
    assertEquals("explicit", sysBlock.path("prompt_cache_breakpoint").path("mode").asText());

    JsonNode convBlock = rootExplicit.get("input").get(1).get("content").get(0);
    assertEquals("explicit", convBlock.path("prompt_cache_breakpoint").path("mode").asText());

    // 4. GPT_5_6_EXPLICIT 下 retention 为 NONE: 发送 options 且无 key/breakpoint 明确表达禁用
    ProviderRequest reqNone =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")))));
    OpenAiResponsesEncodedRequest encExplicitNone =
        encoder.encode(
            reqNone, desc, new OpenAiResponsesConfig(OpenAiPromptCacheMode.GPT_5_6_EXPLICIT));
    JsonNode rootExplicitNone = MAPPER.readTree(encExplicitNone.bodyUtf8Bytes());
    assertFalse(rootExplicitNone.has("prompt_cache_key"));
    assertEquals("explicit", rootExplicitNone.path("prompt_cache_options").path("mode").asText());
    assertEquals("30m", rootExplicitNone.path("prompt_cache_options").path("ttl").asText());
    assertFalse(
        rootExplicitNone.get("input").get(0).get("content").get(0).has("prompt_cache_breakpoint"));
  }

  /** 验证合法 replayState 在原位回放有序 output 项并不重复语义内容。 */
  @Test
  void test_replayState_inPlaceReplaySuccess() throws Exception {
    ProviderDescriptor desc = createDescriptor();

    // 先计算前缀哈希以构造匹配的 replayState
    ArrayNode priorInput = MAPPER.createArrayNode();
    ObjectNode userMsg = priorInput.addObject();
    userMsg.put("type", "message").put("role", "user");
    userMsg.putArray("content").addObject().put("type", "input_text").put("text", "question");
    String validPrefixHash =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", MAPPER.createArrayNode(), priorInput);

    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode outputArr = payload.putArray("output");
    ObjectNode fc = outputArr.addObject();
    fc.put("type", "function_call");
    fc.put("call_id", "call_123");
    fc.put("name", "getWeather");
    fc.put("arguments", "{\"city\":\"Paris\"}");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validPrefixHash,
            payload);

    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_123", "getWeather", "{\"city\":\"Paris\"}"))),
            replayState);

    ProviderRequest request =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("question"))),
                assistantMsg,
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_123",
                            "getWeather",
                            List.of(new ProviderTextBlock("20C")),
                            false,
                            null)))));

    OpenAiResponsesEncodedRequest encoded =
        encoder.encode(request, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode input = root.get("input");
    assertEquals(3, input.size());
    assertEquals("message", input.get(0).path("type").asText());
    assertEquals("function_call", input.get(1).path("type").asText());
    assertEquals("call_123", input.get(1).path("call_id").asText());
    assertEquals("function_call_output", input.get(2).path("type").asText());
  }

  /** 测试意图：非 OPENAI_RESPONSES format 必须回退到语义编码（semantic fallback），不抛出异常。 */
  @Test
  void test_replayState_nonOpenAiFormatFallbackToSemantic() throws Exception {
    ProviderDescriptor desc = createDescriptor();

    ProviderReplayState replayStateOtherFormat =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            desc.affinity("gpt-5.4-mini"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            MAPPER.createObjectNode().put("role", "assistant"));
    ProviderMessage msg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("fallback response")),
            replayStateOtherFormat);
    ProviderRequest req = request(List.of(msg));

    OpenAiResponsesEncodedRequest enc =
        encoder.encode(req, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());
    JsonNode input = root.get("input");
    assertEquals(1, input.size());
    assertEquals("message", input.get(0).path("type").asText());
    assertEquals("assistant", input.get(0).path("role").asText());
    assertEquals("fallback response", input.get(0).path("content").get(0).path("text").asText());
  }

  /**
   * 验证同 OPENAI_RESPONSES format 但 payload 结构非法、缺少白名单字段、存在未知无关字段、 或与 durable 语义矛盾时，无论 affinity/hash
   * 是否匹配均必须抛出 INVALID_REQUEST。
   */
  @Test
  void test_replayState_invalidPayloadRejected() {
    ProviderDescriptor desc = createDescriptor();
    String validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // 1. 缺少 output 数组
    ObjectNode malformedPayload1 = MAPPER.createObjectNode();
    malformedPayload1.put("not_output", 123);
    ProviderReplayState replayState1 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            malformedPayload1);

    ProviderMessage msg1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("hi")), replayState1);
    ProviderRequest req1 = request(List.of(msg1));

    ProviderException ex1 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req1, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. 根 payload 包含非白名单字段
    ObjectNode malformedPayload2 = MAPPER.createObjectNode();
    malformedPayload2.putArray("output");
    malformedPayload2.put("malicious_extra", "injected");
    ProviderReplayState replayState2 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            malformedPayload2);
    ProviderMessage msg2 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("hi")), replayState2);
    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msg2)), desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());

    // 3. output 数组内存在非白名单未知字段的 message item
    ObjectNode malformedPayload3 = MAPPER.createObjectNode();
    ArrayNode outArr3 = malformedPayload3.putArray("output");
    ObjectNode msgItem3 = outArr3.addObject();
    msgItem3.put("type", "message").put("role", "assistant").put("unknown_prop", true);
    msgItem3.putArray("content").addObject().put("type", "output_text").put("text", "hi");
    ProviderReplayState replayState3 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            malformedPayload3);
    ProviderMessage msg3 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("hi")), replayState3);
    ProviderException ex3 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msg3)), desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());

    // 4. output 数组内 message role 不是 assistant
    ObjectNode malformedPayload4 = MAPPER.createObjectNode();
    ArrayNode outArr4 = malformedPayload4.putArray("output");
    ObjectNode msgItem4 = outArr4.addObject();
    msgItem4.put("type", "message").put("role", "user");
    msgItem4.putArray("content").addObject().put("type", "output_text").put("text", "hi");
    ProviderReplayState replayState4 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            malformedPayload4);
    ProviderMessage msg4 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("hi")), replayState4);
    ProviderException ex4 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msg4)), desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex4.kind());

    // 5. function_call 缺少 call_id 和 id
    ObjectNode malformedPayload5 = MAPPER.createObjectNode();
    ArrayNode outArr5 = malformedPayload5.putArray("output");
    ObjectNode fcItem5 = outArr5.addObject();
    fcItem5.put("type", "function_call").put("name", "calc").put("arguments", "{}");
    ProviderReplayState replayState5 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            malformedPayload5);
    ProviderMessage msg5 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))),
            replayState5);
    ProviderException ex5 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msg5)), desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex5.kind());

    // 6. 损坏 payload 即使 affinity/hash 失配，也先被抛出 INVALID_REQUEST 而不走 fallback
    ProviderReplayState replayStateMismatchAndBroken =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("completely_different_model"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            malformedPayload1);
    ProviderMessage msg6 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("hi")),
            replayStateMismatchAndBroken);
    ProviderException ex6 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msg6)), desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex6.kind());
  }

  /**
   * 测试意图：验证回放 tool_call 的 arguments 与 durable 必须完全一致； 即使 affinity 或 prefixHash 故意失配，参数被篡改的 payload
   * 也必须抛出 INVALID_REQUEST 而非回退。
   */
  @Test
  void test_replayState_alteredToolArgumentsRejectedEvenWhenAffinityOrHashMismatch() {
    ProviderDescriptor desc = createDescriptor();

    ObjectNode alteredArgsPayload = MAPPER.createObjectNode();
    alteredArgsPayload
        .putArray("output")
        .addObject()
        .put("type", "function_call")
        .put("call_id", "call_1")
        .put("name", "get_weather")
        .put("arguments", "{\"city\":\"Beijing\"}");

    // 故意设置失配的 affinity 和 hash
    ProviderReplayState replayMismatchAffinityAndHash =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("completely_different_model"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            alteredArgsPayload);

    ProviderMessage msg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "get_weather", "{\"city\":\"Shanghai\"}"))),
            replayMismatchAffinityAndHash);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(request(List.of(msg)), desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("replay tool call mismatch with durable tool call"));
  }

  /**
   * 测试意图：验证 replay thinking 与 durable thinking 的一致性校验； 严禁 opaque reasoning 替换另一段 durable thinking。
   */
  @Test
  void test_replayState_thinkingConsistencyValidation() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    String validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // 1. durable 包含 thinking，但 replay 只有 opaque reasoning（只有 encrypted_content，无 summary）：必须被拒绝
    ObjectNode opaquePayload = MAPPER.createObjectNode();
    ArrayNode out1 = opaquePayload.putArray("output");
    ObjectNode r1 = out1.addObject();
    r1.put("type", "reasoning").put("encrypted_content", "opaque_blob");
    ObjectNode m1 = out1.addObject();
    m1.put("type", "message").put("role", "assistant");
    m1.putArray("content").addObject().put("type", "output_text").put("text", "answer");

    ProviderReplayState replayOpaqueOnly =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            opaquePayload);
    ProviderMessage msgWithDurableThinking =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("answer")),
            replayOpaqueOnly);

    ProviderException ex1 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msgWithDurableThinking)),
                    desc,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. durable 包含 thinking，但 replay 的 summary 文本不一致：必须被拒绝
    ObjectNode mismatchSummaryPayload = MAPPER.createObjectNode();
    ArrayNode out2 = mismatchSummaryPayload.putArray("output");
    ObjectNode r2 = out2.addObject();
    r2.put("type", "reasoning");
    r2.putArray("summary").addObject().put("type", "summary_text").put("text", "different thought");
    ObjectNode m2 = out2.addObject();
    m2.put("type", "message").put("role", "assistant");
    m2.putArray("content").addObject().put("type", "output_text").put("text", "answer");

    ProviderReplayState replayMismatchSummary =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            mismatchSummaryPayload);
    ProviderMessage msgMismatch =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("answer")),
            replayMismatchSummary);

    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msgMismatch)), desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());

    // 3. durable 没有 thinking，但 replay 带有 summary thinking：必须被拒绝
    ProviderMessage msgNoDurableWithReplaySummary =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("answer")),
            replayMismatchSummary);
    ProviderException ex3 =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(List.of(msgNoDurableWithReplaySummary)),
                    desc,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());

    // 4. durable 包含 thinking，且 replay 包含完全匹配的 summary thinking：成功回放
    ObjectNode matchedSummaryPayload = MAPPER.createObjectNode();
    ArrayNode out4 = matchedSummaryPayload.putArray("output");
    ObjectNode r4 = out4.addObject();
    r4.put("type", "reasoning");
    r4.putArray("summary").addObject().put("type", "summary_text").put("text", "durable thought");
    ObjectNode m4 = out4.addObject();
    m4.put("type", "message").put("role", "assistant");
    m4.putArray("content").addObject().put("type", "output_text").put("text", "answer");

    // 计算精确 prefixHash 以便匹配
    String currentHash =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", MAPPER.createArrayNode(), MAPPER.createArrayNode());
    ProviderReplayState replayMatched =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            currentHash,
            matchedSummaryPayload);
    ProviderMessage msgMatched =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("answer")),
            replayMatched);

    OpenAiResponsesEncodedRequest enc4 =
        encoder.encode(request(List.of(msgMatched)), desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root4 = MAPPER.readTree(enc4.bodyUtf8Bytes());
    assertEquals(2, root4.get("input").size());
    assertEquals("reasoning", root4.get("input").get(0).path("type").asText());
    assertEquals("message", root4.get("input").get(1).path("type").asText());
  }

  /** 验证代际（affinity）或前缀哈希失配时回退到语义 assistant 编码，不抛出异常。 */
  @Test
  void test_replayState_mismatchFallbackToSemantic() throws Exception {
    ProviderDescriptor desc = createDescriptor();

    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode outputArr = payload.putArray("output");
    ObjectNode msgNode = outputArr.addObject();
    msgNode.put("type", "message").put("role", "assistant");
    msgNode.putArray("content").addObject().put("type", "output_text").put("text", "hello world");

    // affinity 失配（不同 connectionGenerationId）
    ProviderReplayState replayStateMismatch =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("different_model"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            payload);

    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("hello world")),
            replayStateMismatch);

    ProviderRequest request = request(List.of(assistantMsg));

    OpenAiResponsesEncodedRequest encoded =
        encoder.encode(request, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode input = root.get("input");
    assertEquals(1, input.size());
    assertEquals("message", input.get(0).path("type").asText());
    assertEquals("assistant", input.get(0).path("role").asText());
    assertEquals("hello world", input.get(0).path("content").get(0).path("text").asText());
  }

  /** 验证带有 isError 标志的 tool result 正确映射。 */
  @Test
  void test_toolResultWithErrorFlag() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ProviderRequest req =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_fail",
                            "math_tool",
                            List.of(new ProviderTextBlock("division by zero")),
                            true,
                            null)))));

    OpenAiResponsesEncodedRequest enc =
        encoder.encode(req, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());
    JsonNode input = root.get("input");
    assertEquals(1, input.size());
    assertEquals("function_call_output", input.get(0).path("type").asText());
    assertEquals("division by zero", input.get(0).path("output").asText());
  }

  /** 验证 Assistant 包含 ProviderThinkingBlock、ToolResult 为空或多块以及非法 Schema 拒绝。 */
  @Test
  void test_thinkingBlockAndToolResultVariants() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    // 1. Assistant message with ThinkingBlock
    ProviderRequest req1 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderThinkingBlock("my thoughts"),
                        new ProviderTextBlock("my answer")))));
    OpenAiResponsesEncodedRequest enc1 =
        encoder.encode(req1, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root1 = MAPPER.readTree(enc1.bodyUtf8Bytes());
    JsonNode input1 = root1.get("input");
    assertEquals(2, input1.size());
    assertEquals("reasoning", input1.get(0).path("type").asText());
    assertEquals("my thoughts", input1.get(0).path("summary").get(0).path("text").asText());
    assertEquals("message", input1.get(1).path("type").asText());

    // 2. ToolResult empty blocks
    ProviderRequest req2 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_empty", "tool", List.of(), false, null)))));
    OpenAiResponsesEncodedRequest enc2 =
        encoder.encode(req2, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root2 = MAPPER.readTree(enc2.bodyUtf8Bytes());
    assertEquals("", root2.get("input").get(0).path("output").asText());

    // 3. ToolResult multiple blocks (text + image)
    ProviderRequest req3 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_multi",
                            "tool",
                            List.of(
                                new ProviderTextBlock("diagram:"),
                                new ProviderImageBlock(
                                    "image/png", "data:image/png;base64,iVBORw0KGgo=")),
                            false,
                            null)))));
    OpenAiResponsesEncodedRequest enc3 =
        encoder.encode(req3, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root3 = MAPPER.readTree(enc3.bodyUtf8Bytes());
    assertTrue(root3.get("input").get(0).path("output").isArray());
    assertEquals(2, root3.get("input").get(0).path("output").size());

    // 4. Invalid tool schema (array instead of object)
    ProviderToolDefinition badTool =
        new ProviderToolDefinition("bad_tool", "desc", "[\"not\", \"object\"]");
    ProviderRequest req4 =
        request(
            DEFAULT_VARIANT,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
            List.of(badTool),
            ProviderCacheControl.none());
    assertThrows(
        ProviderException.class,
        () -> encoder.encode(req4, desc, OpenAiResponsesConfig.defaultConfig()));
  }

  /**
   * 测试意图：验证 ProviderToolResultBlock.contents 对 ProviderJsonBlock 的支持； JSON 与 text/image
   * 按原顺序确定性编码；单文本保持简洁 string 兼容，JSON-only 编码为原始 JSON 文本； 多块走 output content array；单块或多块中出现任何不支持
   * block（thinking/document/audio）均明确抛出 INVALID_REQUEST。
   */
  @Test
  void test_toolResult_jsonBlockAndMultiBlockSupport() throws Exception {
    ProviderDescriptor desc = createDescriptor();

    // 1. 单 ProviderJsonBlock（JSON-only）：编码为 string 原始 JSON
    ProviderRequest req1 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_json",
                            "weather",
                            List.of(new ProviderJsonBlock("{\"temperature\":22,\"unit\":\"C\"}")),
                            false,
                            null)))));
    OpenAiResponsesEncodedRequest enc1 =
        encoder.encode(req1, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root1 = MAPPER.readTree(enc1.bodyUtf8Bytes());
    JsonNode output1 = root1.get("input").get(0).path("output");
    assertTrue(output1.isTextual());
    assertEquals("{\"temperature\":22,\"unit\":\"C\"}", output1.asText());

    // 2. 单 ProviderTextBlock：保持单文本的简洁 string wire 兼容
    ProviderRequest req2 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_txt",
                            "tool",
                            List.of(new ProviderTextBlock("plain text result")),
                            false,
                            null)))));
    OpenAiResponsesEncodedRequest enc2 =
        encoder.encode(req2, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root2 = MAPPER.readTree(enc2.bodyUtf8Bytes());
    JsonNode output2 = root2.get("input").get(0).path("output");
    assertTrue(output2.isTextual());
    assertEquals("plain text result", output2.asText());

    // 3. 单 ProviderImageBlock：必须走 output content array
    ProviderRequest req3 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_img",
                            "camera",
                            List.of(
                                new ProviderImageBlock(
                                    "image/png", "data:image/png;base64,iVBORw0KGgo=")),
                            false,
                            null)))));
    OpenAiResponsesEncodedRequest enc3 =
        encoder.encode(req3, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root3 = MAPPER.readTree(enc3.bodyUtf8Bytes());
    JsonNode output3 = root3.get("input").get(0).path("output");
    assertTrue(output3.isArray());
    assertEquals(1, output3.size());
    assertEquals("input_image", output3.get(0).path("type").asText());
    // 工具结果图片必须逐字节保留完整 base64 data URI 作为 image_url，且 detail 固定为 auto
    assertEquals("data:image/png;base64,iVBORw0KGgo=", output3.get(0).path("image_url").asText());
    assertEquals("auto", output3.get(0).path("detail").asText());

    // 4. 多块混合（Text + Json + Image）：按原顺序确定性编码进 output content array
    ProviderRequest req4 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_mixed",
                            "sensor",
                            List.of(
                                new ProviderTextBlock("prefix text"),
                                new ProviderJsonBlock("{\"reading\":99}"),
                                new ProviderImageBlock(
                                    "image/jpeg", "https://api.openai.com/image.jpg")),
                            false,
                            null)))));
    OpenAiResponsesEncodedRequest enc4 =
        encoder.encode(req4, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root4 = MAPPER.readTree(enc4.bodyUtf8Bytes());
    JsonNode output4 = root4.get("input").get(0).path("output");
    assertTrue(output4.isArray());
    assertEquals(3, output4.size());
    assertEquals("input_text", output4.get(0).path("type").asText());
    assertEquals("prefix text", output4.get(0).path("text").asText());
    assertEquals("input_text", output4.get(1).path("type").asText());
    assertEquals("{\"reading\":99}", output4.get(1).path("text").asText());
    assertEquals("input_image", output4.get(2).path("type").asText());
    assertEquals("https://api.openai.com/image.jpg", output4.get(2).path("image_url").asText());

    // 5. 不支持的 block（ThinkingBlock、DocumentBlock、AudioBlock）：单块与多块均抛出 INVALID_REQUEST
    ProviderRequest reqBadThinking =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_bad_th",
                            "tool",
                            List.of(new ProviderThinkingBlock("internal thought")),
                            false,
                            null)))));
    ProviderException exTh =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(reqBadThinking, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exTh.kind());

    ProviderRequest reqBadDoc =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_bad_doc",
                            "tool",
                            List.of(
                                new ProviderDocumentBlock(
                                    "application/pdf", "https://example.com/doc.pdf")),
                            false,
                            null)))));
    ProviderException exDoc =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(reqBadDoc, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exDoc.kind());

    ProviderRequest reqBadAudio =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_bad_audio",
                            "tool",
                            List.of(
                                new ProviderAudioBlock(
                                    "audio/wav", "https://example.com/audio.wav")),
                            false,
                            null)))));
    ProviderException exAudio =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(reqBadAudio, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exAudio.kind());

    // 6. 多块中混入不支持 block（例如 Text + ThinkingBlock 或 Text + DocumentBlock）
    ProviderRequest reqBadMulti1 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_bad_m1",
                            "tool",
                            List.of(new ProviderTextBlock("ok"), new ProviderThinkingBlock("bad")),
                            false,
                            null)))));
    ProviderException exM1 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(reqBadMulti1, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exM1.kind());

    ProviderRequest reqBadMulti2 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_bad_m2",
                            "tool",
                            List.of(
                                new ProviderJsonBlock("{}"),
                                new ProviderDocumentBlock(
                                    "application/pdf", "https://example.com/doc.pdf")),
                            false,
                            null)))));
    ProviderException exM2 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(reqBadMulti2, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exM2.kind());
  }

  /**
   * 测试意图：全面验证回放校验子结构边界条件（非 Object 项、未知项类型、畸形字段、 id 回退、文本与工具调用失配、不支持的 durable 块）、SYSTEM 消息非文本拦截与 URI
   * 校验防御。
   */
  @Test
  void test_replayAndValidationBoundaryConditions() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    String validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // 1. replay payload 缺少 output 数组或不是 array
    ObjectNode missingOutputPayload = MAPPER.createObjectNode();
    missingOutputPayload.put("output", "not_an_array");
    ProviderReplayState missingOutputReplay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            missingOutputPayload);
    ProviderException exMissingOutput =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(
                        List.of(
                            new ProviderMessage(
                                ProviderMessageRole.ASSISTANT,
                                List.of(new ProviderTextBlock("hi")),
                                missingOutputReplay))),
                    desc,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exMissingOutput.kind());

    // 2. output array 包含非 object 项
    ObjectNode nonObjItemPayload = MAPPER.createObjectNode();
    nonObjItemPayload.putArray("output").add(12345);
    ProviderReplayState nonObjItemReplay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            nonObjItemPayload);
    ProviderException exNonObjItem =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(
                        List.of(
                            new ProviderMessage(
                                ProviderMessageRole.ASSISTANT,
                                List.of(new ProviderTextBlock("hi")),
                                nonObjItemReplay))),
                    desc,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exNonObjItem.kind());

    // 3. output item 缺少 type 或为空白
    ObjectNode emptyTypePayload = MAPPER.createObjectNode();
    emptyTypePayload.putArray("output").addObject().put("type", "  ");
    ProviderReplayState emptyTypeReplay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            emptyTypePayload);
    ProviderException exEmptyType =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(
                        List.of(
                            new ProviderMessage(
                                ProviderMessageRole.ASSISTANT,
                                List.of(new ProviderTextBlock("hi")),
                                emptyTypeReplay))),
                    desc,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exEmptyType.kind());

    // 4. output item 类型不支持
    ObjectNode unknownTypePayload = MAPPER.createObjectNode();
    unknownTypePayload.putArray("output").addObject().put("type", "unknown_item_type");
    ProviderReplayState unknownTypeReplay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            unknownTypePayload);
    ProviderException exUnknownType =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(
                        List.of(
                            new ProviderMessage(
                                ProviderMessageRole.ASSISTANT,
                                List.of(new ProviderTextBlock("hi")),
                                unknownTypeReplay))),
                    desc,
                    OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exUnknownType.kind());

    // 5. message 字段格式非法（id 为空白、phase 为空白、content 非 array、content block 非 object、content block type
    // 错误、text 缺失）
    List<ObjectNode> invalidMessages = new ArrayList<>();
    ObjectNode mBlankId = MAPPER.createObjectNode();
    mBlankId.put("type", "message").put("role", "assistant").put("id", " ");
    mBlankId.putArray("content").addObject().put("type", "output_text").put("text", "hi");
    invalidMessages.add(mBlankId);

    ObjectNode mBlankPhase = MAPPER.createObjectNode();
    mBlankPhase.put("type", "message").put("role", "assistant").put("phase", "");
    mBlankPhase.putArray("content").addObject().put("type", "output_text").put("text", "hi");
    invalidMessages.add(mBlankPhase);

    ObjectNode mContentNotArray = MAPPER.createObjectNode();
    mContentNotArray.put("type", "message").put("role", "assistant").put("content", "not_an_array");
    invalidMessages.add(mContentNotArray);

    ObjectNode mBlockNotObj = MAPPER.createObjectNode();
    mBlockNotObj.put("type", "message").put("role", "assistant");
    mBlockNotObj.putArray("content").add("not_object");
    invalidMessages.add(mBlockNotObj);

    ObjectNode mBlockWrongType = MAPPER.createObjectNode();
    mBlockWrongType.put("type", "message").put("role", "assistant");
    mBlockWrongType.putArray("content").addObject().put("type", "input_text").put("text", "hi");
    invalidMessages.add(mBlockWrongType);

    ObjectNode mBlockMissingText = MAPPER.createObjectNode();
    mBlockMissingText.put("type", "message").put("role", "assistant");
    mBlockMissingText.putArray("content").addObject().put("type", "output_text");
    invalidMessages.add(mBlockMissingText);

    for (ObjectNode invalidMsg : invalidMessages) {
      ObjectNode p = MAPPER.createObjectNode();
      p.putArray("output").add(invalidMsg);
      ProviderReplayState rs =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_RESPONSES, desc.affinity("gpt-5.4-mini"), validHash, p);
      assertThrows(
          ProviderException.class,
          () ->
              encoder.encode(
                  request(
                      List.of(
                          new ProviderMessage(
                              ProviderMessageRole.ASSISTANT,
                              List.of(new ProviderTextBlock("hi")),
                              rs))),
                  desc,
                  OpenAiResponsesConfig.defaultConfig()));
    }

    // 6. reasoning 字段格式非法（id blank、encrypted_content blank、summary 非 array、summary block 非
    // object、summary block type 错误、summary block 缺少 text）
    List<ObjectNode> invalidReasonings = new ArrayList<>();
    ObjectNode rBlankId = MAPPER.createObjectNode();
    rBlankId.put("type", "reasoning").put("id", "   ").put("encrypted_content", "enc");
    invalidReasonings.add(rBlankId);

    ObjectNode rBlankEnc = MAPPER.createObjectNode();
    rBlankEnc.put("type", "reasoning").put("encrypted_content", "  ");
    invalidReasonings.add(rBlankEnc);

    ObjectNode rSummaryNotArray = MAPPER.createObjectNode();
    rSummaryNotArray.put("type", "reasoning").put("summary", "string_not_array");
    invalidReasonings.add(rSummaryNotArray);

    ObjectNode rSummaryBlockNotObj = MAPPER.createObjectNode();
    rSummaryBlockNotObj.put("type", "reasoning");
    rSummaryBlockNotObj.putArray("summary").add("string_block");
    invalidReasonings.add(rSummaryBlockNotObj);

    ObjectNode rSummaryBlockWrongType = MAPPER.createObjectNode();
    rSummaryBlockWrongType.put("type", "reasoning");
    rSummaryBlockWrongType
        .putArray("summary")
        .addObject()
        .put("type", "wrong_type")
        .put("text", "th");
    invalidReasonings.add(rSummaryBlockWrongType);

    ObjectNode rSummaryBlockMissingText = MAPPER.createObjectNode();
    rSummaryBlockMissingText.put("type", "reasoning");
    rSummaryBlockMissingText.putArray("summary").addObject().put("type", "summary_text");
    invalidReasonings.add(rSummaryBlockMissingText);

    for (ObjectNode invalidR : invalidReasonings) {
      ObjectNode p = MAPPER.createObjectNode();
      p.putArray("output").add(invalidR);
      ProviderReplayState rs =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_RESPONSES, desc.affinity("gpt-5.4-mini"), validHash, p);
      assertThrows(
          ProviderException.class,
          () ->
              encoder.encode(
                  request(
                      List.of(
                          new ProviderMessage(
                              ProviderMessageRole.ASSISTANT,
                              List.of(new ProviderTextBlock("hi")),
                              rs))),
                  desc,
                  OpenAiResponsesConfig.defaultConfig()));
    }

    // 7. function_call 字段格式非法（call_id blank、id blank、name missing/blank、arguments missing/非 string）
    List<ObjectNode> invalidFunctionCalls = new ArrayList<>();
    ObjectNode fcBlankCallId = MAPPER.createObjectNode();
    fcBlankCallId
        .put("type", "function_call")
        .put("call_id", "  ")
        .put("name", "calc")
        .put("arguments", "{}");
    invalidFunctionCalls.add(fcBlankCallId);

    ObjectNode fcBlankId = MAPPER.createObjectNode();
    fcBlankId
        .put("type", "function_call")
        .put("id", " ")
        .put("name", "calc")
        .put("arguments", "{}");
    invalidFunctionCalls.add(fcBlankId);

    ObjectNode fcMissingName = MAPPER.createObjectNode();
    fcMissingName.put("type", "function_call").put("call_id", "c1").put("arguments", "{}");
    invalidFunctionCalls.add(fcMissingName);

    ObjectNode fcMissingArgs = MAPPER.createObjectNode();
    fcMissingArgs.put("type", "function_call").put("call_id", "c1").put("name", "calc");
    invalidFunctionCalls.add(fcMissingArgs);

    for (ObjectNode invalidFc : invalidFunctionCalls) {
      ObjectNode p = MAPPER.createObjectNode();
      p.putArray("output").add(invalidFc);
      ProviderReplayState rs =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_RESPONSES, desc.affinity("gpt-5.4-mini"), validHash, p);
      assertThrows(
          ProviderException.class,
          () ->
              encoder.encode(
                  request(
                      List.of(
                          new ProviderMessage(
                              ProviderMessageRole.ASSISTANT,
                              List.of(
                                  new ProviderToolCallBlock(
                                      new ProviderToolCall("c1", "calc", "{}"))),
                              rs))),
                  desc,
                  OpenAiResponsesConfig.defaultConfig()));
    }

    // 8. function_call 仅提供 id 而无 call_id：支持合法回退到 id
    ObjectNode fcFallbackId = MAPPER.createObjectNode();
    fcFallbackId
        .put("type", "function_call")
        .put("id", "call_from_id")
        .put("name", "calc")
        .put("arguments", "{}");
    ObjectNode pFallback = MAPPER.createObjectNode();
    pFallback.putArray("output").add(fcFallbackId);
    String calcHash =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", MAPPER.createArrayNode(), MAPPER.createArrayNode());
    ProviderReplayState rsFallback =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            calcHash,
            pFallback);
    OpenAiResponsesEncodedRequest encFallback =
        encoder.encode(
            request(
                List.of(
                    new ProviderMessage(
                        ProviderMessageRole.ASSISTANT,
                        List.of(
                            new ProviderToolCallBlock(
                                new ProviderToolCall("call_from_id", "calc", "{}"))),
                        rsFallback))),
            desc,
            OpenAiResponsesConfig.defaultConfig());
    assertEquals(
        "call_from_id",
        MAPPER.readTree(encFallback.bodyUtf8Bytes()).get("input").get(0).path("id").asText());

    // 9. durable 文本不一致：replay 文本与 durable 文本不匹配
    ObjectNode textMismatchP = MAPPER.createObjectNode();
    textMismatchP
        .putArray("output")
        .addObject()
        .put("type", "message")
        .put("role", "assistant")
        .putArray("content")
        .addObject()
        .put("type", "output_text")
        .put("text", "text_A");
    ProviderReplayState rsTextMismatch =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            textMismatchP);
    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.ASSISTANT,
                            List.of(new ProviderTextBlock("text_B")),
                            rsTextMismatch))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    // 10. durable 工具调用不一致：数量不匹配或名称不匹配
    ObjectNode toolCountMismatchP = MAPPER.createObjectNode();
    toolCountMismatchP
        .putArray("output")
        .addObject()
        .put("type", "function_call")
        .put("call_id", "c1")
        .put("name", "calc")
        .put("arguments", "{}");
    ProviderReplayState rsToolMismatch =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            validHash,
            toolCountMismatchP);
    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.ASSISTANT,
                            List.of(
                                new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}")),
                                new ProviderToolCallBlock(
                                    new ProviderToolCall("c2", "calc2", "{}"))),
                            rsToolMismatch))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.ASSISTANT,
                            List.of(
                                new ProviderToolCallBlock(
                                    new ProviderToolCall("c1", "different_tool", "{}"))),
                            rsToolMismatch))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.ASSISTANT,
                            List.of(
                                new ProviderToolCallBlock(
                                    new ProviderToolCall("c1", "calc", "{\"diff\":true}"))),
                            rsToolMismatch))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    // 11. durable assistant 消息混入非法块（如 ProviderImageBlock）
    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.ASSISTANT,
                            List.of(
                                new ProviderImageBlock("image/png", "https://example.com/a.png")),
                            rsToolMismatch))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    // 12. SYSTEM 消息包含非文本块拦截
    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.SYSTEM,
                            List.of(
                                new ProviderImageBlock(
                                    "image/png", "https://example.com/a.png"))))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    // 13. USER 消息媒体 URI 边界拦截
    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.USER,
                            List.of(
                                new ProviderImageBlock(
                                    "image/png", "data:image/png;base64_no_comma"))))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.USER,
                            List.of(
                                new ProviderImageBlock("image/png", "ftp://example.com/a.png"))))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.USER,
                            List.of(new ProviderImageBlock("image/png", "http:///a.png"))))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));

    // 13d. URI 包含非法字符（触发 URI.create 异常）
    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.USER,
                            List.of(
                                new ProviderImageBlock(
                                    "image/png", "http://example.com/invalid path with spaces"))))),
                desc,
                OpenAiResponsesConfig.defaultConfig()));
  }

  /**
   * 测试意图：验证工具调用参数 arguments 为非 Object（畸形、数组、标量、JSON null）时，在 replay 与 fallback 均严格抛出
   * INVALID_REQUEST 且无原始参数泄露。
   */
  @Test
  void test_toolArguments_strictObjectValidationInReplayAndFallback() {
    ProviderDescriptor desc = createDescriptor();
    String validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    String mismatchedHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    List<String> invalidArgs =
        List.of("12345", "\"scalar_string\"", "true", "[1, 2, 3]", "{\"unclosed\":", "null");

    for (String badArg : invalidArgs) {
      // 1. Semantic fallback: durable 中的 toolCall.argumentsJson 为非 Object
      ProviderMessage fallbackMsg =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))),
              null);
      ProviderException exFallback =
          assertThrows(
              ProviderException.class,
              () ->
                  encoder.encode(
                      request(List.of(fallbackMsg)), desc, OpenAiResponsesConfig.defaultConfig()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exFallback.kind());
      assertNull(exFallback.getCause());
      assertFalse(exFallback.getMessage().contains(badArg));

      // 2. Replay payload: function_call.arguments 为非 Object（即使 hash 不匹配也必须严格拦截）
      ObjectNode badReplayPayload = MAPPER.createObjectNode();
      badReplayPayload
          .putArray("output")
          .addObject()
          .put("type", "function_call")
          .put("call_id", "c1")
          .put("name", "calc")
          .put("arguments", badArg);

      ProviderReplayState badReplayState =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_RESPONSES,
              desc.affinity("gpt-5.4-mini"),
              mismatchedHash,
              badReplayPayload);
      ProviderMessage replayMsg =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))),
              badReplayState);
      ProviderException exReplay =
          assertThrows(
              ProviderException.class,
              () ->
                  encoder.encode(
                      request(List.of(replayMsg)), desc, OpenAiResponsesConfig.defaultConfig()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exReplay.kind());
      assertNull(exReplay.getCause());
      assertFalse(exReplay.getMessage().contains(badArg));

      // 3. Replay durable: durable 中的 toolCall.argumentsJson 为非 Object
      ObjectNode validPayload = MAPPER.createObjectNode();
      validPayload
          .putArray("output")
          .addObject()
          .put("type", "function_call")
          .put("call_id", "c1")
          .put("name", "calc")
          .put("arguments", "{}");

      ProviderReplayState replayStateWithBadDurable =
          new ProviderReplayState(
              ProviderReplayFormat.OPENAI_RESPONSES,
              desc.affinity("gpt-5.4-mini"),
              validHash,
              validPayload);
      ProviderMessage msgWithBadDurable =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))),
              replayStateWithBadDurable);
      ProviderException exDurable =
          assertThrows(
              ProviderException.class,
              () ->
                  encoder.encode(
                      request(List.of(msgWithBadDurable)),
                      desc,
                      OpenAiResponsesConfig.defaultConfig()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exDurable.kind());
      assertNull(exDurable.getCause());
      assertFalse(exDurable.getMessage().contains(badArg));
    }
  }

  @Test
  void finalBodySizeGuardEnforcedAtCallSite() throws Exception {
    // 测试意图：证明 OpenAiResponsesRequestEncoder.encode 在序列化完成后确实调用应用上限守卫。
    // 先用默认阈值编码得到真实字节长度，再以该长度验证边界通过、以少 1 字节验证超限拒绝（无昂贵大内存分配）。
    ProviderRequest request =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("size guard")))));

    int actualBytes =
        new OpenAiResponsesRequestEncoder()
            .encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
            .bodyUtf8Bytes()
            .length;

    OpenAiResponsesEncodedRequest atLimit =
        new OpenAiResponsesRequestEncoder(new RequestBodySizeGuard(actualBytes))
            .encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    assertEquals(actualBytes, atLimit.bodyUtf8Bytes().length);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                new OpenAiResponsesRequestEncoder(new RequestBodySizeGuard(actualBytes - 1))
                    .encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("request body exceeds"));
    assertFalse(ex.getMessage().contains("size guard"));
  }
}

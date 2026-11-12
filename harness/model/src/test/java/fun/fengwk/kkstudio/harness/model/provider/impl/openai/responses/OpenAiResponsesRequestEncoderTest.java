package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
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
import fun.fengwk.kkstudio.harness.model.provider.impl.RequestBodySizeGuard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** 验证 OpenAI Responses 请求编码、参数映射、媒体支持、回放与缓存控制规则。 */
class OpenAiResponsesRequestEncoderTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ModelVariant DEFAULT_VARIANT =
      new ModelVariant("default", null, PromptCacheRetention.NONE, null);

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
        "openai_test", "gpt-5.4-mini", "gpt-5.4-mini", Set.of(ModelInputModality.TEXT), true, true);
  }

  /** 非推理模型：验证顶层 instructions 编码与推理能力无关。 */
  private ModelDescriptor nonReasoningModel() {
    return new ModelDescriptor(
        "openai_test", "gpt-4.1", "gpt-4.1", Set.of(ModelInputModality.TEXT), true, false);
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
            new ModelVariant("v1", "low", PromptCacheRetention.NONE, null),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
            List.of(),
            ProviderCacheControl.none());

    OpenAiResponsesEncodedRequest encoded = encoder.encode(request, createDescriptor());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertEquals("gpt-5.4-mini", root.path("model").asString(""));
    assertTrue(root.path("stream").asBoolean(false));
    assertFalse(root.path("store").asBoolean(false));
    assertFalse(root.has("previous_response_id"));
    assertEquals(1024, root.path("max_output_tokens").asInt(0));
    assertFalse(root.has("temperature"));
    assertFalse(root.has("top_p"));
    assertFalse(root.has("top_k"));
    assertFalse(root.has("stop_sequences"));
    assertEquals("low", root.path("reasoning").path("effort").asString(""));
    assertEquals("auto", root.path("reasoning").path("summary").asString(""));
    assertEquals("reasoning.encrypted_content", root.path("include").get(0).asString(""));
  }

  @Test
  void test_reasoningEffortOffAndEnabledEncodings() throws Exception {
    // 1. effort = "none" -> emit reasoning:{effort:"none"} only; omit summary and omit include
    ProviderRequest reqOff =
        request(new ModelVariant("v1", "off", PromptCacheRetention.NONE, null));
    JsonNode rootOff = MAPPER.readTree(encoder.encode(reqOff, createDescriptor()).bodyUtf8Bytes());
    assertEquals("none", rootOff.path("reasoning").path("effort").asString(""));
    assertFalse(rootOff.path("reasoning").has("summary"));
    assertFalse(rootOff.has("include"));

    // 2. 厂商定义值原样下发，并附带 summary 与 encrypted content
    // include:["reasoning.encrypted_content"]
    ProviderRequest reqEnabled =
        request(new ModelVariant("v1", "xhigh", PromptCacheRetention.NONE, null));
    JsonNode rootEnabled =
        MAPPER.readTree(encoder.encode(reqEnabled, createDescriptor()).bodyUtf8Bytes());
    assertEquals("xhigh", rootEnabled.path("reasoning").path("effort").asString(""));
    assertEquals("auto", rootEnabled.path("reasoning").path("summary").asString(""));
    assertEquals(1, rootEnabled.path("include").size());
    assertEquals("reasoning.encrypted_content", rootEnabled.path("include").get(0).asString(""));

    // 3. effort = null -> omit reasoning and include
    ProviderRequest reqNull =
        request(new ModelVariant("v1", null, PromptCacheRetention.NONE, null));
    JsonNode rootNull =
        MAPPER.readTree(encoder.encode(reqNull, createDescriptor()).bodyUtf8Bytes());
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
            true);
    ProviderRequest request = request(logicalModel, DEFAULT_VARIANT, List.of(), List.of(), null);

    JsonNode root = MAPPER.readTree(encoder.encode(request, createDescriptor()).bodyUtf8Bytes());

    assertEquals("wire-model-id", root.path("model").asString(""));
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

    OpenAiResponsesEncodedRequest encoded = encoder.encode(request, createDescriptor());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode tools = root.get("tools");
    assertNotNull(tools);
    assertEquals(1, tools.size());
    JsonNode t = tools.get(0);
    assertEquals("function", t.path("type").asString(""));
    assertEquals("lookupWeather", t.path("name").asString(""));
    assertEquals("Lookup weather by city", t.path("description").asString(""));
    assertEquals("object", t.path("parameters").path("type").asString(""));
    assertTrue(t.path("strict").asBoolean(false));
    assertEquals(List.of("city"), requiredOf(t.path("parameters")));
    assertFalse(t.path("parameters").path("additionalProperties").asBoolean(false));
  }

  /** 验证系统指令始终编码为唯一的顶层 instructions 字符串，与模型是否具备推理能力无关。 */
  @Test
  void test_systemInstructionIsEncodedAsTopLevelInstructions() throws Exception {
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("user prompt"))));

    JsonNode reasoningRoot = encodedRoot(request(messages));
    assertEquals("Test system instruction.", reasoningRoot.path("instructions").asString(""));
    assertEquals(1, reasoningRoot.get("input").size());
    assertEquals("user", reasoningRoot.get("input").get(0).path("role").asString(""));

    JsonNode plainRoot =
        encodedRoot(request(nonReasoningModel(), DEFAULT_VARIANT, messages, List.of(), null));
    assertEquals("Test system instruction.", plainRoot.path("instructions").asString(""));
    assertEquals(1, plainRoot.get("input").size());
    assertEquals("user", plainRoot.get("input").get(0).path("role").asString(""));
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

    assertTrue(encodedTools(tool).get(0).path("strict").asBoolean(false));
    assertEquals("object", parameters.path("type").asString(""));
    assertEquals(
        List.of("query", "limit", "nullable", "filter", "meta", "tags"), requiredOf(parameters));
    assertFalse(parameters.path("additionalProperties").asBoolean(false));

    JsonNode properties = parameters.path("properties");
    // 已声明必填的属性保持原 schema。
    assertEquals("string", properties.path("query").path("type").asString(""));
    assertFalse(properties.path("query").has("anyOf"));
    // 未声明必填的标量属性改写为任何非 null 值或 null。
    assertEquals(
        "integer", properties.path("limit").path("anyOf").get(0).path("type").asString(""));
    assertEquals("null", properties.path("limit").path("anyOf").get(1).path("type").asString(""));
    // 已经允许 null 的属性不再重复包装。
    assertEquals("string", properties.path("nullable").path("type").get(0).asString(""));
    assertFalse(properties.path("nullable").has("anyOf"));
    // 已声明必填的嵌套 object：全量 required + additionalProperties=false，可缺省属性用 anyOf+null 保留语义。
    JsonNode filter = properties.path("filter");
    assertEquals(List.of("tag", "exact"), requiredOf(filter));
    assertFalse(filter.path("additionalProperties").asBoolean(false));
    assertEquals(
        "string",
        filter.path("properties").path("tag").path("anyOf").get(0).path("type").asString(""));
    assertEquals(
        "null",
        filter.path("properties").path("tag").path("anyOf").get(1).path("type").asString(""));
    assertEquals("boolean", filter.path("properties").path("exact").path("type").asString(""));
    // 可选 object 属性整体被 anyOf+null 包装，其内部 schema 同样完成归一化。
    JsonNode meta = properties.path("meta").path("anyOf").get(0);
    assertEquals("object", meta.path("type").asString(""));
    assertEquals(List.of("note"), requiredOf(meta));
    assertFalse(meta.path("additionalProperties").asBoolean(false));
    assertEquals(
        "string",
        meta.path("properties").path("note").path("anyOf").get(0).path("type").asString(""));
    assertEquals("null", properties.path("meta").path("anyOf").get(1).path("type").asString(""));
    // array items 内的 object schema 同样归一化。
    JsonNode items = properties.path("tags").path("items");
    assertEquals(List.of("name"), requiredOf(items));
    assertFalse(items.path("additionalProperties").asBoolean(false));

    // 共享输入模型不被改写，重复编码结果稳定。
    assertEquals(MAPPER.readTree(schema), MAPPER.readTree(tool.inputSchemaJson()));
    assertEquals(encodedTools(tool), encodedTools(tool));
  }

  /** 测试意图：strict 归一化必须进入组合与定义位置中的 object，但不得改写 default/enum 字面量，也不得把已有 nullable anyOf 再包一层。 */
  @Test
  void test_strictSchemaRecursesCombinatorsWithoutTouchingLiterals() throws Exception {
    String schema =
        """
        {"type":"object",
         "properties":{
           "choice":{"anyOf":[
             {"type":"object","properties":{"x":{"type":"string","default":" keep ","enum":[" keep ","x"]}}},
             {"type":"null"}]},
           "alt":{"oneOf":[{"type":"object","properties":{"y":{"type":"integer"}}}]},
           "all":{"allOf":[{"type":"object","properties":{"z":{"type":"boolean"}}}]},
           "ref":{"$ref":"#/$defs/Box"}},
         "required":["choice"],
         "$defs":{"Box":{"type":"object","properties":{"n":{"type":"string"}}}},
         "definitions":{"Legacy":{"type":"object","properties":{"m":{"type":"number"}}}}}
        """;
    ProviderToolDefinition tool = new ProviderToolDefinition("shape", "shape", schema);
    JsonNode parameters = encodedTools(tool).get(0).path("parameters");

    JsonNode choice = parameters.path("properties").path("choice");
    assertFalse(choice.has("anyOf") && choice.path("anyOf").size() == 3, "nullable anyOf 不得再包裹");
    JsonNode choiceObject = choice.path("anyOf").get(0);
    assertEquals(List.of("x"), requiredOf(choiceObject));
    assertFalse(choiceObject.path("additionalProperties").asBoolean(false));
    JsonNode x = choiceObject.path("properties").path("x").path("anyOf").get(0);
    assertEquals(" keep ", x.path("default").asString(""));
    assertEquals(" keep ", x.path("enum").get(0).asString(""));

    JsonNode oneOfObject =
        parameters.path("properties").path("alt").path("anyOf").get(0).path("oneOf").get(0);
    assertEquals(List.of("y"), requiredOf(oneOfObject));
    assertFalse(oneOfObject.path("additionalProperties").asBoolean(false));
    JsonNode allOfObject =
        parameters.path("properties").path("all").path("anyOf").get(0).path("allOf").get(0);
    assertEquals(List.of("z"), requiredOf(allOfObject));
    assertFalse(allOfObject.path("additionalProperties").asBoolean(false));

    JsonNode defsBox = parameters.path("$defs").path("Box");
    assertEquals(List.of("n"), requiredOf(defsBox));
    assertFalse(defsBox.path("additionalProperties").asBoolean(false));
    JsonNode legacy = parameters.path("definitions").path("Legacy");
    assertEquals(List.of("m"), requiredOf(legacy));
    assertFalse(legacy.path("additionalProperties").asBoolean(false));
  }

  /** 测试意图：allOf 中仅一个分支可空、oneOf 出现多个 null，以及 anyOf 被兄弟 type 禁止 null 时，都不能当成整体已可空；拿不准时安全外包一层 null。 */
  @Test
  void test_strictSchemaDoesNotTreatAmbiguousCombinatorsAsNullable() throws Exception {
    String schema =
        """
        {"type":"object","properties":{
           "mixed":{"allOf":[{"type":"string"},{"type":"null"}]},
           "exclusive":{"oneOf":[{"type":"null"},{"type":"null"}]},
           "blocked":{"type":"string","anyOf":[{"type":"string"},{"type":"null"}]}
         }}
        """;
    JsonNode properties =
        encodedTools(new ProviderToolDefinition("shape", "shape", schema))
            .get(0)
            .path("parameters")
            .path("properties");

    assertEquals("null", properties.path("mixed").path("anyOf").get(1).path("type").asString(""));
    assertEquals(
        "null",
        properties
            .path("mixed")
            .path("anyOf")
            .get(0)
            .path("allOf")
            .get(1)
            .path("type")
            .asString(""));
    assertEquals(
        "null", properties.path("exclusive").path("anyOf").get(1).path("type").asString(""));
    assertEquals(2, properties.path("exclusive").path("anyOf").get(0).path("oneOf").size());
    assertEquals("null", properties.path("blocked").path("anyOf").get(1).path("type").asString(""));
    assertEquals(
        "string", properties.path("blocked").path("anyOf").get(0).path("type").asString(""));
  }

  /** 验证 prompt_cache_key 直接取 runtime 冻结的会话 key：稳定、可复现、不同 session 不同。 */
  @Test
  void test_promptCacheKeyComesFromRuntimeSessionIdentity() throws Exception {
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("user prompt"))));
    String sessionKey = "33333333-3333-3333-3333-333333333333";
    ProviderCacheControl cacheControl =
        ProviderCacheControl.session(PromptCacheRetention.SHORT, sessionKey);
    assertEquals(sessionKey, cacheControl.key());

    ProviderRequest cachedRequest = request(messages, cacheControl);
    JsonNode first =
        MAPPER.readTree(encoder.encode(cachedRequest, createDescriptor()).bodyUtf8Bytes());
    JsonNode second =
        MAPPER.readTree(encoder.encode(cachedRequest, createDescriptor()).bodyUtf8Bytes());

    // 编码器只透出 runtime identity，绝不自造 per-attempt key。
    assertEquals(sessionKey, first.path("prompt_cache_key").asString(""));
    assertEquals(
        first.path("prompt_cache_key").asString(""), second.path("prompt_cache_key").asString(""));
    assertEquals("in_memory", first.path("prompt_cache_retention").asString(""));

    ProviderCacheControl otherSession =
        ProviderCacheControl.session(
            PromptCacheRetention.SHORT, "44444444-4444-4444-4444-444444444444");
    assertNotEquals(otherSession.key(), first.path("prompt_cache_key").asString(""));

    // runtime 未启用缓存时不得凭空发送 key。
    JsonNode none =
        MAPPER.readTree(encoder.encode(request(messages), createDescriptor()).bodyUtf8Bytes());
    assertFalse(none.has("prompt_cache_key"));
  }

  private JsonNode encodedRoot(ProviderRequest request) throws Exception {
    return MAPPER.readTree(encoder.encode(request, createDescriptor()).bodyUtf8Bytes());
  }

  private int encodedMaxOutputTokens(int maxOutputTokens) throws Exception {
    JsonNode root =
        encodedRoot(request(createModel(), DEFAULT_VARIANT, maxOutputTokens, null, null, null));
    return root.path("max_output_tokens").asInt(0);
  }

  private ArrayNode encodedTools(ProviderToolDefinition tool) throws Exception {
    JsonNode root = encodedRoot(request(List.of(), List.of(tool)));
    return (ArrayNode) root.get("tools");
  }

  private static List<String> requiredOf(JsonNode objectSchema) {
    List<String> required = new ArrayList<>();
    objectSchema.path("required").forEach(node -> required.add(node.asString("")));
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

    OpenAiResponsesEncodedRequest encoded = encoder.encode(request, createDescriptor());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode contents = root.get("input").get(0).get("content");
    assertEquals(5, contents.size());

    assertEquals("input_text", contents.get(0).path("type").asString(""));
    assertEquals("input_image", contents.get(1).path("type").asString(""));
    assertEquals("https://example.com/img.jpg", contents.get(1).path("image_url").asString(""));
    assertEquals("input_image", contents.get(2).path("type").asString(""));
    assertEquals(dataImage, contents.get(2).path("image_url").asString(""));
    assertEquals("input_file", contents.get(3).path("type").asString(""));
    assertEquals("https://example.com/doc.pdf", contents.get(3).path("file_url").asString(""));
    assertEquals("input_file", contents.get(4).path("type").asString(""));
    assertEquals(dataPdf, contents.get(4).path("file_data").asString(""));
    assertEquals("document.pdf", contents.get(4).path("filename").asString(""));
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
    assertThrows(ProviderException.class, () -> encoder.encode(reqAudio, createDescriptor()));

    // 视频
    ProviderRequest reqVideo =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderVideoBlock("video/mp4", "https://ex.com/v.mp4")))));
    assertThrows(ProviderException.class, () -> encoder.encode(reqVideo, createDescriptor()));

    // 不支持图片格式
    ProviderRequest reqTiff =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/tiff", "https://ex.com/i.tiff")))));
    assertThrows(ProviderException.class, () -> encoder.encode(reqTiff, createDescriptor()));

    // 非法 URI scheme
    ProviderRequest reqFtp =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/png", "ftp://ex.com/i.png")))));
    assertThrows(ProviderException.class, () -> encoder.encode(reqFtp, createDescriptor()));
  }

  /** 验证 cacheControl 直接映射为顶层 prompt_cache_key 与 retention 档位，不引入任何断点、策略或模式。 */
  @Test
  void test_promptCacheRetentionWireMapping() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("user prompt"))));

    // SHORT -> in_memory
    JsonNode shortRoot =
        MAPPER.readTree(
            encoder
                .encode(
                    request(
                        messages,
                        ProviderCacheControl.session(PromptCacheRetention.SHORT, "session-uuid")),
                    desc)
                .bodyUtf8Bytes());
    assertEquals("session-uuid", shortRoot.path("prompt_cache_key").asString(""));
    assertEquals("in_memory", shortRoot.path("prompt_cache_retention").asString(""));
    assertFalse(shortRoot.has("prompt_cache_options"));

    // LONG -> 24h
    JsonNode longRoot =
        MAPPER.readTree(
            encoder
                .encode(
                    request(
                        messages,
                        ProviderCacheControl.session(PromptCacheRetention.LONG, "session-uuid")),
                    desc)
                .bodyUtf8Bytes());
    assertEquals("session-uuid", longRoot.path("prompt_cache_key").asString(""));
    assertEquals("24h", longRoot.path("prompt_cache_retention").asString(""));
    assertFalse(longRoot.has("prompt_cache_options"));

    // NONE：完全不发 cache hint，明确表达禁用
    JsonNode noneRoot = MAPPER.readTree(encoder.encode(request(messages), desc).bodyUtf8Bytes());
    assertFalse(noneRoot.has("prompt_cache_key"));
    assertFalse(noneRoot.has("prompt_cache_retention"));
    assertFalse(noneRoot.has("prompt_cache_options"));
  }

  /** 验证 refusal block 的原生 contentType 可编码为 refusal，而非普通 output_text。 */
  @Test
  void test_refusalBlockPreservesNativeShape() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    ProviderTextBlock refusalBlock =
        new ProviderTextBlock(
            "I cannot help with that.",
            new ProviderBlockData(
                ProviderReplayFormat.OPENAI_RESPONSES,
                desc.affinity(createModel().modelId()),
                null,
                null,
                "msg_1",
                "message",
                "refusal",
                true,
                null,
                true,
                MAPPER.createObjectNode()));
    ProviderMessage refusal =
        new ProviderMessage(ProviderMessageRole.ASSISTANT, List.of(refusalBlock));

    JsonNode root =
        MAPPER.readTree(encoder.encode(request(List.of(refusal)), desc).bodyUtf8Bytes());
    JsonNode encodedRefusal = root.path("input").get(0).path("content").get(0);
    assertEquals("refusal", encodedRefusal.path("type").asString(""));
    assertEquals("I cannot help with that.", encodedRefusal.path("refusal").asString(""));
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

    OpenAiResponsesEncodedRequest enc = encoder.encode(req, desc);
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());
    JsonNode input = root.get("input");
    assertEquals(1, input.size());
    assertEquals("function_call_output", input.get(0).path("type").asString(""));
    assertEquals("division by zero", input.get(0).path("output").asString(""));
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
    OpenAiResponsesEncodedRequest enc1 = encoder.encode(req1, desc);
    JsonNode root1 = MAPPER.readTree(enc1.bodyUtf8Bytes());
    JsonNode input1 = root1.get("input");
    assertEquals(1, input1.size());
    assertEquals("message", input1.get(0).path("type").asString(""));
    assertEquals(
        "my thoughtsmy answer", input1.get(0).path("content").get(0).path("text").asString(""));

    // 2. ToolResult empty blocks
    ProviderRequest req2 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_empty", "tool", List.of(), false, null)))));
    OpenAiResponsesEncodedRequest enc2 = encoder.encode(req2, desc);
    JsonNode root2 = MAPPER.readTree(enc2.bodyUtf8Bytes());
    assertEquals("", root2.get("input").get(0).path("output").asString(""));

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
    OpenAiResponsesEncodedRequest enc3 = encoder.encode(req3, desc);
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
    assertThrows(ProviderException.class, () -> encoder.encode(req4, desc));
  }

  /**
   * 测试意图：验证 ProviderToolResultBlock.contents 对 ProviderJsonBlock/媒体块的支持； JSON 与 text/image/PDF
   * 按原顺序确定性编码；单文本保持简洁 string 兼容，JSON-only 编码为原始 JSON 文本； 多块或含媒体走 output content array（数组元素与用户
   * content 共用同一 wire 形态）；单块或多块中出现任何不支持 block（thinking/audio/video）均明确抛出 INVALID_REQUEST。
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
    OpenAiResponsesEncodedRequest enc1 = encoder.encode(req1, desc);
    JsonNode root1 = MAPPER.readTree(enc1.bodyUtf8Bytes());
    JsonNode output1 = root1.get("input").get(0).path("output");
    assertTrue(output1.isString());
    assertEquals("{\"temperature\":22,\"unit\":\"C\"}", output1.asString(""));

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
    OpenAiResponsesEncodedRequest enc2 = encoder.encode(req2, desc);
    JsonNode root2 = MAPPER.readTree(enc2.bodyUtf8Bytes());
    JsonNode output2 = root2.get("input").get(0).path("output");
    assertTrue(output2.isString());
    assertEquals("plain text result", output2.asString(""));

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
    OpenAiResponsesEncodedRequest enc3 = encoder.encode(req3, desc);
    JsonNode root3 = MAPPER.readTree(enc3.bodyUtf8Bytes());
    JsonNode output3 = root3.get("input").get(0).path("output");
    assertTrue(output3.isArray());
    assertEquals(1, output3.size());
    assertEquals("input_image", output3.get(0).path("type").asString(""));
    // 工具结果图片必须逐字节保留完整 base64 data URI 作为 image_url，且 detail 固定为 auto
    assertEquals(
        "data:image/png;base64,iVBORw0KGgo=", output3.get(0).path("image_url").asString(""));
    assertEquals("auto", output3.get(0).path("detail").asString(""));

    // 3.5 单 ProviderDocumentBlock（PDF）：工具结果 PDF 与用户 PDF 同形，走 input_file + file_data/filename
    ProviderRequest reqPdf =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_pdf",
                            "docs",
                            List.of(
                                new ProviderDocumentBlock(
                                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK")),
                            false,
                            null)))));
    OpenAiResponsesEncodedRequest encPdf = encoder.encode(reqPdf, desc);
    JsonNode outputPdf = MAPPER.readTree(encPdf.bodyUtf8Bytes()).get("input").get(0).path("output");
    assertTrue(outputPdf.isArray());
    assertEquals(1, outputPdf.size());
    assertEquals("input_file", outputPdf.get(0).path("type").asString(""));
    assertEquals(
        "data:application/pdf;base64,JVBERi0xLjQK",
        outputPdf.get(0).path("file_data").asString(""));
    assertEquals("document.pdf", outputPdf.get(0).path("filename").asString(""));

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
    OpenAiResponsesEncodedRequest enc4 = encoder.encode(req4, desc);
    JsonNode root4 = MAPPER.readTree(enc4.bodyUtf8Bytes());
    JsonNode output4 = root4.get("input").get(0).path("output");
    assertTrue(output4.isArray());
    assertEquals(3, output4.size());
    assertEquals("input_text", output4.get(0).path("type").asString(""));
    assertEquals("prefix text", output4.get(0).path("text").asString(""));
    assertEquals("input_text", output4.get(1).path("type").asString(""));
    assertEquals("{\"reading\":99}", output4.get(1).path("text").asString(""));
    assertEquals("input_image", output4.get(2).path("type").asString(""));
    assertEquals("https://api.openai.com/image.jpg", output4.get(2).path("image_url").asString(""));

    // 5. 不支持的 block（ThinkingBlock、AudioBlock、VideoBlock）：单块与多块均抛出 INVALID_REQUEST
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
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadThinking, desc));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exTh.kind());

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
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadAudio, desc));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exAudio.kind());

    ProviderRequest reqBadVideo =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_bad_video",
                            "tool",
                            List.of(
                                new ProviderVideoBlock(
                                    "video/mp4", "https://example.com/video.mp4")),
                            false,
                            null)))));
    ProviderException exVideo =
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadVideo, desc));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exVideo.kind());

    // 6. 多块中混入不支持 block（例如 Text + ThinkingBlock 或 Text + AudioBlock）
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
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadMulti1, desc));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exM1.kind());

    // 7. 多块混合（Json + PDF）：媒体与文本按原顺序进入同一个 output content array
    ProviderRequest reqMultiMedia =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_multi_media",
                            "docs",
                            List.of(
                                new ProviderJsonBlock("{\"pages\":2}"),
                                new ProviderDocumentBlock(
                                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK"),
                                new ProviderImageBlock(
                                    "image/png", "data:image/png;base64,iVBORw0KGgo=")),
                            false,
                            null)))));
    JsonNode outputMultiMedia =
        MAPPER
            .readTree(encoder.encode(reqMultiMedia, desc).bodyUtf8Bytes())
            .get("input")
            .get(0)
            .path("output");
    assertEquals(3, outputMultiMedia.size());
    assertEquals("input_text", outputMultiMedia.get(0).path("type").asString(""));
    assertEquals("{\"pages\":2}", outputMultiMedia.get(0).path("text").asString(""));
    assertEquals("input_file", outputMultiMedia.get(1).path("type").asString(""));
    assertEquals("input_image", outputMultiMedia.get(2).path("type").asString(""));
  }

  /** 验证同名工具的多次调用按 call_id 分别绑定：两个 function_call_output 各自携带自己的媒体，不会互相覆盖。 */
  @Test
  void test_duplicateToolNamesBindByCallId() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    ProviderRequest req =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_1",
                            "get_media",
                            List.of(
                                new ProviderTextBlock("first"),
                                new ProviderImageBlock(
                                    "image/png", "data:image/png;base64,iVBORw0KGgo=")),
                            false,
                            null))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_2",
                            "get_media",
                            List.of(
                                new ProviderTextBlock("second"),
                                new ProviderDocumentBlock(
                                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK")),
                            false,
                            null)))));

    JsonNode input = MAPPER.readTree(encoder.encode(req, desc).bodyUtf8Bytes()).get("input");
    assertEquals(2, input.size());
    assertEquals("call_1", input.get(0).path("call_id").asString(""));
    assertEquals("call_2", input.get(1).path("call_id").asString(""));
    assertEquals("first", input.get(0).path("output").get(0).path("text").asString(""));
    assertEquals("second", input.get(1).path("output").get(0).path("text").asString(""));
    assertEquals("input_image", input.get(0).path("output").get(1).path("type").asString(""));
    assertEquals("input_file", input.get(1).path("output").get(1).path("type").asString(""));
  }

  /**
   * 工具结果媒体矩阵用例：OpenAI Responses 的 {@code function_call_output.output} 数组只接受 {@code
   * input_text}、{@code input_image} 与 {@code input_file}；其余模态、非法 MIME 与非法 URI 一律 fail closed。
   */
  private record ToolResultItemCase(
      String label, ProviderContentBlock block, String expectedWireType, String expectedSource) {}

  private static Stream<Arguments> toolResultItemCases() {
    return Stream.of(
        Arguments.of(
            new ToolResultItemCase(
                "text-keeps-string-output", new ProviderTextBlock("plain"), "string", null)),
        Arguments.of(
            new ToolResultItemCase(
                "json-keeps-string-output",
                new ProviderJsonBlock("{\"pages\":2}"),
                "string",
                null)),
        Arguments.of(
            new ToolResultItemCase(
                "png-data-uri",
                new ProviderImageBlock("image/png", "data:image/png;base64,iVBORw0KGgo="),
                "input_image",
                "data:image/png;base64,iVBORw0KGgo=")),
        Arguments.of(
            new ToolResultItemCase(
                "jpeg-https-url",
                new ProviderImageBlock("image/jpeg", "https://example.com/chart.jpg"),
                "input_image",
                "https://example.com/chart.jpg")),
        Arguments.of(
            new ToolResultItemCase(
                "pdf-data-uri",
                new ProviderDocumentBlock(
                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK"),
                "input_file",
                "data:application/pdf;base64,JVBERi0xLjQK")),
        Arguments.of(
            new ToolResultItemCase(
                "pdf-https-url",
                new ProviderDocumentBlock("application/pdf", "https://example.com/spec.pdf"),
                "input_file",
                "https://example.com/spec.pdf")),
        Arguments.of(
            new ToolResultItemCase(
                "audio-rejected",
                new ProviderAudioBlock("audio/wav", "data:audio/wav;base64,UklGRg=="),
                null,
                null)),
        Arguments.of(
            new ToolResultItemCase(
                "video-rejected",
                new ProviderVideoBlock("video/mp4", "data:video/mp4;base64,AAAAIGZ0eXA="),
                null,
                null)),
        Arguments.of(
            new ToolResultItemCase(
                "thinking-rejected", new ProviderThinkingBlock("internal"), null, null)),
        Arguments.of(
            new ToolResultItemCase(
                "unsupported-image-mime-rejected",
                new ProviderImageBlock("image/tiff", "https://example.com/scan.tiff"),
                null,
                null)),
        Arguments.of(
            new ToolResultItemCase(
                "non-http-uri-rejected",
                new ProviderImageBlock("image/png", "ftp://example.com/i.png"),
                null,
                null)));
  }

  /** 验证工具结果每个块类型的 wire 结果或明确失败：媒体确实被编码，绝不被静默丢弃。 */
  @ParameterizedTest(name = "tool result item: {0}")
  @MethodSource("toolResultItemCases")
  void test_toolResultItemModalityMatrix(ToolResultItemCase itemCase) throws Exception {
    ProviderRequest req =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "call_1", "tool", List.of(itemCase.block()), false, null)))));

    if (itemCase.expectedWireType() == null) {
      ProviderException ex =
          assertThrows(ProviderException.class, () -> encoder.encode(req, createDescriptor()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNotNull(ex.getMessage());
      return;
    }

    JsonNode output =
        MAPPER
            .readTree(encoder.encode(req, createDescriptor()).bodyUtf8Bytes())
            .get("input")
            .get(0)
            .path("output");
    if ("string".equals(itemCase.expectedWireType())) {
      assertTrue(output.isString());
      return;
    }
    assertTrue(output.isArray());
    assertEquals(1, output.size());
    assertEquals(itemCase.expectedWireType(), output.get(0).path("type").asString(""));
    // 媒体内容确实进入 wire，而不是被替换为占位文本
    assertTrue(output.get(0).toString().contains(itemCase.expectedSource()));
  }

  /** 验证用户图片媒体 URI 格式错误时均被拒绝。 */
  @Test
  void test_rejectsInvalidUserImageUris() {
    ProviderDescriptor desc = createDescriptor();

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
                desc));

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
                desc));

    assertThrows(
        ProviderException.class,
        () ->
            encoder.encode(
                request(
                    List.of(
                        new ProviderMessage(
                            ProviderMessageRole.USER,
                            List.of(new ProviderImageBlock("image/png", "http:///a.png"))))),
                desc));

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
                desc));
  }

  /** 验证语义 tool call 的 arguments 必须是 JSON object，且错误信息不泄露原始参数。 */
  @Test
  void test_toolArgumentsStrictObjectValidation() {
    ProviderDescriptor desc = createDescriptor();
    List<String> invalidArgs =
        List.of("12345", "\"scalar_string\"", "true", "[1, 2, 3]", "{\"unclosed\":", "null");

    for (String badArg : invalidArgs) {
      ProviderMessage message =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", badArg))));
      ProviderException exception =
          assertThrows(
              ProviderException.class, () -> encoder.encode(request(List.of(message)), desc));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
      assertNull(exception.getCause());
      assertFalse(exception.getMessage().contains(badArg));
    }
  }

  /** cacheControl 只下发顶层 prompt_cache_key/retention，绝不改变 input item 形态，也不产生任何断点字段。 */
  @Test
  void cacheControlDoesNotAlterInputItemShape() throws Exception {
    List<ProviderMessage> history =
        List.of(
            user("same"),
            assistant("answer"),
            tool("json", List.of(new ProviderJsonBlock("{\"x\":1}"))),
            tool(
                "media",
                List.of(
                    new ProviderTextBlock("image"),
                    new ProviderImageBlock("image/png", "https://example.com/image.png"))));

    JsonNode cached = explicitRoot(history);
    JsonNode plain =
        MAPPER.readTree(encoder.encode(request(history), createDescriptor()).bodyUtf8Bytes());

    // 顶层只多出 cache key/retention，input 逐字一致
    assertEquals("key", cached.path("prompt_cache_key").asString(""));
    assertEquals("in_memory", cached.path("prompt_cache_retention").asString(""));
    assertEquals(plain.path("input"), cached.path("input"));
    assertEquals(0, cached.findValues("prompt_cache_breakpoint").size());

    // 单文本/JSON 工具结果保持简洁 string 形态；含媒体时使用 output 数组
    JsonNode input = cached.path("input");
    assertTrue(input.get(2).path("output").isString());
    assertEquals("{\"x\":1}", input.get(2).path("output").asString(""));
    assertTrue(input.get(3).path("output").isArray());
    assertEquals("input_image", input.get(3).path("output").get(1).path("type").asString(""));

    // 空工具与空历史不凭空造输入块
    JsonNode emptyTool =
        explicitRoot(List.of(tool("empty", List.of()))).path("input").get(0).path("output");
    assertTrue(emptyTool.isString());
    assertEquals("", emptyTool.asString(""));
    assertTrue(explicitRoot(List.of()).path("input").isEmpty());
    assertTrue(explicitRoot(List.of(assistant(""))).path("input").isEmpty());
  }

  private static ProviderMessage user(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage assistant(String text) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage tool(String id, List<ProviderContentBlock> contents) {
    return new ProviderMessage(
        ProviderMessageRole.TOOL,
        List.of(new ProviderToolResultBlock(id, "tool", contents, false, null)));
  }

  /** 带会话 key 的 cacheControl，模拟真实缓存启用路径。 */
  private static ProviderCacheControl explicitCache() {
    return ProviderCacheControl.session(PromptCacheRetention.SHORT, "key");
  }

  private JsonNode explicitRoot(List<ProviderMessage> messages) throws Exception {
    return MAPPER.readTree(
        encoder.encode(request(messages, explicitCache()), createDescriptor()).bodyUtf8Bytes());
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
            .encode(request, createDescriptor())
            .bodyUtf8Bytes()
            .length;

    OpenAiResponsesEncodedRequest atLimit =
        new OpenAiResponsesRequestEncoder(new RequestBodySizeGuard(actualBytes))
            .encode(request, createDescriptor());
    assertEquals(actualBytes, atLimit.bodyUtf8Bytes().length);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                new OpenAiResponsesRequestEncoder(new RequestBodySizeGuard(actualBytes - 1))
                    .encode(request, createDescriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("request body exceeds"));
    assertFalse(ex.getMessage().contains("size guard"));
  }
}

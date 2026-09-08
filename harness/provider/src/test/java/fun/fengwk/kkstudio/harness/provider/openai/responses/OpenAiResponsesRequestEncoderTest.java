package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 OpenAI Responses 请求编码、参数映射、媒体支持、回放与缓存控制规则。 */
class OpenAiResponsesRequestEncoderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelVariant DEFAULT_VARIANT =
      new ModelVariant("default", null, null, null, null, null, null, List.of(), null);

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
    ModelPricing pricing =
        new ModelPricing(
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
    return new ModelDescriptor(
        "openai_test", "gpt-5.4-mini", Set.of(ModelInputModality.TEXT), true, true, pricing);
  }

  private ProviderRequest request(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        createModel(),
        variant != null ? variant : DEFAULT_VARIANT,
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
            new ModelVariant("v1", 200, 0.5, 0.8, null, null, null, List.of(), "low"),
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
    assertEquals(0.5, root.path("temperature").asDouble());
    assertEquals(0.8, root.path("top_p").asDouble());
    assertEquals(200, root.path("max_output_tokens").asInt());
    assertEquals("low", root.path("reasoning").path("effort").asText());
    assertEquals("auto", root.path("reasoning").path("summary").asText());
  }

  /** 验证严格拒绝 unsupported penalties 和 stopSequences 与 topK。 */
  @Test
  void test_rejectsUnsupportedParameters() {
    // frequencyPenalty
    ProviderRequest req1 =
        request(new ModelVariant("v1", null, null, null, null, 0.5, null, List.of(), null));
    ProviderException ex1 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req1, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // presencePenalty
    ProviderRequest req2 =
        request(new ModelVariant("v1", null, null, null, null, null, 0.5, List.of(), null));
    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req2, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());

    // stopSequences
    ProviderRequest req3 =
        request(new ModelVariant("v1", null, null, null, null, null, null, List.of("STOP"), null));
    ProviderException ex3 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req3, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());

    // topK
    ProviderRequest req4 =
        request(new ModelVariant("v1", null, null, null, 40, null, null, List.of(), null));
    ProviderException ex4 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req4, createDescriptor(), OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex4.kind());
  }

  /** 验证工具声明映射，包含 strict: false 与参数 JSON 解析。 */
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
    assertFalse(t.path("strict").asBoolean());
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

    // 1. AUTOMATIC: 完全不发 cache hint
    OpenAiResponsesEncodedRequest encAuto =
        encoder.encode(request, desc, new OpenAiResponsesConfig(OpenAiPromptCacheMode.AUTOMATIC));
    JsonNode rootAuto = MAPPER.readTree(encAuto.bodyUtf8Bytes());
    assertFalse(rootAuto.has("prompt_cache_key"));
    assertFalse(rootAuto.has("prompt_cache_retention"));
    assertFalse(rootAuto.has("prompt_cache_options"));
    assertFalse(rootAuto.get("input").get(0).get("content").get(0).has("prompt_cache_breakpoint"));

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
        OpenAiResponsesPrefixHasher.calculateHash(MAPPER.createArrayNode(), priorInput);

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

  /** 验证同 format 但 payload 结构非法、损坏或与语义矛盾时明确抛出 INVALID_REQUEST。 */
  @Test
  void test_replayState_invalidPayloadRejected() {
    ProviderDescriptor desc = createDescriptor();

    // 缺少 output 数组
    ObjectNode malformedPayload = MAPPER.createObjectNode();
    malformedPayload.put("not_output", 123);
    ProviderReplayState replayState1 =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            desc.affinity("gpt-5.4-mini"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            malformedPayload);

    ProviderMessage msg1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("hi")), replayState1);
    ProviderRequest req1 = request(List.of(msg1));

    ProviderException ex1 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req1, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 格式不匹配
    ProviderReplayState replayStateWrongFormat =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            desc.affinity("gpt-5.4-mini"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            MAPPER.createObjectNode().put("role", "assistant"));
    ProviderMessage msg2 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("hi")),
            replayStateWrongFormat);
    ProviderRequest req2 = request(List.of(msg2));

    ProviderException ex2 =
        assertThrows(
            ProviderException.class,
            () -> encoder.encode(req2, desc, OpenAiResponsesConfig.defaultConfig()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
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
}

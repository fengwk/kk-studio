package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResourceBlock;
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
import java.util.UUID;
import java.util.stream.Stream;

/** Gemini 请求体序列化与请求映射规则测试。 */
class GeminiRequestEncoderTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ModelVariant DEFAULT_VARIANT =
      new ModelVariant("default", null, PromptCacheRetention.NONE, null);
  private final GeminiRequestEncoder encoder = new GeminiRequestEncoder();

  private static ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "google-test",
        ProviderType.GOOGLE,
        "https://generativelanguage.googleapis.com/v1beta",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
        new UUID(1L, 2L));
  }

  private static ModelDescriptor model(boolean reasoning) {
    return new ModelDescriptor(
        "google-test",
        "gemini-2.5-flash",
        "gemini-2.5-flash",
        Set.of(ModelInputModality.TEXT),
        true,
        reasoning);
  }

  /** 验证基本的用户文本消息能够正确编码为 Gemini contents wire 格式。 */
  @Test
  void encodesBasicTextRequest() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello Gemini")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    assertNotNull(encoded.bodyUtf8Bytes());

    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertTrue(json.has("contents"));
    ArrayNode contents = (ArrayNode) json.get("contents");
    assertEquals(1, contents.size());
    assertEquals("user", contents.get(0).get("role").asString(""));
    assertEquals("Hello Gemini", contents.get(0).get("parts").get(0).get("text").asString(""));
  }

  /** 验证多轮纯文本历史按 user/model/user 顺序编码，且各轮文本保持不变。 */
  @Test
  void encodesMultiTurnTextConversation() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("First message"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("First response"))),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Second message")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    ArrayNode contents = (ArrayNode) json.get("contents");

    assertEquals(3, contents.size());
    assertEquals("user", contents.get(0).path("role").asString(""));
    assertEquals("First message", contents.get(0).path("parts").get(0).path("text").asString(""));
    assertEquals("model", contents.get(1).path("role").asString(""));
    assertEquals("First response", contents.get(1).path("parts").get(0).path("text").asString(""));
    assertEquals("user", contents.get(2).path("role").asString(""));
    assertEquals("Second message", contents.get(2).path("parts").get(0).path("text").asString(""));
  }

  /** 验证请求的系统指令映射为顶层 systemInstruction 的唯一个 parts[0].text，且不进入 contents。 */
  @Test
  void encodesSingleSystemInstructionPartFromRequestInstruction() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "System rule 1.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("User query.")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertTrue(json.has("systemInstruction"));
    ArrayNode sysParts = (ArrayNode) json.get("systemInstruction").get("parts");
    assertEquals(1, sysParts.size());
    assertEquals("System rule 1.", sysParts.get(0).get("text").asString(""));

    ArrayNode contents = (ArrayNode) json.get("contents");
    assertEquals(1, contents.size());
    assertEquals("user", contents.get(0).get("role").asString(""));
    assertEquals("User query.", contents.get(0).path("parts").get(0).path("text").asString(""));
    assertFalse(contents.toString().contains("SYSTEM"));
  }

  /** 验证 generationConfig 只包含请求输出预算：采样/惩罚/停止序列已从契约移除，使用协议默认。 */
  @Test
  void encodesGenerationConfigWithRequestOutputBudgetOnly() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            new ModelVariant("v1", null, PromptCacheRetention.NONE, null),
            4321,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode gen = json.get("generationConfig");
    assertEquals(4321, gen.get("maxOutputTokens").asInt(0));
    assertFalse(gen.has("temperature"));
    assertFalse(gen.has("topP"));
    assertFalse(gen.has("topK"));
    assertFalse(gen.has("frequencyPenalty"));
    assertFalse(gen.has("presencePenalty"));
    assertFalse(gen.has("stopSequences"));
  }

  /** 验证厂商定义的 reasoning effort 原样映射到 thinkingLevel，包括大写与混合大小写（不做大小写归一化）。 */
  @Test
  void encodesProviderDefinedThinkingLevels() throws Exception {
    for (String effort : List.of("low", "medium", "high", "max", "xhigh", "HIGH", "xHigh")) {
      ModelVariant variant =
          new ModelVariant("v-" + effort, effort, PromptCacheRetention.NONE, null);
      ProviderRequest request =
          new ProviderRequest(
              model(true),
              variant,
              1024,
              "Test system instruction.",
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER, List.of(new ProviderTextBlock("Solve this")))),
              List.of(),
              ProviderCacheControl.none());

      GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
      JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

      JsonNode thinkingConfig = json.path("generationConfig").path("thinkingConfig");
      assertTrue(thinkingConfig.path("includeThoughts").asBoolean(false));
      assertEquals(effort, thinkingConfig.path("thinkingLevel").asString(""));
    }
  }

  /** 验证 off 显式关闭推理：includeThoughts=false 且 thinkingBudget=0，不发 thinkingLevel。 */
  @Test
  void encodesThinkingOffExplicitly() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            new ModelVariant("v1", "off", PromptCacheRetention.NONE, null),
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Solve")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode thinkingConfig =
        MAPPER
            .readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes())
            .path("generationConfig")
            .path("thinkingConfig");
    assertFalse(thinkingConfig.path("includeThoughts").asBoolean(false));
    assertEquals(0, thinkingConfig.path("thinkingBudget").asInt(0));
    assertFalse(thinkingConfig.has("thinkingLevel"));
  }

  /** 验证当模型不支持推理且无 reasoning effort 时，不生成 thinkingConfig。 */
  @Test
  void omitsThinkingConfig_whenReasoningDisabled() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode genConfig = json.path("generationConfig");
    assertEquals(1024, genConfig.path("maxOutputTokens").asInt(0));
    assertFalse(genConfig.has("thinkingConfig"));
  }

  /** 验证 native protocolOptions 的官方顶层字段与 generationConfig 子字段无损进入 wire，runtime 只写自己的所有权字段。 */
  @Test
  void mergesNativeProtocolOptionsWithoutLosingOfficialFields() throws Exception {
    String options =
        """
        {
          "safetySettings": [{"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_NONE"}],
          "toolConfig": {"functionCallingConfig": {"mode": "ANY"}},
          "labels": {"team": "ai"},
          "generationConfig": {
            "temperature": 0.4,
            "topP": 0.9,
            "topK": 20,
            "stopSequences": ["STOP"],
            "responseMimeType": "application/json",
            "responseSchema": {"type": "object"},
            "responseJsonSchema": {"type": "object"},
            "mediaResolution": "MEDIA_RESOLUTION_LOW",
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}},
            "imageConfig": {"aspectRatio": "1:1"},
            "routingConfig": {"autoRouting": {"model": "gemini-2.5-flash"}},
            "thinkingConfig": {"includeThoughts": true, "thinkingBudget": 512}
          }
        }
        """;
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            new ModelVariant(
                "v1", null, PromptCacheRetention.NONE, new ProviderProtocolOptions(options)),
            777,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());

    // 非 owned 官方顶层字段原样保留
    assertFalse(json.has("cachedContent"));
    assertEquals("BLOCK_NONE", json.path("safetySettings").get(0).path("threshold").asString(""));
    assertEquals(
        "ANY", json.path("toolConfig").path("functionCallingConfig").path("mode").asString(""));
    assertEquals("ai", json.path("labels").path("team").asString(""));

    // generationConfig 官方子字段保留；无 reasoningEffort 时 native thinkingConfig 亦保留
    JsonNode gen = json.path("generationConfig");
    assertEquals(0.4, gen.path("temperature").asDouble(0.0));
    assertEquals(0.9, gen.path("topP").asDouble(0.0));
    assertEquals(20, gen.path("topK").asInt(0));
    assertEquals("STOP", gen.path("stopSequences").get(0).asString(""));
    assertEquals("application/json", gen.path("responseMimeType").asString(""));
    assertTrue(gen.path("responseSchema").isObject());
    assertTrue(gen.path("responseJsonSchema").isObject());
    assertEquals("MEDIA_RESOLUTION_LOW", gen.path("mediaResolution").asString(""));
    assertEquals(
        "Kore",
        gen.path("speechConfig")
            .path("voiceConfig")
            .path("prebuiltVoiceConfig")
            .path("voiceName")
            .asString(""));
    assertEquals("1:1", gen.path("imageConfig").path("aspectRatio").asString(""));
    assertEquals(
        "gemini-2.5-flash",
        gen.path("routingConfig").path("autoRouting").path("model").asString(""));
    assertTrue(gen.path("thinkingConfig").path("includeThoughts").asBoolean(false));
    assertEquals(512, gen.path("thinkingConfig").path("thinkingBudget").asInt(0));
    // 输出预算始终由 runtime 写自己的子字段
    assertEquals(777, gen.path("maxOutputTokens").asInt(0));

    // runtime facts 仍只由请求决定
    assertEquals(
        "Test system instruction.",
        json.path("systemInstruction").path("parts").get(0).path("text").asString(""));
    assertEquals("Hi", json.path("contents").get(0).path("parts").get(0).path("text").asString(""));
  }

  /** 验证 native hosted tools 原样保留并在末尾追加一个 runtime functionDeclarations tool，且合并结果参与 prefix hash。 */
  @Test
  void mergesNativeHostedToolsWithRuntimeFunctionDeclarations() throws Exception {
    String options =
        """
        {
          "tools": [
            {"googleSearch": {}},
            {"codeExecution": {}},
            {"urlContext": {}},
            {"retrieval": {"vertexAiSearch": {"datastore": "projects/p/dataStores/d"}}}
          ]
        }
        """;
    ProviderToolDefinition tool =
        new ProviderToolDefinition("getWeather", "Get weather", "{\"type\":\"object\"}");
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            new ModelVariant(
                "v1", null, PromptCacheRetention.NONE, new ProviderProtocolOptions(options)),
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Weather?")))),
            List.of(tool),
            ProviderCacheControl.none());

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    ArrayNode tools = (ArrayNode) json.get("tools");
    assertEquals(5, tools.size());
    assertTrue(tools.get(0).has("googleSearch"));
    assertTrue(tools.get(1).has("codeExecution"));
    assertTrue(tools.get(2).has("urlContext"));
    assertEquals(
        "projects/p/dataStores/d",
        tools.get(3).path("retrieval").path("vertexAiSearch").path("datastore").asString(""));
    assertEquals(
        "getWeather", tools.get(4).path("functionDeclarations").get(0).path("name").asString(""));

    // hosted tools 属于请求前缀：同一请求去掉 native tools 后 wire tools 只剩 runtime functionDeclarations
    ProviderRequest withoutNativeTools =
        new ProviderRequest(
            model(false),
            new ModelVariant("v1", null, PromptCacheRetention.NONE, null),
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Weather?")))),
            List.of(tool),
            ProviderCacheControl.none());
    ArrayNode toolsWithoutNative =
        (ArrayNode)
            MAPPER
                .readTree(encoder.encode(withoutNativeTools, descriptor()).bodyUtf8Bytes())
                .get("tools");
    assertEquals(1, toolsWithoutNative.size());
    assertTrue(toolsWithoutNative.get(0).has("functionDeclarations"));
    assertFalse(toolsWithoutNative.get(0).has("googleSearch"));
  }

  /** 验证 runtime 未声明 function 时，native hosted tools 仍原样保留。 */
  @Test
  void keepsNativeHostedToolsWhenRuntimeDeclaresNoFunctions() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            new ModelVariant(
                "v1",
                null,
                PromptCacheRetention.NONE,
                new ProviderProtocolOptions("{\"tools\":[{\"googleSearch\":{}}]}")),
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode tools =
        MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes()).path("tools");
    assertEquals(1, tools.size());
    assertTrue(tools.get(0).has("googleSearch"));
  }

  /** 测试意图：单候选与六种 hosted capability 保留原生声明，运行时仍独占输出预算。 */
  @Test
  void preservesAllHostedCapabilitiesAndSingleCandidate() throws Exception {
    String options =
        "{\"generationConfig\":{\"candidateCount\":1},\"tools\":["
            + "{\"googleSearch\":{}},{\"googleSearchRetrieval\":{}},{\"retrieval\":{}},"
            + "{\"codeExecution\":{}},{\"urlContext\":{}},{\"googleMaps\":{}}]}";
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            new ModelVariant(
                "v1", null, PromptCacheRetention.NONE, new ProviderProtocolOptions(options)),
            1024,
            "system",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());
    JsonNode wire = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    assertEquals(MAPPER.readTree(options).path("tools"), wire.path("tools"));
    assertEquals(1, wire.path("generationConfig").path("candidateCount").intValue());
    assertEquals(1024, wire.path("generationConfig").path("maxOutputTokens").intValue());
  }

  /** 测试意图：原始 1.0/1e0 规范化后等价于单候选 1；非 1 小数仍在拒绝测试中覆盖。 */
  @Test
  void acceptsNormalizedSingleCandidateNumbers() throws Exception {
    for (String numeric : List.of("1.0", "1e0")) {
      ModelVariant variant =
          new ModelVariant(
              "v1",
              null,
              PromptCacheRetention.NONE,
              new ProviderProtocolOptions(
                  "{\"generationConfig\":{\"candidateCount\":" + numeric + "}}"));
      assertEquals(
          "{\"generationConfig\":{\"candidateCount\":1}}",
          variant.protocolOptions().canonicalJson());
      ProviderRequest request =
          new ProviderRequest(
              model(false),
              variant,
              1024,
              "system",
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
              List.of(),
              ProviderCacheControl.none());
      JsonNode root = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
      assertEquals(1, root.path("generationConfig").path("candidateCount").intValue());
      assertEquals(1024, root.path("generationConfig").path("maxOutputTokens").intValue());
    }
  }

  /** 测试意图：原生客户端工具和多候选无法被 runtime 执行/收敛，在发请求之前阻断。 */
  @Test
  void rejectsUnsupportedNativeExecutionOptions() {
    for (String option :
        List.of(
            "{\"generationConfig\":{\"candidateCount\":null}}",
            "{\"generationConfig\":{\"candidateCount\":true}}",
            "{\"generationConfig\":{\"candidateCount\":1.5}}",
            "{\"generationConfig\":{\"candidateCount\":\"1\"}}",
            "{\"generationConfig\":{\"candidateCount\":0}}",
            "{\"generationConfig\":{\"candidateCount\":2}}",
            "{\"generationConfig\":{\"candidateCount\":99999999999999999999999}}",
            "{\"tools\":[null]}",
            "{\"tools\":[{}]}",
            "{\"tools\":[{\"googleSearch\":null}]}",
            "{\"tools\":[{\"googleSearch\":{},\"urlContext\":{}}]}",
            "{\"tools\":[{\"functionDeclarations\":[{\"name\":\"secret\"}]}]}",
            "{\"tools\":[{\"computerUse\":{}}]}")) {
      assertConflictingOptionsRejected(option, null, "secret");
    }
  }

  /**
   * 验证 native options 覆盖 runtime-owned 事实或与 variant reasoningEffort 冲突时明确 INVALID_REQUEST，且不回显
   * value。
   */
  @Test
  void rejectsNativeOptionsConflictingWithRuntimeOwnedFacts() {
    String secret = "AIzaSySecretValue";

    // 1. contents / systemInstruction 是 runtime facts
    assertConflictingOptionsRejected(
        "{\"contents\":[{\"text\":\"" + secret + "\"}]}", null, secret);
    assertConflictingOptionsRejected(
        "{\"systemInstruction\":{\"text\":\"" + secret + "\"}}", null, secret);
    // 自动缓存由 runtime 独占，任何 native cachedContent（包括 null）均冲突。
    assertConflictingOptionsRejected("{\"cachedContent\":\"" + secret + "\"}", null, secret);
    assertConflictingOptionsRejected("{\"cachedContent\":null}", null, secret);

    // 2. maxOutputTokens 始终 runtime-owned
    assertConflictingOptionsRejected(
        "{\"generationConfig\":{\"maxOutputTokens\":123}}", null, "123");

    // 3. variant 声明 reasoningEffort 时 thinkingConfig 由 runtime 独占
    assertConflictingOptionsRejected(
        "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":64}}}", "high", "64");
  }

  /** 验证 native options 中 generationConfig/tools 的形态必须分别是 object/array。 */
  @Test
  void rejectsNativeOptionsWithInvalidContainerTypes() {
    assertConflictingOptionsRejected("{\"generationConfig\":[1,2]}", null, "1");
    assertConflictingOptionsRejected(
        "{\"generationConfig\":\"not_an_object\"}", null, "not_an_object");
    assertConflictingOptionsRejected("{\"tools\":{\"googleSearch\":{}}}", null, "googleSearch");
  }

  /** 断言指定 native options 被拒绝为 INVALID_REQUEST，且异常消息绝不回显 value。 */
  private void assertConflictingOptionsRejected(
      String options, String reasoningEffort, String sensitiveValue) {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            new ModelVariant(
                "v1",
                reasoningEffort,
                PromptCacheRetention.NONE,
                new ProviderProtocolOptions(options)),
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertFalse(ex.getMessage().contains(sensitiveValue), "error must not echo the native value");
  }

  /** 验证任何非 NONE retention 的显式 cacheControl 被明确拒绝：Gemini 仅支持服务端自动缓存，不发送任何 hint。 */
  @Test
  void rejectsExplicitCacheControl() {
    for (ProviderCacheControl explicitCache :
        List.of(
            ProviderCacheControl.session(PromptCacheRetention.SHORT, "session-1"),
            ProviderCacheControl.session(PromptCacheRetention.LONG, "session-2"))) {
      ProviderRequest request =
          new ProviderRequest(
              model(false),
              DEFAULT_VARIANT,
              1024,
              "Test system instruction.",
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
              List.of(),
              explicitCache);

      ProviderException ex =
          assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    }
  }

  /** 验证结构化工具声明被正确编码为 tools:[{functionDeclarations:[...]}]，且 JSON Schema 保持保真。 */
  @Test
  void encodesToolsAndFunctionDeclarations_preservesSchema() throws Exception {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "location": { "type": "string" },
            "unit": { "type": "string", "enum": ["celsius", "fahrenheit"] }
          },
          "required": ["location"]
        }
        """;
    ProviderToolDefinition tool =
        new ProviderToolDefinition("getWeather", "Get current weather", schema);

    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Weather?")))),
            List.of(tool),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertTrue(json.has("tools"));
    ArrayNode fnDecls = (ArrayNode) json.get("tools").get(0).get("functionDeclarations");
    assertEquals(1, fnDecls.size());
    JsonNode fn = fnDecls.get(0);
    assertEquals("getWeather", fn.get("name").asString(""));
    assertEquals("Get current weather", fn.get("description").asString(""));
    assertEquals("object", fn.get("parameters").get("type").asString(""));
    assertTrue(fn.get("parameters").get("properties").has("location"));
  }

  /** 验证非法的工具 schema 抛出 INVALID_REQUEST。 */
  @Test
  void rejectsMalformedToolInputSchemaJson() {
    ProviderToolDefinition badTool = new ProviderToolDefinition("bad", "desc", "not a json");
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(badTool),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证 data URI 图片与音频被无损编码为 inlineData。 */
  @Test
  void encodesUserDataUriToInlineData() throws Exception {
    String dataUri =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/png", dataUri)))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode part = json.get("contents").get(0).get("parts").get(0);
    assertTrue(part.has("inlineData"));
    assertEquals("image/png", part.get("inlineData").get("mimeType").asString(""));
    // base64 载荷必须逐字节保留（去除 data URI 前缀后原样进入 inlineData.data）
    assertEquals(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        part.get("inlineData").get("data").asString(""));
  }

  /** 验证 DOCUMENT/PDF 的 base64 data URI 与 image/audio/video 一样被编码为 inlineData，载荷逐字节保留。 */
  @Test
  void encodesUserPdfDataUriToInlineDataPreservingPayload() throws Exception {
    String pdfBase64 = "data:application/pdf;base64,JVBERi0xLjQgZHVtbXkgcGRmIGNvbnRlbnQ=";
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(
                        new ProviderTextBlock("inspect:"),
                        new ProviderDocumentBlock("application/pdf", pdfBase64)))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) json.path("contents").get(0).path("parts");

    assertEquals(2, parts.size());
    assertEquals("inspect:", parts.get(0).path("text").asString(""));
    assertTrue(parts.get(1).has("inlineData"));
    assertEquals("application/pdf", parts.get(1).path("inlineData").path("mimeType").asString(""));
    assertEquals(
        "JVBERi0xLjQgZHVtbXkgcGRmIGNvbnRlbnQ=",
        parts.get(1).path("inlineData").path("data").asString(""));
    // base64 文档不得退化为 fileData，也不得保留 data: 前缀
    assertFalse(parts.get(1).has("fileData"));
    assertFalse(parts.get(1).path("inlineData").path("data").asString("").startsWith("data:"));
  }

  /** 验证图片、音频和视频 data URI 的 MIME 类型均从 URI 无损透传。 */
  @Test
  void encodesDataUriMediaWithMimeTypes() throws Exception {
    List<ProviderContentBlock> media =
        List.of(
            new ProviderImageBlock("application/octet-stream", "data:image/gif;base64,QQ=="),
            new ProviderImageBlock("application/octet-stream", "data:image/webp;base64,QQ=="),
            new ProviderImageBlock("application/octet-stream", "data:image/svg+xml;base64,QQ=="),
            new ProviderAudioBlock("application/octet-stream", "data:audio/wav;base64,QQ=="),
            new ProviderAudioBlock("application/octet-stream", "data:audio/ogg;base64,QQ=="),
            new ProviderAudioBlock("application/octet-stream", "data:audio/flac;base64,QQ=="),
            new ProviderVideoBlock("video/unknown", "data:video/mp4;base64,QQ=="),
            new ProviderVideoBlock("video/unknown", "data:video/webm;base64,QQ=="),
            new ProviderVideoBlock("video/unknown", "data:video/mpeg;base64,QQ=="));
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(new ProviderMessage(ProviderMessageRole.USER, media)),
            List.of(),
            ProviderCacheControl.none());

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) json.path("contents").get(0).path("parts");
    List<String> expectedMimeTypes =
        List.of(
            "image/gif",
            "image/webp",
            "image/svg+xml",
            "audio/wav",
            "audio/ogg",
            "audio/flac",
            "video/mp4",
            "video/webm",
            "video/mpeg");

    assertEquals(expectedMimeTypes.size(), parts.size());
    for (int i = 0; i < expectedMimeTypes.size(); i++) {
      assertEquals(
          expectedMimeTypes.get(i), parts.get(i).path("inlineData").path("mimeType").asString(""));
      assertEquals("QQ==", parts.get(i).path("inlineData").path("data").asString(""));
    }
  }

  /**
   * 测试意图：普通用户内容（image/audio/video/document）与工具结果统一只接受严格 Base64 data URI——无 base64 标记的 percent/普通
   * data、非法 Base64、空载荷与缺失 media type 都在编码期以 INVALID_REQUEST 拒绝，绝不把无效载荷发往上游。 合法 inline 的逐字节保留由
   * {@code encodesUserDataUriToInlineData}、 {@code
   * encodesUserPdfDataUriToInlineDataPreservingPayload} 与 {@code encodesDataUriMediaWithMimeTypes}
   * 覆盖。
   */
  @Test
  void rejectsNonStrictBase64DataUriForAllUserMediaModalities() {
    List<String> invalidSources =
        List.of(
            "data:image/png,abc",
            "data:image/png,%89PNG%0D",
            "data:image/png;base64,%%%",
            "data:image/png;base64,",
            "data:;base64,QQ==");

    for (String source : invalidSources) {
      List<ProviderContentBlock> blocks =
          List.of(
              new ProviderImageBlock("image/png", source),
              new ProviderAudioBlock("audio/wav", source),
              new ProviderVideoBlock("video/mp4", source),
              new ProviderDocumentBlock("application/pdf", source));
      for (ProviderContentBlock block : blocks) {
        ProviderRequest request =
            new ProviderRequest(
                model(false),
                DEFAULT_VARIANT,
                1024,
                "Test system instruction.",
                List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(block))),
                List.of(),
                ProviderCacheControl.none());
        ProviderException ex =
            assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
        assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
        assertEquals(
            "media source must be a base64 data URI with a non-empty decodable payload",
            ex.getMessage());
      }
    }
  }

  /** 验证 URL 形式的媒体资源（图片、音频、视频、PDF）直接编码为 fileData，绝不执行下载。 */
  @Test
  void encodesUserUrlToFileData_imageAudioVideoPdf() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(
                        new ProviderImageBlock("image/jpeg", "https://example.com/pic.jpg"),
                        new ProviderAudioBlock("audio/mp3", "https://example.com/audio.mp3"),
                        new ProviderVideoBlock("video/mp4", "https://example.com/video.mp4"),
                        new ProviderDocumentBlock("application/pdf", "gs://bucket/doc.pdf")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    ArrayNode parts = (ArrayNode) json.get("contents").get(0).get("parts");
    assertEquals(4, parts.size());
    assertEquals("fileData", parts.get(0).propertyNames().iterator().next());
    assertEquals(
        "https://example.com/pic.jpg", parts.get(0).get("fileData").get("fileUri").asString(""));
    assertEquals("audio/mp3", parts.get(1).get("fileData").get("mimeType").asString(""));
    assertEquals("video/mp4", parts.get(2).get("fileData").get("mimeType").asString(""));
    assertEquals("gs://bucket/doc.pdf", parts.get(3).get("fileData").get("fileUri").asString(""));
  }

  /** 验证未物化的持久化资源块拒绝并报错，不静默丢弃。 */
  @Test
  void rejectsUnsupportedOrUnmaterializedResourceBlock() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderResourceBlock(UUID.randomUUID(), "file.txt", "preview")))),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证连续的 TOOL 结果消息被合并在同一个 role='user' 的 content 中，按顺序映射为 functionResponse。 */
  @Test
  void encodesToolResultBlocks_mergesConsecutiveToolMessagesToUserRole() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Call tools"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{\"a\":1}")),
                        new ProviderToolCallBlock(new ProviderToolCall("c2", "fn2", "{\"b\":2}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1", "fn1", List.of(new ProviderTextBlock("res1")), false, "{}"))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c2", "fn2", List.of(new ProviderTextBlock("res2")), false, "{}")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    ArrayNode contents = (ArrayNode) json.get("contents");
    // contents 应该交替为 3 个：user -> model -> user (合并了 2 个 tool results)
    assertEquals(3, contents.size());
    assertEquals("user", contents.get(0).get("role").asString(""));
    assertEquals("model", contents.get(1).get("role").asString(""));
    assertEquals("user", contents.get(2).get("role").asString(""));

    ArrayNode toolParts = (ArrayNode) contents.get(2).get("parts");
    assertEquals(2, toolParts.size());
    assertEquals("fn1", toolParts.get(0).get("functionResponse").get("name").asString(""));
    assertEquals("c1", toolParts.get(0).get("functionResponse").get("id").asString(""));
    assertEquals(
        "res1",
        toolParts.get(0).get("functionResponse").get("response").get("result").asString(""));
    assertEquals("fn2", toolParts.get(1).get("functionResponse").get("name").asString(""));
  }

  /** 验证无 replayState 时，ASSISTANT 消息按照 contents 原始顺序进行语义 fallback，不按类别重排。 */
  @Test
  void encodesAssistantMessage_fallsBackToOrderedContentsWhenNoReplay() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderThinkingBlock("Deep thought"),
                        new ProviderTextBlock("Answer text"),
                        new ProviderToolCallBlock(new ProviderToolCall("c1", "toolA", "{}"))))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    ArrayNode parts = (ArrayNode) json.get("contents").get(1).get("parts");
    assertEquals(3, parts.size());
    // 原顺序保持：0 is thought, 1 is text, 2 is functionCall
    assertEquals("Deep thought", parts.get(0).get("text").asString(""));
    assertFalse(parts.get(0).has("thought"));
    assertEquals("Answer text", parts.get(1).get("text").asString(""));
    assertFalse(parts.get(1).has("thought"));
    assertEquals("toolA", parts.get(2).get("functionCall").get("name").asString(""));
  }

  @Test
  void replaysGeminiBlocksOnlyForMatchingAffinityAndDropsSignaturesAcrossModels() throws Exception {
    ProviderReplayAffinity affinity = descriptor().affinity("gemini-2.5-flash");
    ObjectNode functionCallFields = MAPPER.createObjectNode();
    functionCallFields.putObject("functionCall").put("futureCallField", "call-extra");
    functionCallFields.put("futurePartField", "part-extra");
    List<ProviderContentBlock> blocks =
        List.of(
            new ProviderThinkingBlock(
                "private thought",
                new ProviderBlockData(
                    ProviderReplayFormat.GEMINI_CONTENT,
                    affinity,
                    "thought-signature",
                    null,
                    null,
                    null,
                    null,
                    false,
                    true,
                    MAPPER.createObjectNode())),
            new ProviderTextBlock(
                "answer",
                new ProviderBlockData(
                    ProviderReplayFormat.GEMINI_CONTENT,
                    affinity,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    MAPPER.createObjectNode())),
            new ProviderToolCallBlock(
                new ProviderToolCall("call-1", "lookup", "{}"),
                new ProviderBlockData(
                    ProviderReplayFormat.GEMINI_CONTENT,
                    affinity,
                    "tool-signature",
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    functionCallFields)));

    ProviderRequest sameModelRequest =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks)),
            List.of(),
            ProviderCacheControl.none());
    ArrayNode sameModelParts =
        (ArrayNode)
            MAPPER
                .readTree(encoder.encode(sameModelRequest, descriptor()).bodyUtf8Bytes())
                .path("contents")
                .get(1)
                .path("parts");
    assertEquals(3, sameModelParts.size());
    assertEquals("thought-signature", sameModelParts.get(0).path("thoughtSignature").asString(""));
    assertEquals("answer", sameModelParts.get(1).path("text").asString(""));
    assertEquals(
        "call-extra",
        sameModelParts.get(2).path("functionCall").path("futureCallField").asString(""));
    assertEquals("part-extra", sameModelParts.get(2).path("futurePartField").asString(""));
    assertEquals("tool-signature", sameModelParts.get(2).path("thoughtSignature").asString(""));

    ProviderReplayAffinity otherModel = descriptor().affinity("gemini-2.5-pro");
    List<ProviderContentBlock> crossModelBlocks =
        List.of(
            new ProviderThinkingBlock(
                "private thought",
                new ProviderBlockData(
                    ProviderReplayFormat.GEMINI_CONTENT,
                    otherModel,
                    "thought-signature",
                    null,
                    null,
                    null,
                    null,
                    false,
                    true,
                    MAPPER.createObjectNode())),
            new ProviderToolCallBlock(
                new ProviderToolCall("call-1", "lookup", "{}"),
                new ProviderBlockData(
                    ProviderReplayFormat.GEMINI_CONTENT,
                    otherModel,
                    "tool-signature",
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    MAPPER.createObjectNode())));
    ProviderRequest crossModelRequest =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(ProviderMessageRole.ASSISTANT, crossModelBlocks)),
            List.of(),
            ProviderCacheControl.none());
    ArrayNode crossModelParts =
        (ArrayNode)
            MAPPER
                .readTree(encoder.encode(crossModelRequest, descriptor()).bodyUtf8Bytes())
                .path("contents")
                .get(1)
                .path("parts");
    assertEquals(2, crossModelParts.size());
    assertEquals("private thought", crossModelParts.get(0).path("text").asString(""));
    assertFalse(crossModelParts.get(0).has("thought"));
    assertFalse(crossModelParts.get(0).has("thoughtSignature"));
    assertEquals("lookup", crossModelParts.get(1).path("functionCall").path("name").asString(""));
    assertFalse(crossModelParts.get(1).has("thoughtSignature"));
  }

  /** 意图：匹配 affinity 时，typed text 与 opaque inlineData block 按原顺序保留未知字段。 */
  @Test
  void replaysOpaqueUnknownBlocksAndFieldsVerbatim() throws Exception {
    ProviderReplayAffinity affinity = descriptor().affinity("gemini-2.5-flash");
    ObjectNode textFields = MAPPER.createObjectNode();
    textFields.putObject("futurePartField").put("trace", "t-1");
    ObjectNode inlineFields = MAPPER.createObjectNode();
    inlineFields.putObject("inlineData").put("mimeType", "image/png").put("data", "QQ==");
    List<ProviderContentBlock> blocks =
        List.of(
            new ProviderTextBlock("answer", geminiBlockData(affinity, null, null, textFields)),
            new ProviderOpaqueBlock(
                "gemini_part", geminiBlockData(affinity, null, null, inlineFields)));
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q1"))),
                new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q2")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    ArrayNode wireParts = (ArrayNode) json.get("contents").get(1).get("parts");
    assertEquals(2, wireParts.size());
    assertEquals("answer", wireParts.get(0).path("text").asString(""));
    assertEquals("t-1", wireParts.get(0).path("futurePartField").path("trace").asString(""));
    assertEquals("image/png", wireParts.get(1).path("inlineData").path("mimeType").asString(""));
    assertEquals("QQ==", wireParts.get(1).path("inlineData").path("data").asString(""));
  }

  /** 验证未声明 reasoningEffort（null 协议默认）时完全不生成 thinkingConfig。 */
  @Test
  void omitsThinkingConfig_whenReasoningEffortAbsent() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertFalse(json.path("generationConfig").has("thinkingConfig"));
  }

  /** 验证空的 messages 列表抛出 INVALID_REQUEST。 */
  @Test
  void rejectsEmptyMessages() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证单 JSON object 的工具结果直接作为 functionResponse.response 的对象值。 */
  @Test
  void encodesToolResultBlocks_singleJsonObject_setsDirectlyAsResponseObject() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(new ProviderJsonBlock("{\"score\":98,\"status\":\"ok\"}")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode resp =
        json.get("contents").get(2).get("parts").get(0).get("functionResponse").get("response");
    assertEquals(98, resp.get("score").asInt(0));
    assertEquals("ok", resp.get("status").asString(""));
    assertFalse(resp.has("result"));
  }

  /** 验证单非 object JSON（如 JSON array 或 primitive）的工具结果封装在 response.result 中。 */
  @Test
  void encodesToolResultBlocks_singleNonObjectJson_placesInResultField() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(new ProviderJsonBlock("[10, 20, 30]")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode resp =
        json.get("contents").get(2).get("parts").get(0).get("functionResponse").get("response");
    assertTrue(resp.has("result"));
    assertTrue(resp.get("result").isArray());
    assertEquals(3, resp.get("result").size());
    assertEquals(20, resp.get("result").get(1).asInt(0));
  }

  /** 验证包含多个 text 与 JSON 块的工具结果按原有顺序组织在有序 results 数组中。 */
  @Test
  void encodesToolResultBlocks_multipleTextAndJson_usesOrderedResultsArray() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(
                                new ProviderTextBlock("header"),
                                new ProviderJsonBlock("{\"count\":5}"),
                                new ProviderTextBlock("footer")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode resp =
        json.get("contents").get(2).get("parts").get(0).get("functionResponse").get("response");
    assertTrue(resp.has("results"));
    ArrayNode results = (ArrayNode) resp.get("results");
    assertEquals(3, results.size());
    assertEquals("header", results.get(0).asString(""));
    assertEquals(5, results.get(1).get("count").asInt(0));
    assertEquals("footer", results.get(2).asString(""));
  }

  /** 验证 error=true 时使用明确的 error wrapper，且单 JSON object 原有的 error 字段不被覆盖。 */
  @Test
  void encodesToolResultBlocks_errorTrue_usesErrorWrapperWithoutOverwritingOriginalJson()
      throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(
                                new ProviderJsonBlock(
                                    "{\"error\":\"inner_failure_detail\",\"code\":404}")),
                            true,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode resp =
        json.get("contents").get(2).get("parts").get(0).get("functionResponse").get("response");

    // 验证外层包含明确的 error: true wrapper
    assertTrue(resp.get("error").asBoolean(false));
    // 验证原始 JSON 作为 result 完整保留，且其内嵌的 "error" 字段未被破坏或覆盖
    JsonNode innerResult = resp.get("result");
    assertEquals("inner_failure_detail", innerResult.get("error").asString(""));
    assertEquals(404, innerResult.get("code").asInt(0));
  }

  /**
   * 工具结果媒体矩阵用例：Gemini 只把保守白名单（image/jpeg、image/png、image/webp、application/pdf）的 Base64 data URI 内联到
   * {@code functionResponse.parts[].inlineData}，其余模态与来源形态一律 fail closed。
   */
  private record ToolResultMediaCase(
      String label,
      ProviderContentBlock block,
      boolean supported,
      String expectedMimeType,
      String expectedBase64) {}

  private static Stream<Arguments> toolResultMediaCases() {
    return Stream.of(
        Arguments.of(
            new ToolResultMediaCase(
                "jpeg-inline",
                new ProviderImageBlock("image/jpeg", "data:image/jpeg;base64,/9j/4AAQ"),
                true,
                "image/jpeg",
                "/9j/4AAQ")),
        Arguments.of(
            new ToolResultMediaCase(
                "png-inline",
                new ProviderImageBlock("image/png", "data:image/png;base64,iVBORw0KGgo="),
                true,
                "image/png",
                "iVBORw0KGgo=")),
        Arguments.of(
            new ToolResultMediaCase(
                "webp-inline-uppercase-declared-mime",
                new ProviderImageBlock("IMAGE/WEBP", "data:image/webp;base64,UklGRg=="),
                true,
                "image/webp",
                "UklGRg==")),
        Arguments.of(
            new ToolResultMediaCase(
                "pdf-inline",
                new ProviderDocumentBlock(
                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK"),
                true,
                "application/pdf",
                "JVBERi0xLjQK")),
        // 未在白名单内的图片 MIME：保守拒绝，不扩大到全部 image/*
        Arguments.of(
            new ToolResultMediaCase(
                "gif-inline-not-in-conservative-allowlist",
                new ProviderImageBlock("image/gif", "data:image/gif;base64,R0lGODlh"),
                false,
                null,
                null)),
        // 音频与视频：Gemini Developer API 的工具结果不承载这两种模态
        Arguments.of(
            new ToolResultMediaCase(
                "audio-inline-rejected",
                new ProviderAudioBlock("audio/wav", "data:audio/wav;base64,UklGRg=="),
                false,
                null,
                null)),
        Arguments.of(
            new ToolResultMediaCase(
                "video-inline-rejected",
                new ProviderVideoBlock("video/mp4", "data:video/mp4;base64,AAAAIGZ0eXA="),
                false,
                null,
                null)),
        // 非 data URI 来源：v1beta FunctionResponsePart 只有 inlineData，没有 fileData
        Arguments.of(
            new ToolResultMediaCase(
                "image-https-url-rejected",
                new ProviderImageBlock("image/png", "https://example.com/chart.png"),
                false,
                null,
                null)),
        Arguments.of(
            new ToolResultMediaCase(
                "pdf-gs-url-rejected",
                new ProviderDocumentBlock("application/pdf", "gs://bucket/spec.pdf"),
                false,
                null,
                null)),
        // data URI 形态非法：MIME 与声明不一致、缺 base64 标记、空载荷
        Arguments.of(
            new ToolResultMediaCase(
                "data-uri-mime-mismatch-rejected",
                new ProviderImageBlock("image/png", "data:image/jpeg;base64,/9j/4AAQ"),
                false,
                null,
                null)),
        Arguments.of(
            new ToolResultMediaCase(
                "data-uri-without-base64-marker-rejected",
                new ProviderImageBlock("image/png", "data:image/png,abc"),
                false,
                null,
                null)),
        Arguments.of(
            new ToolResultMediaCase(
                "data-uri-empty-payload-rejected",
                new ProviderImageBlock("image/png", "data:image/png;base64,"),
                false,
                null,
                null)),
        Arguments.of(
            new ToolResultMediaCase(
                "data-uri-invalid-base64-rejected",
                new ProviderImageBlock("image/png", "data:image/png;base64,%%%"),
                false,
                null,
                null)));
  }

  /** 测试意图：ProviderMessage 构造期已禁止 TOOL 携带非 tool-result，边界在消息模型而不是编码器静默丢弃。 */
  @Test
  void toolMessageRejectsNonToolResultAtConstruction() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderMessage(
                ProviderMessageRole.TOOL, List.of(new ProviderTextBlock("not a tool result"))));
  }

  /** 验证工具结果媒体只以内联 part 挂在 functionResponse 内，绝不作为外层 Content.parts 的同级 part。 */
  @ParameterizedTest(name = "tool result media: {0}")
  @MethodSource("toolResultMediaCases")
  void encodesToolResultBlocks_mediaModalityMatrix(ToolResultMediaCase mediaCase) throws Exception {
    ProviderRequest request =
        toolResultRequest(
            List.of(new ProviderTextBlock("Here is the result:"), mediaCase.block()),
            "c1",
            "get_media");

    if (!mediaCase.supported()) {
      ProviderException ex =
          assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNotNull(ex.getMessage());
      return;
    }

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) json.path("contents").get(2).path("parts");

    // 媒体不是同级 part：该 user content 只有 functionResponse 一个 part
    assertEquals(1, parts.size());
    JsonNode functionResponse = parts.get(0).path("functionResponse");
    assertEquals("get_media", functionResponse.path("name").asString(""));
    assertEquals("c1", functionResponse.path("id").asString(""));
    assertEquals(
        "Here is the result:", functionResponse.path("response").path("result").asString(""));

    JsonNode nested = functionResponse.path("parts");
    assertEquals(1, nested.size());
    assertEquals(
        mediaCase.expectedMimeType(),
        nested.get(0).path("inlineData").path("mimeType").asString(""));
    // 载荷逐字节保留，且不引入官方 v1beta schema 之外的命名字段
    assertEquals(
        mediaCase.expectedBase64(), nested.get(0).path("inlineData").path("data").asString(""));
    assertFalse(nested.get(0).path("inlineData").has("displayName"));
  }

  /** 验证同一 functionResponse 内 text 与多个媒体保持顺序，且 tool 结果的媒体载荷互不串位。 */
  @Test
  void encodesToolResultBlocks_textAndMultipleMediaStayOrderedInsideFunctionResponse()
      throws Exception {
    ProviderRequest request =
        toolResultRequest(
            List.of(
                new ProviderTextBlock("chart and spec:"),
                new ProviderImageBlock("image/png", "data:image/png;base64,iVBORw0KGgo="),
                new ProviderDocumentBlock(
                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK")),
            "c1",
            "get_files");

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) json.path("contents").get(2).path("parts");

    assertEquals(1, parts.size());
    JsonNode functionResponse = parts.get(0).path("functionResponse");
    assertEquals("chart and spec:", functionResponse.path("response").path("result").asString(""));
    JsonNode nested = functionResponse.path("parts");
    assertEquals(2, nested.size());
    assertEquals("image/png", nested.get(0).path("inlineData").path("mimeType").asString(""));
    assertEquals("iVBORw0KGgo=", nested.get(0).path("inlineData").path("data").asString(""));
    assertEquals("application/pdf", nested.get(1).path("inlineData").path("mimeType").asString(""));
    assertEquals("JVBERi0xLjQK", nested.get(1).path("inlineData").path("data").asString(""));
  }

  /** 验证同名工具的多次调用各自按 id 绑定，媒体只挂在对应的 functionResponse 内且不写 $ref 引用。 */
  @Test
  void encodesToolResultBlocks_duplicateToolNamesBindByIdWithoutRefFabrication() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call twice"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(new ProviderToolCall("c1", "get_media", "{}")),
                        new ProviderToolCallBlock(new ProviderToolCall("c2", "get_media", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "get_media",
                            List.of(
                                new ProviderTextBlock("first"),
                                new ProviderImageBlock(
                                    "image/png", "data:image/png;base64,iVBORw0KGgo=")),
                            false,
                            "{}"))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c2",
                            "get_media",
                            List.of(
                                new ProviderTextBlock("second"),
                                new ProviderDocumentBlock(
                                    "application/pdf", "data:application/pdf;base64,JVBERi0xLjQK")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode json = MAPPER.readTree(encoder.encode(request, descriptor()).bodyUtf8Bytes());
    // 两个 TOOL 消息与后续无 USER 消息，wire 中仍是同一个 user content
    ArrayNode parts = (ArrayNode) json.path("contents").get(2).path("parts");
    assertEquals(2, parts.size());

    JsonNode first = parts.get(0).path("functionResponse");
    JsonNode second = parts.get(1).path("functionResponse");
    assertEquals("c1", first.path("id").asString(""));
    assertEquals("c2", second.path("id").asString(""));
    assertEquals("first", first.path("response").path("result").asString(""));
    assertEquals("second", second.path("response").path("result").asString(""));
    assertEquals(
        "iVBORw0KGgo=", first.path("parts").get(0).path("inlineData").path("data").asString(""));
    assertEquals(
        "JVBERi0xLjQK", second.path("parts").get(0).path("inlineData").path("data").asString(""));
    // v1beta 的 FunctionResponseBlob 没有 displayName，因此绝不伪造 $ref 引用
    assertFalse(first.path("response").has("$ref"));
    assertFalse(first.path("parts").get(0).path("inlineData").has("displayName"));
  }

  /** 构造携带单个 TOOL 结果的 Gemini 请求：USER → ASSISTANT tool call → TOOL result。 */
  private static ProviderRequest toolResultRequest(
      List<ProviderContentBlock> resultContents, String toolCallId, String toolName) {
    return new ProviderRequest(
        model(false),
        DEFAULT_VARIANT,
        1024,
        "Test system instruction.",
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT,
                List.of(
                    new ProviderToolCallBlock(new ProviderToolCall(toolCallId, toolName, "{}")))),
            new ProviderMessage(
                ProviderMessageRole.TOOL,
                List.of(
                    new ProviderToolResultBlock(
                        toolCallId, toolName, resultContents, false, "{}")))),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 验证工具结果中出现不受支持的块类型（如嵌套的 tool call）时抛出脱敏的 INVALID_REQUEST。 */
  @Test
  void encodesToolResultBlocks_rejectsUnsupportedBlocksWithInvalidRequest() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(
                                new ProviderToolCallBlock(
                                    new ProviderToolCall("nested", "nested", "{}"))),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals("unsupported content block in tool result", ex.getMessage());
  }

  /** 验证 ProviderJsonBlock 严格完整解析，尾随内容或重复键抛出脱敏的 INVALID_REQUEST。 */
  @Test
  void encodesToolResultBlocks_strictJsonParsing_rejectsTrailingContentAndDuplicates() {
    // 尾随内容测试
    ProviderRequest reqTrailing =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(new ProviderJsonBlock("{\"key\":1} trailing_tokens")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqTrailing, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());
    assertEquals("invalid JSON block in tool result", ex1.getMessage());

    // 重复键测试
    ProviderRequest reqDuplicates =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(new ProviderJsonBlock("{\"dup\":1,\"dup\":2}")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqDuplicates, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
    assertEquals("invalid JSON block in tool result", ex2.getMessage());
  }

  /** 验证连续 TOOL 与 USER 消息合并在同一 wire user content 中，且顺序与 functionResponse 绑定保持一致。 */
  @Test
  void mergesConsecutiveToolAndUserMessagesIntoUserContent() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Initial prompt"))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "toolA",
                            List.of(new ProviderTextBlock("resultA")),
                            false,
                            "{}"))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c2",
                            "toolB",
                            List.of(new ProviderTextBlock("resultB")),
                            false,
                            "{}"))),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Follow-up text")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    ArrayNode contents = (ArrayNode) json.get("contents");

    // 连续的 USER, TOOL, TOOL, USER 应该全部合并为一个 role='user' 的 content
    assertEquals(1, contents.size());
    assertEquals("user", contents.get(0).get("role").asString(""));
    ArrayNode parts = (ArrayNode) contents.get(0).get("parts");
    assertEquals(4, parts.size());
    assertEquals("Initial prompt", parts.get(0).get("text").asString(""));
    assertEquals("toolA", parts.get(1).get("functionResponse").get("name").asString(""));
    assertEquals("toolB", parts.get(2).get("functionResponse").get("name").asString(""));
    assertEquals("Follow-up text", parts.get(3).get("text").asString(""));
  }

  /** 验证工具定义的 inputSchemaJson 必须是 JSON Object，非 Object 时抛出 INVALID_REQUEST。 */
  @Test
  void encodesToolDefinitions_validatesInputSchemaJsonObjectStrictly() {
    ProviderToolDefinition arrayTool = new ProviderToolDefinition("arr_tool", "desc", "[1, 2]");
    ProviderRequest req =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q")))),
            List.of(arrayTool),
            ProviderCacheControl.none());
    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(req, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals("tool parameters must be a JSON object", ex.getMessage());
  }

  /** 验证 ASSISTANT 消息中包含不受支持的 block 类型时抛出 INVALID_REQUEST。 */
  @Test
  void encodesAssistantBlock_rejectsUnsupportedContentBlock() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderImageBlock("image/png", "https://example.com/a.png")))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals("unsupported ASSISTANT content block", ex.getMessage());
  }

  /**
   * 验证 Assistant Tool Call 参数必须是完整的 JSON Object，非 Object/损坏/尾随/重复 key 严格抛出 INVALID_REQUEST，绝不补全为
   * {}。
   */
  @Test
  void encodesAssistantToolCall_rejectsNonObjectOrMalformedArgumentsStrictly() {
    // 1. 非 Object (如 JSON Array 或 Primitive)
    ProviderRequest reqNonObj =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("c1", "fn1", "[1, 2, 3]"))))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqNonObj, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());
    assertEquals("tool call arguments must be a valid JSON object", ex1.getMessage());

    // 2. 格式损坏的 JSON
    ProviderRequest reqMalformed =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("c2", "fn2", "{unclosed_json"))))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqMalformed, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
    assertEquals("tool call arguments must be a valid JSON object", ex2.getMessage());

    // 3. 带有尾随内容的 JSON
    ProviderRequest reqTrailing =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("c3", "fn3", "{\"a\":1} trailing"))))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex3 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqTrailing, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());
    assertEquals("tool call arguments must be a valid JSON object", ex3.getMessage());

    // 4. 重复键的 JSON
    ProviderRequest reqDuplicates =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("c4", "fn4", "{\"k\":1,\"k\":2}"))))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex4 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqDuplicates, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex4.kind());
    assertEquals("tool call arguments must be a valid JSON object", ex4.getMessage());
  }

  /** 验证 Assistant Tool Call 参数为合法 JSON Object 时完整保留并正确序列化。 */
  @Test
  void encodesAssistantToolCall_preservesValidJsonObjectArguments() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall(
                                "c1", "fn1", "{\"city\":\"Hangzhou\",\"score\":100}"))))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) json.get("contents").get(1).get("parts");

    assertEquals(1, parts.size());
    JsonNode fnCall = parts.get(0).get("functionCall");
    assertEquals("fn1", fnCall.get("name").asString(""));
    assertEquals("c1", fnCall.get("id").asString(""));
    assertEquals("Hangzhou", fnCall.get("args").get("city").asString(""));
    assertEquals(100, fnCall.get("args").get("score").asInt(0));
  }

  /** 验证 Tool Result 为空及 error 包含多块时的响应组织。 */
  @Test
  void encodesToolResultBlocks_emptyContentsAndMultiErrorResults() throws Exception {
    // 1. error=false 且 contents 为空列表 -> {result: ""}
    ProviderRequest reqEmptyOk =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(new ProviderToolResultBlock("c1", "fn1", List.of(), false, "{}")))),
            List.of(),
            ProviderCacheControl.none());
    GeminiEncodedRequest enc1 = encoder.encode(reqEmptyOk, descriptor());
    JsonNode json1 = MAPPER.readTree(enc1.bodyUtf8Bytes());
    JsonNode resp1 =
        json1.get("contents").get(2).get("parts").get(0).get("functionResponse").get("response");
    assertEquals("", resp1.get("result").asString(""));

    // 2. error=true 且 contents 为空列表 -> {error: true, result: ""}
    ProviderRequest reqEmptyErr =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(new ProviderToolResultBlock("c1", "fn1", List.of(), true, "{}")))),
            List.of(),
            ProviderCacheControl.none());
    GeminiEncodedRequest enc2 = encoder.encode(reqEmptyErr, descriptor());
    JsonNode json2 = MAPPER.readTree(enc2.bodyUtf8Bytes());
    JsonNode resp2 =
        json2.get("contents").get(2).get("parts").get(0).get("functionResponse").get("response");
    assertTrue(resp2.get("error").asBoolean(false));
    assertEquals("", resp2.get("result").asString(""));

    // 3. error=true 且多块内容 -> {error: true, results: [...]}
    ProviderRequest reqMultiErr =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "fn1",
                            List.of(
                                new ProviderTextBlock("failed part 1"),
                                new ProviderJsonBlock("{\"step\":2}")),
                            true,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());
    GeminiEncodedRequest enc3 = encoder.encode(reqMultiErr, descriptor());
    JsonNode json3 = MAPPER.readTree(enc3.bodyUtf8Bytes());
    JsonNode resp3 =
        json3.get("contents").get(2).get("parts").get(0).get("functionResponse").get("response");
    assertTrue(resp3.get("error").asBoolean(false));
    assertTrue(resp3.has("results"));
    ArrayNode results = (ArrayNode) resp3.get("results");
    assertEquals(2, results.size());
    assertEquals("failed part 1", results.get(0).asString(""));
    assertEquals(2, results.get(1).get("step").asInt(0));
  }

  /** 验证 Media Source URI 严格校验（合法 data URI、合法的 scheme）。 */
  @Test
  void encodesMediaBlock_validatesMediaSourceUriStrictly() {
    // 1. 非法 data URI
    ProviderRequest reqBadData =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/png", "data:not_valid_data_uri")))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadData, descriptor()));
    assertEquals(
        "media source must be a base64 data URI with a non-empty decodable payload",
        ex1.getMessage());

    // 2. 不被允许的 scheme (非 http/https/gs，如 file://)
    ProviderRequest reqBadScheme =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/png", "file:///local/image.png")))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadScheme, descriptor()));
    assertEquals("media fileUri scheme must be http, https or gs", ex2.getMessage());
  }

  /** 验证语义工具调用中的非法参数被拒绝，且异常消息不泄漏原始参数。 */
  @Test
  void rejectsInvalidToolArgumentsWithoutLeakingThem() {
    List<String> invalidArgs =
        List.of("12345", "\"scalar_string\"", "true", "[1, 2, 3]", "{\"unclosed\":", "null");

    for (String badArg : invalidArgs) {
      ProviderRequest request =
          new ProviderRequest(
              model(false),
              DEFAULT_VARIANT,
              1024,
              "Test system instruction.",
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                  new ProviderMessage(
                      ProviderMessageRole.ASSISTANT,
                      List.of(
                          new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", badArg))))),
              List.of(),
              ProviderCacheControl.none());
      ProviderException ex =
          assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertNull(ex.getCause());
      assertFalse(ex.getMessage().contains(badArg));
    }
  }

  @Test
  void finalBodySizeGuardEnforcedAtCallSite() {
    // 测试意图：证明 GeminiRequestEncoder.encode 在序列化完成后确实调用应用上限守卫。
    // 先用默认阈值编码得到真实字节长度，再以该长度验证边界通过、以少 1 字节验证超限拒绝（无昂贵大内存分配）。
    ProviderRequest req =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            "Test system instruction.",
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q")))),
            List.of(),
            ProviderCacheControl.none());

    int actualBytes = encoder.encode(req, descriptor()).bodyUtf8Bytes().length;

    GeminiEncodedRequest atLimit =
        new GeminiRequestEncoder(new RequestBodySizeGuard(actualBytes)).encode(req, descriptor());
    assertEquals(actualBytes, atLimit.bodyUtf8Bytes().length);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                new GeminiRequestEncoder(new RequestBodySizeGuard(actualBytes - 1))
                    .encode(req, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("request body exceeds"));
    assertFalse(ex.getMessage().contains("Q"));
  }
}

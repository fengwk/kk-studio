package fun.fengwk.kkstudio.harness.provider.gemini;

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
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
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

/** Gemini 请求体序列化与请求映射规则测试。 */
class GeminiRequestEncoderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default");
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
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    return new ModelDescriptor(
        "google-test",
        "gemini-2.5-flash",
        "gemini-2.5-flash",
        Set.of(ModelInputModality.TEXT),
        true,
        reasoning,
        pricing);
  }

  /** 验证基本的用户文本消息能够正确编码为 Gemini contents wire 格式。 */
  @Test
  void encodesBasicTextRequest() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello Gemini")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    assertNotNull(encoded.bodyUtf8Bytes());
    assertNotNull(encoded.sourcePrefixHash());

    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertTrue(json.has("contents"));
    ArrayNode contents = (ArrayNode) json.get("contents");
    assertEquals(1, contents.size());
    assertEquals("user", contents.get(0).get("role").asText());
    assertEquals("Hello Gemini", contents.get(0).get("parts").get(0).get("text").asText());
  }

  /** 验证多轮纯文本历史按 user/model/user 顺序编码，且各轮文本保持不变。 */
  @Test
  void encodesMultiTurnTextConversation() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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
    assertEquals("user", contents.get(0).path("role").asText());
    assertEquals("First message", contents.get(0).path("parts").get(0).path("text").asText());
    assertEquals("model", contents.get(1).path("role").asText());
    assertEquals("First response", contents.get(1).path("parts").get(0).path("text").asText());
    assertEquals("user", contents.get(2).path("role").asText());
    assertEquals("Second message", contents.get(2).path("parts").get(0).path("text").asText());
  }

  /** 验证开头的连续 SYSTEM 消息被合并为顶层的 systemInstruction.parts，且仅支持文本。 */
  @Test
  void mergesLeadingSystemMessagesIntoSystemInstructionTextOnly() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("System rule 1."))),
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("System rule 2."))),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("User query.")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertTrue(json.has("systemInstruction"));
    ArrayNode sysParts = (ArrayNode) json.get("systemInstruction").get("parts");
    assertEquals(2, sysParts.size());
    assertEquals("System rule 1.", sysParts.get(0).get("text").asText());
    assertEquals("System rule 2.", sysParts.get(1).get("text").asText());

    ArrayNode contents = (ArrayNode) json.get("contents");
    assertEquals(1, contents.size());
    assertEquals("user", contents.get(0).get("role").asText());
  }

  /** 验证会话中间出现 SYSTEM 消息被明确拒绝，防止语义错乱。 */
  @Test
  void rejectsMidConversationSystemMessages() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello"))),
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("Late system")))),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证 systemInstruction 包含非文本 Block（如图片）时被拒绝。 */
  @Test
  void rejectsNonTextInSystemInstruction() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderImageBlock("image/png", "https://example.com/sys.png")))),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证 generationConfig 只包含请求输出预算：采样/惩罚/停止序列已从契约移除，使用协议默认。 */
  @Test
  void encodesGenerationConfigWithRequestOutputBudgetOnly() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            new ModelVariant("v1"),
            4321,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode gen = json.get("generationConfig");
    assertEquals(4321, gen.get("maxOutputTokens").asInt());
    assertFalse(gen.has("temperature"));
    assertFalse(gen.has("topP"));
    assertFalse(gen.has("topK"));
    assertFalse(gen.has("frequencyPenalty"));
    assertFalse(gen.has("presencePenalty"));
    assertFalse(gen.has("stopSequences"));
  }

  /** 验证 reasoning effort 正确映射到官方 thinkingConfig 的 thinkingLevel 与 includeThoughts。 */
  @Test
  void encodesThinkingConfigLowMediumHigh() throws Exception {
    for (String effort : List.of("low", "medium", "high")) {
      ModelVariant variant = new ModelVariant("v-" + effort, effort);
      ProviderRequest request =
          new ProviderRequest(
              model(true),
              variant,
              1024,
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER, List.of(new ProviderTextBlock("Solve this")))),
              List.of(),
              ProviderCacheControl.none());

      GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
      JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

      JsonNode thinkingConfig = json.path("generationConfig").path("thinkingConfig");
      assertTrue(thinkingConfig.path("includeThoughts").asBoolean());
      assertEquals(effort, thinkingConfig.path("thinkingLevel").asText());
    }
  }

  /** 验证 off 显式关闭推理：includeThoughts=false 且 thinkingBudget=0，不发 thinkingLevel。 */
  @Test
  void encodesThinkingOffExplicitly() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            new ModelVariant("v1", "off"),
            1024,
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
    assertFalse(thinkingConfig.path("includeThoughts").asBoolean());
    assertEquals(0, thinkingConfig.path("thinkingBudget").asInt());
    assertFalse(thinkingConfig.has("thinkingLevel"));
  }

  /** 验证 4 态之外的 reasoning effort 在构造期即被拒绝，不可能到达编码器。 */
  @Test
  void rejectsUnknownReasoningEffort() {
    for (String unsupported : List.of("extreme", "xhigh", "max", "none", "")) {
      assertThrows(IllegalArgumentException.class, () -> new ModelVariant("v1", unsupported));
    }
  }

  /** 验证当模型不支持推理且无 reasoning effort 时，不生成 thinkingConfig。 */
  @Test
  void omitsThinkingConfig_whenReasoningDisabled() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode genConfig = json.path("generationConfig");
    assertEquals(1024, genConfig.path("maxOutputTokens").asInt());
    assertFalse(genConfig.has("thinkingConfig"));
  }

  /** 验证显式 cacheControl 标记被明确拒绝（Gemini 仅支持隐式自动缓存）。 */
  @Test
  void rejectsExplicitCacheControl() {
    ProviderCacheControl explicitCache =
        new ProviderCacheControl(PromptCacheRetention.SHORT, "aff1", Set.of());
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            explicitCache);

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
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
    assertEquals("getWeather", fn.get("name").asText());
    assertEquals("Get current weather", fn.get("description").asText());
    assertEquals("object", fn.get("parameters").get("type").asText());
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
    assertEquals("image/png", part.get("inlineData").get("mimeType").asText());
    assertTrue(part.get("inlineData").get("data").asText().startsWith("iVBORw"));
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
          expectedMimeTypes.get(i), parts.get(i).path("inlineData").path("mimeType").asText());
      assertEquals("QQ==", parts.get(i).path("inlineData").path("data").asText());
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
    assertEquals("fileData", parts.get(0).fieldNames().next());
    assertEquals(
        "https://example.com/pic.jpg", parts.get(0).get("fileData").get("fileUri").asText());
    assertEquals("audio/mp3", parts.get(1).get("fileData").get("mimeType").asText());
    assertEquals("video/mp4", parts.get(2).get("fileData").get("mimeType").asText());
    assertEquals("gs://bucket/doc.pdf", parts.get(3).get("fileData").get("fileUri").asText());
  }

  /** 验证未物化的持久化资源块拒绝并报错，不静默丢弃。 */
  @Test
  void rejectsUnsupportedOrUnmaterializedResourceBlock() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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
    assertEquals("user", contents.get(0).get("role").asText());
    assertEquals("model", contents.get(1).get("role").asText());
    assertEquals("user", contents.get(2).get("role").asText());

    ArrayNode toolParts = (ArrayNode) contents.get(2).get("parts");
    assertEquals(2, toolParts.size());
    assertEquals("fn1", toolParts.get(0).get("functionResponse").get("name").asText());
    assertEquals("c1", toolParts.get(0).get("functionResponse").get("id").asText());
    assertEquals(
        "res1", toolParts.get(0).get("functionResponse").get("response").get("result").asText());
    assertEquals("fn2", toolParts.get(1).get("functionResponse").get("name").asText());
  }

  /** 验证无 replayState 时，ASSISTANT 消息按照 contents 原始顺序进行语义 fallback，不按类别重排。 */
  @Test
  void encodesAssistantMessage_fallsBackToOrderedContentsWhenNoReplay() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
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
    assertEquals("Deep thought", parts.get(0).get("text").asText());
    assertTrue(parts.get(0).get("thought").asBoolean());
    assertEquals("Answer text", parts.get(1).get("text").asText());
    assertFalse(parts.get(1).has("thought"));
    assertEquals("toolA", parts.get(2).get("functionCall").get("name").asText());
  }

  /** 验证当 ASSISTANT 具有合法 GEMINI_CONTENT replayState 时，原位回放其 parts 白名单。 */
  @Test
  void encodesAssistantMessage_replaysNativePartsWhenReplayMatches() throws Exception {
    // 构造第一轮请求计算 prefix hash
    ProviderRequest req1 =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q1")))),
            List.of(),
            ProviderCacheControl.none());
    GeminiEncodedRequest enc1 = encoder.encode(req1, descriptor());
    String hash1 = enc1.sourcePrefixHash();

    // 构造包含合法 replayState 的第二轮 ASSISTANT
    ObjectNode replayPayload = MAPPER.createObjectNode();
    replayPayload.put("role", "model");
    ArrayNode replayedParts = replayPayload.putArray("parts");
    ObjectNode p1 = replayedParts.addObject();
    p1.put("text", "thought text");
    p1.put("thought", true);
    p1.put("thoughtSignature", "sig_123");
    ObjectNode p2 = replayedParts.addObject();
    p2.put("text", "normal text");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            hash1,
            replayPayload);

    ProviderRequest req2 =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q1"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderThinkingBlock("thought text"),
                        new ProviderTextBlock("normal text")),
                    replayState),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q2")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest enc2 = encoder.encode(req2, descriptor());
    JsonNode json = MAPPER.readTree(enc2.bodyUtf8Bytes());

    ArrayNode parts = (ArrayNode) json.get("contents").get(1).get("parts");
    assertEquals(2, parts.size());
    assertEquals("sig_123", parts.get(0).get("thoughtSignature").asText());
    assertTrue(parts.get(0).get("thought").asBoolean());
  }

  /** 验证同格式的非法/损坏 replay payload 明确抛出 INVALID_REQUEST，而非静默吞掉。 */
  @Test
  void rejectsReplay_whenSameFormatCorruptedPayload() throws Exception {
    ProviderRequest req1 =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q1")))),
            List.of(),
            ProviderCacheControl.none());
    String hash1 = encoder.encode(req1, descriptor()).sourcePrefixHash();

    // 损坏的 payload：missing parts 数组
    ObjectNode badPayload = MAPPER.createObjectNode();
    badPayload.put("role", "model");
    badPayload.put("corrupted", "no parts");

    ProviderReplayState badReplay =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            hash1,
            badPayload);

    ProviderRequest req2 =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q1"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("text")),
                    badReplay)),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor()));
  }

  /** 验证不可回放格式（如非 GEMINI_CONTENT）安全降级为语义 fallback 而不失败。 */
  @Test
  void fallsBackToSemanticWhenReplayFormatNotGemini() throws Exception {
    ProviderReplayState otherReplay =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor().affinity("gemini-2.5-flash"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            MAPPER.createObjectNode());

    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("fallback text")),
                    otherReplay)),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertEquals(
        "fallback text", json.get("contents").get(1).get("parts").get(0).get("text").asText());
  }

  /** 验证 affinity 不匹配或 prefixHash 不匹配时安全降级为语义 fallback。 */
  @Test
  void fallsBackToSemanticWhenAffinityOrPrefixHashMismatches() throws Exception {
    ObjectNode validPayload = MAPPER.createObjectNode();
    validPayload.put("role", "model");
    validPayload.putArray("parts").addObject().put("text", "text");

    // 1. affinity mismatch
    ProviderReplayState diffAffinity =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            new ProviderReplayAffinity(
                ProviderType.GOOGLE, "other-provider", UUID.randomUUID(), "gemini-2.5-flash"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            validPayload);

    ProviderRequest req1 =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("text")),
                    diffAffinity)),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest enc1 = encoder.encode(req1, descriptor());
    assertNotNull(enc1);

    // 2. prefixHash mismatch
    ProviderReplayState diffHash =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            validPayload);

    ProviderRequest req2 =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("text")),
                    diffHash)),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest enc2 = encoder.encode(req2, descriptor());
    assertNotNull(enc2);
  }

  /** 验证同格式下 payload 内容与 durable contents 不匹配时明确失败。 */
  @Test
  void rejectsReplay_whenPayloadContentMismatchesDurable() throws Exception {
    ProviderRequest baseReq =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q")))),
            List.of(),
            ProviderCacheControl.none());
    String hash = encoder.encode(baseReq, descriptor()).sourcePrefixHash();

    // 1. 文本内容不匹配
    ObjectNode mismatchTextPayload = MAPPER.createObjectNode();
    mismatchTextPayload.put("role", "model");
    mismatchTextPayload.putArray("parts").addObject().put("text", "tampered text");

    ProviderReplayState replay1 =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            hash,
            mismatchTextPayload);

    ProviderRequest req1 =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("original text")),
                    replay1)),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor()));

    // 2. 工具调用名称不匹配
    ObjectNode mismatchToolPayload = MAPPER.createObjectNode();
    mismatchToolPayload.put("role", "model");
    ObjectNode fnPart = mismatchToolPayload.putArray("parts").addObject().putObject("functionCall");
    fnPart.put("name", "tool_wrong");
    fnPart.putObject("args");

    ProviderReplayState replay2 =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            hash,
            mismatchToolPayload);

    ProviderRequest req2 =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(new ProviderToolCall("c1", "tool_real", "{}"))),
                    replay2)),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor()));
  }

  /** 验证未声明 reasoningEffort（null 协议默认）时完全不生成 thinkingConfig。 */
  @Test
  void omitsThinkingConfig_whenReasoningEffortAbsent() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
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
            model(false), DEFAULT_VARIANT, 1024, List.of(), List.of(), ProviderCacheControl.none());

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
    assertEquals(98, resp.get("score").asInt());
    assertEquals("ok", resp.get("status").asText());
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
    assertEquals(20, resp.get("result").get(1).asInt());
  }

  /** 验证包含多个 text 与 JSON 块的工具结果按原有顺序组织在有序 results 数组中。 */
  @Test
  void encodesToolResultBlocks_multipleTextAndJson_usesOrderedResultsArray() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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
    assertEquals("header", results.get(0).asText());
    assertEquals(5, results.get(1).get("count").asInt());
    assertEquals("footer", results.get(2).asText());
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
    assertTrue(resp.get("error").asBoolean());
    // 验证原始 JSON 作为 result 完整保留，且其内嵌的 "error" 字段未被破坏或覆盖
    JsonNode innerResult = resp.get("result");
    assertEquals("inner_failure_detail", innerResult.get("error").asText());
    assertEquals(404, innerResult.get("code").asInt());
  }

  /** 验证 Gemini 可原生承载的 image/document 工具结果紧随 functionResponse 在同一 user content 中编码。 */
  @Test
  void encodesToolResultBlocks_nativeMediaResults_encodedAsConsecutiveMediaPartsInSameUserContent()
      throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(new ProviderToolCall("c1", "chart_tool", "{}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c1",
                            "chart_tool",
                            List.of(
                                new ProviderTextBlock("Here is the chart:"),
                                new ProviderImageBlock(
                                    "image/png",
                                    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="),
                                new ProviderDocumentBlock(
                                    "application/pdf", "https://example.com/spec.pdf")),
                            false,
                            "{}")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    ArrayNode parts = (ArrayNode) json.get("contents").get(2).get("parts");

    assertEquals(3, parts.size());
    // part 0: functionResponse
    assertTrue(parts.get(0).has("functionResponse"));
    assertEquals(
        "Here is the chart:",
        parts.get(0).get("functionResponse").get("response").get("result").asText());
    // part 1: inlineData
    assertTrue(parts.get(1).has("inlineData"));
    assertEquals("image/png", parts.get(1).get("inlineData").get("mimeType").asText());
    // part 2: fileData
    assertTrue(parts.get(2).has("fileData"));
    assertEquals(
        "https://example.com/spec.pdf", parts.get(2).get("fileData").get("fileUri").asText());
  }

  /** 验证工具结果中出现不受支持的块类型（如嵌套的 tool call）时抛出脱敏的 INVALID_REQUEST。 */
  @Test
  void encodesToolResultBlocks_rejectsUnsupportedBlocksWithInvalidRequest() {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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

  /** 验证连续 TOOL 与 USER 消息合并在同一 wire user content 中，sourcePrefixHash 与实际构建的 wire contents 一致。 */
  @Test
  void sourcePrefixHash_mergesConsecutiveToolAndUserMessages_consistentWithWireContents()
      throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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
    assertEquals("user", contents.get(0).get("role").asText());
    ArrayNode parts = (ArrayNode) contents.get(0).get("parts");
    assertEquals(4, parts.size());
    assertEquals("Initial prompt", parts.get(0).get("text").asText());
    assertEquals("toolA", parts.get(1).get("functionResponse").get("name").asText());
    assertEquals("toolB", parts.get(2).get("functionResponse").get("name").asText());
    assertEquals("Follow-up text", parts.get(3).get("text").asText());

    // 验证 sourcePrefixHash 与对该 wire contents 计算出的 hash 100% 一致
    String expectedHash = GeminiPrefixHasher.calculateHash(null, null, contents);
    assertEquals(expectedHash, encoded.sourcePrefixHash());
  }

  /** 验证并行 tool result 后下一轮 replay 时，相同顺序 hash 匹配并保留 thoughtSignature，顺序变化导致 hash 改变并失效 fallback。 */
  @Test
  void
      replay_parallelToolResultsNextTurn_matchesHashAndPreservesThoughtSignature_failsOnOrderChange()
          throws Exception {
    // 轮次 1：构建包含两个并行 tool result 的消息
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Do both")));
    ProviderMessage assistantCallMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{\"x\":1}")),
                new ProviderToolCallBlock(new ProviderToolCall("c2", "fn2", "{\"y\":2}"))));
    ProviderMessage tool1Msg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "c1", "fn1", List.of(new ProviderTextBlock("res1")), false, "{}")));
    ProviderMessage tool2Msg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "c2", "fn2", List.of(new ProviderTextBlock("res2")), false, "{}")));

    ProviderRequest turn1Request =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            List.of(userMsg, assistantCallMsg, tool1Msg, tool2Msg),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest turn1Encoded = encoder.encode(turn1Request, descriptor());
    String turn1FrozenHash = turn1Encoded.sourcePrefixHash();

    // 构造模型输出的带 thought/thoughtSignature 的 assistant 回放状态
    ObjectNode replayPayload = MAPPER.createObjectNode();
    replayPayload.put("role", "model");
    ArrayNode replayedParts = replayPayload.putArray("parts");
    ObjectNode thoughtPart = replayedParts.addObject();
    thoughtPart.put("text", "thinking about 1 and 2");
    thoughtPart.put("thought", true);
    thoughtPart.put("thoughtSignature", "sig_parallel_12345");
    ObjectNode answerPart = replayedParts.addObject();
    answerPart.put("text", "both completed successfully");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            turn1FrozenHash,
            replayPayload);

    ProviderMessage assistantWithReplay =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("thinking about 1 and 2"),
                new ProviderTextBlock("both completed successfully")),
            replayState);

    // 轮次 2A：顺序一致 (tool1, tool2) -> 应该命中 replay，保留 thoughtSignature
    ProviderRequest turn2MatchRequest =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            List.of(
                userMsg,
                assistantCallMsg,
                tool1Msg,
                tool2Msg,
                assistantWithReplay,
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("next turn")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest turn2MatchEncoded = encoder.encode(turn2MatchRequest, descriptor());
    JsonNode turn2MatchJson = MAPPER.readTree(turn2MatchEncoded.bodyUtf8Bytes());
    ArrayNode turn2ModelParts = (ArrayNode) turn2MatchJson.get("contents").get(3).get("parts");
    assertEquals(2, turn2ModelParts.size());
    assertEquals("sig_parallel_12345", turn2ModelParts.get(0).get("thoughtSignature").asText());

    // 轮次 2B：并行 tool results 顺序发生调换 (tool2, tool1) -> hash 改变，replayState 失效并 fallback
    ProviderRequest turn2ReorderedRequest =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            1024,
            List.of(
                userMsg,
                assistantCallMsg,
                tool2Msg, // 颠倒顺序
                tool1Msg,
                assistantWithReplay,
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("next turn")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest turn2ReorderedEncoded =
        encoder.encode(turn2ReorderedRequest, descriptor());
    JsonNode turn2ReorderedJson = MAPPER.readTree(turn2ReorderedEncoded.bodyUtf8Bytes());
    ArrayNode turn2ReorderedParts =
        (ArrayNode) turn2ReorderedJson.get("contents").get(3).get("parts");
    assertEquals(2, turn2ReorderedParts.size());
    // fallback 时由 durable 生成，不含 thoughtSignature
    assertFalse(turn2ReorderedParts.get(0).has("thoughtSignature"));
  }

  /** 验证同为 GEMINI_CONTENT 格式的损坏 payload，即使 affinity 或 hash 不匹配也必须先严格校验并抛出 INVALID_REQUEST。 */
  @Test
  void replay_validatesPayloadShapeAndFieldsStrictly_evenWhenAffinityOrHashMismatches() {
    String dummyHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // 1. role 不是 model
    ObjectNode payloadWrongRole = MAPPER.createObjectNode();
    payloadWrongRole.put("role", "user");
    payloadWrongRole.putArray("parts").addObject().put("text", "text");
    ProviderReplayState replayWrongRole =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("other-mismatched-model"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            payloadWrongRole);
    ProviderRequest req1 =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("text")),
                    replayWrongRole)),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());
    assertEquals("invalid Gemini replay payload", ex1.getMessage());

    // 2. 顶级混入未授权无关字段
    ObjectNode payloadInjectedTop = MAPPER.createObjectNode();
    payloadInjectedTop.put("role", "model");
    payloadInjectedTop.put("maliciousInjectedField", "attack");
    payloadInjectedTop.putArray("parts").addObject().put("text", "text");
    ProviderReplayState replayInjectedTop =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            dummyHash,
            payloadInjectedTop);
    ProviderRequest req2 =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("text")),
                    replayInjectedTop)),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());

    // 3. part 内部混入未授权字段
    ObjectNode payloadInjectedPart = MAPPER.createObjectNode();
    payloadInjectedPart.put("role", "model");
    ObjectNode p = payloadInjectedPart.putArray("parts").addObject();
    p.put("text", "text");
    p.put("unrecognizedField", "bad");
    ProviderReplayState replayInjectedPart =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            dummyHash,
            payloadInjectedPart);
    ProviderRequest req3 =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderTextBlock("text")),
                    replayInjectedPart)),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex3 =
        assertThrows(ProviderException.class, () -> encoder.encode(req3, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex3.kind());
  }

  /** 验证回放 payload 中 tool call 的 id 与 arguments 与 durable contents 必须全一致，不一致抛出 INVALID_REQUEST。 */
  @Test
  void replay_validatesDurableToolCallIdAndArgumentsStrictly() throws Exception {
    ProviderRequest baseReq =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q")))),
            List.of(),
            ProviderCacheControl.none());
    String hash = encoder.encode(baseReq, descriptor()).sourcePrefixHash();

    // 1. Tool Call ID 不一致
    ObjectNode payloadIdMismatch = MAPPER.createObjectNode();
    payloadIdMismatch.put("role", "model");
    ObjectNode fnPart1 = payloadIdMismatch.putArray("parts").addObject().putObject("functionCall");
    fnPart1.put("name", "tool_fn");
    fnPart1.put("id", "wrong_id");
    fnPart1.putObject("args").put("k", 1);

    ProviderReplayState replayIdMismatch =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            hash,
            payloadIdMismatch);
    ProviderRequest reqIdMismatch =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("real_id", "tool_fn", "{\"k\":1}"))),
                    replayIdMismatch)),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqIdMismatch, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());

    // 2. Tool Call arguments 不一致
    ObjectNode payloadArgsMismatch = MAPPER.createObjectNode();
    payloadArgsMismatch.put("role", "model");
    ObjectNode fnPart2 =
        payloadArgsMismatch.putArray("parts").addObject().putObject("functionCall");
    fnPart2.put("name", "tool_fn");
    fnPart2.put("id", "real_id");
    fnPart2.putObject("args").put("k", 999); // args 不一致

    ProviderReplayState replayArgsMismatch =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            hash,
            payloadArgsMismatch);
    ProviderRequest reqArgsMismatch =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("real_id", "tool_fn", "{\"k\":1}"))),
                    replayArgsMismatch)),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqArgsMismatch, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
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
    assertEquals("fn1", fnCall.get("name").asText());
    assertEquals("c1", fnCall.get("id").asText());
    assertEquals("Hangzhou", fnCall.get("args").get("city").asText());
    assertEquals(100, fnCall.get("args").get("score").asInt());
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
    assertEquals("", resp1.get("result").asText());

    // 2. error=true 且 contents 为空列表 -> {error: true, result: ""}
    ProviderRequest reqEmptyErr =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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
    assertTrue(resp2.get("error").asBoolean());
    assertEquals("", resp2.get("result").asText());

    // 3. error=true 且多块内容 -> {error: true, results: [...]}
    ProviderRequest reqMultiErr =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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
    assertTrue(resp3.get("error").asBoolean());
    assertTrue(resp3.has("results"));
    ArrayNode results = (ArrayNode) resp3.get("results");
    assertEquals(2, results.size());
    assertEquals("failed part 1", results.get(0).asText());
    assertEquals(2, results.get(1).get("step").asInt());
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
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderImageBlock("image/png", "data:not_valid_data_uri")))),
            List.of(),
            ProviderCacheControl.none());
    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(reqBadData, descriptor()));
    assertEquals("invalid data URI in media source", ex1.getMessage());

    // 2. 不被允许的 scheme (非 http/https/gs，如 file://)
    ProviderRequest reqBadScheme =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
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

  /** 验证 Replay Payload 结构边界校验（必须为 Object、parts 数组、每个 part 的类型与合法字段）。 */
  @Test
  void replay_validatesPayloadStructureAndPartsExhaustively() {
    String dummyHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // 1. parts 不是 Array
    ObjectNode p1 = MAPPER.createObjectNode();
    p1.put("role", "model");
    p1.put("parts", "not_an_array");
    assertReplayInvalid(p1, List.of(new ProviderTextBlock("text")), dummyHash);

    // 2. parts 元素不是 Object
    ObjectNode p2 = MAPPER.createObjectNode();
    p2.put("role", "model");
    p2.putArray("parts").add(12345);
    assertReplayInvalid(p2, List.of(new ProviderTextBlock("text")), dummyHash);

    // 3. part 同时包含 text 和 functionCall
    ObjectNode p3 = MAPPER.createObjectNode();
    p3.put("role", "model");
    ObjectNode item3 = p3.putArray("parts").addObject();
    item3.put("text", "text");
    item3.putObject("functionCall").put("name", "fn");
    assertReplayInvalid(p3, List.of(new ProviderTextBlock("text")), dummyHash);

    // 4. part 既没有 text 也没有 functionCall
    ObjectNode p4 = MAPPER.createObjectNode();
    p4.put("role", "model");
    p4.putArray("parts").addObject().put("dummy", true);
    assertReplayInvalid(p4, List.of(new ProviderTextBlock("text")), dummyHash);

    // 5. text part 中 text 不是 string
    ObjectNode p5 = MAPPER.createObjectNode();
    p5.put("role", "model");
    p5.putArray("parts").addObject().put("text", 123);
    assertReplayInvalid(p5, List.of(new ProviderTextBlock("123")), dummyHash);

    // 6. text part 中 thought 不是 boolean
    ObjectNode p6 = MAPPER.createObjectNode();
    p6.put("role", "model");
    ObjectNode item6 = p6.putArray("parts").addObject();
    item6.put("text", "think");
    item6.put("thought", "not_a_boolean");
    assertReplayInvalid(p6, List.of(new ProviderThinkingBlock("think")), dummyHash);

    // 7. text part 中 thoughtSignature 不是 string 或为空白字符串
    ObjectNode p7 = MAPPER.createObjectNode();
    p7.put("role", "model");
    ObjectNode item7 = p7.putArray("parts").addObject();
    item7.put("text", "think");
    item7.put("thought", true);
    item7.put("thoughtSignature", 999);
    assertReplayInvalid(p7, List.of(new ProviderThinkingBlock("think")), dummyHash);

    ObjectNode p7b = MAPPER.createObjectNode();
    p7b.put("role", "model");
    ObjectNode item7b = p7b.putArray("parts").addObject();
    item7b.put("text", "think");
    item7b.put("thought", true);
    item7b.put("thoughtSignature", "   ");
    assertReplayInvalid(p7b, List.of(new ProviderThinkingBlock("think")), dummyHash);

    // 8. functionCall part 中 functionCall 不是 object
    ObjectNode p8 = MAPPER.createObjectNode();
    p8.put("role", "model");
    p8.putArray("parts").addObject().put("functionCall", "not_an_object");
    assertReplayInvalid(
        p8, List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))), dummyHash);

    // 9. functionCall part 顶级混入未授权字段
    ObjectNode p9 = MAPPER.createObjectNode();
    p9.put("role", "model");
    ObjectNode item9 = p9.putArray("parts").addObject();
    item9.putObject("functionCall").put("name", "fn");
    item9.put("extraField", "bad");
    assertReplayInvalid(
        p9, List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))), dummyHash);

    // 10. functionCall part 带有非 textual 或 blank 的 thoughtSignature
    ObjectNode p10 = MAPPER.createObjectNode();
    p10.put("role", "model");
    ObjectNode item10 = p10.putArray("parts").addObject();
    item10.putObject("functionCall").put("name", "fn");
    item10.put("thoughtSignature", 12345);
    assertReplayInvalid(
        p10, List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))), dummyHash);

    ObjectNode p10b = MAPPER.createObjectNode();
    p10b.put("role", "model");
    ObjectNode item10b = p10b.putArray("parts").addObject();
    item10b.putObject("functionCall").put("name", "fn");
    item10b.put("thoughtSignature", "");
    assertReplayInvalid(
        p10b,
        List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
        dummyHash);

    // 11. functionCall 缺少 name 或 name 不是 textual
    ObjectNode p11 = MAPPER.createObjectNode();
    p11.put("role", "model");
    ObjectNode item11 = p11.putArray("parts").addObject().putObject("functionCall");
    item11.put("name", 123);
    assertReplayInvalid(
        p11, List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))), dummyHash);

    // 12. functionCall 的 id 不是 textual
    ObjectNode p12 = MAPPER.createObjectNode();
    p12.put("role", "model");
    ObjectNode item12 = p12.putArray("parts").addObject().putObject("functionCall");
    item12.put("name", "fn");
    item12.put("id", 123);
    assertReplayInvalid(
        p12,
        List.of(new ProviderToolCallBlock(new ProviderToolCall("123", "fn", "{}"))),
        dummyHash);

    // 13. functionCall 的 args 不是 object
    ObjectNode p13 = MAPPER.createObjectNode();
    p13.put("role", "model");
    ObjectNode item13 = p13.putArray("parts").addObject().putObject("functionCall");
    item13.put("name", "fn");
    item13.put("args", "[1, 2]");
    assertReplayInvalid(
        p13, List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))), dummyHash);

    // 14. functionCall 内部混入未知字段
    ObjectNode p14 = MAPPER.createObjectNode();
    p14.put("role", "model");
    ObjectNode item14 = p14.putArray("parts").addObject().putObject("functionCall");
    item14.put("name", "fn");
    item14.put("unknownField", "bad");
    assertReplayInvalid(
        p14, List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))), dummyHash);
  }

  /** 验证 Replay Payload 与 durable 内容比较时的分支覆盖（thinking 不一致、call 数量不一致、durable 非法 block）。 */
  @Test
  void replay_validatesDurableEquivalenceBranches() {
    String dummyHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // 1. Thinking 不一致
    ObjectNode p1 = MAPPER.createObjectNode();
    p1.put("role", "model");
    ObjectNode item1 = p1.putArray("parts").addObject();
    item1.put("text", "payload_thinking");
    item1.put("thought", true);
    assertReplayInvalid(
        p1, List.of(new ProviderThinkingBlock("durable_different_thinking")), dummyHash);

    // 2. Tool Calls 数量不一致
    ObjectNode p2 = MAPPER.createObjectNode();
    p2.put("role", "model");
    ObjectNode item2 = p2.putArray("parts").addObject().putObject("functionCall");
    item2.put("name", "fn1");
    assertReplayInvalid(
        p2,
        List.of(
            new ProviderToolCallBlock(new ProviderToolCall("c1", "fn1", "{}")),
            new ProviderToolCallBlock(new ProviderToolCall("c2", "fn2", "{}"))),
        dummyHash);

    // 3. Durable 内容中包含不支持的 block (例如 ProviderDocumentBlock)
    ObjectNode p3 = MAPPER.createObjectNode();
    p3.put("role", "model");
    p3.putArray("parts").addObject().put("text", "txt");
    assertReplayInvalid(
        p3,
        List.of(new ProviderDocumentBlock("application/pdf", "https://example.com/doc.pdf")),
        dummyHash);
  }

  private void assertReplayInvalid(
      ObjectNode payload, List<ProviderContentBlock> durable, String hash) {
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("gemini-2.5-flash"),
            hash,
            payload);
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(ProviderMessageRole.ASSISTANT, durable, replayState)),
            List.of(),
            ProviderCacheControl.none());

    ProviderException ex =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals("invalid Gemini replay payload", ex.getMessage());
  }

  /**
   * 意图：验证工具调用参数 arguments 为畸形、数组、标量或 JSON null 时，在 semantic fallback 和 replay 校验中均抛出
   * INVALID_REQUEST，且异常消息绝不泄漏原始参数。
   */
  @Test
  void testToolArgumentsStrictObjectValidationInReplayAndFallback() {
    List<String> invalidArgs =
        List.of("12345", "\"scalar_string\"", "true", "[1, 2, 3]", "{\"unclosed\":", "null");

    for (String badArg : invalidArgs) {
      // 1. Fallback 场景
      ProviderRequest reqFallback =
          new ProviderRequest(
              model(false),
              DEFAULT_VARIANT,
              1024,
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                  new ProviderMessage(
                      ProviderMessageRole.ASSISTANT,
                      List.of(
                          new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", badArg))))),
              List.of(),
              ProviderCacheControl.none());
      ProviderException exFallback =
          assertThrows(ProviderException.class, () -> encoder.encode(reqFallback, descriptor()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exFallback.kind());
      assertNull(exFallback.getCause());
      assertFalse(exFallback.getMessage().contains(badArg));

      // 2. Replay 场景（durable 包含非法参数）
      ObjectNode validPayload = MAPPER.createObjectNode();
      validPayload.put("role", "model");
      ObjectNode fn = validPayload.putArray("parts").addObject().putObject("functionCall");
      fn.put("id", "c1").put("name", "fn");
      fn.putObject("args");

      ProviderReplayState rsDurableBad =
          new ProviderReplayState(
              ProviderReplayFormat.GEMINI_CONTENT,
              descriptor().affinity("gemini-2.5-flash"),
              "0000000000000000000000000000000000000000000000000000000000000000",
              validPayload);
      ProviderRequest reqDurableBad =
          new ProviderRequest(
              model(false),
              DEFAULT_VARIANT,
              1024,
              List.of(
                  new ProviderMessage(
                      ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                  new ProviderMessage(
                      ProviderMessageRole.ASSISTANT,
                      List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", badArg))),
                      rsDurableBad)),
              List.of(),
              ProviderCacheControl.none());
      ProviderException exDurable =
          assertThrows(ProviderException.class, () -> encoder.encode(reqDurableBad, descriptor()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, exDurable.kind());
      assertNull(exDurable.getCause());
      assertFalse(exDurable.getMessage().contains(badArg));
    }

    // 3. Replay payload 中的 functionCall 缺少 args 或 args 为非 Object（如数组、标量）
    // 即使 affinity/hash 不匹配也必须严格拦截并抛出 INVALID_REQUEST，严禁静默 fallback 或自动补 {}
    ObjectNode payloadMissingArgs = MAPPER.createObjectNode();
    payloadMissingArgs.put("role", "model");
    ObjectNode fnMissingArgs =
        payloadMissingArgs.putArray("parts").addObject().putObject("functionCall");
    fnMissingArgs.put("id", "c1").put("name", "fn");
    // 不设 args 字段

    ProviderReplayState rsMissingArgs =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("mismatched-model"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            payloadMissingArgs);
    ProviderRequest reqMissingArgs =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
                    rsMissingArgs)),
            List.of(),
            ProviderCacheControl.none());
    ProviderException exMissing =
        assertThrows(ProviderException.class, () -> encoder.encode(reqMissingArgs, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exMissing.kind());
    assertEquals("invalid Gemini replay payload", exMissing.getMessage());

    // 4. Replay payload 中的 args 为数组
    ObjectNode payloadArrayArgs = MAPPER.createObjectNode();
    payloadArrayArgs.put("role", "model");
    ObjectNode fnArrayArgs =
        payloadArrayArgs.putArray("parts").addObject().putObject("functionCall");
    fnArrayArgs.put("id", "c1").put("name", "fn");
    fnArrayArgs.putArray("args").add(1).add(2);

    ProviderReplayState rsArrayArgs =
        new ProviderReplayState(
            ProviderReplayFormat.GEMINI_CONTENT,
            descriptor().affinity("mismatched-model"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            payloadArrayArgs);
    ProviderRequest reqArrayArgs =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            1024,
            List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Q"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "fn", "{}"))),
                    rsArrayArgs)),
            List.of(),
            ProviderCacheControl.none());
    ProviderException exArray =
        assertThrows(ProviderException.class, () -> encoder.encode(reqArrayArgs, descriptor()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exArray.kind());
    assertEquals("invalid Gemini replay payload", exArray.getMessage());
  }
}

package fun.fengwk.kkstudio.harness.provider.gemini;

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
  private static final ModelVariant DEFAULT_VARIANT =
      new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
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

  /** 验证开头的连续 SYSTEM 消息被合并为顶层的 systemInstruction.parts，且仅支持文本。 */
  @Test
  void mergesLeadingSystemMessagesIntoSystemInstructionTextOnly() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
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
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderImageBlock("image/png", "https://example.com/sys.png")))),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证 generationConfig 中的采样参数、候选数、惩罚项被正确编码。 */
  @Test
  void encodesGenerationConfig_variantParameters() throws Exception {
    ModelVariant variant =
        new ModelVariant("v1", 2048, 0.7, 0.9, 40, 0.5, 0.2, List.of("STOP1", "STOP2"), null);

    ProviderRequest request =
        new ProviderRequest(
            model(false),
            variant,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertTrue(json.has("generationConfig"));
    JsonNode gen = json.get("generationConfig");
    assertEquals(2048, gen.get("maxOutputTokens").asInt());
    assertEquals(0.7, gen.get("temperature").asDouble());
    assertEquals(0.9, gen.get("topP").asDouble());
    assertEquals(40, gen.get("topK").asInt());
    assertEquals(0.5, gen.get("frequencyPenalty").asDouble());
    assertEquals(0.2, gen.get("presencePenalty").asDouble());
    assertEquals(2, gen.get("stopSequences").size());
  }

  /** 验证 reasoning effort 正确映射到官方 thinkingConfig 的 thinkingLevel 与 includeThoughts。 */
  @Test
  void encodesThinkingConfig_minimalLowMediumHigh() throws Exception {
    for (String effort : List.of("minimal", "low", "medium", "high")) {
      ModelVariant variant =
          new ModelVariant("v-" + effort, 1024, null, null, null, null, null, List.of(), effort);
      ProviderRequest request =
          new ProviderRequest(
              model(true),
              variant,
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

  /** 验证未知 reasoning effort 明确失败，不乱猜预算。 */
  @Test
  void rejectsUnknownReasoningEffort() {
    // 通过反射或规避构造器规范化的非常规 effort
    ModelVariant variant =
        new ModelVariant("v1", 1024, null, null, null, null, null, List.of(), "extreme");
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            variant,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Solve")))),
            List.of(),
            ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证当模型不支持推理且无 reasoning effort 时，不生成 thinkingConfig。 */
  @Test
  void omitsThinkingConfig_whenReasoningDisabled() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());
    assertFalse(json.has("generationConfig"));
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

  /** 验证 URL 形式的媒体资源（图片、音频、视频、PDF）直接编码为 fileData，绝不执行下载。 */
  @Test
  void encodesUserUrlToFileData_imageAudioVideoPdf() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
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

  /** 验证仅开启 reasoning=true 而不指定 reasoningEffort 时，只输出 includeThoughts: true。 */
  @Test
  void encodesThinkingConfig_modelReasoningTrueWithoutEffort() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(true),
            DEFAULT_VARIANT,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
            List.of(),
            ProviderCacheControl.none());

    GeminiEncodedRequest encoded = encoder.encode(request, descriptor());
    JsonNode json = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode thinking = json.path("generationConfig").path("thinkingConfig");
    assertTrue(thinking.path("includeThoughts").asBoolean());
    assertFalse(thinking.has("thinkingLevel"));
  }

  /** 验证空的 messages 列表抛出 INVALID_REQUEST。 */
  @Test
  void rejectsEmptyMessages() {
    ProviderRequest request =
        new ProviderRequest(
            model(false), DEFAULT_VARIANT, List.of(), List.of(), ProviderCacheControl.none());

    assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor()));
  }

  /** 验证单 JSON object 的工具结果直接作为 functionResponse.response 的对象值。 */
  @Test
  void encodesToolResultBlocks_singleJsonObject_setsDirectlyAsResponseObject() throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            model(false),
            DEFAULT_VARIANT,
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
}

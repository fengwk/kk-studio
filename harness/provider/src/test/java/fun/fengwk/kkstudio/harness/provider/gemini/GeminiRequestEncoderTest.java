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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
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
        "res1", toolParts.get(0).get("functionResponse").get("response").get("response").asText());
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
}

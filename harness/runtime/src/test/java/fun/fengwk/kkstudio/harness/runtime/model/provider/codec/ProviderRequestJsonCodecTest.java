package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

class ProviderRequestJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String CANONICAL_RESOURCE =
      "/fun/fengwk/kkstudio/harness/runtime/model/provider/codec/provider-request.json";
  private static final String JSON_BLOCK = "{\n  \"hint\":\"capital\"\n}";
  private static final String ARGUMENTS_JSON = "{\n  \"q\":\"paris\"\n}";
  private static final String DETAILS_JSON = "{\n  \"lat\":48.85\n}";
  private static final String INPUT_SCHEMA_JSON =
      "{\n  \"type\":\"object\",\n  \"properties\":{\n    \"q\":{\n      \"type\":\"string\"\n    }\n  }\n}";

  private final ProviderRequestJsonCodec codec = new ProviderRequestJsonCodec();

  /** 规范 fixture 覆盖完整的 request graph，并对比 deterministic wire 输出，包含 enum-name 顺序与 raw JSON 字符串。 */
  @Test
  void roundTripsCompleteRequestAndMatchesCanonicalFixture() {
    ProviderRequest request = canonicalRequest();
    String expectedJson = canonicalNode().toString();

    String encoded = codec.encode(request);
    ProviderRequest decoded = codec.decode(encoded);

    assertEquals(expectedJson, encoded);
    assertFalse(encoded.contains("credential"));
    assertEquals(request, decoded);
    assertEquals(encoded, codec.encode(decoded));
    assertEquals(request, codec.decodeNode(codec.encodeNode(request)));
    assertEquals("gpt-5-mini", canonicalNode().path("model").path("modelName").asText());
    // catalog 逻辑名与上游 wire modelId 相互独立，两侧都必须被 canonical fixture 覆盖。
    assertEquals("gpt-5-mini-2025-08-07", canonicalNode().path("model").path("modelId").asText());
    assertEquals("Test system instruction.", canonicalNode().path("systemInstruction").asText());
    assertEquals("Test system instruction.", decoded.systemInstruction());
    assertEquals(
        Set.of(
            ProviderTextBlock.class,
            ProviderResourceBlock.class,
            ProviderThinkingBlock.class,
            ProviderJsonBlock.class,
            ProviderToolCallBlock.class,
            ProviderToolResultBlock.class),
        decoded.messages().stream()
            .flatMap(message -> message.contents().stream())
            .map(ProviderContentBlock::getClass)
            .collect(Collectors.toSet()));
    assertEquals(
        JSON_BLOCK,
        assertInstanceOf(ProviderJsonBlock.class, decoded.messages().get(0).contents().get(3))
            .json());
    assertEquals(
        ARGUMENTS_JSON,
        assertInstanceOf(ProviderToolCallBlock.class, decoded.messages().get(1).contents().get(0))
            .toolCall()
            .argumentsJson());
    assertEquals(
        DETAILS_JSON,
        assertInstanceOf(ProviderToolResultBlock.class, decoded.messages().get(2).contents().get(0))
            .detailsJson());
    assertEquals(INPUT_SCHEMA_JSON, decoded.tools().get(0).inputSchemaJson());
    assertEquals(
        new BigDecimal("3.000000000000"), decoded.model().pricing().inputPerMillionTokens());
  }

  @Test
  void roundTripsExternalizedTextResourceWithTotals() {
    ProviderRequest base = canonicalRequest();
    ProviderResourceBlock externalized =
        ProviderResourceBlock.externalizedText(
            UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"),
            "output.txt",
            2048L,
            120L,
            "preview lines");
    ProviderRequest request =
        new ProviderRequest(
            base.model(),
            base.variant(),
            base.outputTokens(),
            "Test system instruction.",
            List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(externalized))),
            base.tools(),
            base.cacheControl());

    String encoded = codec.encode(request);
    ProviderRequest decoded = codec.decode(encoded);

    assertEquals(request, decoded);
    ProviderResourceBlock decodedBlock =
        (ProviderResourceBlock) decoded.messages().get(0).contents().get(0);
    assertTrue(decodedBlock.isExternalizedText());
    assertEquals(2048L, decodedBlock.totalBytes());
    assertEquals(120L, decodedBlock.totalLines());
  }

  /** 所有合法的 cache capability 形态都必须通过同一严格的 request boundary。 */
  @Test
  void roundTripsEveryLegalPromptCacheModeAndControlShape() {
    List<CacheCase> cases =
        List.of(
            new CacheCase(PromptCacheCapability.unknown(), ProviderCacheControl.none()),
            new CacheCase(PromptCacheCapability.unsupported(), ProviderCacheControl.none()),
            new CacheCase(PromptCacheCapability.automatic(), ProviderCacheControl.none()),
            new CacheCase(
                PromptCacheCapability.affinity(
                    EnumSet.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG)),
                ProviderCacheControl.affinity(PromptCacheRetention.LONG, "affinity")),
            new CacheCase(
                PromptCacheCapability.breakpoints(
                    EnumSet.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
                    EnumSet.of(PromptCacheBreakpoint.TOOLS, PromptCacheBreakpoint.SYSTEM)),
                ProviderCacheControl.breakpoints(
                    PromptCacheRetention.SHORT,
                    "affinity",
                    EnumSet.of(PromptCacheBreakpoint.TOOLS, PromptCacheBreakpoint.SYSTEM))));

    for (CacheCase cacheCase : cases) {
      ProviderRequest request = requestWithCache(cacheCase);
      assertEquals(
          request, codec.decode(codec.encode(request)), cacheCase.capability().mode().name());
    }

    assertEquals(PromptCacheMode.values().length, cases.size());
  }

  /** String 与 raw-JSON boundary 对 duplicate field 与 trailing document 的拒绝行为对称。 */
  @Test
  void rejectsDuplicateAndTrailingDocumentsAtStrictJsonBoundaries() {
    ProviderRequest request = canonicalRequest();
    String encoded = codec.encode(request);
    String duplicateTopLevel =
        "{\"model\":" + canonicalNode().get("model") + "," + encoded.substring(1);

    assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateTopLevel));

    ObjectNode duplicateArguments = canonicalNode();
    ObjectNode toolCall =
        (ObjectNode)
            duplicateArguments.path("messages").get(1).path("contents").get(0).path("toolCall");
    toolCall.put("argumentsJson", "{\"q\":\"paris\",\"q\":\"lyon\"}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateArguments.toString()));

    ProviderToolDefinition invalidTool =
        new ProviderToolDefinition(
            "duplicate_schema", "duplicate schema", "{\"type\":\"object\",\"type\":\"array\"}");
    ProviderRequest invalidRequest =
        new ProviderRequest(
            request.model(),
            request.variant(),
            1024,
            "Test system instruction.",
            request.messages(),
            List.of(invalidTool),
            request.cacheControl());
    assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidRequest));
  }

  /** 每个对象层都拒绝额外字段、缺失字段以及 JSON 类型错误的字段，避免部分兼容的持久化 payload。 */
  @Test
  void rejectsUnknownMissingAndWrongTypedFieldsAtEveryObjectLayer() {
    assertStrictLayer(
        root -> root.put("extra", true),
        root -> root.remove("tools"),
        root -> root.set("messages", NODES.objectNode()));
    assertStrictLayer(
        root -> model(root).put("extra", true),
        root -> model(root).remove("modelName"),
        root -> model(root).put("tools", "true"));
    assertStrictLayer(
        root -> model(root).set("inputModalities", NODES.textNode("TEXT")),
        root -> model(root).remove("inputModalities"),
        root -> ((ArrayNode) model(root).path("inputModalities")).add(1));
    assertStrictLayer(
        root -> variant(root).put("extra", true),
        root -> variant(root).remove("id"),
        root -> variant(root).put("reasoningEffort", 1));
    // variant 只保留 id 与 reasoningEffort：已删除的采样/输出控制不得作为兼容字段被接受。
    for (String removed :
        List.of(
            "maxOutputTokens",
            "temperature",
            "topP",
            "topK",
            "frequencyPenalty",
            "presencePenalty",
            "stopSequences")) {
      assertRejected(root -> variant(root).put(removed, NODES.textNode("legacy")));
    }
    assertStrictLayer(
        root -> pricing(root).put("extra", true),
        root -> pricing(root).remove("currency"),
        root -> pricing(root).put("inputPerMillionTokens", 3));
    assertStrictLayer(
        root -> cacheControl(root).put("extra", true),
        root -> cacheControl(root).remove("affinityKey"),
        root -> cacheControl(root).put("affinityKey", true));
    assertStrictLayer(
        root -> message(root, 0).put("extra", true),
        root -> message(root, 0).remove("role"),
        root -> message(root, 0).set("contents", NODES.objectNode()));
    assertStrictLayer(
        root -> toolDefinition(root).put("extra", true),
        root -> toolDefinition(root).remove("description"),
        root -> toolDefinition(root).set("inputSchemaJson", NODES.objectNode()));
    assertStrictLayer(
        root -> content(root, 0, 0).put("extra", true),
        root -> content(root, 0, 0).remove("text"),
        root -> content(root, 0, 0).put("text", true));
    assertStrictLayer(
        root -> toolCall(root).put("extra", true),
        root -> toolCall(root).remove("id"),
        root -> toolCall(root).set("argumentsJson", NODES.objectNode()));
    assertStrictLayer(
        root -> toolResult(root).put("extra", true),
        root -> toolResult(root).remove("error"),
        root -> toolResult(root).put("error", "false"));
  }

  /** 未知 enum 名与 content discriminator 绝不能被解释为旧别名。 */
  @Test
  void rejectsUnknownEnumsAndDiscriminators() {
    assertRejected(root -> message(root, 0).put("role", "DEVELOPER"));
    assertRejected(root -> cacheControl(root).put("retention", "FOREVER"));
    assertRejected(
        root ->
            ((ArrayNode) cacheControl(root).path("breakpoints")).set(0, NODES.textNode("MESSAGE")));
    assertRejected(
        root ->
            ((ArrayNode) model(root).path("inputModalities")).set(0, NODES.textNode("FOREVER")));
    assertRejected(root -> content(root, 0, 0).put("type", "markdown"));
    assertRejected(root -> content(root, 0, 0).remove("type"));
  }

  /**
   * 旧 wire 协议形态必须被严格拒绝：会话消息中的 SYSTEM 角色已废弃，且顶层必须包含单一的 systemInstruction，缺失或使用旧 preambleMessages
   * 字段均不被接受。
   */
  @Test
  void rejectsLegacyWireShapesWithSystemMessageRoleOrMissingSystemInstruction() {
    // 会话消息中包含 role 为 SYSTEM 的消息必须抛出 IllegalArgumentException
    assertRejected(root -> message(root, 0).put("role", "SYSTEM"));
    assertRejected(
        root -> {
          ObjectNode systemMessage = NODES.objectNode();
          systemMessage.put("role", "SYSTEM");
          ArrayNode contents = systemMessage.putArray("contents");
          ObjectNode textBlock = contents.addObject();
          textBlock.put("type", "text");
          textBlock.put("text", "legacy system instruction");
          ((ArrayNode) root.path("messages")).insert(0, systemMessage);
        });

    // 顶层缺失 systemInstruction 字段必须抛出 IllegalArgumentException
    assertRejected(root -> root.remove("systemInstruction"));
    assertRejected(root -> root.put("systemInstruction", "   "));

    // 包含旧 preambleMessages 字段必须抛出 IllegalArgumentException
    assertRejected(root -> root.putArray("preambleMessages"));
    assertRejected(
        root -> {
          root.remove("systemInstruction");
          root.putArray("preambleMessages");
        });

    // 针对完整 JSON 文本解码同样验证旧 wire 形态的拒绝行为
    ObjectNode legacySystemNode = canonicalNode();
    message(legacySystemNode, 0).put("role", "SYSTEM");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(legacySystemNode.toString()));

    ObjectNode missingInstructionNode = canonicalNode();
    missingInstructionNode.remove("systemInstruction");
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(missingInstructionNode.toString()));
  }

  /** Malformed 根节点、集合、数字以及 raw JSON 字符串必须在进入持久化 request snapshot 前被拒绝。 */
  @Test
  void rejectsMalformedRootsCollectionsNumbersAndRawJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("   "));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));

    assertRejected(root -> model(root).put("providerName", ""));
    assertRejected(
        root ->
            model(root).set("providerName", NODES.numberNode(BigInteger.valueOf(Long.MAX_VALUE))));
    assertRejected(root -> ((ArrayNode) model(root).path("inputModalities")).removeAll());
    assertRejected(root -> variant(root).put("reasoningEffort", 1));
    assertRejected(root -> variant(root).put("reasoningEffort", " "));
    assertRejected(root -> pricing(root).put("serviceTierMultiplier", "bad"));
    assertRejected(root -> pricing(root).put("serviceTierMultiplier", "0"));
    assertRejected(root -> cacheControl(root).putNull("affinityKey"));
    assertRejected(root -> cacheControl(root).put("affinityKey", " "));
    assertRejected(
        root -> {
          cacheControl(root).put("retention", "NONE");
          cacheControl(root).put("affinityKey", "not-allowed");
          cacheControl(root).set("breakpoints", NODES.arrayNode());
        });
    assertRejected(
        root -> {
          cacheControl(root).put("retention", "NONE");
          cacheControl(root).putNull("affinityKey");
        });
    assertRejected(root -> ((ArrayNode) root.path("messages")).set(0, NODES.numberNode(1)));
    assertRejected(root -> ((ArrayNode) root.path("tools")).set(0, NODES.numberNode(1)));
    assertRejected(root -> content(root, 0, 0).put("type", 1));

    assertRejected(root -> toolDefinition(root).put("inputSchemaJson", "[]"));
    assertRejected(root -> toolDefinition(root).put("inputSchemaJson", "{"));
    assertRejected(root -> toolDefinition(root).put("inputSchemaJson", " "));
    assertRejected(root -> toolCall(root).put("argumentsJson", "[]"));
    assertRejected(root -> toolCall(root).put("argumentsJson", "{"));
    assertRejected(root -> toolCall(root).put("argumentsJson", " "));
    assertRejected(root -> toolResult(root).put("detailsJson", "[]"));
    assertRejected(root -> toolResult(root).put("detailsJson", "{"));
    assertRejected(root -> toolResult(root).put("detailsJson", " "));
    assertRejected(root -> content(root, 0, 3).put("json", "not-json"));
    assertRejected(root -> content(root, 0, 3).put("json", " "));
  }

  private void assertStrictLayer(
      Consumer<ObjectNode> unknownMutation,
      Consumer<ObjectNode> missingMutation,
      Consumer<ObjectNode> wrongTypeMutation) {
    assertRejected(unknownMutation);
    assertRejected(missingMutation);
    assertRejected(wrongTypeMutation);
  }

  /** 媒体块携带 attempt-only 的 presigned source：durable codec 对 encode/decode 一律确定性拒绝。 */
  @Test
  void rejectsTransientMediaBlocksOnEncodeAndDecode() {
    ProviderRequest request = canonicalRequest();
    List<ProviderContentBlock> mediaBlocks =
        List.of(
            new ProviderImageBlock("image/png", "data:image/png;base64,AAA"),
            new ProviderDocumentBlock("application/pdf", "data:application/pdf;base64,AAA"),
            new ProviderAudioBlock("audio/wav", "https://example.test/audio.wav"),
            new ProviderVideoBlock("video/mp4", "https://example.test/video.mp4"));
    for (ProviderContentBlock media : mediaBlocks) {
      ProviderRequest mediaRequest =
          new ProviderRequest(
              request.model(),
              request.variant(),
              1024,
              "Test system instruction.",
              List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(media))),
              request.tools(),
              request.cacheControl());
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.encode(mediaRequest),
          media.getClass().getSimpleName());
      // decode 拒绝：完整 canonical 结构上把 USER 消息内容替换为媒体块（避免提前在顶层结构失败）。
      ObjectNode mediaJson = canonicalNode();
      ArrayNode contents = NODES.arrayNode();
      contents.add(mediaBlockNode(media));
      ((ObjectNode) mediaJson.path("messages").get(0)).set("contents", contents);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.decode(mediaJson.toString()),
          media.getClass().getSimpleName());
    }
  }

  /** 意图：Provider 请求内的 durable resource 块必须无损携带图片输入档位；未知/枚举名/缺失字段一律显式拒绝。 */
  @Test
  void roundTripsProviderResourceBlockImageTier() {
    for (ImageInputTier tier : ImageInputTier.values()) {
      ProviderRequest request =
          requestWithResourceBlock(
              ProviderResourceBlock.media(
                  UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"),
                  "photo.png",
                  "preview",
                  tier));

      String encoded = codec.encode(request);

      assertTrue(
          encoded.contains("\"imageTier\":\"" + tier.wireName() + "\""),
          "provider request 必须写出档位 wire 名称：" + encoded);
      assertEquals(request, codec.decode(encoded));
    }

    ProviderRequest withoutTier =
        requestWithResourceBlock(
            ProviderResourceBlock.media(
                UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"), "photo.png", "preview"));
    assertTrue(codec.encode(withoutTier).contains("\"imageTier\":null"));

    // canonical fixture 的 message 0 的第二个块就是 resource：篡改档位字段必须被拒绝。
    assertRejected(root -> content(root, 0, 1).put("imageTier", "4K"));
    assertRejected(root -> content(root, 0, 1).put("imageTier", "P720"));
    assertRejected(root -> content(root, 0, 1).put("imageTier", 720));
    assertRejected(root -> content(root, 0, 1).remove("imageTier"));
  }

  private static ProviderRequest requestWithResourceBlock(ProviderResourceBlock resource) {
    ProviderRequest base = canonicalRequest();
    return new ProviderRequest(
        base.model(),
        base.variant(),
        base.outputTokens(),
        base.systemInstruction(),
        List.of(new ProviderMessage(ProviderMessageRole.USER, List.of(resource))),
        base.tools(),
        base.cacheControl());
  }

  private static ObjectNode mediaBlockNode(ProviderContentBlock media) {
    ObjectNode node = NODES.objectNode();
    if (media instanceof ProviderImageBlock value) {
      node.put("type", "image");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
      return node;
    }
    if (media instanceof ProviderDocumentBlock value) {
      node.put("type", "document");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
      return node;
    }
    if (media instanceof ProviderAudioBlock value) {
      node.put("type", "audio");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
      return node;
    }
    if (media instanceof ProviderVideoBlock value) {
      node.put("type", "video");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
      return node;
    }
    throw new IllegalArgumentException("unexpected media block: " + media.getClass());
  }

  private void assertRejected(Consumer<ObjectNode> mutation) {
    ObjectNode root = canonicalNode();
    mutation.accept(root);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  private static ProviderRequest canonicalRequest() {
    ModelVariant selectedVariant = new ModelVariant("balanced", "medium");
    ModelDescriptor model =
        new ModelDescriptor(
            "openai",
            "gpt-5-mini",
            "gpt-5-mini-2025-08-07",
            Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE),
            true,
            true,
            canonicalPricing());
    ProviderToolCall call = new ProviderToolCall("call-1", "lookup", ARGUMENTS_JSON);
    return new ProviderRequest(
        model,
        selectedVariant,
        1024,
        "Test system instruction.",
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(
                    new ProviderTextBlock("What is the capital of France?"),
                    new ProviderResourceBlock(
                        UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"),
                        "scan.png",
                        "tiny preview"),
                    new ProviderThinkingBlock("the user asks geography"),
                    new ProviderJsonBlock(JSON_BLOCK))),
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT,
                List.of(
                    new ProviderToolCallBlock(call),
                    new ProviderTextBlock("Let me look that up."))),
            new ProviderMessage(
                ProviderMessageRole.TOOL,
                List.of(
                    new ProviderToolResultBlock(
                        "call-1",
                        "lookup",
                        List.of(new ProviderTextBlock("Paris")),
                        false,
                        DETAILS_JSON))),
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT,
                List.of(new ProviderTextBlock("The capital of France is Paris.")))),
        List.of(new ProviderToolDefinition("lookup", "Look up facts", INPUT_SCHEMA_JSON)),
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT,
            "thread-1",
            EnumSet.of(PromptCacheBreakpoint.TOOLS, PromptCacheBreakpoint.SYSTEM)));
  }

  private static ModelPricing canonicalPricing() {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        new BigDecimal("1.5"),
        "v1",
        new BigDecimal("3.000000000000"),
        new BigDecimal("6.000000000000"),
        new BigDecimal("0.300000000000"),
        new BigDecimal("3.750000000000"),
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ProviderRequest requestWithCache(CacheCase cacheCase) {
    ProviderRequest source = canonicalRequest();
    return new ProviderRequest(
        source.model(),
        source.variant(),
        1024,
        "Test system instruction.",
        source.messages(),
        source.tools(),
        cacheCase.control());
  }

  private static ObjectNode canonicalNode() {
    try {
      return (ObjectNode) OBJECT_MAPPER.readTree(readResource(CANONICAL_RESOURCE));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid canonical provider request fixture", exception);
    }
  }

  private static String readResource(String resource) {
    try (InputStream input =
        Objects.requireNonNull(
            ProviderRequestJsonCodecTest.class.getResourceAsStream(resource), resource)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static List<String> textValues(ArrayNode array) {
    return array.valueStream().map(node -> node.textValue()).toList();
  }

  private static ObjectNode model(ObjectNode root) {
    return (ObjectNode) root.path("model");
  }

  private static ObjectNode variant(ObjectNode root) {
    return (ObjectNode) root.path("variant");
  }

  private static ObjectNode pricing(ObjectNode root) {
    return (ObjectNode) model(root).path("pricing");
  }

  private static ObjectNode cacheControl(ObjectNode root) {
    return (ObjectNode) root.path("cacheControl");
  }

  private static ObjectNode message(ObjectNode root, int index) {
    return (ObjectNode) root.path("messages").path(index);
  }

  private static ObjectNode content(ObjectNode root, int messageIndex, int contentIndex) {
    return (ObjectNode) message(root, messageIndex).path("contents").path(contentIndex);
  }

  private static ObjectNode toolDefinition(ObjectNode root) {
    return (ObjectNode) root.path("tools").path(0);
  }

  private static ObjectNode toolCall(ObjectNode root) {
    return (ObjectNode) content(root, 1, 0).path("toolCall");
  }

  private static ObjectNode toolResult(ObjectNode root) {
    return content(root, 2, 0);
  }

  private record CacheCase(PromptCacheCapability capability, ProviderCacheControl control) {}
}

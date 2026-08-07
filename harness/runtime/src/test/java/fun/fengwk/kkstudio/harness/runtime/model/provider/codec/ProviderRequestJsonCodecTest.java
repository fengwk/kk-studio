package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
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

  /**
   * The canonical fixture exercises the complete request graph and compares the exact deterministic
   * wire output, including enum-name ordering and raw JSON strings.
   */
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
    assertEquals(
        Set.of(
            ProviderTextBlock.class,
            ProviderImageBlock.class,
            ProviderAudioBlock.class,
            ProviderVideoBlock.class,
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
        assertInstanceOf(ProviderJsonBlock.class, decoded.messages().get(1).contents().get(5))
            .json());
    assertEquals(
        ARGUMENTS_JSON,
        assertInstanceOf(ProviderToolCallBlock.class, decoded.messages().get(2).contents().get(0))
            .toolCall()
            .argumentsJson());
    assertEquals(
        DETAILS_JSON,
        assertInstanceOf(ProviderToolResultBlock.class, decoded.messages().get(3).contents().get(0))
            .detailsJson());
    assertEquals(INPUT_SCHEMA_JSON, decoded.tools().get(0).inputSchemaJson());
    assertEquals(
        new BigDecimal("3.000000000000"), decoded.model().pricing().inputPerMillionTokens());
  }

  /** Every legal cache capability shape must survive the same strict request boundary. */
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

  /**
   * String and raw-JSON boundaries reject duplicate fields and trailing documents symmetrically.
   */
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
            duplicateArguments.path("messages").get(2).path("contents").get(0).path("toolCall");
    toolCall.put("argumentsJson", "{\"q\":\"paris\",\"q\":\"lyon\"}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateArguments.toString()));

    ProviderToolDefinition invalidTool =
        new ProviderToolDefinition(
            "duplicate_schema", "duplicate schema", "{\"type\":\"object\",\"type\":\"array\"}");
    ProviderRequest invalidRequest =
        new ProviderRequest(
            request.model(),
            request.variant(),
            request.messages(),
            List.of(invalidTool),
            request.cacheControl());
    assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidRequest));
  }

  /**
   * Each object layer rejects an extra field, a missing field, and a field with the wrong JSON
   * type, preventing partially compatible persisted payloads.
   */
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
        root -> variant(root).put("extra", true),
        root -> variant(root).remove("id"),
        root -> variant(root).put("maxOutputTokens", "1024"));
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

  /** Unknown enum names and content discriminators must never be interpreted as legacy aliases. */
  @Test
  void rejectsUnknownEnumsAndDiscriminators() {
    assertRejected(root -> message(root, 0).put("role", "DEVELOPER"));
    assertRejected(root -> cacheControl(root).put("retention", "FOREVER"));
    assertRejected(
        root ->
            ((ArrayNode) cacheControl(root).path("breakpoints")).set(0, NODES.textNode("MESSAGE")));
    assertRejected(root -> content(root, 0, 0).put("type", "markdown"));
    assertRejected(root -> content(root, 0, 0).remove("type"));
  }

  /**
   * Malformed roots, collections, numbers, and raw JSON strings are rejected before they can enter
   * the durable request snapshot.
   */
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
    assertRejected(root -> variant(root).put("temperature", Double.NaN));
    assertRejected(root -> variant(root).put("temperature", "0.2"));
    assertRejected(
        root -> ((ArrayNode) variant(root).path("stopSequences")).set(0, NODES.numberNode(1)));
    assertRejected(
        root -> ((ArrayNode) variant(root).path("stopSequences")).set(0, NODES.textNode(" ")));
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
    assertRejected(root -> content(root, 1, 5).put("json", "not-json"));
    assertRejected(root -> content(root, 1, 5).put("json", " "));
  }

  private void assertStrictLayer(
      Consumer<ObjectNode> unknownMutation,
      Consumer<ObjectNode> missingMutation,
      Consumer<ObjectNode> wrongTypeMutation) {
    assertRejected(unknownMutation);
    assertRejected(missingMutation);
    assertRejected(wrongTypeMutation);
  }

  private void assertRejected(Consumer<ObjectNode> mutation) {
    ObjectNode root = canonicalNode();
    mutation.accept(root);
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(root));
  }

  private static ProviderRequest canonicalRequest() {
    ModelVariant defaultVariant =
        new ModelVariant("default", null, 0.7, 0.9, null, null, null, List.of("STOP"), null);
    ModelVariant selectedVariant =
        new ModelVariant(
            "balanced", 1024, 0.2, 0.8, 40, -0.1, 0.1, List.of("END", "STOP"), "medium");
    ModelDescriptor model =
        new ModelDescriptor("openai", "gpt-5-mini", true, true, canonicalPricing());
    ProviderToolCall call = new ProviderToolCall("call-1", "lookup", ARGUMENTS_JSON);
    return new ProviderRequest(
        model,
        selectedVariant,
        List.of(
            new ProviderMessage(
                ProviderMessageRole.SYSTEM,
                List.of(new ProviderTextBlock("You are a careful assistant."))),
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(
                    new ProviderTextBlock("What is the capital of France?"),
                    new ProviderImageBlock("image/png", "data:image/png;base64,AAA"),
                    new ProviderAudioBlock("audio/wav", "https://example.test/audio.wav"),
                    new ProviderVideoBlock("video/mp4", "https://example.test/video.mp4"),
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
        source.model(), source.variant(), source.messages(), source.tools(), cacheCase.control());
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
    return (ObjectNode) content(root, 2, 0).path("toolCall");
  }

  private static ObjectNode toolResult(ObjectNode root) {
    return content(root, 3, 0);
  }

  private record CacheCase(PromptCacheCapability capability, ProviderCacheControl control) {}
}

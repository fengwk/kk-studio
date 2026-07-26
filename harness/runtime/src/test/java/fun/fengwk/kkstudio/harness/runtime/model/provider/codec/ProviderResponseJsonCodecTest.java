package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

class ProviderResponseJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String CANONICAL_RESOURCE =
      "/fun/fengwk/kkstudio/harness/runtime/model/provider/codec/provider-response.json";
  private static final String FIRST_ARGUMENTS = "{\n  \"q\":\"paris\"\n}";
  private static final String SECOND_ARGUMENTS = "{\"city\":\"Paris\", \"units\":\"metric\"}";
  private static final String RAW_USAGE =
      "{\n  \"completion_tokens\":20,\n  \"prompt_tokens\":100\n}";

  private final ProviderResponseJsonCodec codec = new ProviderResponseJsonCodec();

  /**
   * The canonical fixture verifies exact field order, tool-call order, decimal strings, null
   * metadata, and verbatim raw usage JSON in one complete response.
   */
  @Test
  void roundTripsCompleteResponseAndMatchesCanonicalFixture() {
    ProviderResponse response = canonicalResponse();
    String expectedJson = canonicalNode().toString();

    String encoded = codec.encode(response);
    ProviderResponse decoded = codec.decode(encoded);

    assertEquals(expectedJson, encoded);
    assertEquals(response, decoded);
    assertEquals(encoded, codec.encode(decoded));
    assertEquals(response, codec.decodeNode(codec.encodeNode(response)));
    assertEquals(
        List.of("call-1", "call-2"),
        decoded.toolCalls().stream().map(ProviderToolCall::id).toList());
    assertEquals(FIRST_ARGUMENTS, decoded.toolCalls().get(0).argumentsJson());
    assertEquals(SECOND_ARGUMENTS, decoded.toolCalls().get(1).argumentsJson());
    assertEquals(RAW_USAGE, decoded.rawUsageJson());
    assertEquals(new BigDecimal("0.000911250000"), decoded.cost().total());
  }

  /**
   * Nullable metadata also has an explicit string form, while raw usage may be an ordered array.
   */
  @Test
  void roundTripsStringMetadataAndRawUsageArray() {
    String rawUsageJson = " \n[ {\"cached\":true}, 2 ]\n ";
    ProviderResponse response =
        new ProviderResponse(
            null,
            null,
            List.of(),
            ProviderStopReason.TOOL_CALLS,
            new ModelUsage(1, 2, 3, 4, 5, 6, 21),
            zeroCost(),
            "request-7",
            "priority",
            rawUsageJson);

    ProviderResponse decoded = codec.decode(codec.encode(response));

    assertEquals(response, decoded);
    assertEquals("", decoded.text());
    assertEquals("", decoded.thinking());
    assertEquals("request-7", decoded.requestId());
    assertEquals("priority", decoded.serviceTier());
    assertEquals(rawUsageJson, decoded.rawUsageJson());
  }

  /** String, tool-argument, and raw-usage JSON reject duplicate fields and trailing documents. */
  @Test
  void rejectsDuplicateAndTrailingDocumentsAtStrictJsonBoundaries() {
    ProviderResponse response = canonicalResponse();
    String encoded = codec.encode(response);
    String duplicateTopLevel = "{\"text\":\"duplicate\"," + encoded.substring(1);

    assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded + " []"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateTopLevel));

    ObjectNode duplicateArguments = canonicalNode();
    toolCall(duplicateArguments).put("argumentsJson", "{\"city\":\"Paris\",\"city\":\"Lyon\"}");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicateArguments.toString()));

    ProviderResponse invalidArguments =
        new ProviderResponse(
            response.text(),
            response.thinking(),
            List.of(new ProviderToolCall("call", "weather", "{\"q\":1,\"q\":2}")),
            response.stopReason(),
            response.usage(),
            response.cost(),
            response.requestId(),
            response.serviceTier(),
            response.rawUsageJson());
    assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidArguments));

    ProviderResponse invalidUsage =
        new ProviderResponse(
            response.text(),
            response.thinking(),
            response.toolCalls(),
            response.stopReason(),
            response.usage(),
            response.cost(),
            response.requestId(),
            response.serviceTier(),
            "{\"total\":1,\"total\":2}");
    assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidUsage));
  }

  /** Every response object layer independently rejects unknown, missing, and wrong-typed fields. */
  @Test
  void rejectsUnknownMissingAndWrongTypedFieldsAtEveryObjectLayer() {
    assertStrictLayer(
        root -> root.put("extra", true),
        root -> root.remove("text"),
        root -> root.set("toolCalls", NODES.objectNode()));
    assertStrictLayer(
        root -> toolCall(root).put("extra", true),
        root -> toolCall(root).remove("name"),
        root -> toolCall(root).set("argumentsJson", NODES.objectNode()));
    assertStrictLayer(
        root -> usage(root).put("extra", true),
        root -> usage(root).remove("inputTokens"),
        root -> usage(root).put("inputTokens", "100"));
    assertStrictLayer(
        root -> cost(root).put("extra", true),
        root -> cost(root).remove("currency"),
        root -> cost(root).put("input", 0.1));
  }

  /** Unknown stop reasons and non-text enum values are rejected instead of being downgraded. */
  @Test
  void rejectsUnknownOrWrongTypedStopReason() {
    assertRejected(root -> root.put("stopReason", "LEGACY_STOP"));
    assertRejected(root -> root.put("stopReason", 1));
  }

  /**
   * Malformed roots, numeric ranges, raw JSON fields, and absent nullable fields all fail the
   * persistence boundary with IllegalArgumentException.
   */
  @Test
  void rejectsMalformedRootsNumbersJsonAndNullableFields() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("   "));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));

    assertRejected(root -> root.remove("requestId"));
    assertRejected(root -> root.remove("serviceTier"));
    assertRejected(root -> root.put("requestId", 1));
    assertRejected(root -> root.put("serviceTier", false));
    assertRejected(root -> root.putNull("text"));
    assertRejected(root -> root.putNull("rawUsageJson"));
    assertRejected(root -> root.put("rawUsageJson", "not-json"));
    assertRejected(root -> root.put("rawUsageJson", "\"scalar\""));
    assertRejected(root -> root.put("rawUsageJson", " "));

    assertRejected(root -> ((ArrayNode) root.path("toolCalls")).set(0, NODES.numberNode(1)));
    assertRejected(root -> toolCall(root).put("argumentsJson", "[]"));
    assertRejected(root -> toolCall(root).put("argumentsJson", "{"));
    assertRejected(root -> toolCall(root).put("argumentsJson", " "));

    assertRejected(root -> usage(root).put("inputTokens", -1));
    assertRejected(root -> usage(root).put("inputTokens", 1.5));
    assertRejected(
        root ->
            usage(root)
                .set(
                    "inputTokens",
                    NODES.numberNode(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))));
    assertRejected(root -> cost(root).put("input", "bad"));
    assertRejected(root -> cost(root).put("input", "-0.1"));
    assertRejected(root -> cost(root).put("total", "1"));
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

  private static ProviderResponse canonicalResponse() {
    return new ProviderResponse(
        "The capital of France is Paris.",
        "user asked geography",
        List.of(
            new ProviderToolCall("call-1", "lookup", FIRST_ARGUMENTS),
            new ProviderToolCall("call-2", "weather", SECOND_ARGUMENTS)),
        ProviderStopReason.COMPLETED,
        new ModelUsage(100, 20, 0, 50, 0, 5, 175),
        new ModelCost(
            "USD",
            new BigDecimal("0.000450000000"),
            new BigDecimal("0.000180000000"),
            BigDecimal.ZERO,
            new BigDecimal("0.000281250000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("0.000911250000")),
        null,
        null,
        RAW_USAGE);
  }

  private static ModelCost zeroCost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ObjectNode canonicalNode() {
    try {
      return (ObjectNode) OBJECT_MAPPER.readTree(readResource(CANONICAL_RESOURCE));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid canonical provider response fixture", exception);
    }
  }

  private static String readResource(String resource) {
    try (InputStream input =
        Objects.requireNonNull(
            ProviderResponseJsonCodecTest.class.getResourceAsStream(resource), resource)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static ObjectNode toolCall(ObjectNode root) {
    return (ObjectNode) root.path("toolCalls").path(0);
  }

  private static ObjectNode usage(ObjectNode root) {
    return (ObjectNode) root.path("usage");
  }

  private static ObjectNode cost(ObjectNode root) {
    return (ObjectNode) root.path("cost");
  }
}

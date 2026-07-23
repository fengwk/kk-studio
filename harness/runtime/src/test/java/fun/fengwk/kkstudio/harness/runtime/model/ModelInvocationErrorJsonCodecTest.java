package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

class ModelInvocationErrorJsonCodecTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String CANONICAL_RESOURCE =
      "/fun/fengwk/kkstudio/harness/runtime/model/model-invocation-error.json";

  private final ModelInvocationErrorJsonCodec codec = new ModelInvocationErrorJsonCodec();

  /**
   * The canonical error snapshot has exactly two deterministic fields and round-trips unchanged.
   */
  @Test
  void roundTripsCanonicalErrorSnapshot() {
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider temporarily unavailable");
    String expected = canonicalNode().toString();

    assertEquals(expected, codec.encode(error));
    assertEquals(error, codec.decode(expected));
    assertEquals(error, codec.decodeNode(codec.encodeNode(error)));
  }

  /** Every Provider error classification remains explicit at the durable JSON boundary. */
  @Test
  void roundTripsEveryProviderErrorKind() {
    for (ProviderErrorKind kind : ProviderErrorKind.values()) {
      ModelInvocationError error = new ModelInvocationError(kind, "message-" + kind.name());
      assertEquals(error, codec.decode(codec.encode(error)), kind.name());
    }
  }

  /** String decoding rejects duplicate fields and trailing documents before tree projection. */
  @Test
  void rejectsDuplicateFieldsAndTrailingDocuments() {
    String canonical = canonicalNode().toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(canonical + " {}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"kind\":\"TRANSIENT\",\"kind\":\"AUTH\",\"message\":\"x\"}"));
  }

  /** Unknown, missing, and wrong-typed fields are rejected rather than ignored or defaulted. */
  @Test
  void rejectsUnknownMissingAndWrongTypedFields() {
    ObjectNode unknown = canonicalNode().put("extra", true);
    ObjectNode missing = canonicalNode();
    missing.remove("message");
    ObjectNode wrongKind = canonicalNode().put("kind", 1);
    ObjectNode wrongMessage = canonicalNode().put("message", false);

    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(unknown));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(missing));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(wrongKind));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(wrongMessage));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodeNode(canonicalNode().put("kind", "NETWORK")));
  }

  /** Malformed roots, blank semantic values, and null API arguments fail at the boundary. */
  @Test
  void rejectsMalformedBlankAndNullInputs() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("   "));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodeNode(canonicalNode().put("message", " ")));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(NODES.arrayNode()));
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(NullPointerException.class, () -> new ModelInvocationError(null, "message"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationError(ProviderErrorKind.TRANSIENT, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationError(ProviderErrorKind.TRANSIENT, " "));
  }

  private static ObjectNode canonicalNode() {
    try {
      return (ObjectNode) OBJECT_MAPPER.readTree(readResource(CANONICAL_RESOURCE));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "invalid canonical model invocation error fixture", exception);
    }
  }

  private static String readResource(String resource) {
    try (InputStream input =
        Objects.requireNonNull(
            ModelInvocationErrorJsonCodecTest.class.getResourceAsStream(resource), resource)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}

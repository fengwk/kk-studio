package fun.fengwk.kkstudio.harness.runtime.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strict, deterministic JSON codec for {@link ModelInvocationError}. Mirrors the {@link
 * fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec} style: it builds and reads
 * {@link JsonNode} trees explicitly, requires an exact field set, and rejects unknown, missing, or
 * wrong-typed values with {@link IllegalArgumentException}. No Jackson default typing, polymorphic
 * annotations, reflective POJO binding or compatibility aliases are used.
 */
public final class ModelInvocationErrorJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> EXPECTED_FIELDS = Set.of("kind", "message");

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ModelInvocationErrorJsonCodec() {}

  /**
   * Encodes the snapshot to a canonical JSON string. {@code kind} is emitted as the enum name;
   * {@code message} is emitted verbatim.
   */
  public String encode(ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(error));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("cannot encode model invocation error", exception);
    }
  }

  /** Returns the canonical {@link JsonNode} tree used by both {@link #encode} and tests. */
  public JsonNode encodeNode(ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    ObjectNode node = NODES.objectNode();
    node.put("kind", error.kind().name());
    node.put("message", error.message());
    return node;
  }

  public ModelInvocationError decode(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeNode(OBJECT_MAPPER.readTree(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("malformed model invocation error", exception);
    }
  }

  public ModelInvocationError decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    if (!(value instanceof ObjectNode node)) {
      throw new IllegalArgumentException("model invocation error must be an object");
    }
    requireFields(node);
    ProviderErrorKind kind;
    try {
      kind = ProviderErrorKind.valueOf(text(node, "kind"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown provider error kind", exception);
    }
    return new ModelInvocationError(kind, text(node, "message"));
  }

  private static void requireFields(ObjectNode node) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(EXPECTED_FIELDS)) {
      throw new IllegalArgumentException("unexpected model invocation error fields: " + actual);
    }
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }
}

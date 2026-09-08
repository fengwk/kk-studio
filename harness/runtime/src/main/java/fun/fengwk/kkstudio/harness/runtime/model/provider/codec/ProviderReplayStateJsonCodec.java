package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ProviderReplayState} 的严格、确定性 JSON codec。
 *
 * <p>顶层严格字段为 {@code format}、{@code affinity}、{@code sourcePrefixHash}、{@code payload}；
 * 拒绝任何未知、缺失或重复字段。
 */
public final class ProviderReplayStateJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> ROOT_FIELDS =
      orderedSet("format", "affinity", "sourcePrefixHash", "payload");
  private static final Set<String> AFFINITY_FIELDS =
      orderedSet("providerType", "providerName", "connectionGenerationId", "modelName");

  public String encode(ProviderReplayState state) {
    Objects.requireNonNull(state, "state");
    return write(encodeNode(state));
  }

  public ObjectNode encodeNode(ProviderReplayState state) {
    Objects.requireNonNull(state, "state");
    ObjectNode node = NODES.objectNode();
    node.put("format", state.format().wireValue());
    node.set("affinity", encodeAffinity(state.affinity()));
    node.put("sourcePrefixHash", state.sourcePrefixHash());
    node.set("payload", state.payload());
    return node;
  }

  public ProviderReplayState decode(String json) {
    Objects.requireNonNull(json, "json");
    return decodeNode(parse(json));
  }

  public ProviderReplayState decodeNode(JsonNode node) {
    ObjectNode root = object(node, "providerReplayState");
    requireFields(root, ROOT_FIELDS, "providerReplayState");

    ProviderReplayFormat format;
    try {
      format = ProviderReplayFormat.fromWireValue(text(root, "format"));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("unsupported provider replay format");
    }
    ProviderReplayAffinity affinity = decodeAffinity(root.get("affinity"));
    String sourcePrefixHash = text(root, "sourcePrefixHash");
    JsonNode payloadNode = root.get("payload");
    if (payloadNode == null || !payloadNode.isObject()) {
      throw new IllegalArgumentException("providerReplayState.payload must be a JSON object");
    }

    return new ProviderReplayState(format, affinity, sourcePrefixHash, payloadNode);
  }

  private ObjectNode encodeAffinity(ProviderReplayAffinity affinity) {
    ObjectNode node = NODES.objectNode();
    node.put("providerType", affinity.providerType().wireValue());
    node.put("providerName", affinity.providerName());
    node.put("connectionGenerationId", affinity.connectionGenerationId().toString());
    node.put("modelName", affinity.modelName());
    return node;
  }

  private ProviderReplayAffinity decodeAffinity(JsonNode node) {
    ObjectNode object = object(node, "affinity");
    requireFields(object, AFFINITY_FIELDS, "affinity");

    ProviderType providerType;
    try {
      providerType = ProviderType.fromWireValue(text(object, "providerType"));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("unsupported provider type");
    }
    String providerName = text(object, "providerName");
    UUID connectionGenerationId;
    try {
      connectionGenerationId = UUID.fromString(text(object, "connectionGenerationId"));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("affinity.connectionGenerationId must be a valid UUID");
    }
    String modelName = text(object, "modelName");

    return new ProviderReplayAffinity(
        providerType, providerName, connectionGenerationId, modelName);
  }

  private static JsonNode parse(String json) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(json);
      if (node == null) {
        throw new IllegalArgumentException("empty JSON document");
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed provider replay state JSON");
    }
  }

  private static String write(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode provider replay state JSON");
    }
  }

  private static ObjectNode object(JsonNode value, String context) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return (ObjectNode) value;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static void requireFields(ObjectNode node, Set<String> expected, String context) {
    if (node.size() != expected.size()) {
      throw new IllegalArgumentException(
          context + " field count mismatch: expected " + expected.size() + " fields");
    }
    for (String name : expected) {
      if (!node.has(name)) {
        throw new IllegalArgumentException(context + " missing required field: " + name);
      }
    }
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    Collections.addAll(set, values);
    return Collections.unmodifiableSet(set);
  }
}

package fun.fengwk.kkstudio.harness.model.provider.codec;

import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.object;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.orderedSet;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.requireFieldsWithoutEcho;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.text;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.Objects;
import java.util.Set;

/**
 * {@link ProviderReplayState} 的严格、确定性 JSON codec。
 *
 * <p>顶层严格字段为 {@code format}、{@code affinity}、{@code payload}；拒绝任何未知、缺失或重复字段。
 */
public final class ProviderReplayStateJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> ROOT_FIELDS = orderedSet("format", "affinity", "payload");
  private static final Set<String> AFFINITY_FIELDS =
      orderedSet("providerType", "providerName", "modelId");

  public String encode(ProviderReplayState state) {
    Objects.requireNonNull(state, "state");
    return write(encodeNode(state));
  }

  public ObjectNode encodeNode(ProviderReplayState state) {
    Objects.requireNonNull(state, "state");
    ObjectNode node = NODES.objectNode();
    node.put("format", state.format().wireValue());
    node.set("affinity", encodeAffinity(state.affinity()));
    node.set("payload", state.payload());
    return node;
  }

  public ProviderReplayState decode(String json) {
    Objects.requireNonNull(json, "json");
    return decodeNode(parse(json));
  }

  public ProviderReplayState decodeNode(JsonNode node) {
    ObjectNode root = object(node, "providerReplayState");
    requireFieldsWithoutEcho(root, ROOT_FIELDS, "providerReplayState");

    ProviderReplayFormat format;
    try {
      format = ProviderReplayFormat.fromWireValue(text(root, "format"));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("unsupported provider replay format");
    }
    ProviderReplayAffinity affinity = decodeAffinity(root.get("affinity"));
    JsonNode payloadNode = root.get("payload");
    if (payloadNode == null || !payloadNode.isObject()) {
      throw new IllegalArgumentException("providerReplayState.payload must be a JSON object");
    }

    return new ProviderReplayState(format, affinity, payloadNode);
  }

  private ObjectNode encodeAffinity(ProviderReplayAffinity affinity) {
    ObjectNode node = NODES.objectNode();
    node.put("providerType", affinity.providerType().wireValue());
    node.put("providerName", affinity.providerName());
    node.put("modelId", affinity.modelId());
    return node;
  }

  private ProviderReplayAffinity decodeAffinity(JsonNode node) {
    ObjectNode object = object(node, "affinity");
    requireFieldsWithoutEcho(object, AFFINITY_FIELDS, "affinity");

    ProviderType providerType;
    try {
      providerType = ProviderType.fromWireValue(text(object, "providerType"));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("unsupported provider type");
    }
    String providerName = text(object, "providerName");
    String modelId = text(object, "modelId");

    return new ProviderReplayAffinity(providerType, providerName, modelId);
  }

  private static JsonNode parse(String json) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(json);
      // Jackson 3 空白输入返回 MissingNode（Jackson 2 返回 null），两者都必须判为 empty document。
      if (node == null || node.isMissingNode()) {
        throw new IllegalArgumentException("empty JSON document");
      }
      return node;
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed provider replay state JSON");
    }
  }

  private static String write(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode provider replay state JSON");
    }
  }
}

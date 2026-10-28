package fun.fengwk.kkstudio.harness.environment.daemon;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * IDE_LEASE payload 的严格 codec：{@code {round,bindings:[{viewId,documentId,bindingEpoch}]}}。
 *
 * <p>最多 64 条绑定，禁止重复 viewId；round 与 bindingEpoch 为规范正十进制字符串，viewId/documentId 为 canonical UUID。
 */
public final class DaemonIdeLeaseCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> ROOT_FIELDS = Set.of("round", "bindings");
  private static final Set<String> BINDING_FIELDS = Set.of("viewId", "documentId", "bindingEpoch");

  public String encode(DaemonIdeLease lease) {
    Objects.requireNonNull(lease, "lease");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("round", Long.toString(lease.round()));
    ArrayNode bindings = root.putArray("bindings");
    for (DaemonIdeLease.Binding binding : lease.bindings()) {
      ObjectNode node = bindings.addObject();
      node.put("viewId", binding.viewId().toString());
      node.put("documentId", binding.documentId().toString());
      node.put("bindingEpoch", Long.toString(binding.bindingEpoch()));
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode IDE_LEASE payload", error);
    }
  }

  public DaemonIdeLease decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed IDE_LEASE payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("IDE_LEASE payload must be an object");
    }
    for (String field : root.propertyNames()) {
      if (!ROOT_FIELDS.contains(field)) {
        throw new DaemonProtocolException("unexpected IDE_LEASE field: " + field);
      }
    }
    String roundText = requiredString(root, "round", "IDE_LEASE");
    JsonNode bindingsNode = root.get("bindings");
    if (!(bindingsNode instanceof ArrayNode array)) {
      throw new DaemonProtocolException("IDE_LEASE.bindings must be an array");
    }
    if (array.size() > DaemonIdeLease.MAX_BINDINGS) {
      throw new DaemonProtocolException(
          "IDE_LEASE.bindings must not exceed " + DaemonIdeLease.MAX_BINDINGS);
    }
    List<DaemonIdeLease.Binding> bindings = new ArrayList<>(array.size());
    Set<UUID> viewIds = new HashSet<>();
    for (JsonNode item : array) {
      if (!(item instanceof ObjectNode binding)) {
        throw new DaemonProtocolException("IDE_LEASE.binding must be an object");
      }
      for (String field : binding.propertyNames()) {
        if (!BINDING_FIELDS.contains(field)) {
          throw new DaemonProtocolException("unexpected IDE_LEASE binding field: " + field);
        }
      }
      try {
        UUID viewId =
            DaemonWireUuids.parse(
                requiredString(binding, "viewId", "IDE_LEASE binding"), "IDE_LEASE viewId");
        UUID documentId =
            DaemonWireUuids.parse(
                requiredString(binding, "documentId", "IDE_LEASE binding"), "IDE_LEASE documentId");
        long epoch =
            DaemonWireNumbers.positiveLong(
                requiredString(binding, "bindingEpoch", "IDE_LEASE binding"),
                "IDE_LEASE bindingEpoch");
        if (!viewIds.add(viewId)) {
          throw new IllegalArgumentException("IDE_LEASE must not repeat a viewId");
        }
        bindings.add(new DaemonIdeLease.Binding(viewId, documentId, epoch));
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "IDE_LEASE validation failed: " + error.getMessage(), error);
      }
    }
    try {
      return new DaemonIdeLease(
          DaemonWireNumbers.positiveLong(roundText, "IDE_LEASE.round"), bindings);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "IDE_LEASE validation failed: " + error.getMessage(), error);
    }
  }

  private static String requiredString(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new DaemonProtocolException(context + "." + field + " must be a string");
    }
    return value.stringValue();
  }
}

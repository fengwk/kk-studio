package fun.fengwk.kkstudio.harness.runtime.configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict deterministic codec for the compact runtime configuration snapshot. */
public final class RuntimeConfigJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> TOP_FIELDS =
      orderedSet("agent", "model", "environmentName", "toolNames", "skillNames", "yoloEnabled");
  private static final Set<String> AGENT_FIELDS =
      orderedSet("definitionId", "name", "systemPrompt");
  private static final Set<String> MODEL_FIELDS = orderedSet("descriptor", "variant");
  private static final ModelDescriptorJsonCodec MODEL_CODEC = new ModelDescriptorJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public String encode(RuntimeConfigSnapshot snapshot) {
    return write(encodeNode(Objects.requireNonNull(snapshot, "snapshot")));
  }

  public ObjectNode encodeNode(RuntimeConfigSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    ObjectNode root = NODES.objectNode();
    root.set("agent", writeAgent(snapshot.agent()));
    root.set("model", writeModel(snapshot.model()));
    if (snapshot.environmentName() == null) {
      root.putNull("environmentName");
    } else {
      root.put("environmentName", snapshot.environmentName());
    }
    writeNames(root.putArray("toolNames"), snapshot.toolNames());
    writeNames(root.putArray("skillNames"), snapshot.skillNames());
    root.put("yoloEnabled", snapshot.yoloEnabled());
    return root;
  }

  public RuntimeConfigSnapshot decode(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeNode(MAPPER.readTree(json));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed runtime config JSON", error);
    }
  }

  public RuntimeConfigSnapshot decodeNode(JsonNode value) {
    ObjectNode root = requireObject(value, "runtimeConfig");
    requireFields(root, TOP_FIELDS, "runtimeConfig");
    AgentSnapshot agent = readAgent(requireField(root, "agent", "runtimeConfig"));
    ModelSnapshot model = readModel(requireField(root, "model", "runtimeConfig"));
    String environmentName = nullableText(root.get("environmentName"), "environmentName");
    List<String> toolNames = readNames(root.get("toolNames"), "toolNames");
    List<String> skillNames = readNames(root.get("skillNames"), "skillNames");
    boolean yoloEnabled = requiredBoolean(root, "yoloEnabled", "runtimeConfig");
    return new RuntimeConfigSnapshot(
        agent, model, environmentName, toolNames, skillNames, yoloEnabled);
  }

  private static ObjectNode writeAgent(AgentSnapshot agent) {
    ObjectNode node = NODES.objectNode();
    node.put("definitionId", agent.definitionId());
    node.put("name", agent.name());
    if (agent.systemPrompt() == null) {
      node.putNull("systemPrompt");
    } else {
      node.put("systemPrompt", agent.systemPrompt());
    }
    return node;
  }

  private static ObjectNode writeModel(ModelSnapshot model) {
    ObjectNode node = NODES.objectNode();
    node.set("descriptor", MODEL_CODEC.encodeDescriptorNode(model.descriptor()));
    node.set("variant", MODEL_CODEC.encodeVariantNode(model.variant()));
    return node;
  }

  private static void writeNames(ArrayNode array, List<String> names) {
    for (String name : names) {
      array.add(name);
    }
  }

  private static AgentSnapshot readAgent(JsonNode value) {
    ObjectNode node = requireObject(value, "agent");
    requireFields(node, AGENT_FIELDS, "agent");
    long definitionId = requiredPositiveLong(node, "definitionId", "agent");
    String name = requiredText(node, "name", "agent");
    JsonNode promptNode = node.get("systemPrompt");
    if (promptNode == null || (!promptNode.isNull() && !promptNode.isTextual())) {
      throw new IllegalArgumentException("agent.systemPrompt must be text or null");
    }
    return new AgentSnapshot(
        definitionId, name, promptNode.isNull() ? null : promptNode.textValue());
  }

  private static ModelSnapshot readModel(JsonNode value) {
    ObjectNode node = requireObject(value, "model");
    requireFields(node, MODEL_FIELDS, "model");
    ModelDescriptor descriptor =
        MODEL_CODEC.decodeDescriptorNode(requireField(node, "descriptor", "model"));
    ModelVariant variant = MODEL_CODEC.decodeVariantNode(requireField(node, "variant", "model"));
    return new ModelSnapshot(descriptor, variant);
  }

  private static String nullableText(JsonNode value, String field) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text or null");
    }
    return value.textValue();
  }

  private static List<String> readNames(JsonNode value, String field) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    List<String> result = new ArrayList<>(array.size());
    Set<String> seen = new HashSet<>();
    for (JsonNode element : array) {
      if (!element.isTextual() || element.textValue().isBlank()) {
        throw new IllegalArgumentException(field + " must contain non-blank text names");
      }
      if (!seen.add(element.textValue())) {
        throw new IllegalArgumentException(
            field + " contains duplicate name: " + element.textValue());
      }
      result.add(element.textValue());
    }
    return result;
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode runtime config JSON", error);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be a JSON object");
    }
    return object;
  }

  private static JsonNode requireField(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(context + " must declare '" + field + "'");
    }
    return value;
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static long requiredPositiveLong(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() <= 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a positive integer");
    }
    return value.longValue();
  }

  private static boolean requiredBoolean(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static void requireFields(ObjectNode node, Set<String> expected, String name) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          name + " unexpected fields: " + actual + " (expected " + expected + ")");
    }
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return set;
  }
}

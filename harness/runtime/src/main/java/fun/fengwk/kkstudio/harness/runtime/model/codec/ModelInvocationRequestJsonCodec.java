package fun.fengwk.kkstudio.harness.runtime.model.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict JSON codec for the durable ModelInvocationRequest wrapper. */
public final class ModelInvocationRequestJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final ProviderRequestJsonCodec PROVIDER_CODEC = new ProviderRequestJsonCodec();
  private static final ToolDescriptorJsonCodec TOOL_CODEC = new ToolDescriptorJsonCodec();
  private static final Set<String> FIELDS =
      orderedSet("providerRequest", "toolBindings", "skillBindings", "yoloEnabled");
  private static final Set<String> TOOL_FIELDS = orderedSet("descriptor", "environmentName");
  private static final Set<String> SKILL_FIELDS =
      orderedSet("name", "description", "sourceEnvironment");

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public String encode(ModelInvocationRequest request) {
    try {
      return MAPPER.writeValueAsString(encodeNode(request));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode model invocation request", error);
    }
  }

  public ObjectNode encodeNode(ModelInvocationRequest request) {
    if (request == null) {
      throw new NullPointerException("request");
    }
    ObjectNode root = NODES.objectNode();
    root.set("providerRequest", PROVIDER_CODEC.encodeNode(request.providerRequest()));
    ArrayNode tools = root.putArray("toolBindings");
    for (ToolBinding binding : request.toolBindings()) {
      ObjectNode node = tools.addObject();
      node.set("descriptor", TOOL_CODEC.encodeNode(binding.descriptor()));
      if (binding.environmentName() == null) {
        node.putNull("environmentName");
      } else {
        node.put("environmentName", binding.environmentName());
      }
    }
    ArrayNode skills = root.putArray("skillBindings");
    for (SkillBinding skill : request.skillBindings()) {
      ObjectNode node = skills.addObject();
      node.put("name", skill.name());
      node.put("description", skill.description());
      node.put("sourceEnvironment", skill.sourceEnvironment());
    }
    root.put("yoloEnabled", request.yoloEnabled());
    return root;
  }

  public ModelInvocationRequest decode(String json) {
    try {
      return decodeNode(MAPPER.readTree(json));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed model invocation request JSON", error);
    }
  }

  public ModelInvocationRequest decodeNode(JsonNode value) {
    ObjectNode root = object(value, "modelInvocationRequest");
    fields(root, FIELDS, "modelInvocationRequest");
    ProviderRequest providerRequest =
        PROVIDER_CODEC.decodeNode(required(root, "providerRequest", "modelInvocationRequest"));
    List<ToolBinding> bindings = decodeTools(array(root.get("toolBindings"), "toolBindings"));
    List<SkillBinding> skills = decodeSkills(array(root.get("skillBindings"), "skillBindings"));
    JsonNode yolo = required(root, "yoloEnabled", "modelInvocationRequest");
    if (!yolo.isBoolean()) {
      throw new IllegalArgumentException("yoloEnabled must be boolean");
    }
    return new ModelInvocationRequest(providerRequest, bindings, skills, yolo.booleanValue());
  }

  private static List<ToolBinding> decodeTools(ArrayNode array) {
    ArrayList<ToolBinding> result = new ArrayList<>(array.size());
    Set<String> names = new HashSet<>();
    for (JsonNode value : array) {
      ObjectNode node = object(value, "toolBinding");
      fields(node, TOOL_FIELDS, "toolBinding");
      ToolDescriptor descriptor =
          TOOL_CODEC.decodeNode(required(node, "descriptor", "toolBinding"));
      JsonNode env = node.get("environmentName");
      String environmentName = env == null || env.isNull() ? null : text(env, "environmentName");
      ToolBinding binding = ToolBinding.of(descriptor, environmentName);
      if (!names.add(descriptor.name())) {
        throw new IllegalArgumentException("duplicate tool binding: " + descriptor.name());
      }
      result.add(binding);
    }
    return List.copyOf(result);
  }

  private static List<SkillBinding> decodeSkills(ArrayNode array) {
    ArrayList<SkillBinding> result = new ArrayList<>(array.size());
    Set<String> names = new HashSet<>();
    for (JsonNode value : array) {
      ObjectNode node = object(value, "skillBinding");
      fields(node, SKILL_FIELDS, "skillBinding");
      SkillBinding skill =
          new SkillBinding(
              text(required(node, "name", "skillBinding"), "name"),
              text(required(node, "description", "skillBinding"), "description"),
              text(required(node, "sourceEnvironment", "skillBinding"), "sourceEnvironment"));
      if (!names.add(skill.name())) {
        throw new IllegalArgumentException("duplicate skill binding: " + skill.name());
      }
      result.add(skill);
    }
    return List.copyOf(result);
  }

  private static ArrayNode array(JsonNode value, String name) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(name + " must be an array");
    }
    return array;
  }

  private static ObjectNode object(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return object;
  }

  private static JsonNode required(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(context + " must declare " + field);
    }
    return value;
  }

  private static String text(JsonNode value, String field) {
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static void fields(ObjectNode node, Set<String> expected, String context) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(context + " unexpected fields: " + actual);
    }
  }

  private static Set<String> orderedSet(String... names) {
    return new LinkedHashSet<>(List.of(names));
  }
}

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
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 严格 deterministic 的 {@link RuntimeConfigSnapshot} JSON codec。
 *
 * <p>codec 边界拒绝：
 *
 * <ul>
 *   <li>未知顶层 / 子对象字段；
 *   <li>缺失必填字段；
 *   <li>错误类型（如非 integer 的 numeric 字段、非 string 的 enum）；
 *   <li>trailing token（共享 {@link ObjectMapper} 启用 {@link
 *       DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；
 *   <li>duplicate field（启用 {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION}）。
 * </ul>
 *
 * <p>字段顺序固定为 {@code agent, model, tools, skills, yoloEnabled}；{@code agent.systemPrompt} 显式输出其值。
 * {@code model.descriptor} 与 {@code model.variant} 直接委派给 {@link ModelDescriptorJsonCodec}；{@code
 * tools[].descriptor} 直接委派给 {@link ToolDescriptorJsonCodec}，本 codec 不复制任何 model / tool descriptor
 * 字段实现。
 */
public final class RuntimeConfigJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> TOP_FIELDS =
      orderedSet("agent", "model", "tools", "skills", "yoloEnabled");
  private static final Set<String> AGENT_FIELDS =
      orderedSet("definitionId", "name", "systemPrompt");
  private static final Set<String> MODEL_FIELDS = orderedSet("descriptor", "variant");
  private static final Set<String> TOOL_FIELDS = orderedSet("descriptor", "environmentName");
  private static final Set<String> SKILL_FIELDS =
      orderedSet("name", "description", "sourceEnvironment");

  private static final ToolDescriptorJsonCodec TOOL_CODEC = new ToolDescriptorJsonCodec();
  private static final ModelDescriptorJsonCodec MODEL_CODEC = new ModelDescriptorJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public RuntimeConfigJsonCodec() {}

  /** 把快照编码为 deterministic canonical JSON 文本。 */
  public String encode(RuntimeConfigSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    return write(encodeNode(snapshot));
  }

  /** 把快照编码为 deterministic canonical {@link ObjectNode}。 */
  public ObjectNode encodeNode(RuntimeConfigSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    ObjectNode node = NODES.objectNode();
    node.set("agent", writeAgent(snapshot.agent()));
    node.set("model", writeModel(snapshot.model()));
    ArrayNode tools = node.putArray("tools");
    for (ToolBinding binding : snapshot.tools()) {
      tools.add(writeTool(binding));
    }
    ArrayNode skills = node.putArray("skills");
    for (SkillSnapshot skill : snapshot.skills()) {
      skills.add(writeSkill(skill));
    }
    node.put("yoloEnabled", snapshot.yoloEnabled());
    return node;
  }

  /** 解码单个 canonical JSON 文本。 */
  public RuntimeConfigSnapshot decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed runtime config JSON", error);
    }
    return decodeNode(root);
  }

  /** 从任意 {@link JsonNode} 解码快照。 */
  public RuntimeConfigSnapshot decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode root = requireObject(value, "runtimeConfig");
    requireFields(root, TOP_FIELDS, "runtimeConfig");

    AgentSnapshot agent = readAgent(requireField(root, "agent", "runtimeConfig"));
    ModelSnapshot model = readModel(requireField(root, "model", "runtimeConfig"));

    ArrayNode toolsNode = requireArray(root.get("tools"), "tools");
    List<ToolBinding> tools = new ArrayList<>(toolsNode.size());
    Set<String> providerNames = new HashSet<>();
    for (JsonNode element : toolsNode) {
      ToolBinding binding = readTool(element);
      String providerName = binding.descriptor().name();
      if (!providerNames.add(providerName)) {
        throw new IllegalArgumentException(
            "tools contains duplicate provider tool name: " + providerName);
      }
      tools.add(binding);
    }

    ArrayNode skillsNode = requireArray(root.get("skills"), "skills");
    List<SkillSnapshot> skills = new ArrayList<>(skillsNode.size());
    Set<String> skillNames = new HashSet<>();
    for (JsonNode element : skillsNode) {
      SkillSnapshot skill = readSkill(element);
      if (!skillNames.add(skill.name())) {
        throw new IllegalArgumentException("skills contains duplicate name: " + skill.name());
      }
      skills.add(skill);
    }

    boolean yoloEnabled = requiredBoolean(root, "yoloEnabled", "runtimeConfig");

    return new RuntimeConfigSnapshot(agent, model, tools, skills, yoloEnabled);
  }

  // ---------- Subtree writers ----------

  private static ObjectNode writeAgent(AgentSnapshot agent) {
    ObjectNode node = NODES.objectNode();
    node.put("definitionId", agent.definitionId());
    node.put("name", agent.name());
    node.put("systemPrompt", agent.systemPrompt());
    return node;
  }

  private static ObjectNode writeModel(ModelSnapshot model) {
    ObjectNode node = NODES.objectNode();
    node.set("descriptor", MODEL_CODEC.encodeDescriptorNode(model.descriptor()));
    node.set("variant", MODEL_CODEC.encodeVariantNode(model.variant()));
    return node;
  }

  private static ObjectNode writeTool(ToolBinding binding) {
    ObjectNode node = NODES.objectNode();
    node.set("descriptor", TOOL_CODEC.encodeNode(binding.descriptor()));
    if (binding.environmentName() == null) {
      node.putNull("environmentName");
    } else {
      node.put("environmentName", binding.environmentName());
    }
    return node;
  }

  private static ObjectNode writeSkill(SkillSnapshot skill) {
    ObjectNode node = NODES.objectNode();
    node.put("name", skill.name());
    node.put("description", skill.description());
    node.put("sourceEnvironment", skill.sourceEnvironment());
    return node;
  }

  // ---------- Subtree readers ----------

  private static AgentSnapshot readAgent(JsonNode value) {
    ObjectNode node = requireObject(value, "agent");
    requireFields(node, AGENT_FIELDS, "agent");
    long definitionId = requiredPositiveLong(node, "definitionId", "agent");
    String name = requiredText(node, "name", "agent");
    JsonNode promptNode = node.get("systemPrompt");
    if (promptNode == null || !promptNode.isNull() && !promptNode.isTextual()) {
      throw new IllegalArgumentException("agent.systemPrompt must be text or null");
    }
    String systemPrompt = promptNode.isNull() ? null : promptNode.textValue();
    return new AgentSnapshot(definitionId, name, systemPrompt);
  }

  private static ModelSnapshot readModel(JsonNode value) {
    ObjectNode node = requireObject(value, "model");
    requireFields(node, MODEL_FIELDS, "model");
    ModelDescriptor descriptor =
        MODEL_CODEC.decodeDescriptorNode(requireField(node, "descriptor", "model"));
    ModelVariant variant = MODEL_CODEC.decodeVariantNode(requireField(node, "variant", "model"));
    return new ModelSnapshot(descriptor, variant);
  }

  private static ToolBinding readTool(JsonNode value) {
    ObjectNode node = requireObject(value, "tool");
    requireFields(node, TOOL_FIELDS, "tool");
    ToolDescriptor descriptor = TOOL_CODEC.decodeNode(requireField(node, "descriptor", "tool"));
    JsonNode envNode = node.get("environmentName");
    String environmentName;
    if (envNode == null || envNode.isNull()) {
      environmentName = null;
    } else {
      if (!envNode.isTextual()) {
        throw new IllegalArgumentException("tool.environmentName must be text or null");
      }
      environmentName = envNode.textValue();
      if (environmentName.isBlank()) {
        throw new IllegalArgumentException("tool.environmentName must not be blank");
      }
    }
    if (environmentName == null) {
      return ToolBinding.of(descriptor);
    }
    return ToolBinding.of(descriptor, environmentName);
  }

  private static SkillSnapshot readSkill(JsonNode value) {
    ObjectNode node = requireObject(value, "skill");
    requireFields(node, SKILL_FIELDS, "skill");
    return new SkillSnapshot(
        requiredText(node, "name", "skill"),
        requiredText(node, "description", "skill"),
        requiredText(node, "sourceEnvironment", "skill"));
  }

  // ---------- Helpers ----------

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

  private static ArrayNode requireArray(JsonNode value, String name) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(name + " must be a JSON array");
    }
    return array;
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
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    String text = value.textValue();
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(context + "." + field + " must be non-blank");
    }
    return text;
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

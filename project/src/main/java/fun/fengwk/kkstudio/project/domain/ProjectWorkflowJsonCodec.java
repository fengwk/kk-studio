package fun.fengwk.kkstudio.project.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ProjectWorkflow} 的严格 JSON 编解码：workflow 配置整体保存，JSON 是唯一事实源。
 *
 * <p>规范形状是 {@code {"states":[...]}}，数组顺序即展示顺序。解析拒绝 duplicate field、trailing token、
 * unknown/missing/null field、错误类型，以及非法状态、边与额度组合（后者由领域对象判定）。编码输出确定性规范文本： 省略 {@code enabled=true}、空
 * {@code next} 与空缺可选字段，使相同配置始终得到相同文本，可用于版本比较与请求指纹。
 */
public final class ProjectWorkflowJsonCodec {

  private static final String CONTEXT = "project workflow";
  private static final String STATE_CONTEXT = CONTEXT + " state";
  private static final Set<String> ROOT_FIELDS = Set.of("states");
  private static final Set<String> STATE_FIELDS =
      Set.of("state", "name", "agent", "environment", "instructions", "maxRuns", "enabled", "next");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 解码 workflow 配置；任何非法 JSON 结构或非法 workflow 语义都抛 {@link IllegalArgumentException}。 */
  public ProjectWorkflow decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed " + CONTEXT + " JSON", error);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException(CONTEXT + " must be a JSON object");
    }
    ObjectNode node = (ObjectNode) root;
    rejectUnknownFields(node, ROOT_FIELDS, CONTEXT);
    JsonNode states = required(node, "states", CONTEXT);
    if (!states.isArray()) {
      throw new IllegalArgumentException(CONTEXT + ".states must be an array");
    }
    List<ProjectWorkflowState> parsed = new ArrayList<>();
    for (JsonNode element : states) {
      parsed.add(decodeState(element));
    }
    return new ProjectWorkflow(parsed);
  }

  /** 编码为确定性规范 JSON 文本。 */
  public String encode(ProjectWorkflow workflow) {
    Objects.requireNonNull(workflow, "workflow");
    ObjectNode root = NODES.objectNode();
    ArrayNode states = root.putArray("states");
    for (ProjectWorkflowState state : workflow.states()) {
      ObjectNode entry = states.addObject();
      entry.put("state", state.state().value());
      entry.put("name", state.name());
      if (state.agent() != null) {
        entry.put("agent", state.agent());
      }
      if (state.environment() != null) {
        entry.put("environment", state.environment());
      }
      if (state.instructions() != null) {
        entry.put("instructions", state.instructions());
      }
      if (state.maxRuns() != null) {
        entry.put("maxRuns", state.maxRuns());
      }
      if (!state.enabled()) {
        entry.put("enabled", false);
      }
      if (!state.next().isEmpty()) {
        ArrayNode next = entry.putArray("next");
        for (ProjectStateCode target : state.next()) {
          next.add(target.value());
        }
      }
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode " + CONTEXT + " JSON", error);
    }
  }

  private static ProjectWorkflowState decodeState(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(STATE_CONTEXT + " must be a JSON object");
    }
    ObjectNode node = (ObjectNode) value;
    rejectUnknownFields(node, STATE_FIELDS, STATE_CONTEXT);
    ProjectStateCode state = ProjectStateCode.of(text(node, "state", STATE_CONTEXT));
    String name = text(node, "name", STATE_CONTEXT);
    String agent = optionalText(node, "agent", STATE_CONTEXT);
    String environment = optionalText(node, "environment", STATE_CONTEXT);
    String instructions = optionalText(node, "instructions", STATE_CONTEXT);
    Integer maxRuns = optionalInt(node, "maxRuns", STATE_CONTEXT);
    boolean enabled = optionalBoolean(node, "enabled", STATE_CONTEXT);
    List<ProjectStateCode> next = optionalStateCodes(node, "next", STATE_CONTEXT);
    return new ProjectWorkflowState(
        state, name, agent, environment, instructions, maxRuns, enabled, next);
  }

  private static void rejectUnknownFields(ObjectNode node, Set<String> allowed, String context) {
    List<String> unknown = new ArrayList<>();
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      String field = names.next();
      if (!allowed.contains(field)) {
        unknown.add(field);
      }
    }
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(context + " has unknown fields " + unknown);
    }
  }

  private static JsonNode required(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(context + " must declare non-null " + field);
    }
    return value;
  }

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be a string");
    }
    return value.textValue();
  }

  private static String optionalText(ObjectNode node, String field, String context) {
    return node.has(field) ? text(node, field, context) : null;
  }

  private static Integer optionalInt(ObjectNode node, String field, String context) {
    if (!node.has(field)) {
      return null;
    }
    JsonNode value = required(node, field, context);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(context + "." + field + " must be a 32-bit integer");
    }
    return value.intValue();
  }

  private static boolean optionalBoolean(ObjectNode node, String field, String context) {
    if (!node.has(field)) {
      return true;
    }
    JsonNode value = required(node, field, context);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static List<ProjectStateCode> optionalStateCodes(
      ObjectNode node, String field, String context) {
    if (!node.has(field)) {
      return List.of();
    }
    JsonNode value = required(node, field, context);
    if (!value.isArray()) {
      throw new IllegalArgumentException(context + "." + field + " must be an array");
    }
    List<ProjectStateCode> codes = new ArrayList<>();
    for (JsonNode element : value) {
      if (element == null || !element.isTextual()) {
        throw new IllegalArgumentException(
            context + "." + field + " must contain state code strings");
      }
      codes.add(ProjectStateCode.of(element.textValue()));
    }
    return codes;
  }
}

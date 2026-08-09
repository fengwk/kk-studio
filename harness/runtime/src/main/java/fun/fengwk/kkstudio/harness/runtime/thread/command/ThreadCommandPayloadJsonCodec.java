package fun.fengwk.kkstudio.harness.runtime.thread.command;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 7 类 typed Thread command payload 的严格、确定性 JSON codec。
 *
 * <p>command type 本身不编码：durable {@code command_type} 单列与 HTTP DTO 外层 discriminator 负责类型。
 * USER/CUSTOM 的 {@code message} 子树委派 {@link AgentMessageJsonCodec}；SET_MODEL 携带完整 {@link
 * ModelSelection}；SET_ENVIRONMENT 的 {@code environmentName} 为可空 canonical 逻辑路由名称，null 表示 clear。
 *
 * <p>codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON null（除规定 optional 字段）；trailing token（共享 {@link
 * ObjectMapper} 启用 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用
 * {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION}）。字段顺序固定；list 顺序保留。
 */
public final class ThreadCommandPayloadJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> USER_MESSAGE_FIELDS = orderedSet("message");
  private static final Set<String> CUSTOM_MESSAGE_FIELDS = orderedSet("message");
  private static final Set<String> SET_AGENT_FIELDS = orderedSet("agentName");
  private static final Set<String> SET_MODEL_FIELDS = orderedSet("model");
  private static final Set<String> SET_ACTIVE_TOOLS_FIELDS = orderedSet("activeTools");
  private static final Set<String> SET_YOLO_FIELDS = orderedSet("yoloEnabled");
  private static final Set<String> SET_ENVIRONMENT_FIELDS = orderedSet("environmentName");
  private static final Set<String> MODEL_SELECTION_FIELDS =
      orderedSet("providerName", "modelName", "variant");

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ThreadCommandPayloadJsonCodec() {}

  /** 把 {@link ThreadCommandPayload} 编码为 canonical JSON 文本；不包含 command type discriminator。 */
  public String encode(ThreadCommandPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return write(encodeNode(payload));
  }

  /**
   * 把 canonical JSON 文本按指定 {@link ThreadCommandType} 解码为对应 payload；任何非法结构抛 {@link
   * IllegalArgumentException}。
   */
  public ThreadCommandPayload decode(ThreadCommandType type, String json) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(
          "malformed " + type.name() + " command payload JSON", error);
    }
    if (root == null) {
      throw new IllegalArgumentException("malformed " + type.name() + " command payload JSON");
    }
    return switch (type) {
      case USER_MESSAGE -> decodeUserMessage(root);
      case CUSTOM_MESSAGE -> decodeCustomMessage(root);
      case SET_AGENT -> decodeSetAgent(root);
      case SET_MODEL -> decodeSetModel(root);
      case SET_ACTIVE_TOOLS -> decodeSetActiveTools(root);
      case SET_YOLO -> decodeSetYolo(root);
      case SET_ENVIRONMENT -> decodeSetEnvironment(root);
    };
  }

  // ---------- 编码器 ----------

  private static ObjectNode encodeNode(ThreadCommandPayload payload) {
    return switch (payload) {
      case UserMessageCommandPayload value -> NODES
          .objectNode()
          .set("message", MESSAGE_CODEC.encodeNode(value.message()));
      case CustomMessageCommandPayload value -> NODES
          .objectNode()
          .set("message", MESSAGE_CODEC.encodeNode(value.message()));
      case SetAgentCommandPayload value -> NODES.objectNode().put("agentName", value.agentName());
      case SetModelCommandPayload value -> NODES
          .objectNode()
          .set("model", encodeModelSelection(value.model()));
      case SetActiveToolsCommandPayload value -> {
        ObjectNode node = NODES.objectNode();
        ArrayNode activeTools = node.putArray("activeTools");
        for (String activeTool : value.activeTools()) {
          activeTools.add(activeTool);
        }
        yield node;
      }
      case SetYoloCommandPayload value -> NODES
          .objectNode()
          .put("yoloEnabled", value.yoloEnabled());
      case SetEnvironmentCommandPayload value -> value.environmentName() == null
          ? NODES.objectNode().putNull("environmentName")
          : NODES.objectNode().put("environmentName", value.environmentName().value());
    };
  }

  private static ObjectNode encodeModelSelection(ModelSelection selection) {
    ObjectNode node = NODES.objectNode();
    node.put("providerName", selection.providerName());
    node.put("modelName", selection.modelName());
    node.put("variant", selection.variant());
    return node;
  }

  // ---------- 解码器 ----------

  private static UserMessageCommandPayload decodeUserMessage(JsonNode value) {
    ObjectNode node = requireObject(value, "USER_MESSAGE");
    requireExactFields(node, USER_MESSAGE_FIELDS, "USER_MESSAGE");
    return new UserMessageCommandPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static CustomMessageCommandPayload decodeCustomMessage(JsonNode value) {
    ObjectNode node = requireObject(value, "CUSTOM_MESSAGE");
    requireExactFields(node, CUSTOM_MESSAGE_FIELDS, "CUSTOM_MESSAGE");
    return new CustomMessageCommandPayload(MESSAGE_CODEC.decodeNode(node.get("message")));
  }

  private static SetAgentCommandPayload decodeSetAgent(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_AGENT");
    requireExactFields(node, SET_AGENT_FIELDS, "SET_AGENT");
    return new SetAgentCommandPayload(canonicalText(node, "agentName", "SET_AGENT"));
  }

  private static SetModelCommandPayload decodeSetModel(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_MODEL");
    requireExactFields(node, SET_MODEL_FIELDS, "SET_MODEL");
    return new SetModelCommandPayload(decodeModelSelection(node.get("model")));
  }

  private static SetActiveToolsCommandPayload decodeSetActiveTools(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_ACTIVE_TOOLS");
    requireExactFields(node, SET_ACTIVE_TOOLS_FIELDS, "SET_ACTIVE_TOOLS");
    ArrayNode activeToolsNode =
        requireArray(node.get("activeTools"), "SET_ACTIVE_TOOLS.activeTools");
    List<String> activeTools = new ArrayList<>(activeToolsNode.size());
    for (JsonNode activeTool : activeToolsNode) {
      if (!activeTool.isTextual()) {
        throw new IllegalArgumentException("SET_ACTIVE_TOOLS.activeTools elements must be text");
      }
      activeTools.add(
          CommandValueValidation.requireCanonicalName(
              activeTool.textValue(), "SET_ACTIVE_TOOLS.activeTools element"));
    }
    return new SetActiveToolsCommandPayload(activeTools);
  }

  private static SetYoloCommandPayload decodeSetYolo(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_YOLO");
    requireExactFields(node, SET_YOLO_FIELDS, "SET_YOLO");
    return new SetYoloCommandPayload(requiredBoolean(node, "yoloEnabled", "SET_YOLO"));
  }

  private static SetEnvironmentCommandPayload decodeSetEnvironment(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_ENVIRONMENT");
    requireExactFields(node, SET_ENVIRONMENT_FIELDS, "SET_ENVIRONMENT");
    JsonNode environmentName = node.get("environmentName");
    if (environmentName.isNull()) {
      return new SetEnvironmentCommandPayload(null);
    }
    if (!environmentName.isTextual()) {
      throw new IllegalArgumentException("SET_ENVIRONMENT.environmentName must be text or null");
    }
    return new SetEnvironmentCommandPayload(new EnvironmentName(environmentName.textValue()));
  }

  private static ModelSelection decodeModelSelection(JsonNode value) {
    ObjectNode node = requireObject(value, "SET_MODEL.model");
    requireExactFields(node, MODEL_SELECTION_FIELDS, "SET_MODEL.model");
    return new ModelSelection(
        canonicalText(node, "providerName", "SET_MODEL.model"),
        canonicalText(node, "modelName", "SET_MODEL.model"),
        canonicalText(node, "variant", "SET_MODEL.model"));
  }

  // ---------- 通用工具方法 ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode thread command payload JSON", error);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String context) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return object;
  }

  private static ArrayNode requireArray(JsonNode value, String context) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(context + " must be a JSON array");
    }
    return array;
  }

  private static String canonicalText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return CommandValueValidation.requireCanonicalName(value.textValue(), context + "." + field);
  }

  private static boolean requiredBoolean(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static void requireExactFields(ObjectNode node, Set<String> expected, String context) {
    Set<String> actual = new LinkedHashSet<>();
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      actual.add(names.next());
    }
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          context + " unexpected fields: " + actual + " (expected " + expected + ")");
    }
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return Set.copyOf(set);
  }
}

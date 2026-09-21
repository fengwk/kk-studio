package fun.fengwk.kkstudio.platform.environment.registry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code environment_connection.skill_state} 与 {@code recent_events} 两个 JSONB 数组的严格 codec。
 *
 * <p>两列只由本进程写入，因此解码对未知字段、重复键、尾随内容、错误类型与非法取值一律 fail-closed：损坏的持久化投影绝不静默降级成 「没有安装事实」。
 */
public final class EnvironmentStateCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Set<String> SKILL_STATE_FIELDS =
      Set.of("packageName", "status", "installedCommit", "localPath", "error");
  private static final Set<String> EVENT_FIELDS = Set.of("time", "level", "type", "message");

  public String encodeSkillState(List<EnvironmentSkillState> states) {
    Objects.requireNonNull(states, "states");
    ArrayNode root = MAPPER.createArrayNode();
    for (EnvironmentSkillState state : states) {
      Objects.requireNonNull(state, "states[]");
      ObjectNode node = root.addObject();
      node.put("packageName", state.packageName());
      node.put("status", state.status());
      putNullable(node, "installedCommit", state.installedCommit());
      putNullable(node, "localPath", state.localPath());
      putNullable(node, "error", state.error());
    }
    return write(root, "skill_state");
  }

  public List<EnvironmentSkillState> decodeSkillState(String json) {
    ArrayNode root = readArray(json, "skill_state");
    List<EnvironmentSkillState> states = new ArrayList<>(root.size());
    for (JsonNode element : root) {
      ObjectNode node = requireObject(element, "skill_state", SKILL_STATE_FIELDS);
      states.add(
          new EnvironmentSkillState(
              text(node, "packageName", true),
              text(node, "status", true),
              text(node, "installedCommit", false),
              text(node, "localPath", false),
              text(node, "error", false)));
    }
    return List.copyOf(states);
  }

  /** 编码单条事件，供围栏 SQL 以 {@code ?::jsonb} 原子追加。 */
  public String encodeEvent(EnvironmentEvent event) {
    Objects.requireNonNull(event, "event");
    return write(eventNode(event), "recent_events");
  }

  public String encodeEvents(List<EnvironmentEvent> events) {
    Objects.requireNonNull(events, "events");
    ArrayNode root = MAPPER.createArrayNode();
    for (EnvironmentEvent event : events) {
      Objects.requireNonNull(event, "events[]");
      root.add(eventNode(event));
    }
    return write(root, "recent_events");
  }

  private static ObjectNode eventNode(EnvironmentEvent event) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("time", event.time().toString());
    node.put("level", event.level());
    node.put("type", event.type());
    node.put("message", event.message());
    return node;
  }

  public List<EnvironmentEvent> decodeEvents(String json) {
    ArrayNode root = readArray(json, "recent_events");
    List<EnvironmentEvent> events = new ArrayList<>(root.size());
    for (JsonNode element : root) {
      ObjectNode node = requireObject(element, "recent_events", EVENT_FIELDS);
      events.add(
          new EnvironmentEvent(
              instant(node),
              text(node, "level", true),
              text(node, "type", true),
              text(node, "message", true)));
    }
    return List.copyOf(events);
  }

  private static Instant instant(ObjectNode node) {
    JsonNode value = node.get("time");
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException("recent_events.time must be text");
    }
    try {
      return Instant.parse(value.textValue());
    } catch (DateTimeParseException error) {
      throw new IllegalStateException("recent_events.time must be an ISO-8601 instant", error);
    }
  }

  private static void putNullable(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static String text(ObjectNode node, String field, boolean required) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      if (required) {
        throw new IllegalStateException("environment state field is required: " + field);
      }
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalStateException("environment state field must be text: " + field);
    }
    return value.textValue();
  }

  private static ObjectNode requireObject(JsonNode element, String column, Set<String> fields) {
    if (!(element instanceof ObjectNode node)) {
      throw new IllegalStateException(column + " elements must be objects");
    }
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!fields.contains(field)) {
                throw new IllegalStateException("unexpected " + column + " field: " + field);
              }
            });
    return node;
  }

  private static ArrayNode readArray(String json, String column) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("malformed " + column + " JSON", error);
    }
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalStateException(column + " must be a JSON array");
    }
    return array;
  }

  private static String write(JsonNode node, String column) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode " + column, error);
    }
  }
}

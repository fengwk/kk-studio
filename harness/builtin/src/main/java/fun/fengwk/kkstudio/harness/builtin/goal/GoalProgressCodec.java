package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code goal.progress} CUSTOM snapshot 的严格、确定性 JSON 编解码器。
 *
 * <p>要求 {@code schemaVersion = 1}，{@code dataJson} 精确包含且仅包含 4 个字段（{@code goalId}、{@code status}、
 * {@code reason}、{@code reportedAt}）。未知 / 缺失 / 重复字段、尾随 token、非法状态或时间戳一律 fail-closed 拒绝。
 */
final class GoalProgressCodec {

  static final int SCHEMA_VERSION = 1;

  private static final Set<String> FIELDS = Set.of("goalId", "status", "reason", "reportedAt");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  String encode(GoalProgress progress) {
    return write(encodeNode(progress));
  }

  /** 编码为 JSON 对象节点，供 envelope 组合。 */
  ObjectNode encodeNode(GoalProgress progress) {
    Objects.requireNonNull(progress, "progress");
    ObjectNode node = MAPPER.createObjectNode();
    node.put("goalId", progress.goalId().toString());
    node.put("status", progress.status().wireValue());
    node.put("reason", progress.reason());
    node.put("reportedAt", progress.reportedAt().toString());
    return node;
  }

  GoalProgress decode(CustomStateSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (snapshot.schemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "unsupported goal progress schemaVersion: " + snapshot.schemaVersion());
    }
    JsonNode parsed = parse(snapshot.dataJson(), "goal progress");
    if (!(parsed instanceof ObjectNode node)) {
      throw new IllegalArgumentException("goal progress must be an object");
    }
    requireFields(node);
    UUID goalId;
    try {
      goalId = UUID.fromString(requiredText(node, "goalId"));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("goal progress.goalId must be a UUID", error);
    }
    return new GoalProgress(
        goalId,
        GoalStatus.parseTerminal(requiredText(node, "status")),
        requiredText(node, "reason"),
        instant(node, "reportedAt"));
  }

  JsonNode parse(String json, String context) {
    Objects.requireNonNull(json, "json");
    try {
      JsonNode value = MAPPER.readTree(json);
      if (value == null) {
        throw new IllegalArgumentException(context + " must not be empty");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed " + context + " JSON", error);
    }
  }

  private static void requireFields(ObjectNode node) {
    if (node.size() != FIELDS.size()) {
      throw new IllegalArgumentException("goal progress fields must be exactly " + FIELDS);
    }
    for (String field : FIELDS) {
      if (!node.has(field)) {
        throw new IllegalArgumentException("goal progress must declare " + field);
      }
    }
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("goal progress." + field + " must be text");
    }
    return value.textValue();
  }

  private static Instant instant(ObjectNode node, String field) {
    String value = requiredText(node, field);
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException("goal progress." + field + " must be an instant", error);
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode goal progress JSON", error);
    }
  }
}

package fun.fengwk.kkstudio.harness.plugins.goal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;

/** Goal CUSTOM snapshot 的严格、确定性 JSON codec。 */
final class GoalStateCodec {

  static final int SCHEMA_VERSION = 1;

  private static final Set<String> FIELDS =
      Set.of("objective", "tokenBudget", "status", "reason", "createdAt", "updatedAt");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  String encode(GoalState state) {
    Objects.requireNonNull(state, "state");
    ObjectNode node = MAPPER.createObjectNode();
    node.put("objective", state.objective());
    if (state.tokenBudget() == null) {
      node.putNull("tokenBudget");
    } else {
      node.put("tokenBudget", state.tokenBudget());
    }
    node.put("status", state.status().wireValue());
    if (state.reason() == null) {
      node.putNull("reason");
    } else {
      node.put("reason", state.reason());
    }
    node.put("createdAt", state.createdAt().toString());
    node.put("updatedAt", state.updatedAt().toString());
    return write(node);
  }

  GoalState decode(CustomEntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.schemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "unsupported goal state schemaVersion: " + payload.schemaVersion());
    }
    JsonNode parsed = parse(payload.dataJson(), "goal state");
    if (!(parsed instanceof ObjectNode node)) {
      throw new IllegalArgumentException("goal state must be an object");
    }
    requireFields(node);
    return new GoalState(
        requiredText(node, "objective"),
        nullablePositiveLong(node, "tokenBudget"),
        GoalStatus.parse(requiredText(node, "status")),
        nullableText(node, "reason"),
        instant(node, "createdAt"),
        instant(node, "updatedAt"));
  }

  String envelope(GoalState state) {
    ObjectNode root = MAPPER.createObjectNode();
    if (state == null) {
      root.putNull("goal");
    } else {
      root.set("goal", parse(encode(state), "goal state"));
    }
    return write(root);
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
      throw new IllegalArgumentException("goal state fields must be exactly " + FIELDS);
    }
    for (String field : FIELDS) {
      if (!node.has(field)) {
        throw new IllegalArgumentException("goal state must declare " + field);
      }
    }
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("goal state." + field + " must be text");
    }
    return value.textValue();
  }

  private static String nullableText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new IllegalArgumentException("goal state must declare " + field);
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("goal state." + field + " must be text or null");
    }
    return value.textValue();
  }

  private static Long nullablePositiveLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new IllegalArgumentException("goal state must declare " + field);
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
      throw new IllegalArgumentException(
          "goal state." + field + " must be a positive long or null");
    }
    return value.longValue();
  }

  private static Instant instant(ObjectNode node, String field) {
    String value = requiredText(node, field);
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException("goal state." + field + " must be an instant", error);
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode goal state JSON", error);
    }
  }
}

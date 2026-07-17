package fun.fengwk.kkstudio.harness.runtime.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Single strict codec for the frozen task execution policy stored in AgentSnapshot. */
public final class TaskPolicyCodec {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TaskPolicyCodec() {}

  public static TaskPolicy decode(String json) {
    try {
      JsonNode policy = OBJECT_MAPPER.readTree(json);
      if (policy == null || !policy.isObject()) {
        throw new IllegalArgumentException("execution policy must be a JSON object");
      }
      return new TaskPolicy(
          positive(policy, "maxDepth", TaskPolicy.DEFAULT_MAX_DEPTH),
          positive(policy, "maxDirectSubagents", TaskPolicy.DEFAULT_MAX_DIRECT),
          optionalPositive(policy, "maxTotalSubagents"),
          positive(policy, "maxTurns", TaskPolicy.DEFAULT_MAX_TURNS),
          optionalDuration(policy, "idleTimeoutMillis"));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("execution policy must be valid JSON", error);
    }
  }

  private static int positive(JsonNode policy, String field, int fallback) {
    JsonNode value = policy.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException("execution policy " + field + " must be positive");
    }
    return value.intValue();
  }

  private static Integer optionalPositive(JsonNode policy, String field) {
    JsonNode value = policy.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException("execution policy " + field + " must be positive");
    }
    return value.intValue();
  }

  private static Duration optionalDuration(JsonNode policy, String field) {
    JsonNode value = policy.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
      throw new IllegalArgumentException("execution policy " + field + " must be positive");
    }
    return Duration.ofMillis(value.longValue());
  }
}

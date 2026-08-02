package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Objects;

/**
 * Immutable name reference carried by one response-producing turn.
 *
 * <p>This value deliberately contains no expanded Agent, Model, Tool, or Skill definition. Those
 * definitions are resolved afresh while planning each provider request.
 */
public record TurnSettings(String agentName, String environmentName, boolean yoloEnabled) {

  public TurnSettings {
    agentName = canonicalName(agentName, "agentName");
    environmentName = canonicalNullableName(environmentName, "environmentName");
  }

  private static String canonicalNullableName(String value, String field) {
    return value == null ? null : canonicalName(value, field);
  }

  private static String canonicalName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(field + " must be <= 128 characters");
    }
    return value;
  }
}

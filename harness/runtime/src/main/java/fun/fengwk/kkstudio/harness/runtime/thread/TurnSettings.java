package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Objects;

/**
 * Immutable Agent reference carried by one response-producing turn.
 *
 * <p>This value deliberately contains no expanded Agent, Model, Tool, Skill, or Environment
 * definition. The Thread-selected Environment is resolved afresh while planning each provider
 * request.
 */
public record TurnSettings(String agentName, boolean yoloEnabled) {

  public TurnSettings {
    agentName = canonicalName(agentName, "agentName");
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

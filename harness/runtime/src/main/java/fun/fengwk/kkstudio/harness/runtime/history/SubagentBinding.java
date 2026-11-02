package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;

/** 回合可委派的子代理名称与展示描述快照，是 TurnCapabilities 的组成部分。 */
public record SubagentBinding(String name, String description) {

  public SubagentBinding {
    name = requireCanonical(name, "name", 64);
    description = Objects.requireNonNull(description, "description");
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be <= " + maxLength + " characters");
    }
    return value;
  }
}

package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;

/**
 * 冻结到一次 Model invocation 中的不可变 skill 事实。
 *
 * <p>body 被刻意排除在外：只有稳定的 canonical name、description，以及可空的完整 source Environment binding， 才是 durable
 * 请求事实。展示元数据与 skill body 不会进入请求。
 */
public record SkillBinding(String name, String description, EnvironmentBinding sourceEnvironment) {

  public SkillBinding {
    name = requireCanonical(name, "name", 128);
    description = requireCanonical(description, "description", 1024);
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
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

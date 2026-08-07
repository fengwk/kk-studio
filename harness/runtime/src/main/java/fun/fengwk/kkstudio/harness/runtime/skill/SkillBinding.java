package fun.fengwk.kkstudio.harness.runtime.skill;

import java.util.Objects;

/**
 * 冻结在单次 model invocation 中的不可变 skill 事实。
 *
 * <p>body 刻意省略。name、description 与 source environment 是构建 provider prompt 与路由后续 {@code load_skill}
 * 调用所需的精确元数据。
 */
public record SkillBinding(String name, String description, String sourceEnvironment) {

  public SkillBinding {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    sourceEnvironment = requireNonBlank(sourceEnvironment, "sourceEnvironment");
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    return value;
  }
}

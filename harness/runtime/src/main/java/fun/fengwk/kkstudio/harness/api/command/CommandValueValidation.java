package fun.fengwk.kkstudio.harness.api.command;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.GoalSetting;

import java.util.Objects;

/**
 * 用于稳定名称与 client command ID 的小型 command 包工具类。
 *
 * <p>Agent name 与 Environment name 的约束必须与 {@link BranchSettings} 完全一致，否则同一值会通过 command codec 却在
 * reducer 应用时失败。command payload 的构造器与 wire codec 共用本类，因此校验只有一处实现。
 */
public final class CommandValueValidation {

  private CommandValueValidation() {}

  public static String requireCanonicalName(String value, String field) {
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

  /** nullable Environment 名：null 表示解除环境选择；非 null 复用 {@link BranchSettings} 的 canonical 校验。 */
  public static String requireCanonicalEnvironmentName(String value, String field) {
    return BranchSettings.requireCanonicalEnvironmentName(value, field);
  }

  /** Goal 正文：null 表示清除；非 null 复用 {@link GoalSetting} 的 canonical 校验。 */
  public static String requireCanonicalGoalText(String value, String field) {
    return value == null ? null : GoalSetting.requireCanonicalText(value, field);
  }
}

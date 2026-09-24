package fun.fengwk.kkstudio.harness.runtime.entry;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link BranchSettings} 中由用户维护的 Goal 设置：唯一目标正文与本次设置的不可变 id。
 *
 * <p>只有用户经 typed GOAL 输入命令产生新快照；Agent 无权创建或改写正文。每次设置（即使文本相同）都由 runtime 分配新的 {@code id}，Agent
 * 的进度报告只按 id 绑定当前目标，因此旧报告不会命中新目标。Goal 的清除由 {@code BranchSettings.goal == null} 表达，空文本不是清除。
 */
public record GoalSetting(UUID id, String text) {

  /** 目标正文的 Unicode 码点上限。 */
  public static final int MAX_TEXT_CODE_POINTS = 2_000;

  public GoalSetting {
    id = Objects.requireNonNull(id, "id");
    text = requireCanonicalText(text, "text");
  }

  /**
   * 校验目标正文：非 null、非 blank、无首尾空白且不超过 {@value #MAX_TEXT_CODE_POINTS} 码点。command payload 与本类型共用同一 规则。
   */
  public static String requireCanonicalText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.codePointCount(0, value.length()) > MAX_TEXT_CODE_POINTS) {
      throw new IllegalArgumentException(
          field + " must be <= " + MAX_TEXT_CODE_POINTS + " code points");
    }
    return value;
  }
}

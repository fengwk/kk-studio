package fun.fengwk.kkstudio.harness.builtin.goal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条 {@code CUSTOM goal.progress} Entry：Agent 对某个用户 Goal 的终态声明。
 *
 * <p>声明只绑定 {@code goalId}，不复制目标正文、不删除 Goal、也不改变任何业务状态；它只是「Agent 报告」，不是系统验收。 {@code goalId}
 * 指向产生该声明时生效的用户 Goal，因此用户设置新目标或清除目标后，旧声明不再属于当前目标。
 */
public record GoalProgress(UUID goalId, GoalStatus status, String reason, Instant reportedAt) {

  public GoalProgress {
    goalId = Objects.requireNonNull(goalId, "goalId");
    status = Objects.requireNonNull(status, "status");
    if (!status.terminal()) {
      throw new IllegalArgumentException("goal progress status must be terminal");
    }
    reason = requireNonBlank(reason, "reason");
    reportedAt = Objects.requireNonNull(reportedAt, "reportedAt");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }
}

package fun.fengwk.kkstudio.harness.builtin.goal;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条 Goal CUSTOM Entry 持有的不可变 branch state 全量替换快照。
 *
 * <p>不为 Goal 建立独立数据库表，快照事实完整存放在 {@code harness_entry} 的 CUSTOM payload 中。 {@link GoalStatus#ACTIVE}
 * 为唯一活跃状态且不得附带 reason；终态（{@link GoalStatus#COMPLETE} 或 {@link GoalStatus#BLOCKED}）必须包含非空 reason。
 * 时间戳在工具执行时截断至毫秒，且保证 {@code updatedAt >= createdAt}。
 */
public record GoalState(
    String objective,
    Long tokenBudget,
    GoalStatus status,
    String reason,
    Instant createdAt,
    Instant updatedAt) {

  public GoalState {
    objective = requireNonBlank(objective, "objective");
    if (tokenBudget != null && tokenBudget <= 0) {
      throw new IllegalArgumentException("tokenBudget must be positive when present");
    }
    status = Objects.requireNonNull(status, "status");
    if (reason != null) {
      reason = requireNonBlank(reason, "reason");
    }
    if (!status.terminal() && reason != null) {
      throw new IllegalArgumentException("an active goal must not carry a terminal reason");
    }
    if (status.terminal() && reason == null) {
      throw new IllegalArgumentException("a terminal goal requires a reason");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }
}

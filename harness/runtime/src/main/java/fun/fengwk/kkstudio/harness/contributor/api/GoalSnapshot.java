package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 当前 Assistant branch 生效的用户 Goal 只读快照。
 *
 * <p>{@code id} 是本次用户设置的不可变标识，Agent 的进度报告只按它绑定当前目标；{@code text} 是用户原文，Agent 无权改写。
 */
public record GoalSnapshot(UUID id, String text) {

  public GoalSnapshot {
    id = Objects.requireNonNull(id, "id");
    text = Objects.requireNonNull(text, "text");
  }
}

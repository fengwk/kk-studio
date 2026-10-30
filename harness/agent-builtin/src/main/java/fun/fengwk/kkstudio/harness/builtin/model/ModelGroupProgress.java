package fun.fengwk.kkstudio.harness.builtin.model;

import java.util.Objects;

/**
 * 模型组逻辑调用在 durable invocation 上已确认的进度。
 *
 * <p>{@code memberIndex} 是当前成员（0 起）；此前成员都已在该逻辑调用内被淘汰。{@code memberAttempts} 只统计当前成员的普通尝试次数，
 * 切换成员时归零；全局 {@code ModelInvocation.attempt} 仍作为所有真实上游 attempt 的审计序号递增。
 */
public record ModelGroupProgress(
    String groupName, long groupVersion, int memberIndex, int memberAttempts) {

  public ModelGroupProgress {
    Objects.requireNonNull(groupName, "groupName");
    if (groupName.isBlank()) {
      throw new IllegalArgumentException("groupName must not be blank");
    }
    if (groupVersion < 0) {
      throw new IllegalArgumentException("groupVersion must not be negative");
    }
    if (memberIndex < 0) {
      throw new IllegalArgumentException("memberIndex must not be negative");
    }
    if (memberAttempts < 0) {
      throw new IllegalArgumentException("memberAttempts must not be negative");
    }
  }

  public ModelGroupProgress retryCurrent() {
    return new ModelGroupProgress(groupName, groupVersion, memberIndex, memberAttempts + 1);
  }

  public ModelGroupProgress nextMember() {
    return new ModelGroupProgress(groupName, groupVersion, memberIndex + 1, 0);
  }
}

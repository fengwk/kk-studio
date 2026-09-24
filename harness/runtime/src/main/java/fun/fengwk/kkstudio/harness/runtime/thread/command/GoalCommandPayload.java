package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;

/**
 * typed 用户 Goal 命令的 payload。
 *
 * <p>{@code text} 为 null 表示用户显式清除当前 branch Goal；非 null 时是新的目标正文（非 blank、无首尾空白、限长）。空字符串不是清除。 目标快照 id
 * 由 runtime 在 speculative planning 时分配，client 不提供。
 */
public record GoalCommandPayload(String text) implements ThreadCommandPayload {

  public GoalCommandPayload {
    if (text != null) {
      text = GoalSetting.requireCanonicalText(text, "text");
    }
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.GOAL;
  }
}

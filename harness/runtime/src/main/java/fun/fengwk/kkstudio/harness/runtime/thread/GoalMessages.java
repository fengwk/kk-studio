package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

/**
 * 用户 Goal 的两个模型可见投影：设置/清除当次的冻结输入消息，以及压缩后仍生效的用户级历史背景。
 *
 * <p>两者都是 USER 消息，而不是 systemInstruction：Goal 正文由用户拥有，Agent 只能报告进度。输入消息包含用户原文与当次行动要求；
 * 历史背景只陈述当前处于生效状态的 Goal（原文逐字，超出 {@link GoalSetting#MAX_TEXT_CODE_POINTS} 的快照本身不可能存在），并且
 * 明确不是新命令、不重发「立即开始」。清除后的背景明确说明当前没有用户设定 Goal。
 */
public final class GoalMessages {

  private GoalMessages() {}

  /** 设置 Goal 的冻结输入 USER 消息：用户原文 + 本 turn 立即开始的行动要求。 */
  public static AgentMessage inputSet(String goalText) {
    return AgentMessage.user(
        "I am setting a long-term goal for this branch. Keep working towards it from this turn on"
            + " until I change or clear it. The goal text below is mine and is authoritative:\n\n---"
            + " goal begin ---\n"
            + goalText
            + "\n--- goal end ---\n\nStart advancing this goal now, and report progress against"
            + " it as you go.");
  }

  /** 清除 Goal 的冻结输入 USER 消息：明确当前没有用户设定 Goal。 */
  public static AgentMessage inputCleared() {
    return AgentMessage.user(
        "I am clearing the goal of this branch. There is no user-set goal any more; ignore any"
            + " earlier goal text and follow only my explicit instructions from now on.");
  }

  /** 压缩后的生效 Goal 背景：当前 Goal 原文，标明是历史背景而非新指令。 */
  public static AgentMessage backgroundSet(String goalText) {
    return AgentMessage.user(
        "Background for the compacted history above (not a new instruction): the user set the"
            + " following long-term goal for this branch and it is still in effect.\n\n--- goal begin"
            + " ---\n"
            + goalText
            + "\n--- goal end ---\n\nThe user's actual messages follow.");
  }

  /** 压缩后的无 Goal 背景：即使更早的摘要提到过目标，当前没有用户设定 Goal。 */
  public static AgentMessage backgroundCleared() {
    return AgentMessage.user(
        "Background for the compacted history above (not a new instruction): the user has cleared"
            + " the goal of this branch. There is no user-set goal in effect, even if an earlier"
            + " summary mentions one. The user's actual messages follow.");
  }
}

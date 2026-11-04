package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;

/**
 * 用户 Goal 的两个模型可见投影：设置/清除当次的冻结输入消息，以及压缩后仍生效的用户级历史背景。
 *
 * <p>两者都是 USER 消息，而不是 systemInstruction：Goal 正文由用户拥有，Agent 只能报告进度。输入消息包含用户原文与当次行动要求；
 * 历史背景逐字陈述压缩边界冻结的 Goal 快照（或该冻结快照无生效 Goal 的事实），明确不是新命令、不重发「立即开始」，也不声称发生了 清除。后续 Goal 变更由真实输入消息自身携带。
 */
public final class GoalMessages {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/prompts/messages/";

  private GoalMessages() {}

  /** 设置 Goal 的冻结输入 USER 消息：用户原文 + 本 turn 立即开始的行动要求。 */
  public static AgentMessage inputSet(String goalText) {
    return message("goal-input-set.md", Map.of("goal", String.valueOf(goalText)));
  }

  /** 清除 Goal 的冻结输入 USER 消息：正文严格表达当前没有用户设定 Goal。 */
  public static AgentMessage inputCleared() {
    return message("goal-input-cleared.md", Map.of());
  }

  /** 压缩后的生效 Goal 背景：正文严格来自执行本次压缩的 COMPACTION TURN_START 冻结 settings，标明是压缩边界的历史背景而非新指令。 */
  public static AgentMessage backgroundSet(String goalText) {
    return message("goal-background-set.md", Map.of("goal", String.valueOf(goalText)));
  }

  /** 压缩后的无 Goal 背景：冻结的压缩边界快照没有生效 Goal，只陈述该事实（不声称曾被清除）。 */
  public static AgentMessage backgroundCleared() {
    return message("goal-background-cleared.md", Map.of());
  }

  private static AgentMessage message(String name, Map<String, ?> values) {
    return AgentMessage.user(PromptRenderer.render(ROOT + name, values).stripTrailing());
  }
}

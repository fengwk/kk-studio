package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;
import java.util.UUID;

/**
 * 关闭 Turn 的最终回答判定：正常完成且无 continueModel 义务时，只有紧邻 TURN_END 之前的 ASSISTANT 消息可冻结为最终回答。
 *
 * <p>普通模型终态物化（{@code ModelOutcomeAppender}）与迟到退出确认补结算（{@code ExecutionExitLedger}）必须使用同一规则，避免
 * FAILED、机械续作或非 ASSISTANT 结尾的回合在不同提交顺序下得到不同的委派结果。
 */
public final class FinalAnswerEntry {

  private FinalAnswerEntry() {}

  /**
   * 返回可冻结的最终回答 Entry id；不满足正常完成、无续作义务或紧邻 ASSISTANT 消息时返回 {@code null}。
   *
   * @param outcome TURN_END 结局
   * @param continueModel 是否仍负有续写义务
   * @param precedingEntry TURN_END 之前紧邻的 Entry；不存在时为 {@code null}
   */
  public static UUID resolve(TurnEndOutcome outcome, boolean continueModel, Entry precedingEntry) {
    Objects.requireNonNull(outcome, "outcome");
    if (outcome != TurnEndOutcome.COMPLETED || continueModel || precedingEntry == null) {
      return null;
    }
    if (precedingEntry.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT) {
      return precedingEntry.id();
    }
    return null;
  }
}

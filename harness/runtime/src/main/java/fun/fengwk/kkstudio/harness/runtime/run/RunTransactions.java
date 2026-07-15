package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import java.time.Instant;
import java.util.List;

/** 跨 Session/Entry/Run 的原子提交边界。 */
public interface RunTransactions {
  AgentRun submitUserMessage(
      long sessionId, Long expectedLeafEntryId, AgentMessage userMessage, Instant now);

  /**
   * 在 Turn 边界消费当前 Run 的 PENDING STEER。true 才允许继续构建 Context/发 Provider request； false 表示 ownership
   * 已丢失或该 Run 已在事务内因 cancel 终止。
   */
  boolean consumeSteering(AgentRun claimedRun, Instant now);

  /**
   * Assistant Entry、终态选择与 activeRunId 清理一次提交。 {@code assistantCompleted} 必须严格为一个
   * ASSISTANT_COMPLETED event。 实现自行决定 RUN_COMPLETED / RUN_REQUEUED 并追加对应 event。
   */
  boolean complete(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      RunEventDraft assistantCompleted,
      Instant now);

  /** transient attempt 与 retry events 一次提交，失败 partial 不写 Session。 */
  boolean requeue(
      AgentRun claimedRun, Instant nextAttemptAt, List<RunEventDraft> retryEvents, Instant now);

  /** Compaction Entry、events 与重新排队一次提交；旧 Entry 保持不变。 */
  boolean compactAndRequeue(
      AgentRun claimedRun,
      CompactionEntryPayload compaction,
      Instant nextAttemptAt,
      List<RunEventDraft> compactionEvents,
      Instant now);

  /** FAILED/CANCELLED terminal events、状态与 activeRunId 清理一次提交。 */
  boolean terminate(
      AgentRun claimedRun,
      RunStatus terminalStatus,
      List<RunEventDraft> terminalEvents,
      Instant now);
}

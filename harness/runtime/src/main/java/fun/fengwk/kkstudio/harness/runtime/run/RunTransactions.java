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

  /** Assistant Entry、terminal events、Run terminal 与 activeRunId 清理一次提交。 */
  boolean complete(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      List<RunEventDraft> terminalEvents,
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

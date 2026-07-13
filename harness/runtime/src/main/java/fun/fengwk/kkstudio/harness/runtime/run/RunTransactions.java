package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import java.time.Instant;

/** 跨 Session/Entry/Run 的原子提交边界。 */
public interface RunTransactions {
  AgentRun submitUserMessage(
      long sessionId, Long expectedLeafEntryId, AgentMessage userMessage, Instant now);

  /** Assistant Entry、Run terminal 与 activeRunId 清理一次提交。 */
  boolean complete(AgentRun claimedRun, MessageEntryPayload assistant, Instant now);

  /** transient attempt 仅更新 Run，失败 partial 不写 Session。 */
  boolean requeue(AgentRun claimedRun, Instant nextAttemptAt, Instant now);

  /** Compaction Entry 追加后重新排队；旧 Entry 保持不变。 */
  boolean compactAndRequeue(
      AgentRun claimedRun, CompactionEntryPayload compaction, Instant nextAttemptAt, Instant now);

  /** FAILED/CANCELLED terminal 与 activeRunId 清理一次提交。 */
  boolean terminate(AgentRun claimedRun, RunStatus terminalStatus, Instant now);
}

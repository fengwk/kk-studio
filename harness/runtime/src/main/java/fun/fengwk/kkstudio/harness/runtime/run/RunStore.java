package fun.fengwk.kkstudio.harness.runtime.run;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Durable Run 的数据库队列端口。 */
public interface RunStore {
  Optional<AgentRun> find(long runId);

  /** 原子 claim 到期 QUEUED Run 或 lease 已过期的 RUNNING Run。 */
  Optional<AgentRun> claimDue(String leaseOwner, Instant now, Duration leaseDuration);

  /** 仅当前 owner/attempt 可延长 lease；返回 false 表示 ownership 已丢失。 */
  boolean heartbeat(
      long runId, String leaseOwner, int attempt, Instant now, Duration leaseDuration);

  /** 持久化取消请求；真正的 terminal 迁移仍由持有者或后续协调者完成。 */
  boolean requestCancel(long runId, Instant requestedAt);
}

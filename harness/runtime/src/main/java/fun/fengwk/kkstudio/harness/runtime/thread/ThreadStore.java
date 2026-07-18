package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** AgentThread 持久化与 processor fencing 端口。 */
public interface ThreadStore {
  Optional<AgentThread> find(long threadId);

  List<AgentThread> listBySession(long sessionId);

  void create(AgentThread thread);

  /**
   * 尝试获取 thread 执行权。成功返回带新 token 的快照；失败表示另一节点仍持有有效 lease。
   *
   * <p>规则：processorToken 为空，或 processorUntil 已过期，才可 acquire。
   */
  Optional<AgentThread> tryAcquire(
      long threadId, String processorToken, Instant now, Duration leaseDuration);

  /** 在持有 token 时续租；token 不匹配返回 false。 */
  boolean renew(long threadId, String processorToken, Instant now, Duration leaseDuration);

  /** 在持有 token 时释放；token 不匹配返回 false。 */
  boolean release(long threadId, String processorToken, Instant now);

  /** 仅当当前 processorToken 匹配时推进 headEntryId 与 version。 */
  boolean advanceHead(
      long threadId,
      String processorToken,
      long expectedHeadEntryId,
      long newHeadEntryId,
      Instant now);

  /** 仅当当前 processorToken 匹配时更新 YOLO。 */
  boolean updateYolo(long threadId, String processorToken, boolean yoloEnabled, Instant now);

  /** 仅当当前 processorToken 匹配时更新 agent 与冻结配置。 */
  boolean updateAgent(
      long threadId,
      String processorToken,
      Long agentDefinitionId,
      String runtimeConfigJson,
      Instant now);

  /** 分配下一个 input sequence（在提交事务内调用）。 */
  long allocateInputSequence(long threadId, Instant now);
}

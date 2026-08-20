package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;

/**
 * 同步 Harness control plane 的类型化业务冲突。
 *
 * <p>对于每一个用户可恢复的拒绝，均以本异常代替裸 {@link IllegalStateException}；被破坏的持久化 不变量（所有权错误、sibling 混合挂接、ordinal
 * 不连续、数量不匹配）仍为 {@link IllegalStateException}。
 */
public final class HarnessRuntimeConflictException extends RuntimeException {

  private final Reason reason;

  public HarnessRuntimeConflictException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /** 类型化的冲突分类，对 HTTP 映射与客户端重试决策保持稳定。 */
  public Reason reason() {
    return reason;
  }

  /** Harness control plane 冲突分类的封闭集合。 */
  public enum Reason {
    /** 受 revision 守护的 control 请求与当前 Thread revision 不匹配。 */
    STALE_REVISION,
    /** 新 command batch 所期望的 head/next-command-sequence 游标已过期。 */
    STALE_COMMAND_CURSOR,
    /** Ordered replay：已存在的 clientCommandId 携带了不同的 payload。 */
    COMMAND_ID_REUSED,
    /** Ordered replay：仅 batch id 的一个严格子集已存在；缺失的 command 永远不会被补齐。 */
    PARTIAL_COMMAND_REPLAY,
    /** Ordered replay：id 存在且 payload 相等，但 sequence 在请求顺序中不连续。 */
    COMMAND_REPLAY_ORDER_MISMATCH,
    /** NEW_SESSION / ENTRY：预分配的 threadId 已被不同 materialization（不同 hash 或不同 Session）占用。 */
    MATERIALIZATION_ID_REUSED,
    /** Thread 存在等待原子 apply 的 terminal Model/Tool result。 */
    TERMINAL_APPLY_PENDING,
    /** Stop 幂等键已被非 Stop 的关闭操作使用过。 */
    STOP_REQUEST_ID_REUSED,
    /** Approval target 缺失、不属于请求 thread 或当前不适用。 */
    APPROVAL_NOT_APPLICABLE,
    /** Approval 已被决策，且请求未重放已存储的决策。 */
    APPROVAL_DECISION_MISMATCH,
    /** Thread 当前不满足手动压缩的可用性条件。 */
    MANUAL_COMPACTION_UNAVAILABLE
  }
}

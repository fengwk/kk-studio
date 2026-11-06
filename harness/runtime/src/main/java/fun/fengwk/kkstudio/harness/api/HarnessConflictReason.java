package fun.fengwk.kkstudio.harness.api;

/** Harness control plane 冲突的稳定 reason 名称。 */
public enum HarnessConflictReason {
  /** 受 version 守护的请求与当前 Thread version 不匹配。 */
  STALE_VERSION,
  /** 新 command batch 的 head / next-command-sequence 游标已过期。 */
  STALE_COMMAND_CURSOR,
  /** 同一 idempotencyKey 携带了不同 payload。 */
  IDEMPOTENCY_KEY_REUSED,
  /** 仅 batch 的一部分 command 已存在。 */
  PARTIAL_COMMAND_REPLAY,
  /** 已存在的 command sequence 与请求顺序不连续。 */
  COMMAND_REPLAY_ORDER_MISMATCH,
  /** 预分配的 threadId 已关联到不同的创建请求或 Session。 */
  THREAD_ID_REUSED,
  /** Stop 幂等键已被其他关闭操作使用。 */
  STOP_REQUEST_ID_REUSED,
  /** Approval 目标缺失、不属于请求 Thread 或当前不适用。 */
  APPROVAL_NOT_APPLICABLE,
  /** Approval 请求未重放已存储的决策。 */
  APPROVAL_DECISION_MISMATCH,
  /** 人工输入目标缺失、不属于请求 Thread 或当前不适用。 */
  INPUT_SUBMISSION_NOT_APPLICABLE,
  /** 人工输入提交不满足冻结问卷。 */
  INPUT_SUBMISSION_INVALID,
  /** 人工输入提交身份或答案与已接受事实不一致。 */
  INPUT_SUBMISSION_MISMATCH,
  /** Thread 当前不满足手动压缩条件。 */
  MANUAL_COMPACTION_UNAVAILABLE,
  /** 同一 Session 内已存在同名执行根 Thread。 */
  THREAD_NAME_CONFLICT,
  /** STOPPED Thread 的退出或递归委派尚未收敛。 */
  EXIT_UNCONFIRMED,
  /** 放弃退出确认的目标不适用。 */
  EXIT_ABANDON_NOT_APPLICABLE
}

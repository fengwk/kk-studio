package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread Command 聚合的事务内视图。
 *
 * <p>Command 只能以 QUEUED 插入，并按 Thread 内单调 sequence 推进。Command 锁必须发生在 Thread 锁之后。
 */
public interface CommandStore {

  /** 按 {@code (threadId, idempotencyKey)} 幂等查找 Command。 */
  Optional<ThreadCommand> findByIdempotencyKey(UUID threadId, UUID idempotencyKey);

  /** 按 {@code (threadId, sequence)} 定位 Command；不产生业务行锁。 */
  Optional<ThreadCommand> find(UUID threadId, long sequence);

  /** 读取 Thread 全部 QUEUED Command；要求 Thread 已锁定，返回行视为已锁定。 */
  List<ThreadCommand> loadQueued(UUID threadId);

  /** 判断是否存在 sequence 大于给定值的 APPLIED NOTIFICATION Command。 */
  boolean hasAppliedNotificationAfter(UUID threadId, long afterSequence);

  /** 读取指定 stopRequestId 取消的全部 Command，按 sequence 升序。 */
  List<ThreadCommand> loadCancelledByRequest(UUID threadId, UUID stopRequestId);

  /** 批量插入 QUEUED Command；要求 Thread 已锁定。 */
  void insert(List<ThreadCommand> commands);

  /** 批量更新 Command 生命周期；要求行与 Thread 均已锁定。 */
  void update(List<ThreadCommand> commands);
}

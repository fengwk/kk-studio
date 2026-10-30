package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockRank;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL 事务内锁阶梯、幂等加锁与同阶排序防御。 */
final class PostgresqlHarnessLocks {

  private final PostgresqlHarnessTransaction tx;

  /** 当前事务已成功获取锁的实体资源标识集合，用于支持加锁幂等并防范重入。 */
  private final Set<LockKey> locked = new HashSet<>();

  private final Set<UUID> lockedTrees = new HashSet<>();

  /** 本事务是否已获取全局 Join 准入锁（幂等标记；准入锁不参与行锁阶梯）。 */
  private boolean joinAdmissionLocked;

  /** 记录各 assistantEntryId 下当前已锁定的最高 tool callIndex，防范乱序持锁。 */
  private final Map<UUID, Integer> highestToolCallIndexByAssistant = new HashMap<>();

  /** 当前事务已达到的最高锁阶梯等级，确保层级单调递增。 */
  private LockRank highestLockRank;

  /** 当前事务已锁定的最高 Thread UUID，确保同级按 UUID 升序加锁。 */
  private UUID highestThreadId;

  /** 当前事务已锁定的最高 WorkTarget，确保同级按 (type, id) 升序加锁。 */
  private WorkTarget highestWorkTarget;

  PostgresqlHarnessLocks(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  boolean isLocked(LockKey key) {
    return locked.contains(key);
  }

  boolean isLockedTree(UUID rootThreadId) {
    return lockedTrees.contains(rootThreadId);
  }

  boolean hasLockedTrees() {
    return !lockedTrees.isEmpty();
  }

  /** 新子 Thread 在已持有同树锁的插入路径上直接纳入树锁，不再触发 UUID 顺序检查。 */
  void recordJoinedChildThreadLock(UUID threadId) {
    locked.add(LockKey.thread(threadId));
    if (highestThreadId == null || UuidOrder.COMPARATOR.compare(threadId, highestThreadId) > 0) {
      highestThreadId = threadId;
    }
  }

  void lockTree(UUID rootThreadId) {
    if (lockedTrees.contains(rootThreadId)) {
      return;
    }
    if (highestLockRank != null) {
      throw new IllegalStateException("tree lock must precede business row locks");
    }
    if (!lockedTrees.isEmpty()
        && UuidOrder.COMPARATOR.compare(
                rootThreadId, lockedTrees.stream().max(UuidOrder.COMPARATOR).orElseThrow())
            <= 0) {
      throw new IllegalStateException("tree locks must be acquired in ascending root order");
    }
    tx.queryForObject(
        "select true from pg_advisory_xact_lock(hashtextextended('harness-tree:' || ?::text, 0))",
        Boolean.class,
        rootThreadId);
    lockedTrees.add(rootThreadId);
  }

  void lockJoinAdmission() {
    if (joinAdmissionLocked) {
      return;
    }
    if (highestLockRank != null || !lockedTrees.isEmpty()) {
      throw new IllegalStateException(
          "join admission lock must precede tree and business row locks");
    }
    // 两个 int 键的事务级 advisory lock：与 tree 使用的单 bigint 键空间天然隔离。
    tx.queryForObject(
        "select true from pg_advisory_xact_lock(?::int, ?::int)",
        Boolean.class,
        PostgresqlHarnessTransaction.JOIN_ADMISSION_LOCK_NAMESPACE,
        PostgresqlHarnessTransaction.JOIN_ADMISSION_LOCK_KEY);
    joinAdmissionLocked = true;
  }

  /**
   * 锁阶梯单向递增防御：禁止在已获取高阶锁之后回退获取低阶锁。
   *
   * <p>唯一例外是同一执行树内、已持有<b>该行所属树</b>事务级锁的前提下回写 COMMAND（父 Thread 结果交付）：交付命令可能在 Model/Tool 收敛过程中（Work
   * 之前）或 Work fencing 之后（Stop 收尾）写入。例外严格限定在「command 所属 Thread 落在已持有的树锁内」， 仅持有其它执行树的树锁不允许逆序加锁。
   */
  void requireCanLockRank(LockRank rank, UUID commandThreadId) {
    if (highestLockRank != null && rank.ordinal() < highestLockRank.ordinal()) {
      if (rank == LockRank.COMMAND && commandThreadId != null && isInLockedTree(commandThreadId)) {
        return;
      }
      throw new IllegalStateException(
          "lock order violation: cannot acquire " + rank + " after " + highestLockRank);
    }
  }

  void requireCanLockRank(LockRank rank) {
    requireCanLockRank(rank, null);
  }

  /** 同阶梯加锁排序防御：多个 Thread 必须严格按 UUID 升序加锁。 */
  void requireCanLockThread(UUID threadId) {
    LockKey key = LockKey.thread(threadId);
    if (locked.contains(key)) {
      return;
    }
    requireCanLock(key);
    if (highestThreadId != null && UuidOrder.COMPARATOR.compare(threadId, highestThreadId) <= 0) {
      throw new IllegalStateException(
          "thread locks must be acquired by ascending id: "
              + highestThreadId
              + " before "
              + threadId);
    }
  }

  void recordThreadLock(UUID threadId) {
    requireCanLockThread(threadId);
    LockKey key = LockKey.thread(threadId);
    if (!locked.contains(key)) {
      lockResource(key);
      highestThreadId = threadId;
    }
  }

  void requireCanLock(LockKey key) {
    if (!locked.contains(key)) {
      requireCanLockRank(key.rank(), key.ownerThreadId());
    }
  }

  void lockResource(LockKey key) {
    requireCanLock(key);
    if (locked.add(key)) {
      highestLockRank = key.rank();
    }
  }

  /** 同阶梯加锁排序防御：同一 assistantEntryId 下的多个 ToolInvocation 必须按 callIndex 严格递增加锁。 */
  void requireCanLockTools(List<ToolInvocation> invocations) {
    Map<UUID, Integer> callIndexes = new HashMap<>(highestToolCallIndexByAssistant);
    for (ToolInvocation invocation : invocations) {
      LockKey key = LockKey.tool(invocation.id());
      if (locked.contains(key)) {
        continue;
      }
      requireCanLock(key);
      Integer previous = callIndexes.put(invocation.assistantEntryId(), invocation.callIndex());
      if (previous != null && invocation.callIndex() <= previous) {
        throw new IllegalStateException(
            "tool invocation locks for assistant entry "
                + invocation.assistantEntryId()
                + " must be acquired by ascending callIndex");
      }
    }
  }

  void lockTool(ToolInvocation invocation) {
    requireCanLockTools(List.of(invocation));
    LockKey key = LockKey.tool(invocation.id());
    if (!locked.contains(key)) {
      lockResource(key);
      highestToolCallIndexByAssistant.put(invocation.assistantEntryId(), invocation.callIndex());
    }
  }

  /** 同阶梯加锁排序防御：多个 Work 目标必须严格按 (targetType.ordinal(), targetId) 升序加锁。 */
  void requireCanLockWork(WorkTarget target) {
    LockKey key = LockKey.work(target);
    if (locked.contains(key)) {
      return;
    }
    requireCanLock(key);
    if (highestWorkTarget != null
        && PostgresqlHarnessTransaction.WORK_LOCK_ORDER.compare(target, highestWorkTarget) <= 0) {
      if (target.type() == WorkTargetType.THREAD
          && !lockedTrees.isEmpty()
          && locked.contains(LockKey.thread(target.id()))
          && isInLockedTree(target.id())) {
        return;
      }
      throw new IllegalStateException(
          "work locks must be acquired by ascending (type, id): "
              + highestWorkTarget
              + " before "
              + target);
    }
  }

  boolean isInLockedTree(UUID threadId) {
    if (lockedTrees.isEmpty()) {
      return false;
    }
    UUID root =
        tx.queryOne(
                "select root_thread_id from harness_thread where id = ?",
                (resultSet, rowNumber) -> resultSet.getObject("root_thread_id", UUID.class),
                threadId)
            .orElse(threadId);
    return lockedTrees.contains(root);
  }

  /** 验证当前事务是否允许执行 claimNextWork：必须先达到 WORK 锁阶梯，且在此之前不能持有任何其他 Work 锁。 */
  void requireCanClaimWork() {
    requireCanLockRank(LockRank.WORK);
    if (highestWorkTarget != null) {
      throw new IllegalStateException(
          "claimNextWork must be the first Work lock acquisition in a transaction");
    }
  }

  void recordWorkLock(WorkTarget target) {
    requireCanLockWork(target);
    LockKey key = LockKey.work(target);
    if (!locked.contains(key)) {
      lockResource(key);
      highestWorkTarget = target;
    }
  }

  void requireLocked(LockKey key) {
    if (!locked.contains(key)) {
      throw new IllegalStateException(key + " is not locked in this transaction");
    }
  }
}

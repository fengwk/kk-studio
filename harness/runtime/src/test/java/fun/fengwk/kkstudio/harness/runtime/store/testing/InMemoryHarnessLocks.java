package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 内存参考实现的 per-transaction 锁阶梯记账。
 *
 * <p>本类只登记本事务已获取的行锁与树锁，并在实际加锁前以 {@link IllegalStateException} 拒绝已知逆序；真正的互斥由 {@link
 * InMemoryHarnessStore} 的单一全局 monitor 提供。锁阶梯为 {@code SESSION -> THREAD -> COMMAND -> DRIVER -> TOOL
 * -> WORK}： Thread 同层按 {@link UuidOrder} 升序，Tool siblings 按 callIndex 升序，Work 同层按 (type, id)
 * 升序；Driver 级供任一 Driver 的私有行使用。
 *
 * <p>{@link #isInLockedTree(UUID)} 依赖事务快照里的 Thread 根链，因此由事务核心注入只读查找函数，本类不持有状态快照。
 */
final class InMemoryHarnessLocks {

  /** Command 行锁获取顺序：{@code (threadId, sequence)} 升序。 */
  static final Comparator<ThreadCommand> COMMAND_LOCK_ORDER =
      Comparator.comparing(ThreadCommand::threadId, UuidOrder.COMPARATOR)
          .thenComparingLong(ThreadCommand::sequence);

  /** Tool 行锁获取顺序：{@code (assistantEntryId, callIndex, id)} 升序。 */
  static final Comparator<ToolInvocation> TOOL_LOCK_ORDER =
      Comparator.comparing(ToolInvocation::assistantEntryId, UuidOrder.COMPARATOR)
          .thenComparingInt(ToolInvocation::callIndex)
          .thenComparing(ToolInvocation::id, UuidOrder.COMPARATOR);

  /** Work 行锁获取顺序：{@code (targetType.ordinal(), targetId)} 升序。 */
  static final Comparator<WorkTarget> WORK_LOCK_ORDER =
      Comparator.comparingInt((WorkTarget target) -> target.type().ordinal())
          .thenComparing(WorkTarget::id, UuidOrder.COMPARATOR);

  private final Function<UUID, ThreadState> threadLookup;
  private final Set<LockKey> locked = new HashSet<>();
  private final Set<UUID> lockedTrees = new HashSet<>();
  private final Map<UUID, Integer> highestToolCallIndexByAssistant = new HashMap<>();
  private boolean joinAdmissionLocked;
  private LockRank highestLockRank;
  private UUID highestThreadId;
  private WorkTarget highestWorkTarget;

  InMemoryHarnessLocks(Function<UUID, ThreadState> threadLookup) {
    this.threadLookup = threadLookup;
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
      lock(key);
      highestThreadId = threadId;
    }
  }

  /** 追加式子 Thread 创建：父 Thread 已锁定，新行继承同一执行树的锁，不参与同层升序校验。 */
  void recordJoinedChildThreadLock(UUID threadId) {
    locked.add(LockKey.thread(threadId));
    if (highestThreadId == null || UuidOrder.COMPARATOR.compare(threadId, highestThreadId) > 0) {
      highestThreadId = threadId;
    }
  }

  void requireCanLock(LockKey key) {
    if (!locked.contains(key)) {
      requireCanLockRank(key.rank(), key.ownerThreadId());
    }
  }

  void lock(LockKey key) {
    requireCanLock(key);
    if (locked.add(key)) {
      highestLockRank = key.rank();
    }
  }

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
      lock(key);
      highestToolCallIndexByAssistant.put(invocation.assistantEntryId(), invocation.callIndex());
    }
  }

  void requireCanLockWork(WorkTarget target) {
    LockKey key = LockKey.work(target);
    if (locked.contains(key)) {
      return;
    }
    requireCanLock(key);
    if (highestWorkTarget != null && WORK_LOCK_ORDER.compare(target, highestWorkTarget) <= 0) {
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
      lock(key);
      highestWorkTarget = target;
    }
  }

  void requireLocked(LockKey key) {
    if (!locked.contains(key)) {
      throw new IllegalStateException(key + " is not locked in this transaction");
    }
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

  boolean isInLockedTree(UUID threadId) {
    if (lockedTrees.isEmpty()) {
      return false;
    }
    ThreadState thread = threadLookup.apply(threadId);
    return lockedTrees.contains(thread == null ? threadId : thread.rootThreadId());
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
    // 参考实现由全局 monitor 串行化事务，准入锁只需保留与 PG 一致的“必须最先获取”防御。
    joinAdmissionLocked = true;
  }

  enum LockRank {
    SESSION,
    THREAD,
    COMMAND,
    DRIVER,
    TOOL,
    WORK
  }

  /** 行锁 key；{@code ownerThreadId} 记录 COMMAND 行所属 Thread，用于同树例外判定。 */
  record LockKey(LockRank rank, String key, UUID ownerThreadId) {
    static LockKey session(UUID id) {
      return new LockKey(LockRank.SESSION, "session:" + id, null);
    }

    static LockKey thread(UUID id) {
      return new LockKey(LockRank.THREAD, "thread:" + id, null);
    }

    static LockKey command(UUID threadId, long sequence) {
      return new LockKey(LockRank.COMMAND, "command:" + threadId + ":" + sequence, threadId);
    }

    static LockKey driver(UUID id) {
      return new LockKey(LockRank.DRIVER, "driver:" + id, null);
    }

    static LockKey tool(UUID id) {
      return new LockKey(LockRank.TOOL, "tool:" + id, null);
    }

    static LockKey work(WorkTarget target) {
      return new LockKey(LockRank.WORK, "work:" + target.type() + ":" + target.id(), null);
    }
  }
}

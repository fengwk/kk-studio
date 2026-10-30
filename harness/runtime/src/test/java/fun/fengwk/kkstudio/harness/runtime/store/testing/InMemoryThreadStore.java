package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Thread 聚合原语：Thread 行携带当前 projection，更新必须已锁定且通过 {@link ThreadState#validateTransition}。
 *
 * <p>树锁必须先于业务行锁，并按执行根 id 升序获取。{@link #delete(List)} 是应用侧深删除原语：先在已锁定的树内按锁阶梯补锁 Command、 driver 行、Tool
 * 与 Work，再移除引用这些 Thread 的全部子事实。
 */
final class InMemoryThreadStore implements ThreadStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryThreadStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public void insert(ThreadState thread) {
    tx.checkOpen();
    Objects.requireNonNull(thread, "thread");
    HarnessStoreTime.requireMillisecondPrecision(thread.createdAt());
    HarnessStoreTime.requireMillisecondPrecision(thread.updatedAt());
    InMemoryHarnessTransaction.requireAbsent(state.threads, thread.id(), "thread");
    tx.requireExistingEntry(thread.headEntryId());
    requireThreadHeadInSession(thread);
    if (thread.parentThreadId() != null && !state.threads.containsKey(thread.parentThreadId())) {
      throw new IllegalArgumentException(
          "parent thread " + thread.parentThreadId() + " does not exist");
    }
    requireUniqueRootThreadName(thread);
    InMemoryHarnessLocks locks = tx.locks();
    boolean joinedChild =
        thread.parentThreadId() != null
            && locks.hasLockedTrees()
            && locks.isLocked(InMemoryHarnessLocks.LockKey.thread(thread.parentThreadId()));
    if (!joinedChild) {
      locks.requireCanLockThread(thread.id());
    }
    state.threads.put(thread.id(), thread);
    if (joinedChild) {
      locks.recordJoinedChildThreadLock(thread.id());
    } else {
      locks.recordThreadLock(thread.id());
    }
  }

  /** Thread 的 head Entry 必须属于 Thread 持久化的 Session（Session -&gt; head 同一 Session 约束）。 */
  private void requireThreadHeadInSession(ThreadState thread) {
    if (!state.sessions.containsKey(thread.sessionId())) {
      throw new IllegalArgumentException("session " + thread.sessionId() + " does not exist");
    }
    Entry head = state.entries.get(thread.headEntryId());
    if (head == null || !head.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "thread head entry "
              + thread.headEntryId()
              + " must belong to thread session "
              + thread.sessionId());
    }
  }

  @Override
  public Optional<ThreadState> find(UUID id) {
    tx.checkOpen();
    return Optional.ofNullable(state.threads.get(id));
  }

  @Override
  public Optional<ThreadState> lock(UUID id) {
    tx.checkOpen();
    InMemoryHarnessLocks locks = tx.locks();
    locks.requireCanLockThread(id);
    ThreadState thread = state.threads.get(id);
    if (thread != null) {
      locks.recordThreadLock(id);
    }
    return Optional.ofNullable(thread);
  }

  @Override
  public List<ThreadState> listBySession(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (!state.sessions.containsKey(sessionId)) {
      throw new IllegalArgumentException("session " + sessionId + " does not exist");
    }
    return state.threads.values().stream()
        .filter(thread -> thread.sessionId().equals(sessionId))
        .sorted(
            Comparator.comparing(ThreadState::createdAt)
                .thenComparing(ThreadState::id, UuidOrder.COMPARATOR))
        .toList();
  }

  @Override
  public List<ThreadState> listChildren(UUID parentThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    return state.threads.values().stream()
        .filter(thread -> parentThreadId.equals(thread.parentThreadId()))
        .sorted(Comparator.comparing(ThreadState::id, UuidOrder.COMPARATOR))
        .toList();
  }

  @Override
  public void update(ThreadState thread) {
    tx.checkOpen();
    Objects.requireNonNull(thread, "thread");
    HarnessStoreTime.requireMillisecondPrecision(thread.createdAt());
    HarnessStoreTime.requireMillisecondPrecision(thread.updatedAt());
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(thread.id()));
    ThreadState stored = state.threads.get(thread.id());
    if (stored == null) {
      throw new IllegalArgumentException("thread " + thread.id() + " does not exist");
    }
    ThreadState.validateTransition(stored, thread);
    tx.requireExistingEntry(thread.headEntryId());
    requireThreadHeadInSession(thread);
    requireUniqueRootThreadName(thread);
    state.threads.put(thread.id(), thread);
  }

  /**
   * 模拟 {@code uk_harness_thread_root_name}：同一 Session 内执行根（parent 为 null）名称唯一，子代理与跨 Session 同名不受影响。
   * 按规范化后的原值精确比较，不引入大小写/兼容折叠。
   */
  private void requireUniqueRootThreadName(ThreadState thread) {
    if (thread.parentThreadId() != null) {
      return;
    }
    boolean conflict =
        state.threads.values().stream()
            .anyMatch(
                existing ->
                    !existing.id().equals(thread.id())
                        && existing.parentThreadId() == null
                        && existing.sessionId().equals(thread.sessionId())
                        && existing.name().equals(thread.name()));
    if (conflict) {
      throw new HarnessRuntimeConflictException(
          HarnessRuntimeConflictException.Reason.THREAD_NAME_CONFLICT,
          "thread name "
              + thread.name()
              + " is already used by an execution root in session "
              + thread.sessionId());
    }
  }

  @Override
  public List<UUID> findAncestorChain(UUID threadId) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    if (!state.threads.containsKey(threadId)) {
      return List.of();
    }
    List<UUID> chain = new ArrayList<>();
    for (UUID current = threadId; current != null; ) {
      chain.add(current);
      current = state.threads.get(current).parentThreadId();
    }
    return List.copyOf(chain);
  }

  @Override
  public List<ThreadState> listByRoot(UUID rootThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    return state.threads.values().stream()
        .filter(thread -> thread.rootThreadId().equals(rootThreadId))
        .toList();
  }

  @Override
  public void lockTree(UUID rootThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    tx.locks().lockTree(rootThreadId);
  }

  @Override
  public int delete(List<UUID> threadIds) {
    tx.checkOpen();
    Objects.requireNonNull(threadIds, "threadIds");
    InMemoryHarnessLocks locks = tx.locks();
    List<UUID> copied = List.copyOf(threadIds).stream().sorted(UuidOrder.COMPARATOR).toList();
    for (int i = 1; i < copied.size(); i++) {
      if (copied.get(i).equals(copied.get(i - 1))) {
        throw new IllegalArgumentException(
            "duplicate thread id " + copied.get(i) + " must not be deleted twice");
      }
    }
    for (UUID threadId : copied) {
      locks.requireLocked(InMemoryHarnessLocks.LockKey.thread(threadId));
      if (!locks.isInLockedTree(threadId)) {
        throw new IllegalStateException("thread " + threadId + " is not in a locked tree");
      }
      if (!state.threads.containsKey(threadId)) {
        throw new IllegalArgumentException("thread " + threadId + " does not exist");
      }
    }
    Set<UUID> threadIdSet = Set.copyOf(copied);
    for (ThreadJoin join : state.joins.values()) {
      if (threadIdSet.contains(join.childThreadId())
          || (join.parentThreadId() != null && threadIdSet.contains(join.parentThreadId()))) {
        throw new IllegalArgumentException(
            "cannot delete thread referenced by join " + join.invocationId());
      }
    }
    for (UUID threadId : copied) {
      for (ThreadState thread : state.threads.values()) {
        if (threadId.equals(thread.parentThreadId()) && !threadIdSet.contains(thread.id())) {
          throw new IllegalArgumentException(
              "cannot delete parent thread "
                  + threadId
                  + " while child thread "
                  + thread.id()
                  + " still exists");
        }
      }
    }

    List<ThreadCommand> commands =
        state.commands.values().stream()
            .filter(command -> threadIdSet.contains(command.threadId()))
            .sorted(InMemoryHarnessLocks.COMMAND_LOCK_ORDER)
            .toList();
    for (ThreadCommand command : commands) {
      locks.lock(InMemoryHarnessLocks.LockKey.command(command.threadId(), command.sequence()));
    }

    List<UUID> driverRowIds =
        state.driverRows.keySet().stream()
            .filter(id -> threadIdSet.contains(state.driverRowOwners.get(id)))
            .sorted(UuidOrder.COMPARATOR)
            .toList();
    for (UUID driverRowId : driverRowIds) {
      locks.lock(InMemoryHarnessLocks.LockKey.driver(driverRowId));
    }

    List<ToolInvocation> tools =
        state.toolInvocations.values().stream()
            .filter(tool -> threadIdSet.contains(tool.threadId()))
            .sorted(InMemoryHarnessLocks.TOOL_LOCK_ORDER)
            .toList();
    Set<UUID> toolIds = new HashSet<>();
    for (ToolInvocation tool : tools) {
      toolIds.add(tool.id());
      locks.lockTool(tool);
    }

    List<WorkTarget> workTargets =
        state.works.keySet().stream()
            .filter(
                target ->
                    (target.type() == WorkTargetType.THREAD && threadIdSet.contains(target.id()))
                        || (target.type() == WorkTargetType.MODEL
                            && driverRowIds.contains(target.id()))
                        || (target.type() == WorkTargetType.TOOL && toolIds.contains(target.id())))
            .sorted(InMemoryHarnessLocks.WORK_LOCK_ORDER)
            .toList();
    for (WorkTarget target : workTargets) {
      locks.recordWorkLock(target);
    }

    for (WorkTarget target : workTargets) {
      state.works.remove(target);
    }
    for (ThreadCommand command : commands) {
      state.commands.remove(new InMemoryState.CommandKey(command.threadId(), command.sequence()));
    }
    for (ToolInvocation tool : tools) {
      state.toolInvocations.remove(tool.id());
    }
    for (UUID driverRowId : driverRowIds) {
      state.driverRows.remove(driverRowId);
      state.driverRowOwners.remove(driverRowId);
    }
    for (UUID threadId : copied) {
      state.threads.remove(threadId);
    }
    state.executions.values().removeIf(fact -> copied.contains(fact.threadId()));
    return copied.size();
  }
}

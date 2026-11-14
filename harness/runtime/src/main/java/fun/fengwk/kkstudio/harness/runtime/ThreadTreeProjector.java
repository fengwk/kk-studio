package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.ThreadTreeNode;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行树窄摘要的读取：按不可变 {@code rootThreadId} 取同根全部 Thread 并投影为 {@link ThreadTreeNode}。
 *
 * <p>一次事务先取该根的树锁，再按 UUID 升序锁定全部 Thread 后生成节点；不按 Session 成员替代永久父子关系，也不装载历史 Entry。树锁串行化同树 Invocation
 * 写入，因此只读探测不需要回退 Model / Tool 锁阶梯。纯查询，不写入、不推进 version。
 */
final class ThreadTreeProjector {

  private final HarnessStore store;
  private final DriverRegistry driverRegistry;

  ThreadTreeProjector(HarnessStore store, DriverRegistry driverRegistry) {
    this.store = Objects.requireNonNull(store, "store");
    this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
  }

  /** 目标 Thread 所属执行树；目标不存在抛 not-found，兄弟顺序为 {@code createdAt} 再 UUID。 */
  List<ThreadTreeNode> project(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return store.transaction(
        tx -> {
          ThreadState target =
              ThreadTreeLocks.lockForThread(tx, threadId)
                  .orElseThrow(
                      () ->
                          DomainException.notFound(
                              "thread", "thread " + threadId + " does not exist"));
          List<ThreadState> locked = lockTreeThreads(tx, target.rootThreadId());
          ThreadContextProbe probe = new ThreadContextProbe(driverRegistry);
          List<ThreadTreeNode> nodes = new ArrayList<>(locked.size());
          for (ThreadState thread : locked) {
            nodes.add(toNode(tx, probe, thread));
          }
          nodes.sort(
              Comparator.comparing((ThreadTreeNode node) -> node.thread().createdAt())
                  .thenComparing(node -> node.thread().id(), UuidOrder.COMPARATOR));
          return List.copyOf(nodes);
        });
  }

  private static List<ThreadState> lockTreeThreads(HarnessStore.Transaction tx, UUID rootThreadId) {
    List<UUID> lockOrder =
        tx.threads().listByRoot(rootThreadId).stream()
            .map(ThreadState::id)
            .sorted(UuidOrder.COMPARATOR)
            .toList();
    List<ThreadState> locked = new ArrayList<>(lockOrder.size());
    for (UUID id : lockOrder) {
      locked.add(tx.threads().lock(id).orElseThrow());
    }
    return locked;
  }

  private static ThreadTreeNode toNode(
      HarnessStore.Transaction tx, ThreadContextProbe probe, ThreadState thread) {
    List<ThreadCommand> queued = tx.commands().loadQueued(thread.id());
    EntryCurrentState state = tx.entries().loadCurrent(thread.headEntryId());
    EntryPathMetrics metrics = tx.entries().loadPathMetrics(thread.headEntryId());
    int incompleteChildJoins = tx.joins().countIncompleteChildren(thread.id());
    boolean unconsumedAppliedNotifications =
        ThreadInputDemand.hasUnconsumedNotification(tx, thread);
    // 树锁串行化同树 Invocation 写入；只读探测不在多个节点间回退 Model/Tool 锁阶梯，也不装载历史。
    ThreadContext context = probe.probe(tx, thread, state);
    List<ExecutionExitFact> unconfirmed = tx.exits().loadUnconfirmed(thread.id());
    boolean incompleteOwnerChild = tx.joins().hasIncompleteOwnerChild(thread.id());
    ThreadRuntimeStatus status =
        ThreadSnapshot.evaluateRuntimeStatus(
            thread.executionControl(),
            ThreadContext.liveStateOf(context),
            state,
            thread.id(),
            queued,
            unconsumedAppliedNotifications,
            incompleteChildJoins,
            incompleteOwnerChild,
            !unconfirmed.isEmpty());
    return new ThreadTreeNode(
        thread,
        state.baseSettings(),
        status,
        ThreadSnapshot.evaluateOutcome(status, state),
        metrics);
  }
}

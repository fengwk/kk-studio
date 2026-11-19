package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.history.EntrySlice;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Thread snapshot 的装配：在调用方已锁定 Thread 行的同一事务内读取 queued Commands、Driver 私有状态、Tool siblings 与 收敛事实。
 *
 * <p>只读：不获取执行树锁、不推进 version。Driver generation checkpoint 可能在 Thread version 不变时推进，因此调用方不得把 version
 * 当作完整快照 ETag。
 */
final class ThreadSnapshotReader {

  private final DriverRegistry driverRegistry;

  ThreadSnapshotReader(DriverRegistry driverRegistry) {
    this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
  }

  /** 已锁定 Thread 的完整当前快照。 */
  ThreadSnapshot read(HarnessStore.Transaction tx, ThreadState thread) {
    return read(tx, thread, null);
  }

  /** 已锁定 Thread 的当前状态与指定历史切片；{@code afterEntryId} 为 null 时是完整历史。 */
  ThreadSnapshot read(HarnessStore.Transaction tx, ThreadState thread, UUID afterEntryId) {
    EntrySlice slice = tx.entries().loadSlice(thread.headEntryId(), afterEntryId);
    List<ThreadCommand> queued = tx.commands().loadQueued(thread.id());
    LockedThreadContext locked = ThreadContextLock.load(tx, thread, driverRegistry);
    GenerationProjection generation = generationOf(locked.context());
    requireGenerationOnPath(tx, thread, generation);
    List<ToolInvocation> toolSiblings = toolSiblingsOf(locked.context());
    Map<UUID, UUID> toolChildThreadIds = new HashMap<>();
    for (ToolInvocation invocation : toolSiblings) {
      tx.joins()
          .find(invocation.id())
          .filter(
              join ->
                  thread.id().equals(join.parentThreadId())
                      && join.delivery() == JoinDelivery.SETTLE_TOOL_RESULT)
          .ifPresent(join -> toolChildThreadIds.put(join.invocationId(), join.childThreadId()));
    }
    return new ThreadSnapshot(
        thread,
        locked.entryState(),
        slice,
        queued,
        generation,
        toolSiblings,
        toolChildThreadIds,
        ThreadContext.liveStateOf(locked.context()),
        tx.stops().loadByThread(thread.id()),
        tx.joins().countIncompleteChildren(thread.id()),
        ThreadInputDemand.hasUnconsumedNotification(tx, thread),
        tx.exits().loadUnconfirmed(thread.id()),
        tx.joins().hasIncompleteOwnerChild(thread.id()));
  }

  /** Driver 声明的请求 / 结果 head 必须仍在 Thread 路径上，否则持久化事实已被破坏。 */
  private static void requireGenerationOnPath(
      HarnessStore.Transaction tx, ThreadState thread, GenerationProjection generation) {
    if (generation == null) {
      return;
    }
    if (!tx.entries().isAncestor(thread.headEntryId(), generation.requestHeadEntryId())) {
      throw new IllegalStateException(
          "driver generation request head "
              + generation.requestHeadEntryId()
              + " is not on the thread path");
    }
    if (generation.resultEntryId() != null
        && !tx.entries().isAncestor(thread.headEntryId(), generation.resultEntryId())) {
      throw new IllegalStateException(
          "driver generation result entry "
              + generation.resultEntryId()
              + " is not on the thread path");
    }
  }

  static GenerationProjection generationOf(ThreadContext context) {
    return switch (context) {
      case ThreadContext.GenerationActive active -> active.driverState().projection();
      case ThreadContext.GenerationTerminalPending pending -> pending.driverState().projection();
      default -> null;
    };
  }

  private static List<ToolInvocation> toolSiblingsOf(ThreadContext context) {
    return switch (context) {
      case ThreadContext.ToolActive active -> active.siblings();
      case ThreadContext.ToolTerminalPending pending -> pending.siblings();
      default -> List.of();
    };
  }
}

package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stop 的执行树收集：解析目标 Thread、全部永久后代与各自派生的 stopRequestId，并给出 Join 交付所需直接父的加锁集合。
 *
 * <p>只读取 Thread 树与父引用，不获取任何业务行锁；调用方必须先持有执行树锁，再用 {@link Tree#sessionIds()} 与 {@link
 * Tree#threadIds()} 按规范顺序锁 Session 与 Thread，保持 Stop 的锁序不变。
 */
final class StopTreeCollector {

  private StopTreeCollector() {}

  /** 停止集合中的一个节点及其不可重用的停止身份。 */
  record Candidate(ThreadState thread, UUID stopRequestId) {

    Candidate {
      thread = Objects.requireNonNull(thread, "thread");
      stopRequestId = Objects.requireNonNull(stopRequestId, "stopRequestId");
    }
  }

  /** 收集结果：待停止节点，以及按 UuidOrder 去重排序的 Session/Thread 加锁集合（含目标直接父）。 */
  record Tree(List<Candidate> candidates, List<UUID> sessionIds, List<UUID> threadIds) {

    Tree {
      candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
      sessionIds = List.copyOf(Objects.requireNonNull(sessionIds, "sessionIds"));
      threadIds = List.copyOf(Objects.requireNonNull(threadIds, "threadIds"));
    }
  }

  static Tree collect(HarnessStore.Transaction tx, StopCommand command) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(command, "command");
    ThreadState targetThread =
        tx.threads()
            .find(command.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + command.threadId() + " does not exist"));

    // 收集目标线程及其所有永久后代线程，并派生确定的子 stopRequestId
    List<Candidate> candidates = new ArrayList<>();
    candidates.add(new Candidate(targetThread, command.stopRequestId()));
    Map<UUID, UUID> stopRequestIds = new HashMap<>();
    stopRequestIds.put(targetThread.id(), command.stopRequestId());

    List<UUID> frontier = new ArrayList<>();
    frontier.add(targetThread.id());
    Set<UUID> visited = new HashSet<>();
    visited.add(targetThread.id());

    while (!frontier.isEmpty()) {
      UUID parentId = frontier.remove(0);
      UUID parentStopId = stopRequestIds.get(parentId);
      List<ThreadState> children = tx.threads().listChildren(parentId);
      for (ThreadState child : children) {
        if (visited.add(child.id())) {
          UUID childStopId = deriveChildStopRequestId(parentStopId, child.id());
          stopRequestIds.put(child.id(), childStopId);
          candidates.add(new Candidate(child, childStopId));
          frontier.add(child.id());
        }
      }
    }

    // 子树内所有其它节点的直接父都在停止集合内；子 Join 只向直接父交付，因此外部只补目标的一个直接父。
    List<ThreadState> parentThreads = new ArrayList<>();
    UUID parentThreadId = targetThread.parentThreadId();
    if (parentThreadId != null) {
      tx.threads().find(parentThreadId).ifPresent(parentThreads::add);
    }

    List<UUID> sessionIds = new ArrayList<>();
    for (Candidate candidate : candidates) {
      sessionIds.add(candidate.thread().sessionId());
    }
    for (ThreadState parent : parentThreads) {
      sessionIds.add(parent.sessionId());
    }
    List<UUID> threadIds = new ArrayList<>();
    for (Candidate candidate : candidates) {
      threadIds.add(candidate.thread().id());
    }
    for (ThreadState parent : parentThreads) {
      threadIds.add(parent.id());
    }
    return new Tree(
        candidates,
        sessionIds.stream().distinct().sorted(UuidOrder.COMPARATOR).toList(),
        threadIds.stream().distinct().sorted(UuidOrder.COMPARATOR).toList());
  }

  static UUID deriveChildStopRequestId(UUID parentStopRequestId, UUID childThreadId) {
    Objects.requireNonNull(parentStopRequestId, "parentStopRequestId");
    Objects.requireNonNull(childThreadId, "childThreadId");
    return UUID.nameUUIDFromBytes(
        (parentStopRequestId + ":" + childThreadId).getBytes(StandardCharsets.UTF_8));
  }
}

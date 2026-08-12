package fun.fengwk.kkstudio.core.ai.runtime.task;

import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 进程内 task 并发 reservation 与活动状态 relay。
 *
 * <p>durable 子 Session/Thread 始终是唯一执行事实；状态 relay 只把嵌套 task 的瞬时心跳投影给祖先 Thread UI，不参与调度或终态判定。
 */
public final class SubagentRunRegistry {

  private final Map<UUID, Integer> activeByParent = new HashMap<>();
  private final Map<UUID, Integer> activeByRoot = new HashMap<>();
  private final Set<UUID> activeThreads = new HashSet<>();
  private final Map<UUID, UUID> parentByThread = new HashMap<>();
  private final Map<UUID, RelayedStatus> statusByThread = new HashMap<>();

  synchronized Reservation reserve(
      UUID parentThreadId, UUID rootThreadId, UUID resumeThreadId, SubagentConfig config) {
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    if (resumeThreadId != null && activeThreads.contains(resumeThreadId)) {
      throw new IllegalArgumentException(
          "subagent session \"" + resumeThreadId + "\" is currently running");
    }
    int parentActive = activeByParent.getOrDefault(parentThreadId, 0);
    if (parentActive >= config.maxConcurrency()) {
      throw new IllegalArgumentException(
          "subagent concurrency limit reached ("
              + parentActive
              + "/"
              + config.maxConcurrency()
              + ")");
    }
    int rootActive = activeByRoot.getOrDefault(rootThreadId, 0);
    Integer maxTotal = config.maxTotalConcurrency();
    if (maxTotal != null && rootActive >= maxTotal) {
      throw new IllegalArgumentException(
          "subagent tree concurrency limit reached (" + rootActive + "/" + maxTotal + ")");
    }
    activeByParent.put(parentThreadId, parentActive + 1);
    activeByRoot.put(rootThreadId, rootActive + 1);
    if (resumeThreadId != null) {
      activeThreads.add(resumeThreadId);
    }
    return new Reservation(this, parentThreadId, rootThreadId, resumeThreadId);
  }

  private synchronized void attach(Reservation reservation, UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    if (reservation.closed) {
      return;
    }
    if (reservation.threadId != null && !reservation.threadId.equals(threadId)) {
      throw new IllegalStateException("subagent reservation is already attached");
    }
    if (!activeThreads.add(threadId) && !threadId.equals(reservation.threadId)) {
      throw new IllegalArgumentException(
          "subagent session \"" + threadId + "\" is currently running");
    }
    reservation.threadId = threadId;
    parentByThread.put(threadId, reservation.parentThreadId);
  }

  synchronized void publishStatus(RelayedStatus status) {
    Objects.requireNonNull(status, "status");
    if (!activeThreads.contains(status.threadId())) {
      throw new IllegalStateException(
          "cannot publish status for inactive subagent session " + status.threadId());
    }
    statusByThread.put(status.threadId(), status);
  }

  synchronized List<RelayedStatus> descendantStatuses(UUID ancestorThreadId) {
    Objects.requireNonNull(ancestorThreadId, "ancestorThreadId");
    List<RelayedStatus> descendants = new ArrayList<>();
    for (RelayedStatus status : statusByThread.values()) {
      if (isDescendant(status.threadId(), ancestorThreadId)) {
        descendants.add(status);
      }
    }
    descendants.sort(
        Comparator.comparingInt(RelayedStatus::depth)
            .thenComparing(RelayedStatus::threadId, UuidOrder.COMPARATOR));
    return List.copyOf(descendants);
  }

  private boolean isDescendant(UUID threadId, UUID ancestorThreadId) {
    Set<UUID> visited = new HashSet<>();
    UUID parent = parentByThread.get(threadId);
    while (parent != null && visited.add(parent)) {
      if (parent.equals(ancestorThreadId)) {
        return true;
      }
      parent = parentByThread.get(parent);
    }
    return false;
  }

  private synchronized void release(Reservation reservation) {
    if (reservation.closed) {
      return;
    }
    reservation.closed = true;
    decrement(activeByParent, reservation.parentThreadId);
    decrement(activeByRoot, reservation.rootThreadId);
    if (reservation.threadId != null) {
      activeThreads.remove(reservation.threadId);
      parentByThread.remove(reservation.threadId);
      statusByThread.remove(reservation.threadId);
    }
  }

  private static void decrement(Map<UUID, Integer> counts, UUID key) {
    int next = counts.getOrDefault(key, 0) - 1;
    if (next > 0) {
      counts.put(key, next);
    } else {
      counts.remove(key);
    }
  }

  record RelayedApproval(UUID invocationId, String toolName, String reason) {
    RelayedApproval {
      Objects.requireNonNull(invocationId, "invocationId");
      if (toolName == null || toolName.isBlank()) {
        throw new IllegalArgumentException("toolName must not be blank");
      }
    }
  }

  record RelayedStatus(
      UUID threadId,
      String subagentType,
      String state,
      int depth,
      int turns,
      int toolCalls,
      String lastActivity,
      List<RelayedApproval> approvals) {
    RelayedStatus {
      Objects.requireNonNull(threadId, "threadId");
      if (subagentType == null || subagentType.isBlank()) {
        throw new IllegalArgumentException("subagentType must not be blank");
      }
      if (state == null || state.isBlank()) {
        throw new IllegalArgumentException("state must not be blank");
      }
      if (depth < 0 || turns < 0 || toolCalls < 0) {
        throw new IllegalArgumentException("status counters must not be negative");
      }
      if (lastActivity == null || lastActivity.isBlank()) {
        throw new IllegalArgumentException("lastActivity must not be blank");
      }
      approvals = List.copyOf(Objects.requireNonNull(approvals, "approvals"));
    }
  }

  static final class Reservation implements AutoCloseable {
    private final SubagentRunRegistry owner;
    private final UUID parentThreadId;
    private final UUID rootThreadId;
    private UUID threadId;
    private boolean closed;

    private Reservation(
        SubagentRunRegistry owner, UUID parentThreadId, UUID rootThreadId, UUID threadId) {
      this.owner = owner;
      this.parentThreadId = parentThreadId;
      this.rootThreadId = rootThreadId;
      this.threadId = threadId;
    }

    void attach(UUID threadId) {
      owner.attach(this, threadId);
    }

    @Override
    public void close() {
      owner.release(this);
    }
  }
}

package fun.fengwk.kkstudio.core.ai.runtime.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 进程内 task 并发 reservation 与活动状态 relay。
 *
 * <p>durable 子 Session/Thread 始终是唯一执行事实；状态 relay 只把嵌套 task 的瞬时心跳投影给祖先 Thread UI，不参与调度或终态判定。
 */
public final class SubagentRunRegistry {

  private final Map<Long, Integer> activeByParent = new HashMap<>();
  private final Map<Long, Integer> activeByRoot = new HashMap<>();
  private final Set<Long> activeThreads = new HashSet<>();
  private final Map<Long, Long> parentByThread = new HashMap<>();
  private final Map<Long, RelayedStatus> statusByThread = new HashMap<>();

  synchronized Reservation reserve(
      long parentThreadId, long rootThreadId, Long resumeThreadId, SubagentConfig config) {
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

  private synchronized void attach(Reservation reservation, long threadId) {
    if (reservation.closed) {
      return;
    }
    if (reservation.threadId != null && reservation.threadId != threadId) {
      throw new IllegalStateException("subagent reservation is already attached");
    }
    if (!activeThreads.add(threadId) && !Long.valueOf(threadId).equals(reservation.threadId)) {
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

  synchronized List<RelayedStatus> descendantStatuses(long ancestorThreadId) {
    List<RelayedStatus> descendants = new ArrayList<>();
    for (RelayedStatus status : statusByThread.values()) {
      if (isDescendant(status.threadId(), ancestorThreadId)) {
        descendants.add(status);
      }
    }
    descendants.sort(
        Comparator.comparingInt(RelayedStatus::depth).thenComparingLong(RelayedStatus::threadId));
    return List.copyOf(descendants);
  }

  private boolean isDescendant(long threadId, long ancestorThreadId) {
    Set<Long> visited = new HashSet<>();
    Long parent = parentByThread.get(threadId);
    while (parent != null && visited.add(parent)) {
      if (parent == ancestorThreadId) {
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

  private static void decrement(Map<Long, Integer> counts, long key) {
    int next = counts.getOrDefault(key, 0) - 1;
    if (next > 0) {
      counts.put(key, next);
    } else {
      counts.remove(key);
    }
  }

  record RelayedApproval(long invocationId, String toolName, String reason) {
    RelayedApproval {
      if (invocationId <= 0) {
        throw new IllegalArgumentException("invocationId must be positive");
      }
      if (toolName == null || toolName.isBlank()) {
        throw new IllegalArgumentException("toolName must not be blank");
      }
    }
  }

  record RelayedStatus(
      long threadId,
      String subagentType,
      String state,
      int depth,
      int turns,
      int toolCalls,
      String lastActivity,
      List<RelayedApproval> approvals) {
    RelayedStatus {
      if (threadId <= 0) {
        throw new IllegalArgumentException("threadId must be positive");
      }
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
    private final long parentThreadId;
    private final long rootThreadId;
    private Long threadId;
    private boolean closed;

    private Reservation(
        SubagentRunRegistry owner, long parentThreadId, long rootThreadId, Long threadId) {
      this.owner = owner;
      this.parentThreadId = parentThreadId;
      this.rootThreadId = rootThreadId;
      this.threadId = threadId;
    }

    void attach(long threadId) {
      owner.attach(this, threadId);
    }

    @Override
    public void close() {
      owner.release(this);
    }
  }
}

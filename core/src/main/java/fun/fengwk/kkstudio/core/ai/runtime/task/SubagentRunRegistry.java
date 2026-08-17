package fun.fengwk.kkstudio.core.ai.runtime.task;

import lombok.extern.slf4j.Slf4j;

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
 *
 * <p>{@link #subscribeDescendants} 按 ancestor 订阅 descendant 变化：publish/attach/release
 * 三个变更路径都在锁外备份通知 （回调绝不在 registry 锁内执行，避免回调再入 registry 造成死锁），单个回调异常只隔离自身，不阻断同一 ancestor 的其他订阅者；发布
 * thread 自身的 status 只通知其「严格祖先」，因此观察者发布自己的 child 时不会唤醒自己形成循环。
 */
@Slf4j
public final class SubagentRunRegistry {

  private final Map<UUID, Integer> activeByParent = new HashMap<>();
  private final Map<UUID, Integer> activeByRoot = new HashMap<>();
  private final Set<UUID> activeThreads = new HashSet<>();
  private final Map<UUID, UUID> parentByThread = new HashMap<>();
  private final Map<UUID, RelayedStatus> statusByThread = new HashMap<>();
  private final Map<UUID, Set<Runnable>> descendantSubscribers = new HashMap<>();

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

  void attach(Reservation reservation, UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    List<UUID> ancestors;
    synchronized (this) {
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
      ancestors = ancestorsOf(threadId);
    }
    notifyDescendantChange(ancestors);
  }

  void publishStatus(RelayedStatus status) {
    Objects.requireNonNull(status, "status");
    List<UUID> ancestors;
    synchronized (this) {
      if (!activeThreads.contains(status.threadId())) {
        throw new IllegalStateException(
            "cannot publish status for inactive subagent session " + status.threadId());
      }
      statusByThread.put(status.threadId(), status);
      ancestors = ancestorsOf(status.threadId());
    }
    // status 的 thread 自身不是自己的 descendant：观察自己 child 的订阅者不会被自己的发布唤醒。
    notifyDescendantChange(ancestors);
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

  /**
   * 订阅 {@code ancestorThreadId} 的 descendant 变化：任何 descendant 的 status 发布、attach 或 reservation
   * 释放都会在锁外触发 {@code onChange}。返回的 {@link ChangeSubscription} 释放订阅且幂等；回调异常被隔离，不影响其他订阅者与后续通知。
   */
  public ChangeSubscription subscribeDescendants(UUID ancestorThreadId, Runnable onChange) {
    Objects.requireNonNull(ancestorThreadId, "ancestorThreadId");
    Objects.requireNonNull(onChange, "onChange");
    Runnable subscriber = onChange;
    synchronized (this) {
      descendantSubscribers
          .computeIfAbsent(ancestorThreadId, ignored -> new HashSet<>())
          .add(subscriber);
    }
    boolean[] closed = new boolean[1];
    return new ChangeSubscription() {
      @Override
      public void close() {
        synchronized (SubagentRunRegistry.this) {
          if (closed[0]) {
            return;
          }
          closed[0] = true;
          Set<Runnable> subscribers = descendantSubscribers.get(ancestorThreadId);
          if (subscribers != null) {
            subscribers.remove(subscriber);
            if (subscribers.isEmpty()) {
              descendantSubscribers.remove(ancestorThreadId);
            }
          }
        }
      }
    };
  }

  private void release(Reservation reservation) {
    List<UUID> ancestors;
    synchronized (this) {
      if (reservation.closed) {
        return;
      }
      reservation.closed = true;
      decrement(activeByParent, reservation.parentThreadId);
      decrement(activeByRoot, reservation.rootThreadId);
      if (reservation.threadId != null) {
        ancestors = ancestorsOf(reservation.threadId);
        activeThreads.remove(reservation.threadId);
        parentByThread.remove(reservation.threadId);
        statusByThread.remove(reservation.threadId);
      } else {
        ancestors = List.of();
      }
    }
    notifyDescendantChange(ancestors);
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

  /** threadId 的严格祖先链（不含自身）；必须在持锁状态下调用。 */
  private List<UUID> ancestorsOf(UUID threadId) {
    List<UUID> ancestors = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    UUID parent = parentByThread.get(threadId);
    while (parent != null && visited.add(parent)) {
      ancestors.add(parent);
      parent = parentByThread.get(parent);
    }
    return ancestors;
  }

  /**
   * 在锁外通知 ancestors 上注册的全部订阅者：先拷贝目标再逐个调用，避免回调持有 registry 锁（回调可能再入
   * descendantStatuses/reserve/publishStatus）；单个回调异常只隔离自身。
   */
  private void notifyDescendantChange(List<UUID> ancestors) {
    if (ancestors.isEmpty()) {
      return;
    }
    List<Runnable> targets;
    synchronized (this) {
      targets = new ArrayList<>();
      for (UUID ancestor : ancestors) {
        Set<Runnable> subscribers = descendantSubscribers.get(ancestor);
        if (subscribers != null) {
          targets.addAll(subscribers);
        }
      }
    }
    for (Runnable target : targets) {
      try {
        target.run();
      } catch (RuntimeException error) {
        // 单个订阅者异常只隔离自身；不会阻止同一 ancestor 的其他订阅者，也不会让 registry 状态不一致。回调只是 wake 提示，失败不影响事实。
        log.warn("subagent descendant subscriber callback failed; skipping", error);
      }
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

  /** descendant-change 订阅句柄；{@link #close()} 无 checked-exception 且幂等。 */
  public interface ChangeSubscription extends AutoCloseable {
    @Override
    void close();
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

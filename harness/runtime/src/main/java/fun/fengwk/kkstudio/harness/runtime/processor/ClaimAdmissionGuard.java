package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * per-invocation atomic admission guard 与本地 execution registry。
 *
 * <p>以 {@link ConcurrentHashMap#compute} 提供 per-invocation 线性化准入：
 *
 * <ul>
 *   <li>同 token 去重：同一 claim 的重复 / 并发投递直接拒绝准入，不 cancel、不 mutation。
 *   <li>内部复核 DB claim ownership：准入前在原子计算内复核 DB 所有权，确认当前 claim 为有效 owner。
 *   <li>失权无取消副作用：DB 复核失权时绝不取消或干扰正在运行的合法 execution。
 *   <li>代际替换：新 claim 证实持有 DB 所有权后，原子捕获并释放旧 execution，使旧 claim 安全退场。
 *   <li>禁止在 guard 返回后再读取 registry 盲目取消；长 I/O（如 preflight）绝不放入锁内。
 * </ul>
 */
final class ClaimAdmissionGuard {

  interface GuardedExecution {
    ClaimedWork claim();

    void abandon();
  }

  static final class Entry {
    final String token;
    volatile GuardedExecution execution;
    volatile boolean superseded;

    Entry(String token) {
      this.token = token;
    }
  }

  static final class Ticket<E extends GuardedExecution> implements AutoCloseable {
    private final ClaimAdmissionGuard guard;
    private final UUID invocationId;
    private final Entry entry;
    private final AtomicBoolean closed = new AtomicBoolean();

    Ticket(ClaimAdmissionGuard guard, UUID invocationId, Entry entry) {
      this.guard = guard;
      this.invocationId = invocationId;
      this.entry = entry;
    }

    boolean isSuperseded() {
      return entry.superseded;
    }

    boolean attach(E execution) {
      Objects.requireNonNull(execution, "execution");
      AtomicBoolean attached = new AtomicBoolean();
      guard.entries.computeIfPresent(
          invocationId,
          (id, current) -> {
            if (current == entry && !current.superseded) {
              current.execution = execution;
              attached.set(true);
            }
            return current;
          });
      return attached.get();
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      guard.releaseTicket(invocationId, entry);
    }
  }

  private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
  private volatile boolean closed;

  /** ThreadProcessor 的无 execution 准入。 */
  boolean tryAdmit(UUID invocationId, String token) {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(token, "token");
    if (closed) {
      return false;
    }
    AtomicBoolean admitted = new AtomicBoolean();
    entries.compute(
        invocationId,
        (id, current) -> {
          if (closed) {
            return current;
          }
          if (current != null && token.equals(current.token)) {
            return current;
          }
          if (current != null) {
            current.superseded = true;
            if (current.execution != null) {
              current.execution.abandon();
            }
          }
          admitted.set(true);
          return new Entry(token);
        });
    return admitted.get();
  }

  /** 仅释放仍由指定 token 持有的纯准入槽位。 */
  void release(UUID invocationId, String token) {
    entries.computeIfPresent(
        invocationId,
        (id, current) -> {
          if (token.equals(current.token) && current.execution == null) {
            return null;
          }
          return current;
        });
  }

  /**
   * 原子准入：在 per-invocation 锁内复核 DB claim ownership，确认持有后安全捕获并释放旧 execution。
   *
   * @param claim 当前 claim
   * @param claimVerifier 内部复核 DB claim ownership 的无锁/短事务校验
   * @return 成功准入返回 Ticket，失败（同 token 重复或失权）返回 null
   */
  <E extends GuardedExecution> Ticket<E> tryAdmit(
      ClaimedWork claim, BooleanSupplier claimVerifier) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(claimVerifier, "claimVerifier");
    UUID invocationId = claim.target().id();
    String token = claim.leaseToken();
    AtomicReference<Ticket<E>> ticketRef = new AtomicReference<>();
    AtomicReference<GuardedExecution> oldExecution = new AtomicReference<>();

    entries.compute(
        invocationId,
        (id, current) -> {
          if (current != null && token.equals(current.token)) {
            return current;
          }
          if (!claimVerifier.getAsBoolean()) {
            return current;
          }
          if (current != null) {
            current.superseded = true;
            if (current.execution != null) {
              oldExecution.set(current.execution);
            }
          }
          Entry next = new Entry(token);
          ticketRef.set(new Ticket<>(this, invocationId, next));
          return next;
        });

    GuardedExecution toAbandon = oldExecution.get();
    if (toAbandon != null) {
      toAbandon.abandon();
    }
    return ticketRef.get();
  }

  void releaseTicket(UUID invocationId, Entry entry) {
    entries.computeIfPresent(
        invocationId,
        (id, current) -> {
          if (current == entry && current.execution == null && !current.superseded) {
            return null;
          }
          return current;
        });
  }

  @SuppressWarnings("unchecked")
  <E extends GuardedExecution> E getExecution(UUID invocationId) {
    Objects.requireNonNull(invocationId, "invocationId");
    Entry entry = entries.get(invocationId);
    if (entry != null && !entry.superseded) {
      return (E) entry.execution;
    }
    return null;
  }

  boolean cancel(UUID invocationId) {
    Objects.requireNonNull(invocationId, "invocationId");
    AtomicReference<GuardedExecution> cancelled = new AtomicReference<>();
    entries.computeIfPresent(
        invocationId,
        (id, current) -> {
          current.superseded = true;
          if (current.execution != null) {
            cancelled.set(current.execution);
          }
          return null;
        });
    GuardedExecution execution = cancelled.get();
    if (execution != null) {
      execution.abandon();
      return true;
    }
    return false;
  }

  void releaseExecution(GuardedExecution execution) {
    if (execution == null) {
      return;
    }
    UUID invocationId = execution.claim().target().id();
    entries.computeIfPresent(
        invocationId,
        (id, current) -> {
          if (current.execution == execution) {
            return null;
          }
          return current;
        });
  }

  boolean hasActiveExecution() {
    for (Entry entry : entries.values()) {
      if (entry.execution != null && !entry.superseded) {
        return true;
      }
    }
    return false;
  }

  void close() {
    closed = true;
    List<GuardedExecution> toAbandon = new ArrayList<>();
    for (UUID id : List.copyOf(entries.keySet())) {
      entries.computeIfPresent(
          id,
          (key, current) -> {
            current.superseded = true;
            if (current.execution != null) {
              toAbandon.add(current.execution);
            }
            return null;
          });
    }
    for (GuardedExecution execution : toAbandon) {
      execution.abandon();
    }
  }
}

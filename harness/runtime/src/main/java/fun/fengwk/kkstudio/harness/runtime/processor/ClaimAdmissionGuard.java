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
 *   <li>类级泛型：registry 路径与 execution 强类型绑定，内部无 unchecked 转换。
 *   <li>同 token 去重：同一 claim 的重复 / 并发投递直接拒绝准入，不 cancel、不 mutation。
 *   <li>内部复核 DB claim ownership：准入前在原子计算内复核 DB 所有权，确认当前 claim 为有效 owner。
 *   <li>失权无取消副作用：DB 复核失权时绝不取消或干扰正在运行的合法 execution。
 *   <li>代际替换：新 claim 证实持有 DB 所有权后，原子捕获旧 execution，并在锁外 abandon，使旧 claim 安全退场。
 *   <li>close 守卫：close 期间及关闭后的迟到准入不创建 slot、不执行 verifier，旧 ticket 拒绝 attach。
 * </ul>
 */
public final class ClaimAdmissionGuard<E extends ClaimAdmissionGuard.GuardedExecution> {

  /** 进程内 execution 的最小契约：绑定 claim，并可被本地放弃。 */
  public interface GuardedExecution {
    ClaimedWork claim();

    void abandon();
  }

  private static final class Entry<E extends GuardedExecution> {
    private final String token;
    private volatile E execution;
    private volatile boolean superseded;

    private Entry(String token) {
      this.token = token;
    }
  }

  /** 一次准入的票据：登记 execution、查询是否被替代，关闭时释放未 attach 的 slot。 */
  public static final class Ticket<E extends GuardedExecution> implements AutoCloseable {
    private final ClaimAdmissionGuard<E> guard;
    private final UUID invocationId;

    /** {@code null} 表示 guard 已 close：迟到准入不持有 slot，只能按既有路由收敛。 */
    private final Entry<E> entry;

    private final AtomicBoolean closed = new AtomicBoolean();

    private Ticket(ClaimAdmissionGuard<E> guard, UUID invocationId, Entry<E> entry) {
      this.guard = guard;
      this.invocationId = invocationId;
      this.entry = entry;
    }

    public boolean isSuperseded() {
      return entry == null || entry.superseded;
    }

    public boolean attach(E execution) {
      return entry != null && guard.attach(this, entry, execution);
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true) || entry == null) {
        return;
      }
      guard.releaseTicket(invocationId, entry);
    }
  }

  private final ConcurrentHashMap<UUID, Entry<E>> entries = new ConcurrentHashMap<>();
  private volatile boolean closed;

  ClaimAdmissionGuard() {}

  Ticket<E> tryAdmit(ClaimedWork claim, BooleanSupplier claimVerifier) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(claimVerifier, "claimVerifier");
    UUID invocationId = claim.target().id();
    if (closed) {
      // close 后迟到准入：不创建 slot、不执行 verifier，返回不可 attach 的 ticket，由调用方按既有路由收敛。
      return new Ticket<>(this, invocationId, null);
    }
    String token = claim.leaseToken();
    AtomicReference<Ticket<E>> ticketRef = new AtomicReference<>();
    AtomicReference<E> oldExecution = new AtomicReference<>();

    entries.compute(
        invocationId,
        (id, current) -> {
          if (closed) {
            return current;
          }
          if (current != null && token.equals(current.token)) {
            return current;
          }
          if (!claimVerifier.getAsBoolean()) {
            return current;
          }
          if (closed) {
            return current;
          }
          if (current != null) {
            current.superseded = true;
            if (current.execution != null) {
              oldExecution.set(current.execution);
            }
          }
          Entry<E> next = new Entry<>(token);
          ticketRef.set(new Ticket<>(this, invocationId, next));
          return next;
        });

    E toAbandon = oldExecution.get();
    if (toAbandon != null) {
      toAbandon.abandon();
    }
    Ticket<E> admitted = ticketRef.get();
    if (admitted == null && closed) {
      // close 在原子计算内获胜：同样不留下 slot，交由调用方按 closed 路由收敛。
      return new Ticket<>(this, invocationId, null);
    }
    return admitted;
  }

  private boolean attach(Ticket<E> ticket, Entry<E> entry, E execution) {
    Objects.requireNonNull(execution, "execution");
    if (closed) {
      return false;
    }
    AtomicBoolean attached = new AtomicBoolean();
    entries.computeIfPresent(
        ticket.invocationId,
        (id, current) -> {
          if (!closed && current == entry && !current.superseded) {
            current.execution = execution;
            attached.set(true);
          }
          return current;
        });
    return attached.get();
  }

  private void releaseTicket(UUID invocationId, Entry<E> entry) {
    entries.computeIfPresent(
        invocationId,
        (id, current) -> {
          if (current == entry && current.execution == null && !current.superseded) {
            return null;
          }
          return current;
        });
  }

  E getExecution(UUID invocationId) {
    Objects.requireNonNull(invocationId, "invocationId");
    Entry<E> entry = entries.get(invocationId);
    if (entry != null && !entry.superseded) {
      return entry.execution;
    }
    return null;
  }

  boolean cancel(UUID invocationId) {
    Objects.requireNonNull(invocationId, "invocationId");
    AtomicReference<E> cancelledExecution = new AtomicReference<>();
    AtomicBoolean cancelledEntry = new AtomicBoolean();
    entries.computeIfPresent(
        invocationId,
        (id, current) -> {
          cancelledEntry.set(true);
          current.superseded = true;
          if (current.execution != null) {
            cancelledExecution.set(current.execution);
          }
          return null;
        });
    E execution = cancelledExecution.get();
    if (execution != null) {
      execution.abandon();
    }
    return cancelledEntry.get();
  }

  void releaseExecution(E execution) {
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
    for (Entry<E> entry : entries.values()) {
      if (entry.execution != null && !entry.superseded) {
        return true;
      }
    }
    return false;
  }

  boolean isClosed() {
    return closed;
  }

  void close() {
    closed = true;
    List<E> toAbandon = new ArrayList<>();
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
    for (E execution : toAbandon) {
      execution.abandon();
    }
  }
}

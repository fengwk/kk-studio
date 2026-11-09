package fun.fengwk.kkstudio.harness.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionDrain;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionHandle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRecovery;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRegistration;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地执行物理生命周期的实现：一个两阶段执行日志、一个进程内物理句柄集合、一个退出确认器。
 *
 * <p>登记顺序固定在端口内：物理句柄 → PREPARED 原子写入并 fsync → 数据库登记事务 → 事务返回后才允许 body。取消、失权与 supersede 都不从物理集合移除；
 * 只有 body 与本地资源都结束后才写 EXIT_PROOF。排空失败、资源关闭失败或证明未落账时日志目录锁保留到 JVM 退出。
 *
 * <p>本实现不做任何持久化决策：PREPARED / EXIT_PROOF 由 {@link ExecutionExitJournal} 落盘，数据库登记由调用方事务完成，确认与恢复由
 * {@link ExecutionExitConfirmer} 负责。
 *
 * <p>物理语义：取消在 attach body 之前就会被记住；只有确认登记且未被取消时 {@link LocalExecutionHandle#attachBody} 才放行
 * body。仍存活的已附着 body 继续留在物理集合中，只有 body 结束或被 detach 后才允许证明退出；无法证明退出时目录锁保留到 JVM 退出。
 */
@Slf4j
public final class ExecutionLifecycle implements LocalExecutionLifecycle {

  private static final String PHASE_BEGIN = "EXECUTION_BEGIN";
  private static final String PHASE_EXIT = "EXECUTION_EXIT";
  private static final String PHASE_REGISTRATION_RECONCILE = "EXECUTION_REGISTRATION_RECONCILE";

  private final HarnessStore store;
  private final ExecutionExitJournal journal;
  private final ExecutionExitConfirmer confirmer;
  private final Object monitor = new Object();
  private final Map<UUID, Handle> active = new HashMap<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean accepting = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 仍有物理执行或未证明退出；只在持有 monitor 时读取或推进。 */
  private boolean retainsJournalLock;

  public ExecutionLifecycle(
      HarnessStore store, ExecutionExitJournal journal, ExecutionExitConfirmer confirmer) {
    this.store = Objects.requireNonNull(store, "store");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.confirmer = Objects.requireNonNull(confirmer, "confirmer");
  }

  @Override
  public LocalExecutionRecovery start() {
    if (closed.get()) {
      throw new IllegalStateException("local execution lifecycle is closed");
    }
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("local execution lifecycle is already started");
    }
    LocalExecutionRecovery recovery = confirmer.recoverPreviousRun();
    accepting.set(true);
    return recovery;
  }

  @Override
  public boolean accepting() {
    return accepting.get();
  }

  @Override
  public LocalExecutionHandle begin(
      LocalExecutionIdentity identity, LocalExecutionRegistration registration) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(registration, "registration");
    Handle handle;
    synchronized (monitor) {
      if (!accepting.get()) {
        throw new IllegalStateException("local execution lifecycle does not accept new executions");
      }
      handle = new Handle(identity);
      if (active.putIfAbsent(identity.executionId(), handle) != null) {
        throw new IllegalStateException(
            "local execution " + identity.executionId() + " already holds a physical handle");
      }
    }
    try {
      journal.write(ExecutionExitJournalRecord.prepared(identity));
    } catch (RuntimeException failure) {
      // PREPARED 写入失败：不登记、不执行。
      finish(handle);
      log.warn(
          "cannot prepare local execution; phase={}; executionId={}; exception={}",
          PHASE_BEGIN,
          identity.executionId(),
          failure.getClass().getSimpleName());
      throw failure;
    }
    ExecutionExitFact registered;
    try {
      registered = registration.register();
    } catch (RuntimeException failure) {
      // 数据库登记失败或结果不明：不进入 body，按精确身份对账后由调用方释放资源并结束句柄。
      handle.registrationUncertain(reconcileRegistration(identity, failure));
      return handle;
    }
    if (registered == null) {
      // 事务已提交但没有登记（stale / Stop / 已失权）：正常失权结果，不进入 body，由调用方释放资源后确认未启动。
      handle.registrationAbsent();
      return handle;
    }
    if (!ExecutionExitJournalRecord.prepared(identity).matchesFactIdentity(registered)) {
      // 登记了与本代次不符的事实：不能把调用方的错误登记当成安全结论，保留物理句柄与目录锁。
      IllegalStateException mismatch =
          new IllegalStateException(
              "registered execution fact does not match the frozen identity: "
                  + identity.executionId());
      retainJournalLock(identity.executionId(), mismatch);
      throw mismatch;
    }
    handle.registered(registered);
    return handle;
  }

  /**
   * 登记结果不明时按精确身份读取权威事实。
   *
   * <p>先在事务内取得该 Thread 锁再读取事实，使并发的登记提交与缺失判断串行化；Thread 已不存在时执行事实不可能存在。读取失败时保持未决， 绝不猜测登记结果。
   */
  private RegistrationLookup reconcileRegistration(
      LocalExecutionIdentity identity, RuntimeException registrationFailure) {
    try {
      return store.transaction(
          tx -> {
            if (tx.threads().lock(identity.threadId()).isEmpty()) {
              return RegistrationLookup.absent();
            }
            ExecutionExitFact fact = tx.exits().find(identity.executionId()).orElse(null);
            if (fact == null) {
              return RegistrationLookup.absent();
            }
            if (!ExecutionExitJournalRecord.prepared(identity).matchesFactIdentity(fact)) {
              return RegistrationLookup.conflict();
            }
            return RegistrationLookup.present(fact);
          });
    } catch (RuntimeException lookupFailure) {
      log.warn(
          "cannot reconcile local execution registration; phase={}; executionId={};"
              + " registrationException={}; lookupException={}",
          PHASE_REGISTRATION_RECONCILE,
          identity.executionId(),
          registrationFailure.getClass().getSimpleName(),
          lookupFailure.getClass().getSimpleName());
      return RegistrationLookup.undetermined();
    }
  }

  @Override
  public LocalExecutionDrain drain(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("drain timeout must not be negative");
    }
    accepting.set(false);
    // 物理等待必须使用单调时钟且有界：可固定的业务 Clock 不能决定排空是否结束。
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (monitor) {
      while (!active.isEmpty()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          break;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      if (!active.isEmpty()) {
        // 同一 deadline 已到：只请求取消，不从物理集合移除，也不伪造退出。
        for (Handle handle : active.values()) {
          handle.requestCancel();
        }
        retainsJournalLock = true;
        journal.holdUntilProcessExit();
      }
      List<UUID> unfinished = new ArrayList<>(active.keySet());
      unfinished.sort(null);
      return new LocalExecutionDrain(unfinished, retainsJournalLock);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    accepting.set(false);
    synchronized (monitor) {
      if (!active.isEmpty() || retainsJournalLock) {
        retainsJournalLock = true;
        journal.holdUntilProcessExit();
        log.warn(
            "local execution lifecycle closed with unfinished physical executions;"
                + " active={}; the journal directory lock is retained until process exit",
            active.size());
      }
    }
  }

  /** 从物理集合移除并唤醒排空等待；只删除同一 executionId 的对应句柄。 */
  private void finish(Handle handle) {
    synchronized (monitor) {
      active.remove(handle.identity.executionId(), handle);
      monitor.notifyAll();
    }
  }

  /**
   * 无法证明退出：保留日志与未确认事实，并要求目录锁保留到 JVM 退出。
   *
   * <p>目录锁已随 journal 关闭而释放时如实记录错误，不假装锁仍被持有。
   */
  private void retainJournalLock(UUID executionId, Throwable failure) {
    synchronized (monitor) {
      retainsJournalLock = true;
    }
    try {
      journal.holdUntilProcessExit();
    } catch (RuntimeException journalFailure) {
      log.error(
          "cannot retain the execution journal directory lock; it was already released;"
              + " phase={}; executionId={}; exception={}",
          PHASE_EXIT,
          executionId,
          journalFailure.getClass().getSimpleName());
    }
    log.warn(
        "local execution exit is not proven; phase={}; executionId={}; exception={}",
        PHASE_EXIT,
        executionId,
        failure == null ? "-" : failure.getClass().getSimpleName());
  }

  /** 一次本地执行的物理句柄；结束方法幂等，只有第一次成功结束调用生效。 */
  private final class Handle implements LocalExecutionHandle {

    private final LocalExecutionIdentity identity;
    private final AtomicBoolean settled = new AtomicBoolean();
    private final Object bodyMonitor = new Object();
    private volatile ExecutionExitFact fact;
    private volatile boolean registered;
    private volatile RegistrationLookup registrationLookup = RegistrationLookup.undetermined();
    private volatile Thread body;
    private boolean cancelRequested;

    private Handle(LocalExecutionIdentity identity) {
      this.identity = identity;
    }

    @Override
    public ExecutionExitFact fact() {
      return fact;
    }

    @Override
    public boolean registered() {
      return registered;
    }

    @Override
    public boolean attachBody(Thread body) {
      Objects.requireNonNull(body, "body");
      synchronized (bodyMonitor) {
        // 取消在 attach 之前也必须生效：否则先 requestCancel 再 attach 会丢掉取消并放行 body。
        if (cancelRequested || settled.get() || !registered) {
          return false;
        }
        this.body = body;
        return true;
      }
    }

    @Override
    public void detachBody() {
      synchronized (bodyMonitor) {
        body = null;
      }
    }

    @Override
    public void requestCancel() {
      Thread target;
      synchronized (bodyMonitor) {
        cancelRequested = true;
        target = body;
      }
      if (target != null) {
        target.interrupt();
      }
    }

    @Override
    public void provenExit(ExecutionExitState state) {
      Objects.requireNonNull(state, "state");
      if (state != ExecutionExitState.STUDIO_EXITED && state != ExecutionExitState.NOT_STARTED) {
        throw new IllegalArgumentException(
            "a local execution handle can only prove studio exit or not-started");
      }
      if (state == ExecutionExitState.STUDIO_EXITED && !registered) {
        throw new IllegalStateException(
            "execution "
                + identity.executionId()
                + " was not registered and cannot claim studio exit");
      }
      synchronized (bodyMonitor) {
        if (body != null && body.isAlive()) {
          throw new IllegalStateException(
              "execution "
                  + identity.executionId()
                  + " still has a live attached body; detach it before proving exit");
        }
        body = null;
        if (!settled.compareAndSet(false, true)) {
          return;
        }
      }
      try {
        if (registered || fact != null) {
          confirmer.confirm(fact, state);
        } else if (registrationLookup.status() == RegistrationStatus.ABSENT) {
          journal.delete(identity.executionId());
        } else {
          // 登记结果与清理依据都未知或身份冲突：保留 PREPARED，交给重启恢复或人工处理。
          retainJournalLock(identity.executionId(), null);
        }
      } catch (RuntimeException failure) {
        retainJournalLock(identity.executionId(), failure);
      } finally {
        finish(this);
      }
    }

    @Override
    public void exitNotProven() {
      boolean liveBody;
      boolean settledHere;
      synchronized (bodyMonitor) {
        liveBody = body != null && body.isAlive();
        if (liveBody) {
          settledHere = false;
        } else {
          body = null;
          settledHere = settled.compareAndSet(false, true);
        }
      }
      if (liveBody) {
        // 物理 body 仍存活：保留引用和物理句柄，只保留目录锁，等待 body 自己结束或排空。
        retainJournalLock(identity.executionId(), null);
        return;
      }
      if (!settledHere) {
        return;
      }
      retainJournalLock(identity.executionId(), null);
      finish(this);
    }

    /**
     * 登记回调抛出后设置对账结果；此时 {@link #registered()} 保持 {@code false}，调用方不得进入 body。
     *
     * <p>唯一例外是数据库确实已提交本次登记：保留事实，等待调用方在释放资源后确认 {@code NOT_STARTED}。
     */
    private void registrationUncertain(RegistrationLookup lookup) {
      this.registrationLookup = lookup;
      this.fact = lookup.fact();
    }

    /** 事务已提交但没有登记（stale / Stop / 已失权）：释放资源后以 {@code NOT_STARTED} 结束即可清理 PREPARED。 */
    private void registrationAbsent() {
      this.registrationLookup = RegistrationLookup.absent();
    }

    private void registered(ExecutionExitFact fact) {
      this.fact = fact;
      this.registered = true;
    }
  }

  private enum RegistrationStatus {
    ABSENT,
    PRESENT,
    CONFLICT,
    UNDETERMINED
  }

  /** 登记结果不明时对数据库的权威读取；只有 ABSENT 允许清理 PREPARED。 */
  private record RegistrationLookup(RegistrationStatus status, ExecutionExitFact fact) {

    private static RegistrationLookup absent() {
      return new RegistrationLookup(RegistrationStatus.ABSENT, null);
    }

    private static RegistrationLookup present(ExecutionExitFact fact) {
      return new RegistrationLookup(RegistrationStatus.PRESENT, fact);
    }

    private static RegistrationLookup conflict() {
      return new RegistrationLookup(RegistrationStatus.CONFLICT, null);
    }

    private static RegistrationLookup undetermined() {
      return new RegistrationLookup(RegistrationStatus.UNDETERMINED, null);
    }
  }
}

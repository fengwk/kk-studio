package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Test-only in-memory reference implementation of {@link HarnessStore}，供 Processor / 契约测试复用。
 *
 * <p>本类只是事务核心的宿主：持有唯一已提交 {@link InMemoryState}、全局 monitor、测试时钟、环境目录与事务生命周期。事务实现从 {@link
 * InMemoryHarnessTransaction} 开始，九个聚合原语分别在 {@link InMemorySessionStore}、{@link InMemoryEntryStore}、
 * {@link InMemoryThreadStore}、{@link InMemoryCommandStore}、{@link InMemoryToolStore}、{@link
 * InMemoryWorkStore}、 {@link InMemoryJoinStore}、{@link InMemoryStopReceiptStore} 与 {@link
 * InMemoryExecutionExitStore} 中实现；事务核心只负责 单一复制快照、commit/rollback、事务时钟、锁阶梯与 driver 私有行。
 *
 * <p>{@link #transaction} 由单个全局 monitor 串行化：进入事务时对唯一快照做一次 shallow copy（持久记录均为 immutable records，浅拷贝
 * map 即可隔离），回调成功提交快照，抛出 {@link RuntimeException} 或 {@link Error} 时丢弃快照（完整 rollback，包括
 * nextId）。事务句柄在回调结束后关闭，任何后续调用抛 {@link IllegalStateException}；同一 Store 的重入事务被拒绝。
 *
 * <p>模拟核心 schema 约束：Session / Entry / Thread / ToolInvocation id 唯一；Entry 写入前用完整 {@link
 * fun.fengwk.kkstudio.harness.runtime.history.EntryPath} 校验；Thread head Entry 存在；Command 只能以 QUEUED
 * 插入并保持 {@code (thread, sequence)} 与 {@code (thread, idempotencyKey)} 唯一；ToolInvocation 保持 {@code
 * (assistantEntryId, callIndex)} 唯一且 call/binding 与 Assistant ToolCall 精确一致；Work target 必须存在且保持
 * {@code (targetType, targetId)} 主键。细节见各聚合实现类。
 *
 * <p>通用 driver 快照：扩展 Store 可继承事务核心，经其公开的 driver row / attribute hook 在同一事务快照中挂载不透明行与属性 （hook 只存在于
 * runtime test-jar，不是生产 SPI）。driver row 写入要求所属 Thread 已在本事务锁定并登记 row -&gt; owner Thread；子类行随 owner
 * Thread 的深删除一并移除。核心只保证 copy-on-write / rollback / owner 关联 / 行锁阶梯，不解释行内容，也不校验具体 driver 协议或状态机。
 *
 * <p>锁阶梯：SESSION -&gt; THREAD -&gt; COMMAND -&gt; DRIVER -&gt; TOOL -&gt; WORK；Thread 同层按 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.UuidOrder} 升序，Tool siblings 按 callIndex 升序，Work 同层按
 * (type, id) 升序。实际加锁前的逆序防御见 {@link InMemoryHarnessLocks}。返回对象与 list 均为 immutable records / copies。
 */
public class InMemoryHarnessStore implements HarnessStore {

  private final Object monitor = new Object();
  private InMemoryState committed;
  private boolean inTransaction;
  private volatile RouteReadyPredicate routeReadyPredicate;
  private volatile EnvironmentReadyLeasePredicate environmentReadyLeasePredicate;
  private final Map<EnvironmentId, String> environmentNames = new HashMap<>();
  private volatile Clock clock = HarnessStoreTime.millisecondClock(Clock.systemUTC());

  /** Route-ready 判定谓词接口，供契约测试注入环境就绪状态与 lease 有效性。 */
  @FunctionalInterface
  public interface RouteReadyPredicate {
    boolean isRouteReady(UUID nodeInstanceId, EnvironmentId environmentId, Instant now);
  }

  /**
   * 环境 READY 租约判定谓词：任意节点是否持有该环境的有效 READY 连接租约（权威时间域为 {@code now}）。
   *
   * <p>与 {@link RouteReadyPredicate} 分层：后者判定「本节点 + 本 leaseToken」能否承接路由，本谓词判定环境在读取投影中是否已上线（无有效 READY
   * 租约即为等待环境）。未注入时视为环境没有有效 READY 租约。
   */
  @FunctionalInterface
  public interface EnvironmentReadyLeasePredicate {
    boolean hasReadyLease(EnvironmentId environmentId, Instant now);
  }

  public InMemoryHarnessStore() {
    this(0L, null);
  }

  public InMemoryHarnessStore(RouteReadyPredicate routeReadyPredicate) {
    this(0L, routeReadyPredicate);
  }

  /** 仅用于测试的构造函数，用于播种全局 id 序列；下一个分配的 id 为 {@code initialNextId + 1}。 */
  public InMemoryHarnessStore(long initialNextId) {
    this(initialNextId, null);
  }

  public InMemoryHarnessStore(long initialNextId, RouteReadyPredicate routeReadyPredicate) {
    if (initialNextId < 0) {
      throw new IllegalArgumentException("initialNextId must not be negative");
    }
    this.committed = new InMemoryState();
    this.committed.nextId = initialNextId;
    this.routeReadyPredicate = routeReadyPredicate;
  }

  /** 测试时钟：每个事务首次 {@link HarnessStore.Transaction#now()} 时读取一次，对应生产实现的数据库时钟。 */
  public void setClock(Clock clock) {
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
  }

  /** 把测试时钟固定在给定时刻。 */
  public void setNow(Instant now) {
    setClock(Clock.fixed(now, ZoneOffset.UTC));
  }

  public void setRouteReadyPredicate(RouteReadyPredicate routeReadyPredicate) {
    this.routeReadyPredicate = routeReadyPredicate;
  }

  public void setEnvironmentReadyLeasePredicate(
      EnvironmentReadyLeasePredicate environmentReadyLeasePredicate) {
    this.environmentReadyLeasePredicate = environmentReadyLeasePredicate;
  }

  /** 测试环境目录：名称沿 Work 冻结身份查询，不依赖 Thread 设置。 */
  public void setEnvironmentName(EnvironmentId environmentId, String name) {
    synchronized (monitor) {
      environmentNames.put(environmentId, name);
    }
  }

  private int transactionCount;
  private final ThreadLocal<ArrayList<Runnable>> afterCommitActions = new ThreadLocal<>();

  /** 测试钩子：在每个事务开始前执行，用于确定性地观察事务与并发回调的因果关系。 */
  public volatile Runnable beforeTransaction;

  public int transactionCount() {
    synchronized (monitor) {
      return transactionCount;
    }
  }

  public void resetTransactionCount() {
    synchronized (monitor) {
      transactionCount = 0;
    }
  }

  /** 测试钩子：直接把已存在 Work 行的 lease deadline 改写为给定值（通常已过期），用于验证 stale ownership 路径。 */
  public void forceLeaseUntil(WorkTarget target, Instant leaseUntil) {
    Objects.requireNonNull(target, "target");
    HarnessStoreTime.requireMillisecondPrecision(leaseUntil);
    synchronized (monitor) {
      Work work = committed.works.get(target);
      if (work == null || work.leaseToken() == null) {
        throw new IllegalStateException("work has no lease to rewrite: " + target);
      }
      committed.works.put(
          target,
          new Work(
              work.target(),
              work.availableAt(),
              work.wakeVersion(),
              work.leaseToken(),
              leaseUntil,
              work.requiredEnvironmentId()));
    }
  }

  /** 测试钩子：直接把已存在 Work 行的 availableAt 改写为给定值（通常已 due），用于验证“立即唤醒/到期即可 claim”路径。 */
  public void forceAvailableAt(WorkTarget target, Instant availableAt) {
    Objects.requireNonNull(target, "target");
    HarnessStoreTime.requireMillisecondPrecision(availableAt);
    synchronized (monitor) {
      Work work = committed.works.get(target);
      if (work == null) {
        throw new IllegalStateException("work does not exist to rewrite: " + target);
      }
      committed.works.put(
          target,
          new Work(
              work.target(),
              availableAt,
              work.wakeVersion(),
              work.leaseToken(),
              work.leaseUntil(),
              work.requiredEnvironmentId()));
    }
  }

  @Override
  public <T> T transaction(Function<HarnessStore.Transaction, T> callback) {
    Objects.requireNonNull(callback, "callback");
    Runnable hook = beforeTransaction;
    if (hook != null) {
      hook.run();
    }
    ArrayList<Runnable> pending = new ArrayList<>();
    afterCommitActions.set(pending);
    T result;
    synchronized (monitor) {
      if (inTransaction) {
        afterCommitActions.remove();
        throw new IllegalStateException("nested transactions are not supported");
      }
      inTransaction = true;
      transactionCount++;
      try {
        InMemoryHarnessTransaction tx = createTransaction(InMemoryState.copyOf(committed));
        try {
          result = callback.apply(tx);
          tx.close();
          committed = tx.state();
        } catch (RuntimeException | Error error) {
          tx.close();
          afterCommitActions.remove();
          throw error;
        }
      } finally {
        inTransaction = false;
      }
    }
    afterCommitActions.remove();
    for (Runnable action : pending) {
      action.run();
    }
    return result;
  }

  @Override
  public void afterCommit(Runnable action) {
    Objects.requireNonNull(action, "action");
    ArrayList<Runnable> pending = afterCommitActions.get();
    if (pending == null) {
      throw new IllegalStateException("afterCommit must be registered inside a store transaction");
    }
    pending.add(action);
  }

  /** 内存实现没有环境事务；手动压缩的事务外入口在这里始终放行。 */
  @Override
  public void assertNoAmbientTransaction() {}

  /** 创建当前事务句柄的工厂；测试子类可返回扩展事务，与核心 Store 共用同一快照与锁语义。 */
  protected InMemoryHarnessTransaction createTransaction(InMemoryState state) {
    return new InMemoryHarnessTransaction(this, state);
  }

  Instant currentInstant() {
    return clock.instant();
  }

  boolean isRouteReady(UUID nodeInstanceId, EnvironmentId environmentId, Instant now) {
    RouteReadyPredicate predicate = routeReadyPredicate;
    if (predicate == null) {
      return true;
    }
    return nodeInstanceId != null && predicate.isRouteReady(nodeInstanceId, environmentId, now);
  }

  boolean hasReadyEnvironmentLease(EnvironmentId environmentId, Instant now) {
    EnvironmentReadyLeasePredicate predicate = environmentReadyLeasePredicate;
    return predicate != null && predicate.hasReadyLease(environmentId, now);
  }

  String environmentName(EnvironmentId environmentId) {
    return environmentNames.get(environmentId);
  }
}

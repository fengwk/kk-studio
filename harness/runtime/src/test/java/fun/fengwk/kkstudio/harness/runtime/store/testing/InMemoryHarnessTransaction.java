package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.store.CommandStore;
import fun.fengwk.kkstudio.harness.runtime.store.EntryStore;
import fun.fengwk.kkstudio.harness.runtime.store.ExecutionExitStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.JoinStore;
import fun.fengwk.kkstudio.harness.runtime.store.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.store.StopReceiptStore;
import fun.fengwk.kkstudio.harness.runtime.store.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.store.ToolStore;
import fun.fengwk.kkstudio.harness.runtime.store.WorkStore;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 内存参考实现的事务核心：单一复制快照上的 typed 句柄。
 *
 * <p>本类只负责事务生命周期（owner thread / closed 检查、commit 后的快照替换由 Store 完成）、事务时钟与 id 分配、锁阶梯记账、 driver
 * 私有行与属性的受控访问，以及跨聚合共享的只读辅助。九个聚合原语分别在 {@link InMemorySessionStore}、{@link InMemoryEntryStore}、{@link
 * InMemoryThreadStore}、{@link InMemoryCommandStore}、{@link InMemoryToolStore}、{@link
 * InMemoryWorkStore}、{@link InMemoryJoinStore}、{@link InMemoryStopReceiptStore} 与 {@link
 * InMemoryExecutionExitStore} 中实现，它们共享本事务的同一 {@link InMemoryState}、同一 {@link InMemoryHarnessLocks}
 * 与同一 {@link #now()}。
 *
 * <p>扩展事务（例如 Builtin 私有模型视图）继承本类，经本类公开的 driver row / attribute hook 在同一快照内读写不透明行；核心不解释
 * 行内容，也不为扩展维护第二份状态。这些 hook 只存在于 runtime test-jar，不是生产 SPI：整包只出现在 test sources，调用方只有 {@code
 * harness/agent-builtin} 的测试基座。
 */
public class InMemoryHarnessTransaction implements HarnessStore.Transaction {

  private final InMemoryHarnessStore store;
  private final InMemoryState state;
  private final InMemoryHarnessLocks locks;
  private final Thread owner = Thread.currentThread();
  private boolean closed;
  private Instant now;

  private final SessionStore sessions;
  private final EntryStore entries;
  private final ThreadStore threads;
  private final CommandStore commands;
  private final ToolStore tools;
  private final WorkStore work;
  private final JoinStore joins;
  private final StopReceiptStore stops;
  private final ExecutionExitStore exits;

  public InMemoryHarnessTransaction(InMemoryHarnessStore store, InMemoryState state) {
    this.store = Objects.requireNonNull(store, "store");
    this.state = Objects.requireNonNull(state, "state");
    this.locks = new InMemoryHarnessLocks(threadId -> state.threads.get(threadId));
    this.sessions = new InMemorySessionStore(this);
    this.entries = new InMemoryEntryStore(this);
    this.threads = new InMemoryThreadStore(this);
    this.commands = new InMemoryCommandStore(this);
    this.tools = new InMemoryToolStore(this);
    this.work = new InMemoryWorkStore(this);
    this.joins = new InMemoryJoinStore(this);
    this.stops = new InMemoryStopReceiptStore(this);
    this.exits = new InMemoryExecutionExitStore(this);
  }

  @Override
  public Instant now() {
    checkOpen();
    if (now == null) {
      now = store.currentInstant();
    }
    return now;
  }

  @Override
  public UUID nextId() {
    checkOpen();
    state.nextId = Math.addExact(state.nextId, 1L);
    return new UUID(0L, state.nextId);
  }

  @Override
  public SessionStore sessions() {
    checkOpen();
    return sessions;
  }

  @Override
  public EntryStore entries() {
    checkOpen();
    return entries;
  }

  @Override
  public ThreadStore threads() {
    checkOpen();
    return threads;
  }

  @Override
  public CommandStore commands() {
    checkOpen();
    return commands;
  }

  @Override
  public ToolStore tools() {
    checkOpen();
    return tools;
  }

  @Override
  public WorkStore work() {
    checkOpen();
    return work;
  }

  @Override
  public JoinStore joins() {
    checkOpen();
    return joins;
  }

  @Override
  public StopReceiptStore stops() {
    checkOpen();
    return stops;
  }

  @Override
  public ExecutionExitStore exits() {
    checkOpen();
    return exits;
  }

  /** 事务边界结束后关闭句柄；之后的任何调用都被拒绝。 */
  void close() {
    closed = true;
  }

  /** 严格校验单线程约束与句柄活跃状态：只有创建它的线程可以在回调内使用。 */
  void checkOpen() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("transaction handle may only be used by its owner thread");
    }
    if (closed) {
      throw new IllegalStateException("transaction is closed");
    }
  }

  InMemoryState state() {
    return state;
  }

  InMemoryHarnessLocks locks() {
    return locks;
  }

  boolean isInLockedTree(UUID threadId) {
    return locks.isInLockedTree(threadId);
  }

  /** 跨聚合引用校验：Entry 必须存在，否则与聚合内部保持同一 {@link IllegalArgumentException} 文本。 */
  Entry requireExistingEntry(UUID entryId) {
    Entry entry = state.entries.get(entryId);
    if (entry == null) {
      throw new IllegalArgumentException("entry " + entryId + " does not exist");
    }
    return entry;
  }

  static void requireAbsent(Map<UUID, ?> rows, UUID id, String kind) {
    if (rows.containsKey(id)) {
      throw new IllegalArgumentException("duplicate " + kind + " id " + id);
    }
  }

  boolean isRouteReady(UUID nodeInstanceId, EnvironmentId environmentId, Instant now) {
    return store.isRouteReady(nodeInstanceId, environmentId, now);
  }

  boolean hasReadyEnvironmentLease(EnvironmentId environmentId, Instant now) {
    return store.hasReadyEnvironmentLease(environmentId, now);
  }

  String environmentName(EnvironmentId environmentId) {
    return store.environmentName(environmentId);
  }

  // ---------- 测试基座扩展 hook：runtime test-jar 内部，Driver 私有视图专用，不是生产 SPI ----------

  /** 按 id 读取不透明 driver 行；不存在返回 {@link Optional#empty()}。 */
  public Optional<Object> findDriverRow(UUID id) {
    checkOpen();
    return Optional.ofNullable(state.driverRows.get(id));
  }

  /** 按 id 读取并锁定 driver 行；行存在才产生 driver 级行锁，不存在不改变锁阶梯。 */
  public Optional<Object> lockDriverRow(UUID id) {
    checkOpen();
    InMemoryHarnessLocks.LockKey lockKey = InMemoryHarnessLocks.LockKey.driver(id);
    locks.requireCanLock(lockKey);
    Object row = state.driverRows.get(id);
    if (row != null) {
      locks.lock(lockKey);
    }
    return Optional.ofNullable(row);
  }

  /** 插入 driver 行并登记 owner Thread；owner 必须存在且已在本事务锁定。 */
  public void insertDriverRow(UUID id, UUID ownerThreadId, Object value) {
    checkOpen();
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerThreadId, "ownerThreadId");
    Objects.requireNonNull(value, "value");
    requireAbsent(state.driverRows, id, "driver row");
    if (!state.threads.containsKey(ownerThreadId)) {
      throw new IllegalArgumentException("thread " + ownerThreadId + " does not exist");
    }
    locks.requireLocked(InMemoryHarnessLocks.LockKey.thread(ownerThreadId));
    InMemoryHarnessLocks.LockKey lockKey = InMemoryHarnessLocks.LockKey.driver(id);
    locks.requireCanLock(lockKey);
    state.driverRows.put(id, value);
    state.driverRowOwners.put(id, ownerThreadId);
    locks.lock(lockKey);
  }

  /** 改写已存在且已锁定的 driver 行。 */
  public void updateDriverRow(UUID id, Object value) {
    checkOpen();
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(value, "value");
    locks.requireLocked(InMemoryHarnessLocks.LockKey.driver(id));
    if (!state.driverRows.containsKey(id)) {
      throw new IllegalArgumentException("driver row " + id + " does not exist");
    }
    state.driverRows.put(id, value);
  }

  /** 删除已存在且已锁定的 driver 行，同时移除 owner 关联。 */
  public boolean deleteDriverRow(UUID id) {
    checkOpen();
    Objects.requireNonNull(id, "id");
    if (!state.driverRows.containsKey(id)) {
      throw new IllegalArgumentException("driver row " + id + " does not exist");
    }
    locks.requireLocked(InMemoryHarnessLocks.LockKey.driver(id));
    state.driverRowOwners.remove(id);
    return state.driverRows.remove(id) != null;
  }

  /** 当前快照内全部 driver 行的不可变拷贝。 */
  public Map<UUID, Object> driverRows() {
    checkOpen();
    return Map.copyOf(state.driverRows);
  }

  /** 读取 driver 属性（例如 group 成员冷却时间）；属性属于同一快照并随 rollback 丢弃。 */
  public Optional<Object> driverAttribute(Object key) {
    checkOpen();
    return Optional.ofNullable(state.driverAttributes.get(key));
  }

  /** 写入 driver 属性；属性不参与锁阶梯。 */
  public void putDriverAttribute(Object key, Object value) {
    checkOpen();
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    state.driverAttributes.put(key, value);
  }

  /** 断言 owner Thread 已在本事务锁定；不主动加锁，错误分类与核心一致。 */
  public void requireThreadLocked(UUID threadId) {
    checkOpen();
    locks.requireLocked(InMemoryHarnessLocks.LockKey.thread(threadId));
  }

  /** 断言 driver 行已在本事务锁定；不主动加锁。 */
  public void requireDriverRowLocked(UUID id) {
    checkOpen();
    locks.requireLocked(InMemoryHarnessLocks.LockKey.driver(id));
  }
}

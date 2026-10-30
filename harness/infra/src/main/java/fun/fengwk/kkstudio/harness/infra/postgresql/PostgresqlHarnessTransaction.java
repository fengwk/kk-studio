package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.CommandStore;
import fun.fengwk.kkstudio.harness.runtime.store.EntryStore;
import fun.fengwk.kkstudio.harness.runtime.store.ExecutionExitStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.JoinStore;
import fun.fengwk.kkstudio.harness.runtime.store.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.store.StopReceiptStore;
import fun.fengwk.kkstudio.harness.runtime.store.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.store.ToolStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.WorkStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.work.DurableWorkQueue;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link HarnessStore.Transaction} typed primitives 的 PostgreSQL 实现。
 *
 * <p>本类仅实现底层持久化原语的精确映射，绝不包含 Thread 下一步决策、重试策略、权限判定或 Tool 聚合等用例逻辑。
 *
 * <p><b>Transaction 线程约束：</b> 事务句柄严格由创建它的单线程独占（{@link #owner}），禁止跨线程传递或并发调用；每次操作前均通过 {@link
 * #checkOpen()} 校验 调用线程身份与活跃状态，已关闭或跨线程调用直接抛出 {@link IllegalStateException}。
 *
 * <p><b>首个数据库故障 poisoning 防护：</b> 事务内任何底层 JDBC 操作捕获的 {@link DataAccessException} 或数据完整性违规均通过 {@link
 * #remember(RuntimeException)} 记录到 {@link #databaseFailure} 字段中，并原样抛出。若外层代码未中断执行，在事务提交前 {@link
 * #rethrowDatabaseFailure()} 将强制重新抛出该首个故障，阻断脏状态提交。
 *
 * <p><b>严格锁序防御（Lock Ranking &amp; Ordering）：</b> 所有跨实体并发加锁严格遵守单向递增的层级排序：
 *
 * <pre>{@code
 * SESSION -> THREAD -> COMMAND -> DRIVER -> TOOL -> WORK
 * }</pre>
 *
 * 在会批量获取同阶锁的路径中，按稳定顺序发起数据库加锁：
 *
 * <ul>
 *   <li>Thread 必须按 {@link UuidOrder#COMPARATOR} 升序；
 *   <li>Command 按 {@code (threadId, sequence)} 升序；
 *   <li>Tool 必须按 {@code (assistantEntryId, callIndex, id)} 严格递增升序；
 *   <li>Work 必须按 {@code (targetType.ordinal(), targetId)} 严格递增升序。
 * </ul>
 *
 * 已显式跟踪的层级逆序或同阶逆序会在获取新的受控锁前抛出 {@link IllegalStateException}；SQL 查询本身通过稳定 {@code ORDER BY}
 * 固定批量锁顺序。该机制收敛本适配器内的已知锁逆序路径，但不替代 PostgreSQL 对其它数据库死锁的检测与回滚。
 *
 * <p><b>事务内 EntryPath 局部缓存（Transaction-local Cache）与 Invalidation：</b> 维护事务局部的 {@link
 * #entryPathCache}（{@code Map<UUID, EntryPath>}），在单事务内优化 Entry 路径读取与构建开销：
 *
 * <ul>
 *   <li>首次读取未命中缓存时，执行单次不可变回溯递归 CTE（{@link #LOAD_ENTRY_PATH}）并存入正向缓存；
 *   <li>连续调用 {@link #insertEntry(Entry)} 时，加载或复用 parent path 后在内存中派生新路径并写入缓存，实现子节点后续读取 0 次 CTE；
 *   <li>窄查询（{@link #loadContributorCustomEntriesOnPath}）在 cache miss 时执行独立窄 CTE，绝不将不完整投影写回完整路径缓存；
 *   <li>执行 {@link #deleteEntries} 或 {@link #deleteSession} 时，按 session 整体驱逐相关缓存，避免脏读；
 *   <li>事务 {@link #close()} 时立即清空全部缓存。
 * </ul>
 *
 * <p><b>Environment route 路由围栏与 Work claim：</b> 调度器通过 {@link #claimNextWork} 开启独立 short transaction
 * 选取待处理任务：
 *
 * <ul>
 *   <li>{@code durable_work.required_environment_id} 仅用于 TOOL Work；为空时无亲和性要求；
 *   <li>非空时，只有在 {@code environment_connection} 中存在匹配的 {@code environment_id}、所有者为当前 Dispatcher 的
 *       {@code nodeInstanceId}、连接状态为 {@code READY} 且租约有效的记录时，当前节点才可 claim；
 *   <li>路由不确定或底层查询失败时严格 fail closed；
 *   <li>层次区分：Environment route 路由围栏与 PostgreSQL 底层 {@code FOR UPDATE SKIP LOCKED} 行级跳锁以及应用层 {@code
 *       lease_token} / {@code lease_until} 所有权围栏分属不同层次，互不替代；避免把 {@code SKIP LOCKED} 误称为分布式锁。
 *   <li>{@link #requestWork} 对已冻结的 affinity 实施严格冲突拒绝，已存在的 Work 不允许被修改为冲突的不同非空 affinity。
 * </ul>
 *
 * <p><b>Final Work Fence：</b> Processor 的持久化变更先于 Work 终态变更。{@link #completeWork} 与 {@link
 * #rescheduleWork} 在执行终态更新前先由 {@link #lockWork} 获取 Work 行级排他锁，再由 Work 领域跃迁校验 {@code lease_token} 与
 * {@code claimedWakeVersion}。若租约丢失则抛出异常导致整个事务回滚；若执行期间有新 wake 到达使版本递增，则清除租约保留行，防止并发工作被静默覆盖。
 */
final class PostgresqlHarnessTransaction implements HarnessStore.Transaction {

  static final Comparator<ThreadCommand> COMMAND_LOCK_ORDER =
      Comparator.comparing(ThreadCommand::threadId, UuidOrder.COMPARATOR)
          .thenComparingLong(ThreadCommand::sequence);

  static final Comparator<ToolInvocation> TOOL_LOCK_ORDER =
      Comparator.comparing(ToolInvocation::assistantEntryId, UuidOrder.COMPARATOR)
          .thenComparingInt(ToolInvocation::callIndex)
          .thenComparing(ToolInvocation::id, UuidOrder.COMPARATOR);

  static final Comparator<WorkTarget> WORK_LOCK_ORDER =
      Comparator.comparingInt((WorkTarget target) -> target.type().ordinal())
          .thenComparing(WorkTarget::id, UuidOrder.COMPARATOR);

  /** 全局 Join 准入锁的两个 int 键（"{@code kkST}"/"{@code join}"）：与 tree 使用的单 bigint 键空间隔离。 */
  static final int JOIN_ADMISSION_LOCK_NAMESPACE = 0x6B6B5354;

  static final int JOIN_ADMISSION_LOCK_KEY = 0x6A6F696E;

  /** 同一 Session 内执行根（parent 为 null）名称唯一的部分唯一索引名：唯一命中它才映射为名称冲突。 */
  private static final String ROOT_THREAD_NAME_INDEX = "uk_harness_thread_root_name";

  /** 底层 Spring JdbcTemplate，绑定当前事务的数据库连接。 */
  private final JdbcTemplate jdbc;

  /** 外部注入的 UUID 生成器。 */
  private final Supplier<UUID> idGenerator;

  /** 创建本事务的 Store 身份 token；typed view 必须与当前 Store 精确同源。 */
  private final Object storeToken;

  /** 事务内 EntryPath 正向局部缓存，避免重复递归 CTE 查询。 */
  private final Map<UUID, EntryPath> entryPathCache = new HashMap<>();

  /** 创建本事务 handle 的宿主线程，用于严格守护单线程约束。 */
  private final Thread owner = Thread.currentThread();

  /** 记录事务生命周期内发生的首个数据库异常（poisoning），用于在完成前强制重抛。 */
  private RuntimeException databaseFailure;

  /** 事务 handle 关闭状态标识。 */
  private boolean closed;

  /** 本事务首次读取的数据库时间；之后的 {@link #now()} 返回同一值。 */
  private Instant transactionNow;

  private final PostgresqlHarnessLocks locks;

  PostgresqlHarnessTransaction(JdbcTemplate jdbc, Supplier<UUID> idGenerator, Object storeToken) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.storeToken = Objects.requireNonNull(storeToken, "storeToken");
    this.locks = new PostgresqlHarnessLocks(this);
  }

  /** 校验 typed view 来自当前 Store、仍在本回调生命周期内且由 owner thread 使用。 */
  void requireView(Object expectedStoreToken) {
    checkOpen();
    if (storeToken != expectedStoreToken) {
      throw new IllegalArgumentException(
          "store view requires a transaction created by this PostgresqlHarnessStore");
    }
  }

  /** 关闭事务 handle 并清空事务内 EntryPath 局部缓存。 */
  void close() {
    closed = true;
    entryPathCache.clear();
  }

  Map<UUID, EntryPath> entryPathCache() {
    return entryPathCache;
  }

  boolean isLocked(LockKey key) {
    return locks.isLocked(key);
  }

  boolean isLockedTree(UUID rootThreadId) {
    return locks.isLockedTree(rootThreadId);
  }

  boolean hasLockedTrees() {
    return locks.hasLockedTrees();
  }

  void recordJoinedChildThreadLock(UUID threadId) {
    locks.recordJoinedChildThreadLock(threadId);
  }

  void lockTree(UUID rootThreadId) {
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    locks.lockTree(rootThreadId);
  }

  void lockJoinAdmission() {
    locks.lockJoinAdmission();
  }

  /** 若底层操作曾触发数据库异常，在此强制重新抛出该首个故障（poisoning），彻底阻断被破坏的事务继续提交。 */
  void rethrowDatabaseFailure() {
    if (databaseFailure != null) {
      throw databaseFailure;
    }
  }

  public Instant now() {
    checkOpen();
    if (transactionNow == null) {
      transactionNow = databaseNow();
    }
    return transactionNow;
  }

  public UUID nextId() {
    checkOpen();
    UUID id = idGenerator.get();
    if (id == null) {
      throw new IllegalStateException("id generator returned null");
    }
    return id;
  }

  Entry requireExistingEntry(UUID entryId) {
    return entries
        .find(entryId)
        .orElseThrow(() -> new IllegalArgumentException("entry " + entryId + " does not exist"));
  }

  TurnStartPayload requireTurnStartPayload(UUID entryId) {
    Entry turnStart = requireExistingEntry(entryId);
    if (!(turnStart.payload() instanceof TurnStartPayload payload)) {
      throw new IllegalArgumentException("turnStartEntryId must reference a TURN_START entry");
    }
    return payload;
  }

  boolean queryExists(String sql, UUID id) {
    return Boolean.TRUE.equals(queryForObject(sql, Boolean.class, id));
  }

  /** 读取数据库权威时间（毫秒精度），作为 Work lease 有效性与 deadline 的唯一时间域。 */
  Instant databaseNow() {
    return queryOne(
            "select date_trunc('milliseconds', statement_timestamp()) as now",
            (resultSet, rowNumber) -> resultSet.getTimestamp("now").toInstant())
        .orElseThrow(() -> new IllegalStateException("statement_timestamp returned no row"));
  }

  void notifyWorkAvailable(WorkTargetType type) {
    notifyChannel(DurableWorkQueue.CHANNEL, type.name());
  }

  /** Thread version 失效：payload 为 {@code {threadId}:{version}}，与既有协议一致。 */
  void notifyThreadVersion(UUID threadId, long version) {
    notifyChannel(PostgresqlHarnessNotificationChannel.THREAD_VERSION, threadId + ":" + version);
  }

  /** 执行树失效：payload 为真实执行根 id；绝不伪造根 Thread 的 version。 */
  void notifyExecutionTree(UUID rootThreadId) {
    notifyChannel(PostgresqlHarnessNotificationChannel.THREAD_TREE, rootThreadId.toString());
  }

  /**
   * 待处理交互失效：payload 为真实执行根 id；来源 Thread 已不可解析时为空 payload，由 listener 全量 resync； 数据库故障与祖先链异常不在此吞掉。
   */
  void notifyToolInteraction(UUID rootThreadId) {
    notifyChannel(
        PostgresqlHarnessNotificationChannel.TOOL_INTERACTION,
        rootThreadId == null ? "" : rootThreadId.toString());
  }

  /**
   * 在同一自管理事务连接上执行内建 {@code pg_notify}；PostgreSQL 仅在提交时投递，回滚不投递。
   *
   * <p>底层故障经 {@link #queryOne} 记录为事务首个故障并原样抛出（poisoning），绝不吞掉发布失败后继续提交。
   */
  void notifyChannel(String channel, String payload) {
    boolean sent =
        queryOne(
                "select pg_notify(?, ?) as ignored, true as sent",
                (resultSet, rowNumber) -> resultSet.getBoolean("sent"),
                channel,
                payload)
            .orElseThrow(() -> new IllegalStateException("pg_notify returned no row"));
    if (!sent) {
      throw new IllegalStateException("pg_notify did not confirm execution");
    }
  }

  /** Thread 不可变的执行根；Thread 必须存在。 */
  UUID executionRootOfThread(UUID threadId) {
    return queryForObject(
        "select root_thread_id from harness_thread where id = ?", UUID.class, threadId);
  }

  /**
   * 沿 {@code harness_tool_invocation -> harness_model_invocation -> harness_thread} 定位来源
   * Thread，再解析真实执行根。
   *
   * <p>返回 {@code null} 仅表示来源 Thread 已不可解析（合法清理路径上来源行已删除），此时调用方以空 payload 触发 listener 全量 resync；
   * 数据库故障一律向上抛出，绝不吞成空 payload。
   */
  UUID executionRootOfToolInvocation(UUID toolInvocationId) {
    Optional<UUID> sourceThreadId =
        queryOne(
            """
            select i.thread_id
            from harness_tool_invocation i
            where i.id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getObject("thread_id", UUID.class),
            toolInvocationId);
    return sourceThreadId.map(this::executionRootOfThread).orElse(null);
  }

  /** 仅等待审批/等待输入两种状态属于人工交互等待态。 */
  static boolean isWaitingInteraction(ToolInvocationStatus status) {
    return status == ToolInvocationStatus.WAITING_APPROVAL
        || status == ToolInvocationStatus.WAITING_INPUT;
  }

  /**
   * 环境等待待处理失效：仅对冻结了 {@code required_environment_id} 的 TOOL Work 发布，payload 沿用 interaction channel
   * 的真实执行根协议。使用 Work 行冻结的环境事实，而非当前 composer/settings；只提示待处理查询刷新，不创建审批事实。
   */
  void notifyEnvironmentWorkChanged(Work work) {
    if (work.requiredEnvironmentId() != null) {
      notifyToolInteraction(executionRootOfToolInvocation(work.target().id()));
    }
  }

  <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... arguments) {
    List<T> rows;
    try {
      rows = jdbc.query(sql, mapper, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
    if (rows.size() > 1) {
      throw new IllegalStateException("query expected at most one row but returned " + rows.size());
    }
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... arguments) {
    try {
      return List.copyOf(jdbc.query(sql, mapper, arguments));
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
    try {
      return jdbc.queryForObject(sql, requiredType, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  int updateJdbc(String sql, Object... arguments) {
    try {
      return jdbc.update(sql, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  /** 构造由 count 个 {@code ?} 组成的 JDBC 参数占位符列表，供可变长 IN 子句使用。 */
  static String placeholders(int count) {
    StringBuilder sql = new StringBuilder(count * 3);
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append('?');
    }
    return sql.toString();
  }

  <T> Optional<T> writeOne(String sql, RowMapper<T> mapper, Object... arguments) {
    return queryOne(sql, mapper, arguments);
  }

  /** 记录事务内发生的首个数据库异常（poisoning），以防止脏状态下的继续执行或掩盖初始错误。 */
  <T extends RuntimeException> T remember(T failure) {
    if (databaseFailure == null) {
      databaseFailure = failure;
    }
    return failure;
  }

  static IllegalArgumentException integrityViolation(DataIntegrityViolationException error) {
    return new IllegalArgumentException("PostgreSQL integrity constraint violation", error);
  }

  /**
   * 仅当完整性冲突确由 root-name 部分唯一索引（{@link #ROOT_THREAD_NAME_INDEX}）触发时，才映射为类型化 {@link
   * HarnessRuntimeConflictException}（{@code THREAD_NAME_CONFLICT}）；其它完整性错误原样返回，保持既有 {@link
   * IllegalArgumentException} 语义，不吞掉、不误分类。事务已由 {@link #remember(RuntimeException)} 标记 poisoned，
   * 因此该冲突不会提交任何脏状态。
   */
  RuntimeException translateThreadNameConflict(ThreadState thread, RuntimeException error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psqlException) {
        ServerErrorMessage serverMessage = psqlException.getServerErrorMessage();
        if (serverMessage != null && ROOT_THREAD_NAME_INDEX.equals(serverMessage.getConstraint())) {
          return new HarnessRuntimeConflictException(
              HarnessRuntimeConflictException.Reason.THREAD_NAME_CONFLICT,
              "thread name "
                  + thread.name()
                  + " is already used by an execution root in session "
                  + thread.sessionId(),
              error);
        }
        return error;
      }
    }
    return error;
  }

  /** 严格校验单线程约束与事务活跃状态：只有创建该 handle 的线程才可调用，且已关闭后拒绝执行。 */
  void checkOpen() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("transaction handle may only be used by its owner thread");
    }
    if (closed) {
      throw new IllegalStateException("transaction is closed");
    }
  }

  void requireCanLockRank(LockRank rank, UUID commandThreadId) {
    locks.requireCanLockRank(rank, commandThreadId);
  }

  void requireCanLockRank(LockRank rank) {
    locks.requireCanLockRank(rank);
  }

  void requireCanLockThread(UUID threadId) {
    locks.requireCanLockThread(threadId);
  }

  void recordThreadLock(UUID threadId) {
    locks.recordThreadLock(threadId);
  }

  void requireCanLock(LockKey key) {
    locks.requireCanLock(key);
  }

  void lockResource(LockKey key) {
    locks.lockResource(key);
  }

  void requireCanLockTools(List<ToolInvocation> invocations) {
    locks.requireCanLockTools(invocations);
  }

  void lockTool(ToolInvocation invocation) {
    locks.lockTool(invocation);
  }

  void requireCanLockWork(WorkTarget target) {
    locks.requireCanLockWork(target);
  }

  boolean isInLockedTree(UUID threadId) {
    return locks.isInLockedTree(threadId);
  }

  void requireCanClaimWork() {
    locks.requireCanClaimWork();
  }

  void recordWorkLock(WorkTarget target) {
    locks.recordWorkLock(target);
  }

  void requireLocked(LockKey key) {
    locks.requireLocked(key);
  }

  static void requireSingleUpdate(int updated, String kind, Object id) {
    if (updated != 1) {
      throw new IllegalArgumentException(kind + " " + id + " does not exist");
    }
  }

  enum LockRank {
    SESSION,
    THREAD,
    COMMAND,
    DRIVER,
    TOOL,
    WORK
  }

  /** 行锁 key；{@code ownerThreadId} 记录 COMMAND 行所属 Thread，用于同树例外判定。 */
  record LockKey(LockRank rank, String key, UUID ownerThreadId) {
    static LockKey session(UUID id) {
      return new LockKey(LockRank.SESSION, "session:" + id, null);
    }

    static LockKey thread(UUID id) {
      return new LockKey(LockRank.THREAD, "thread:" + id, null);
    }

    static LockKey command(UUID threadId, long sequence) {
      return new LockKey(LockRank.COMMAND, "command:" + threadId + ":" + sequence, threadId);
    }

    static LockKey driver(UUID id) {
      return new LockKey(LockRank.DRIVER, "driver:" + id, null);
    }

    static LockKey tool(UUID id) {
      return new LockKey(LockRank.TOOL, "tool:" + id, null);
    }

    static LockKey work(WorkTarget target) {
      return new LockKey(LockRank.WORK, "work:" + target.type() + ":" + target.id(), null);
    }
  }

  private final PostgresqlSessionStore sessions = new PostgresqlSessionStore(this);

  @Override
  public SessionStore sessions() {
    checkOpen();
    return sessions;
  }

  private final PostgresqlEntryStore entries = new PostgresqlEntryStore(this);

  @Override
  public EntryStore entries() {
    checkOpen();
    return entries;
  }

  private final PostgresqlThreadStore threads = new PostgresqlThreadStore(this);

  @Override
  public ThreadStore threads() {
    checkOpen();
    return threads;
  }

  private final PostgresqlCommandStore commands = new PostgresqlCommandStore(this);

  @Override
  public CommandStore commands() {
    checkOpen();
    return commands;
  }

  private final PostgresqlToolStore tools = new PostgresqlToolStore(this);

  @Override
  public ToolStore tools() {
    checkOpen();
    return tools;
  }

  private final PostgresqlWorkStore work = new PostgresqlWorkStore(this);

  @Override
  public WorkStore work() {
    checkOpen();
    return work;
  }

  private final PostgresqlJoinStore joins = new PostgresqlJoinStore(this);

  @Override
  public JoinStore joins() {
    checkOpen();
    return joins;
  }

  private final PostgresqlStopReceiptStore stops = new PostgresqlStopReceiptStore(this);

  @Override
  public StopReceiptStore stops() {
    checkOpen();
    return stops;
  }

  private final PostgresqlExecutionExitStore exits = new PostgresqlExecutionExitStore(this);

  @Override
  public ExecutionExitStore exits() {
    checkOpen();
    return exits;
  }

  BuiltinStore.Transaction builtin() {
    checkOpen();
    return builtin;
  }

  private final PostgresqlModelTransaction builtin = new PostgresqlModelTransaction(this);
}

package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 七表 {@link HarnessStore.Transaction} typed primitives 的 PostgreSQL 实现。
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
 * SESSION -> THREAD -> COMMAND -> MODEL -> TOOL -> WORK
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
 *   <li>{@code harness_work.required_environment_id} 仅用于 TOOL Work；为空时无亲和性要求；
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

  /**
   * Work claim-only 短事务候选选取与租约写入 SQL。
   *
   * <p>Candidate CTE 执行条件过滤与环境路由围栏判定：
   *
   * <ul>
   *   <li>目标类型匹配、已到期（{@code available_at <= statement_timestamp()}）且无未过期租约；
   *   <li>Environment route 亲和性：当 {@code required_environment_id} 为空时无亲和性；非空时通过 EXISTS 关联 {@code
   *       environment_connection}，要求连接所有者为当前传入的 {@code nodeInstanceId}、状态为 {@code READY} 且租约有效；
   *   <li>使用 {@code FOR UPDATE SKIP LOCKED} 悲观跳过正被其他事务锁定或并发处理的行，选出单条候选；
   *   <li>外层 UPDATE 原子写入新的 {@code lease_token} 与 {@code lease_until} 并返回完整 Work 行。
   * </ul>
   */
  private static final String CLAIM_NEXT_WORK =
      """
      with candidate as (
          select target_type, target_id
          from harness_work
          where target_type = ?
            and available_at <= statement_timestamp()
            and (lease_until is null or lease_until <= statement_timestamp())
            and (
                required_environment_id is null
                or exists (
                    select 1
                    from environment_connection ec
                    where ec.environment_id = harness_work.required_environment_id
                      and ec.owner_node_id = ?
                      and ec.status = 'READY'
                      and ec.lease_until > statement_timestamp()
                )
            )
          order by available_at, target_id
          for update skip locked
          limit 1
      )
      update harness_work work
      set lease_token = ?, lease_until = ?
      from candidate
      where work.target_type = candidate.target_type
        and work.target_id = candidate.target_id
      returning work.*
      """;

  /**
   * Work 登记与唤醒 upsert SQL。
   *
   * <p>插入新工作或更新既有工作：
   *
   * <ul>
   *   <li>若目标不存在则插入初始行（初始 wake_version = 1，lease 为空）；
   *   <li>若已存在则更新 {@code available_at = least(current, requested)} 且递增 {@code wake_version}；
   *   <li>保留既有 {@code lease_token} 与 {@code lease_until} 不变；
   *   <li>冲突拒绝：WHERE 子句要求传入的 {@code required_environment_id} 必须与既有值一致或传入为 null； 若传入了与已冻结 affinity
   *       冲突的值，则更新 0 行并导致上层抛错拒绝。
   * </ul>
   */
  private static final String REQUEST_WORK =
      """
      insert into harness_work (
          target_type, target_id, available_at, wake_version, lease_token, lease_until, required_environment_id
      ) values (?, ?, ?, 1, null, null, ?)
      on conflict (target_type, target_id) do update
      set available_at = least(harness_work.available_at, excluded.available_at),
          wake_version = harness_work.wake_version + 1
      where (harness_work.required_environment_id is not distinct from excluded.required_environment_id
             or excluded.required_environment_id is null)
      returning *
      """;

  /**
   * 单次不可变 EntryPath 递归读取 CTE。
   *
   * <p>以指定 head 为起点向上回溯直至根节点：
   *
   * <ul>
   *   <li>通过 {@code CYCLE id SET is_cycle USING path} 阻断环路破坏并标明环路哨兵；
   *   <li>按 {@code depth desc} 输出自 ROOT 到 head 的单调递增有序 Entry 序列。
   * </ul>
   */
  private static final String LOAD_ENTRY_PATH =
      """
      with recursive entry_path as (
          select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state, 1 as depth
          from harness_entry
          where id = ?
          union all
          select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, e.provider_replay_state, ep.depth + 1
          from harness_entry e
          join entry_path ep on e.id = ep.parent_entry_id
          where ep.parent_entry_id is not null
      ) cycle id set is_cycle using path
      select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state
      from entry_path
      order by depth desc
      """;

  /**
   * 窄查询递归 CTE：仅提取路径上的 ROOT、head、环路哨兵及指定 contributorId 的 CUSTOM Entry。
   *
   * <p>用于局部 Contributor 状态提取；递归仍遍历祖先链，但只返回必要节点，减少结果传输与 Java 侧完整 EntryPath 物化，其结果绝不回填完整路径缓存。
   */
  private static final String LOAD_CONTRIBUTOR_CUSTOM_ENTRIES_ON_PATH =
      """
      with recursive custom_entry_path as (
          select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state, 0 as depth
          from harness_entry
          where id = ?
          union all
          select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, e.provider_replay_state, cep.depth + 1
          from harness_entry e
          join custom_entry_path cep on e.id = cep.parent_entry_id
          where cep.parent_entry_id is not null
      ) cycle id set is_cycle using path
      select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state, depth, is_cycle
      from custom_entry_path
      where depth = 0
         or entry_type = 'ROOT'
         or is_cycle
         or (entry_type = 'CUSTOM' and payload ->> 'contributorId' = ?)
      order by depth desc
      """;

  private record PathEntryRow(Entry entry, int depth, boolean isCycle) {}

  private static final RowMapper<PathEntryRow> PATH_ENTRY_ROW =
      (resultSet, rowNumber) -> {
        Entry entry = PostgresqlHarnessRows.ENTRY.mapRow(resultSet, rowNumber);
        int depth = resultSet.getInt("depth");
        boolean isCycle = resultSet.getBoolean("is_cycle");
        return new PathEntryRow(entry, depth, isCycle);
      };

  private static final Comparator<ThreadCommand> COMMAND_LOCK_ORDER =
      Comparator.comparing(ThreadCommand::threadId, UuidOrder.COMPARATOR)
          .thenComparingLong(ThreadCommand::sequence);
  private static final Comparator<ToolInvocation> TOOL_LOCK_ORDER =
      Comparator.comparing(ToolInvocation::assistantEntryId, UuidOrder.COMPARATOR)
          .thenComparingInt(ToolInvocation::callIndex)
          .thenComparing(ToolInvocation::id, UuidOrder.COMPARATOR);
  private static final Comparator<WorkTarget> WORK_LOCK_ORDER =
      Comparator.comparingInt((WorkTarget target) -> target.type().ordinal())
          .thenComparing(WorkTarget::id, UuidOrder.COMPARATOR);

  /** 底层 Spring JdbcTemplate，绑定当前事务的数据库连接。 */
  private final JdbcTemplate jdbc;

  /** 外部注入的 UUID 生成器。 */
  private final Supplier<UUID> idGenerator;

  /** 当前事务已成功获取锁的实体资源标识集合，用于支持加锁幂等并防范重入。 */
  private final Set<LockKey> locked = new HashSet<>();

  /** 记录各 assistantEntryId 下当前已锁定的最高 tool callIndex，防范乱序持锁。 */
  private final Map<UUID, Integer> highestToolCallIndexByAssistant = new HashMap<>();

  /** 事务内 EntryPath 正向局部缓存，避免重复递归 CTE 查询。 */
  private final Map<UUID, EntryPath> entryPathCache = new HashMap<>();

  /** 创建本事务 handle 的宿主线程，用于严格守护单线程约束。 */
  private final Thread owner = Thread.currentThread();

  /** 记录事务生命周期内发生的首个数据库异常（poisoning），用于在完成前强制重抛。 */
  private RuntimeException databaseFailure;

  /** 当前事务已达到的最高锁阶梯等级，确保层级单调递增。 */
  private LockRank highestLockRank;

  /** 当前事务已锁定的最高 Thread UUID，确保同级按 UUID 升序加锁。 */
  private UUID highestThreadId;

  /** 当前事务已锁定的最高 WorkTarget，确保同级按 (type, id) 升序加锁。 */
  private WorkTarget highestWorkTarget;

  /** 事务 handle 关闭状态标识。 */
  private boolean closed;

  PostgresqlHarnessTransaction(JdbcTemplate jdbc, Supplier<UUID> idGenerator) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  /** 关闭事务 handle 并清空事务内 EntryPath 局部缓存。 */
  void close() {
    closed = true;
    entryPathCache.clear();
  }

  /** 若底层操作曾触发数据库异常，在此强制重新抛出该首个故障（poisoning），彻底阻断被破坏的事务继续提交。 */
  void rethrowDatabaseFailure() {
    if (databaseFailure != null) {
      throw databaseFailure;
    }
  }

  @Override
  public UUID nextId() {
    checkOpen();
    UUID id = idGenerator.get();
    if (id == null) {
      throw new IllegalStateException("id generator returned null");
    }
    return id;
  }

  @Override
  public void insertSession(Session session) {
    checkOpen();
    Objects.requireNonNull(session, "session");
    update(
        "insert into harness_session (id, name, created_at) values (?, ?, ?)",
        session.id(),
        session.name(),
        PostgresqlHarnessRows.timestamp(session.createdAt()));
  }

  @Override
  public Optional<Session> findSession(UUID id) {
    checkOpen();
    return queryOne(
        "select * from harness_session where id = ?", PostgresqlHarnessRows.SESSION, id);
  }

  @Override
  public void updateSession(Session session) {
    checkOpen();
    Objects.requireNonNull(session, "session");
    requireLocked(LockKey.session(session.id()));
    Session stored =
        findSession(session.id())
            .orElseThrow(
                () -> new IllegalArgumentException("session " + session.id() + " does not exist"));
    Session.validateTransition(stored, session);
    int updated =
        update("update harness_session set name = ? where id = ?", session.name(), session.id());
    requireSingleUpdate(updated, "session", session.id());
  }

  @Override
  public Optional<Session> lockSessionForKeyShare(UUID id) {
    checkOpen();
    requireCanLockRank(LockRank.SESSION);
    Optional<Session> session =
        queryOne(
            "select * from harness_session where id = ? for key share",
            PostgresqlHarnessRows.SESSION,
            id);
    session.ifPresent(ignored -> lock(LockKey.session(id)));
    return session;
  }

  @Override
  public Optional<Session> lockSessionForUpdate(UUID id) {
    checkOpen();
    requireCanLockRank(LockRank.SESSION);
    Optional<Session> session =
        queryOne(
            "select * from harness_session where id = ? for update",
            PostgresqlHarnessRows.SESSION,
            id);
    session.ifPresent(ignored -> lock(LockKey.session(id)));
    return session;
  }

  /**
   * 插入新的 Entry 节点并同步更新事务内 EntryPath 局部缓存。
   *
   * <p>若为非 ROOT Entry，从父节点已缓存的路径直接内存追加派生子节点的 {@link EntryPath} 并写入 {@link
   * #entryPathCache}，使同事务内后续子节点构建达到 0 次递归 CTE 开销。
   */
  @Override
  public void insertEntry(Entry entry) {
    checkOpen();
    Objects.requireNonNull(entry, "entry");
    if (findEntry(entry.id()).isPresent()) {
      throw new IllegalArgumentException("duplicate entry id " + entry.id());
    }
    if (findSession(entry.sessionId()).isEmpty()) {
      throw new IllegalArgumentException("session " + entry.sessionId() + " does not exist");
    }
    EntryPath candidatePath;
    if (entry.payload().type().isRoot()) {
      if (hasRoot(entry.sessionId())) {
        throw new IllegalArgumentException(
            "session " + entry.sessionId() + " already has a ROOT entry");
      }
      candidatePath = new EntryPath(List.of(entry));
    } else {
      if (!hasRoot(entry.sessionId())) {
        throw new IllegalArgumentException(
            "session " + entry.sessionId() + " must have a ROOT entry before any other entry");
      }
      EntryPath parentPath = loadEntryPath(entry.parentEntryId());
      List<Entry> nextPath = new ArrayList<>(parentPath.entries());
      nextPath.add(entry);
      candidatePath = new EntryPath(nextPath);
    }
    update(
        """
        insert into harness_entry (
            id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state
        ) values (?, ?, ?, ?, cast(? as jsonb), ?, cast(? as jsonb))
        """,
        entry.id(),
        entry.sessionId(),
        entry.parentEntryId(),
        entry.payload().type().name(),
        PostgresqlHarnessRows.ENTRY_PAYLOADS.encode(entry.payload()),
        PostgresqlHarnessRows.timestamp(entry.createdAt()),
        encodeEntryProviderReplayState(entry));
    entryPathCache.put(entry.id(), candidatePath);
  }

  private static String encodeEntryProviderReplayState(Entry entry) {
    return entry.providerReplayState() == null
        ? null
        : PostgresqlHarnessRows.PROVIDER_REPLAY_STATES.encode(entry.providerReplayState());
  }

  @Override
  public Optional<Entry> findEntry(UUID id) {
    checkOpen();
    return queryOne("select * from harness_entry where id = ?", PostgresqlHarnessRows.ENTRY, id);
  }

  /**
   * 查找指定 Session 的 ROOT Entry。
   *
   * <p>优先复用当前事务局部缓存 {@link #entryPathCache} 中已有的同 Session 路径根节点；未命中时通过 {@code
   * uk_harness_entry_single_root} 索引点查，并将单节点根路径写回缓存。
   */
  @Override
  public Optional<Entry> findRootEntry(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    for (EntryPath path : entryPathCache.values()) {
      if (path.root().sessionId().equals(sessionId)) {
        return Optional.of(path.root());
      }
    }
    Optional<Entry> found =
        queryOne(
            """
            select *
            from harness_entry
            where session_id = ? and entry_type = 'ROOT'
            """,
            PostgresqlHarnessRows.ENTRY,
            sessionId);
    found.ifPresent(root -> entryPathCache.putIfAbsent(root.id(), new EntryPath(List.of(root))));
    return found;
  }

  /**
   * 读取指定 head 的完整不可变 EntryPath。
   *
   * <p>优先从 {@link #entryPathCache} 读取；未命中时执行单次递归回溯 CTE（{@link #LOAD_ENTRY_PATH}）并存入缓存。
   */
  @Override
  public EntryPath loadEntryPath(UUID headEntryId) {
    checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = entryPathCache.get(headEntryId);
    if (cached != null) {
      return cached;
    }
    List<Entry> entries = queryList(LOAD_ENTRY_PATH, PostgresqlHarnessRows.ENTRY, headEntryId);
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    Set<UUID> visited = new HashSet<>();
    for (Entry entry : entries) {
      if (!visited.add(entry.id())) {
        throw new IllegalArgumentException("entry parent cycle detected at " + entry.id());
      }
    }
    EntryPath path = new EntryPath(entries);
    entryPathCache.put(headEntryId, path);
    return path;
  }

  /**
   * 加载指定 head 路径上属于特定 Contributor 的自定义 Entry。
   *
   * <p>若完整路径在 {@link #entryPathCache} 中已命中，直接在内存中过滤返回；cold cache 则执行窄查询 CTE（{@link
   * #LOAD_CONTRIBUTOR_CUSTOM_ENTRIES_ON_PATH}），部分投影结果绝不写入完整路径缓存。
   */
  @Override
  public List<Entry> loadContributorCustomEntriesOnPath(UUID headEntryId, String contributorId) {
    checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(contributorId, "contributorId");
    EntryPath cached = entryPathCache.get(headEntryId);
    if (cached != null) {
      return cached.entries().stream()
          .filter(
              entry ->
                  entry.payload() instanceof CustomEntryPayload custom
                      && contributorId.equals(custom.contributorId()))
          .toList();
    }
    List<PathEntryRow> rows =
        queryList(
            LOAD_CONTRIBUTOR_CUSTOM_ENTRIES_ON_PATH, PATH_ENTRY_ROW, headEntryId, contributorId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    for (PathEntryRow row : rows) {
      if (row.isCycle()) {
        throw new IllegalArgumentException("entry parent cycle detected at " + row.entry().id());
      }
    }
    PathEntryRow rootRow = rows.get(0);
    if (!rootRow.entry().payload().type().isRoot()) {
      throw new IllegalArgumentException("entry path for " + headEntryId + " does not reach root");
    }
    PathEntryRow headRow = rows.get(rows.size() - 1);
    if (headRow.depth() != 0 || !headRow.entry().id().equals(headEntryId)) {
      throw new IllegalArgumentException("entry path does not end with head " + headEntryId);
    }
    UUID sessionId = rootRow.entry().sessionId();
    for (PathEntryRow row : rows) {
      if (!row.entry().sessionId().equals(sessionId)) {
        throw new IllegalArgumentException("entry path crosses sessions: " + row.entry().id());
      }
    }
    return rows.stream()
        .map(PathEntryRow::entry)
        .filter(
            entry ->
                entry.payload() instanceof CustomEntryPayload custom
                    && contributorId.equals(custom.contributorId()))
        .toList();
  }

  @Override
  public List<Entry> loadEntriesBySessionId(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (findSession(sessionId).isEmpty()) {
      throw new IllegalArgumentException("session " + sessionId + " does not exist");
    }
    return queryList(
        """
        select *
        from harness_entry
        where session_id = ?
        order by created_at, id
        """,
        PostgresqlHarnessRows.ENTRY,
        sessionId);
  }

  @Override
  public void insertThread(ThreadState thread) {
    checkOpen();
    Objects.requireNonNull(thread, "thread");
    requireThreadHeadInSession(thread);
    requireCanLockThread(thread.id());
    update(
        """
        insert into harness_thread (
            id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled,
            next_command_sequence, version, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        thread.id(),
        thread.sessionId(),
        thread.headEntryId(),
        thread.creationRequestHash(),
        thread.name(),
        thread.yoloEnabled(),
        thread.nextCommandSequence(),
        thread.version(),
        PostgresqlHarnessRows.timestamp(thread.createdAt()),
        PostgresqlHarnessRows.timestamp(thread.updatedAt()));
    recordThreadLock(thread.id());
  }

  /** Thread 的 head Entry 必须属于 Thread 持久化的 Session（由同一 Session 的复合 FK 强制，这里给出更早的显式检查）。 */
  private void requireThreadHeadInSession(ThreadState thread) {
    if (findSession(thread.sessionId()).isEmpty()) {
      throw new IllegalArgumentException("session " + thread.sessionId() + " does not exist");
    }
    Boolean sameSession =
        queryForObject(
            """
            select exists (
                select 1
                from harness_entry
                where id = ? and session_id = ?
            )
            """,
            Boolean.class,
            thread.headEntryId(),
            thread.sessionId());
    if (!Boolean.TRUE.equals(sameSession)) {
      throw new IllegalArgumentException(
          "thread head entry "
              + thread.headEntryId()
              + " must belong to thread session "
              + thread.sessionId());
    }
  }

  @Override
  public Optional<ThreadState> findThread(UUID id) {
    checkOpen();
    return queryOne("select * from harness_thread where id = ?", PostgresqlHarnessRows.THREAD, id);
  }

  @Override
  public Optional<ThreadState> lockThread(UUID id) {
    checkOpen();
    requireCanLockThread(id);
    Optional<ThreadState> thread =
        queryOne(
            "select * from harness_thread where id = ? for update",
            PostgresqlHarnessRows.THREAD,
            id);
    thread.ifPresent(ignored -> recordThreadLock(id));
    return thread;
  }

  @Override
  public List<ThreadState> listThreadsBySession(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (findSession(sessionId).isEmpty()) {
      throw new IllegalArgumentException("session " + sessionId + " does not exist");
    }
    return queryList(
        """
        select *
        from harness_thread
        where session_id = ?
        order by created_at, id
        """,
        PostgresqlHarnessRows.THREAD,
        sessionId);
  }

  @Override
  public void updateThread(ThreadState thread) {
    checkOpen();
    Objects.requireNonNull(thread, "thread");
    requireLocked(LockKey.thread(thread.id()));
    ThreadState stored =
        findThread(thread.id())
            .orElseThrow(
                () -> new IllegalArgumentException("thread " + thread.id() + " does not exist"));
    ThreadState.validateTransition(stored, thread);
    requireThreadHeadInSession(thread);
    int updated =
        update(
            """
            update harness_thread
            set head_entry_id = ?,
                name = ?,
                yolo_enabled = ?,
                next_command_sequence = ?,
                version = ?,
                updated_at = ?
            where id = ?
            """,
            thread.headEntryId(),
            thread.name(),
            thread.yoloEnabled(),
            thread.nextCommandSequence(),
            thread.version(),
            PostgresqlHarnessRows.timestamp(thread.updatedAt()),
            thread.id());
    requireSingleUpdate(updated, "thread", thread.id());
  }

  @Override
  public Optional<ThreadCommand> findCommandByIdempotencyKey(UUID threadId, UUID idempotencyKey) {
    checkOpen();
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return queryOne(
        """
        select *
        from harness_thread_command
        where thread_id = ? and idempotency_key = ?
        """,
        PostgresqlHarnessRows.COMMAND,
        threadId,
        idempotencyKey);
  }

  @Override
  public List<ThreadCommand> loadQueuedCommands(UUID threadId) {
    checkOpen();
    requireLocked(LockKey.thread(threadId));
    requireCanLockRank(LockRank.COMMAND);
    List<ThreadCommand> commands =
        queryList(
            """
            select *
            from harness_thread_command
            where thread_id = ?
              and applied_turn_start_entry_id is null
              and cancelled_at is null
            order by sequence
            for update
            """,
            PostgresqlHarnessRows.COMMAND,
            threadId);
    for (ThreadCommand command : commands) {
      lock(LockKey.command(command.threadId(), command.sequence()));
    }
    return commands;
  }

  @Override
  public List<ThreadCommand> loadCommandsByThread(UUID threadId) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return queryList(
        """
        select *
        from harness_thread_command
        where thread_id = ?
        order by sequence
        """,
        PostgresqlHarnessRows.COMMAND,
        threadId);
  }

  @Override
  public List<ThreadCommand> loadCancelledCommandsByRequest(UUID threadId, UUID stopRequestId) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(stopRequestId, "stopRequestId");
    return queryList(
        """
        select *
        from harness_thread_command
        where thread_id = ? and stop_request_id = ?
        order by sequence
        """,
        PostgresqlHarnessRows.COMMAND,
        threadId,
        stopRequestId);
  }

  @Override
  public void insertCommands(List<ThreadCommand> commands) {
    checkOpen();
    List<ThreadCommand> copied = List.copyOf(commands).stream().sorted(COMMAND_LOCK_ORDER).toList();
    Set<CommandSequenceKey> sequences = new HashSet<>();
    Set<CommandIdempotencyKey> keys = new HashSet<>();
    for (ThreadCommand command : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(command.cancelledAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(command.createdAt());
      if (!sequences.add(new CommandSequenceKey(command.threadId(), command.sequence()))) {
        throw new IllegalArgumentException(
            "duplicate command sequence "
                + command.sequence()
                + " on thread "
                + command.threadId());
      }
      if (!keys.add(new CommandIdempotencyKey(command.threadId(), command.idempotencyKey()))) {
        throw new IllegalArgumentException(
            "duplicate idempotencyKey "
                + command.idempotencyKey()
                + " on thread "
                + command.threadId());
      }
      if (command.state() != ThreadCommandState.QUEUED) {
        throw new IllegalArgumentException("inserted commands must be QUEUED");
      }
      if (findThread(command.threadId()).isEmpty()) {
        throw new IllegalArgumentException("thread " + command.threadId() + " does not exist");
      }
      requireLocked(LockKey.thread(command.threadId()));
      requireUniqueCommandKey(command);
    }
    if (!copied.isEmpty()) {
      requireCanLockRank(LockRank.COMMAND);
    }
    for (ThreadCommand command : copied) {
      update(
          """
          insert into harness_thread_command (
              thread_id, sequence, command_type, payload, idempotency_key,
              request_hash, applied_turn_start_entry_id, stop_request_id,
              cancelled_at, created_at
          ) values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
          """,
          command.threadId(),
          command.sequence(),
          command.type().name(),
          PostgresqlHarnessRows.COMMAND_PAYLOADS.encode(command.payload()),
          command.idempotencyKey(),
          command.requestHash(),
          command.appliedTurnStartEntryId(),
          command.stopRequestId(),
          PostgresqlHarnessRows.timestamp(command.cancelledAt()),
          PostgresqlHarnessRows.timestamp(command.createdAt()));
      lock(LockKey.command(command.threadId(), command.sequence()));
    }
  }

  @Override
  public void updateCommands(List<ThreadCommand> commands) {
    checkOpen();
    List<ThreadCommand> copied = List.copyOf(commands).stream().sorted(COMMAND_LOCK_ORDER).toList();
    for (ThreadCommand command : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(command.cancelledAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(command.createdAt());
      ThreadCommand stored =
          findCommand(command.threadId(), command.sequence())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "command "
                              + command.threadId()
                              + "/"
                              + command.sequence()
                              + " does not exist"));
      requireLocked(LockKey.command(command.threadId(), command.sequence()));
      requireSameCommandIdentity(stored, command);
      requireLocked(LockKey.thread(command.threadId()));
      requireValidCommandLifecycle(stored, command);
      requireValidAppliedTurnStart(command);
    }
    for (ThreadCommand command : copied) {
      int updated =
          update(
              """
              update harness_thread_command
              set applied_turn_start_entry_id = ?, stop_request_id = ?, cancelled_at = ?
              where thread_id = ? and sequence = ?
              """,
              command.appliedTurnStartEntryId(),
              command.stopRequestId(),
              PostgresqlHarnessRows.timestamp(command.cancelledAt()),
              command.threadId(),
              command.sequence());
      requireSingleUpdate(updated, "command", command.threadId() + "/" + command.sequence());
    }
  }

  @Override
  public Optional<ModelInvocation> findModelInvocation(UUID id) {
    checkOpen();
    return queryOne(
        "select * from harness_model_invocation where id = ?",
        PostgresqlHarnessRows.MODEL_INVOCATION,
        id);
  }

  @Override
  public Optional<ModelInvocation> lockModelInvocation(UUID id) {
    checkOpen();
    LockKey lockKey = LockKey.model(id);
    requireCanLock(lockKey);
    Optional<ModelInvocation> invocation =
        queryOne(
            "select * from harness_model_invocation where id = ? for update",
            PostgresqlHarnessRows.MODEL_INVOCATION,
            id);
    invocation.ifPresent(ignored -> lock(lockKey));
    return invocation;
  }

  @Override
  public Optional<ModelInvocation> findModelInvocationByTurn(UUID threadId, UUID turnStartEntryId) {
    checkOpen();
    return queryOne(
        """
        select *
        from harness_model_invocation
        where thread_id = ? and turn_start_entry_id = ?
        """,
        PostgresqlHarnessRows.MODEL_INVOCATION,
        threadId,
        turnStartEntryId);
  }

  @Override
  public void insertModelInvocation(ModelInvocation invocation) {
    checkOpen();
    Objects.requireNonNull(invocation, "invocation");
    if (findModelInvocation(invocation.id()).isPresent()) {
      throw new IllegalArgumentException("duplicate model invocation id " + invocation.id());
    }
    requireUniqueModelTurn(invocation);
    ThreadState thread =
        findThread(invocation.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + invocation.threadId() + " does not exist"));
    requireLocked(LockKey.thread(invocation.threadId()));
    TurnStartPayload turnStartPayload = requireTurnStartPayload(invocation.turnStartEntryId());
    if (!turnStartPayload.ownerThreadId().equals(invocation.threadId())) {
      throw new IllegalArgumentException(
          "turn start ownerThreadId must equal the model invocation threadId");
    }
    Integer contextWindow = turnStartPayload.contextWindow();
    if (contextWindow == null || contextWindow <= 0) {
      throw new IllegalArgumentException(
          "model invocations require a positive turn start contextWindow");
    }
    if (invocation.status() != ModelInvocationStatus.READY || invocation.attempt() != 0) {
      throw new IllegalArgumentException("new model invocations must be READY with attempt 0");
    }
    if (!thread.headEntryId().equals(invocation.requestHeadEntryId())) {
      throw new IllegalArgumentException(
          "requestHeadEntryId must equal the current thread head entry");
    }
    requireValidModelBranch(invocation, thread);
    requireValidModelResultEntry(invocation);
    LockKey lockKey = LockKey.model(invocation.id());
    requireCanLock(lockKey);
    update(
        """
        insert into harness_model_invocation (
            id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec, status, attempt,
            stream_checkpoint, result, error, result_entry_id, failed_attempts, created_at, updated_at,
            provider_replay_state
        ) values (
            ?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb), cast(? as jsonb),
            cast(? as jsonb), ?, cast(? as jsonb), ?, ?, cast(? as jsonb)
        )
        """,
        invocation.id(),
        invocation.threadId(),
        invocation.turnStartEntryId(),
        invocation.requestHeadEntryId(),
        PostgresqlHarnessRows.MODEL_REQUESTS.encode(invocation.requestSpec()),
        invocation.status().name(),
        invocation.attempt(),
        encodeStreamCheckpoint(invocation),
        encodeModelResult(invocation),
        encodeModelError(invocation),
        invocation.resultEntryId(),
        PostgresqlHarnessRows.MODEL_FAILED_ATTEMPTS.encode(invocation.failedAttempts()),
        PostgresqlHarnessRows.timestamp(invocation.createdAt()),
        PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
        encodeModelProviderReplayState(invocation));
    lock(lockKey);
  }

  @Override
  public void updateModelInvocation(ModelInvocation invocation) {
    checkOpen();
    Objects.requireNonNull(invocation, "invocation");
    requireLocked(LockKey.model(invocation.id()));
    ModelInvocation stored =
        findModelInvocation(invocation.id())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "model invocation " + invocation.id() + " does not exist"));
    ModelInvocation.validateTransition(stored, invocation);
    requireValidModelResultEntry(invocation);
    if (stored.resultEntryId() == null && invocation.resultEntryId() != null) {
      ModelAttemptMaterialization.validate(
          stored, invocation, loadEntryPath(invocation.resultEntryId()));
    }
    int updated =
        update(
            """
            update harness_model_invocation
            set status = ?,
                attempt = ?,
                stream_checkpoint = cast(? as jsonb),
                result = cast(? as jsonb),
                error = cast(? as jsonb),
                result_entry_id = ?,
                failed_attempts = cast(? as jsonb),
                updated_at = ?,
                provider_replay_state = cast(? as jsonb)
            where id = ?
            """,
            invocation.status().name(),
            invocation.attempt(),
            encodeStreamCheckpoint(invocation),
            encodeModelResult(invocation),
            encodeModelError(invocation),
            invocation.resultEntryId(),
            PostgresqlHarnessRows.MODEL_FAILED_ATTEMPTS.encode(invocation.failedAttempts()),
            PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
            encodeModelProviderReplayState(invocation),
            invocation.id());
    requireSingleUpdate(updated, "model invocation", invocation.id());
  }

  private static String encodeModelProviderReplayState(ModelInvocation invocation) {
    return invocation.providerReplayState() == null
        ? null
        : PostgresqlHarnessRows.PROVIDER_REPLAY_STATES.encode(invocation.providerReplayState());
  }

  @Override
  public Optional<ToolInvocation> findToolInvocation(UUID id) {
    checkOpen();
    return queryOne(
        "select * from harness_tool_invocation where id = ?",
        PostgresqlHarnessRows.TOOL_INVOCATION,
        id);
  }

  @Override
  public Optional<ToolInvocation> lockToolInvocation(UUID id) {
    checkOpen();
    Optional<ToolInvocation> current = findToolInvocation(id);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    requireCanLockTools(current.stream().toList());
    Optional<ToolInvocation> invocation =
        queryOne(
            "select * from harness_tool_invocation where id = ? for update",
            PostgresqlHarnessRows.TOOL_INVOCATION,
            id);
    invocation.ifPresent(this::lockTool);
    return invocation;
  }

  @Override
  public List<ToolInvocation> loadToolInvocationsByAssistantEntryId(UUID assistantEntryId) {
    checkOpen();
    return queryList(
        """
        select *
        from harness_tool_invocation
        where assistant_entry_id = ?
        order by call_index
        """,
        PostgresqlHarnessRows.TOOL_INVOCATION,
        assistantEntryId);
  }

  @Override
  public List<ToolInvocation> lockToolInvocationsByAssistantEntryId(UUID assistantEntryId) {
    checkOpen();
    requireCanLockRank(LockRank.TOOL);
    List<ToolInvocation> current = loadToolInvocationsByAssistantEntryId(assistantEntryId);
    requireCanLockTools(current);
    List<ToolInvocation> invocations =
        queryList(
            """
            select *
            from harness_tool_invocation
            where assistant_entry_id = ?
            order by call_index
            for update
            """,
            PostgresqlHarnessRows.TOOL_INVOCATION,
            assistantEntryId);
    for (ToolInvocation invocation : invocations) {
      lockTool(invocation);
    }
    return invocations;
  }

  @Override
  public void insertToolInvocations(List<ToolInvocation> invocations) {
    checkOpen();
    List<ToolInvocation> copied =
        List.copyOf(invocations).stream().sorted(TOOL_LOCK_ORDER).toList();
    Set<UUID> ids = new HashSet<>();
    Set<ToolCallIndexKey> callIndexes = new HashSet<>();
    for (ToolInvocation invocation : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.createdAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.updatedAt());
      if (!ids.add(invocation.id())) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      if (!callIndexes.add(
          new ToolCallIndexKey(invocation.assistantEntryId(), invocation.callIndex()))) {
        throw new IllegalArgumentException(
            "duplicate tool invocation callIndex "
                + invocation.callIndex()
                + " on assistant entry "
                + invocation.assistantEntryId());
      }
      if (findToolInvocation(invocation.id()).isPresent()) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      if ((invocation.status() != ToolInvocationStatus.READY
              && invocation.status() != ToolInvocationStatus.FAILED)
          || invocation.attempt() != 0
          || invocation.approval() != null) {
        throw new IllegalArgumentException(
            "new tool invocations must be READY or unattached FAILED with attempt 0 and no approval");
      }
      requireUniqueToolCallIndex(invocation);
      requireValidToolReferences(invocation);
    }
    requireCanLockTools(copied);
    for (ToolInvocation invocation : copied) {
      update(
          """
          insert into harness_tool_invocation (
              id, model_invocation_id, assistant_entry_id, call_index, call, binding, status, attempt,
              approval, result, effects, error, created_at, updated_at
          ) values (
              ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, cast(? as jsonb),
              cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?
          )
          """,
          invocation.id(),
          invocation.modelInvocationId(),
          invocation.assistantEntryId(),
          invocation.callIndex(),
          PostgresqlHarnessRows.TOOL_CALLS.encode(invocation.call()),
          encodeToolBinding(invocation),
          invocation.status().name(),
          invocation.attempt(),
          encodeToolApproval(invocation),
          encodeToolResult(invocation),
          encodeToolEffects(invocation),
          encodeToolError(invocation),
          PostgresqlHarnessRows.timestamp(invocation.createdAt()),
          PostgresqlHarnessRows.timestamp(invocation.updatedAt()));
      lockTool(invocation);
    }
  }

  @Override
  public void updateToolInvocations(List<ToolInvocation> invocations) {
    checkOpen();
    List<ToolInvocation> copied =
        List.copyOf(invocations).stream().sorted(TOOL_LOCK_ORDER).toList();
    Set<UUID> ids = new HashSet<>();
    for (ToolInvocation invocation : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.createdAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.updatedAt());
      if (!ids.add(invocation.id())) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      requireLocked(LockKey.tool(invocation.id()));
      ToolInvocation stored =
          findToolInvocation(invocation.id())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "tool invocation " + invocation.id() + " does not exist"));
      ToolInvocation.validateTransition(stored, invocation);
    }
    for (ToolInvocation invocation : copied) {
      int updated =
          update(
              """
              update harness_tool_invocation
              set status = ?,
                  attempt = ?,
                  approval = cast(? as jsonb),
                  result = cast(? as jsonb),
                  effects = cast(? as jsonb),
                  error = cast(? as jsonb),
                  updated_at = ?
              where id = ?
              """,
              invocation.status().name(),
              invocation.attempt(),
              encodeToolApproval(invocation),
              encodeToolResult(invocation),
              encodeToolEffects(invocation),
              encodeToolError(invocation),
              PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
              invocation.id());
      requireSingleUpdate(updated, "tool invocation", invocation.id());
    }
  }

  @Override
  public Optional<Work> findWork(WorkTarget target) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    return queryOne(
        "select * from harness_work where target_type = ? and target_id = ?",
        PostgresqlHarnessRows.WORK,
        target.type().name(),
        target.id());
  }

  /**
   * 按锁阶梯获取单个 Work 的排他行级锁（{@code FOR UPDATE}）。
   *
   * <p>必须遵守 {@code (targetType.ordinal(), targetId)} 升序排序。
   */
  @Override
  public Optional<Work> lockWork(WorkTarget target) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    requireCanLockWork(target);
    Optional<Work> work =
        queryOne(
            """
            select *
            from harness_work
            where target_type = ? and target_id = ?
            for update
            """,
            PostgresqlHarnessRows.WORK,
            target.type().name(),
            target.id());
    work.ifPresent(ignored -> recordWorkLock(target));
    return work;
  }

  /**
   * Work 所有权围栏校验：获取行级排他锁并校验 {@code lease_token} 匹配且 {@code lease_until > now}。
   *
   * <p>若租约已过期或被其他 Dispatcher 接管，返回 empty，调用方绝不能推进该 Work 状态。
   */
  @Override
  public Optional<Work> lockClaimedWork(ClaimedWork claim, Instant now) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    requireCanLockWork(claim.target());
    Optional<Work> work =
        queryOne(
            """
            select *
            from harness_work
            where target_type = ?
              and target_id = ?
              and lease_token = ?
              and lease_until > ?
            for update
            """,
            PostgresqlHarnessRows.WORK,
            claim.target().type().name(),
            claim.target().id(),
            claim.leaseToken(),
            PostgresqlHarnessRows.timestamp(now));
    work.ifPresent(ignored -> recordWorkLock(claim.target()));
    return work;
  }

  /** 删除指定 Work。要求必须已持有其属主实体的行级锁。 */
  @Override
  public boolean deleteWork(WorkTarget target) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    if (findWork(target).isEmpty()) {
      return false;
    }
    requireWorkOwnerLocked(target);
    if (lockWork(target).isEmpty()) {
      return false;
    }
    int deleted =
        update(
            "delete from harness_work where target_type = ? and target_id = ?",
            target.type().name(),
            target.id());
    requireSingleUpdate(deleted, "work", target.id());
    return true;
  }

  // ---------- 应用侧深删除原语 ----------

  @Override
  public int deleteThreads(List<UUID> threadIds) {
    checkOpen();
    Objects.requireNonNull(threadIds, "threadIds");
    List<UUID> copied = List.copyOf(threadIds).stream().sorted(UuidOrder.COMPARATOR).toList();
    for (int i = 1; i < copied.size(); i++) {
      if (copied.get(i).equals(copied.get(i - 1))) {
        throw new IllegalArgumentException(
            "duplicate thread id " + copied.get(i) + " must not be deleted twice");
      }
    }
    for (UUID threadId : copied) {
      requireLocked(LockKey.thread(threadId));
    }
    if (copied.isEmpty()) {
      return 0;
    }

    for (UUID threadId : copied) {
      List<ThreadCommand> commands =
          queryList(
              """
              select *
              from harness_thread_command
              where thread_id = ?
              order by sequence
              for update
              """,
              PostgresqlHarnessRows.COMMAND,
              threadId);
      for (ThreadCommand command : commands) {
        lock(LockKey.command(command.threadId(), command.sequence()));
      }
    }

    List<ModelInvocation> models = new ArrayList<>();
    for (UUID threadId : copied) {
      models.addAll(
          queryList(
              "select * from harness_model_invocation where thread_id = ?",
              PostgresqlHarnessRows.MODEL_INVOCATION,
              threadId));
    }
    models.sort(Comparator.comparing(ModelInvocation::id, UuidOrder.COMPARATOR));
    List<ModelInvocation> lockedModels = new ArrayList<>(models.size());
    for (ModelInvocation model : models) {
      lockModelInvocation(model.id()).ifPresent(lockedModels::add);
    }

    List<ToolInvocation> tools = new ArrayList<>();
    for (ModelInvocation model : lockedModels) {
      tools.addAll(
          queryList(
              "select * from harness_tool_invocation where model_invocation_id = ?",
              PostgresqlHarnessRows.TOOL_INVOCATION,
              model.id()));
    }
    tools.sort(TOOL_LOCK_ORDER);
    List<ToolInvocation> lockedTools = new ArrayList<>(tools.size());
    for (ToolInvocation tool : tools) {
      lockToolInvocation(tool.id()).ifPresent(lockedTools::add);
    }

    List<WorkTarget> workTargets = new ArrayList<>();
    for (UUID threadId : copied) {
      workTargets.add(new WorkTarget(WorkTargetType.THREAD, threadId));
    }
    for (ModelInvocation model : lockedModels) {
      workTargets.add(new WorkTarget(WorkTargetType.MODEL, model.id()));
    }
    for (ToolInvocation tool : lockedTools) {
      workTargets.add(new WorkTarget(WorkTargetType.TOOL, tool.id()));
    }
    workTargets.sort(WORK_LOCK_ORDER);
    List<WorkTarget> lockedWorkTargets = new ArrayList<>();
    for (WorkTarget target : workTargets) {
      if (lockWork(target).isPresent()) {
        lockedWorkTargets.add(target);
      }
    }

    for (WorkTarget target : lockedWorkTargets) {
      if (!deleteWork(target)) {
        throw new IllegalStateException(
            "locked work disappeared while deleting threads: " + target);
      }
    }
    for (UUID threadId : copied) {
      update("delete from harness_thread_command where thread_id = ?", threadId);
    }
    if (!lockedTools.isEmpty()) {
      deleteToolInvocationsByIds(lockedTools.stream().map(ToolInvocation::id).toList());
    }
    for (ModelInvocation model : lockedModels) {
      deleteModelInvocation(model.id());
    }
    for (UUID threadId : copied) {
      int deleted = update("delete from harness_thread where id = ?", threadId);
      requireSingleUpdate(deleted, "thread", threadId);
    }
    return copied.size();
  }

  /** 删除指定 Session 下的全部 Entry 节点（叶子优先逐层删除），并同步驱逐事务内缓存。 */
  @Override
  public int deleteEntries(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    entryPathCache.values().removeIf(path -> path.root().sessionId().equals(sessionId));
    int total = 0;
    while (true) {
      // 叶子优先：同一语句只删除父不在批内的行（parent FK 顺序天然成立），逐层剥到 ROOT。
      int deleted =
          update(
              """
              delete from harness_entry e
              where e.session_id = ?
                and not exists (
                    select 1
                    from harness_entry child
                    where child.session_id = e.session_id
                      and child.parent_entry_id = e.id
                )
              """,
              sessionId);
      total += deleted;
      if (deleted == 0) {
        return total;
      }
    }
  }

  /** 删除指定 Session 并从事务局部缓存中驱逐该 Session 的全部 EntryPath。 */
  @Override
  public boolean deleteSession(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    entryPathCache.values().removeIf(path -> path.root().sessionId().equals(sessionId));
    return update("delete from harness_session where id = ?", sessionId) == 1;
  }

  @Override
  public int deleteToolInvocationsByIds(List<UUID> toolInvocationIds) {
    checkOpen();
    List<UUID> copied =
        List.copyOf(toolInvocationIds).stream().sorted(UuidOrder.COMPARATOR).toList();
    for (int i = 1; i < copied.size(); i++) {
      if (copied.get(i).equals(copied.get(i - 1))) {
        throw new IllegalArgumentException(
            "duplicate tool invocation id " + copied.get(i) + " must not be deleted twice");
      }
    }
    for (UUID toolInvocationId : copied) {
      if (findToolInvocation(toolInvocationId).isEmpty()) {
        throw new IllegalArgumentException(
            "tool invocation " + toolInvocationId + " does not exist");
      }
      requireLocked(LockKey.tool(toolInvocationId));
    }
    if (copied.isEmpty()) {
      return 0;
    }
    for (UUID toolInvocationId : copied) {
      int deleted = update("delete from harness_tool_invocation where id = ?", toolInvocationId);
      requireSingleUpdate(deleted, "tool invocation", toolInvocationId);
    }
    return copied.size();
  }

  @Override
  public boolean deleteModelInvocation(UUID modelInvocationId) {
    checkOpen();
    Objects.requireNonNull(modelInvocationId, "modelInvocationId");
    if (findModelInvocation(modelInvocationId).isEmpty()) {
      throw new IllegalArgumentException(
          "model invocation " + modelInvocationId + " does not exist");
    }
    requireLocked(LockKey.model(modelInvocationId));
    if (hasToolInvocationChildren(modelInvocationId)) {
      throw new IllegalArgumentException(
          "model invocation " + modelInvocationId + " has tool invocation children");
    }
    int deleted = update("delete from harness_model_invocation where id = ?", modelInvocationId);
    requireSingleUpdate(deleted, "model invocation", modelInvocationId);
    return true;
  }

  @Override
  public void requestWork(WorkTarget target, Instant requestedAt) {
    requestWork(target, requestedAt, null);
  }

  /**
   * 登记或唤醒指定 Work。
   *
   * <p>必须在已锁定属主 Thread 的事务内调用。执行 upsert 原语：
   *
   * <ul>
   *   <li>若 Work 不存在则新建，初始 wake_version 为 1；
   *   <li>若已存在则更新 {@code available_at = least(current, requested)} 且递增 {@code wake_version}；
   *   <li>{@code requiredEnvironmentId} 仅允许用于 TOOL Work；
   *   <li><b>已冻结 Affinity 冲突拒绝：</b>upsert WHERE 子句要求传入的 {@code requiredEnvironmentId} 与既有值一致或传入为
   *       null； 若已存在的 Work 绑定的环境与新传入的值冲突，更新 0 行并抛出 {@link IllegalArgumentException} 明确拒绝，防止环境亲和性漂移；
   *   <li>提交前发送 {@code pg_notify} availability hint。
   * </ul>
   */
  @Override
  public void requestWork(
      WorkTarget target, Instant requestedAt, EnvironmentId requiredEnvironmentId) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedAt, "requestedAt");
    PostgresqlHarnessRows.requireMillisecondPrecision(requestedAt);
    if (requiredEnvironmentId != null && target.type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "requiredEnvironmentId must be null for target type " + target.type());
    }
    requireWorkOwnerLocked(target);
    requireCanLockWork(target);
    Work work =
        writeOne(
                REQUEST_WORK,
                PostgresqlHarnessRows.WORK,
                target.type().name(),
                target.id(),
                PostgresqlHarnessRows.timestamp(requestedAt),
                requiredEnvironmentId == null ? null : requiredEnvironmentId.value())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "conflicting requiredEnvironmentId for work target " + target));
    recordWorkLock(work.target());
    notifyWorkAvailable();
  }

  @Override
  public Optional<ClaimedWork> claimNextWork(
      WorkTargetType targetType, Instant now, String leaseToken, Instant leaseUntil) {
    return claimNextWork(targetType, now, leaseToken, leaseUntil, null);
  }

  /**
   * Work claim-only 短事务：以 {@code FOR UPDATE SKIP LOCKED} 和 Environment route 路由围栏选取单条候选并签发租约。
   *
   * <p><b>Environment route 路由围栏：</b>
   *
   * <ul>
   *   <li>{@code required_environment_id} 为空时无亲和性要求，任何活跃 Dispatcher 均可获取；
   *   <li>非空时，要求在 {@code environment_connection} 中存在匹配的 {@code environment_id}、所有者为传入的 {@code
   *       nodeInstanceId}、状态为 {@code READY} 且租约未过期的记录；
   *   <li>路由不确定（如断联、未就绪、租约过期、归属其他节点）时不返回候选；底层数据库故障则抛错并使 claim 事务失败；
   *   <li>层次区分：本路由围栏与底层数据库行级并发控制 {@code FOR UPDATE SKIP LOCKED} 及应用层 {@code lease_token} / {@code
   *       lease_until} 所有权围栏分属不同层次，互不替代。
   * </ul>
   */
  @Override
  public Optional<ClaimedWork> claimNextWork(
      WorkTargetType targetType,
      Instant now,
      String leaseToken,
      Instant leaseUntil,
      UUID nodeInstanceId) {
    checkOpen();
    Objects.requireNonNull(targetType, "targetType");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(leaseUntil, "leaseUntil");
    PostgresqlHarnessRows.requireMillisecondPrecision(now);
    PostgresqlHarnessRows.requireMillisecondPrecision(leaseUntil);
    Work.initial(new WorkTarget(targetType, new UUID(0L, 0L)), now)
        .claim(now, leaseToken, leaseUntil);
    requireCanClaimWork();
    Optional<Work> claimed =
        writeOne(
            CLAIM_NEXT_WORK,
            PostgresqlHarnessRows.WORK,
            targetType.name(),
            nodeInstanceId,
            leaseToken,
            PostgresqlHarnessRows.timestamp(leaseUntil));
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    Work work = claimed.get();
    requireTargetExists(work.target());
    recordWorkLock(work.target());
    return Optional.of(
        new ClaimedWork(
            work.target(),
            work.wakeVersion(),
            work.leaseToken(),
            work.leaseUntil(),
            work.requiredEnvironmentId()));
  }

  /** 在持有 Work 锁前提下为已 claim 的 Work 续期租约。 */
  @Override
  public void renewWork(ClaimedWork claim, Instant now, Instant newLeaseUntil) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
    PostgresqlHarnessRows.requireMillisecondPrecision(now);
    PostgresqlHarnessRows.requireMillisecondPrecision(newLeaseUntil);
    Work renewed = lockedWork(claim.target()).renew(claim.leaseToken(), now, newLeaseUntil);
    updateWork(renewed);
  }

  /**
   * Final Work Fence：完成工作并校验 wakeVersion。
   *
   * <p>若 claimed wakeVersion 仍为最新，则直接删除该 Work 行；若处理期间有新 wake 请求导致版本递增，则清除租约保留该行
   * 并发送唤醒通知待后续调度；若租约丢失则抛错回滚事务。
   */
  @Override
  public Optional<Work> completeWork(ClaimedWork claim, Instant now) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    PostgresqlHarnessRows.requireMillisecondPrecision(now);
    Work work = lockedWork(claim.target());
    Optional<Work> next = work.complete(claim.leaseToken(), claim.claimedWakeVersion(), now);
    if (next.isEmpty()) {
      int deleted =
          update(
              "delete from harness_work where target_type = ? and target_id = ?",
              claim.target().type().name(),
              claim.target().id());
      requireSingleUpdate(deleted, "work", claim.target().id());
    } else {
      updateWork(next.get());
      notifyWorkAvailable();
    }
    return next;
  }

  /** 重排 Work 调度时间：清除租约并设置新的 requestedAt，保留当前 wakeVersion。 */
  @Override
  public void rescheduleWork(ClaimedWork claim, Instant now, Instant requestedAt) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(requestedAt, "requestedAt");
    PostgresqlHarnessRows.requireMillisecondPrecision(now);
    PostgresqlHarnessRows.requireMillisecondPrecision(requestedAt);
    Work next =
        lockedWork(claim.target())
            .reschedule(claim.leaseToken(), claim.claimedWakeVersion(), now, requestedAt);
    updateWork(next);
    notifyWorkAvailable();
  }

  private Optional<ThreadCommand> findCommand(UUID threadId, long sequence) {
    return queryOne(
        "select * from harness_thread_command where thread_id = ? and sequence = ?",
        PostgresqlHarnessRows.COMMAND,
        threadId,
        sequence);
  }

  private boolean hasRoot(UUID sessionId) {
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_entry
                where session_id = ? and entry_type = 'ROOT'
            )
            """,
            Boolean.class,
            sessionId);
    return Boolean.TRUE.equals(exists);
  }

  private boolean hasToolInvocationChildren(UUID modelInvocationId) {
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_tool_invocation
                where model_invocation_id = ?
            )
            """,
            Boolean.class,
            modelInvocationId);
    return Boolean.TRUE.equals(exists);
  }

  private Entry requireExistingEntry(UUID entryId) {
    return findEntry(entryId)
        .orElseThrow(() -> new IllegalArgumentException("entry " + entryId + " does not exist"));
  }

  private TurnStartPayload requireTurnStartPayload(UUID entryId) {
    Entry turnStart = requireExistingEntry(entryId);
    if (!(turnStart.payload() instanceof TurnStartPayload payload)) {
      throw new IllegalArgumentException("turnStartEntryId must reference a TURN_START entry");
    }
    return payload;
  }

  private void requireUniqueCommandKey(ThreadCommand command) {
    Boolean sequenceExists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_thread_command
                where thread_id = ? and sequence = ?
            )
            """,
            Boolean.class,
            command.threadId(),
            command.sequence());
    if (Boolean.TRUE.equals(sequenceExists)) {
      throw new IllegalArgumentException(
          "command sequence "
              + command.sequence()
              + " already used on thread "
              + command.threadId());
    }
    Boolean clientExists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_thread_command
                where thread_id = ? and idempotency_key = ?
            )
            """,
            Boolean.class,
            command.threadId(),
            command.idempotencyKey());
    if (Boolean.TRUE.equals(clientExists)) {
      throw new IllegalArgumentException(
          "idempotencyKey "
              + command.idempotencyKey()
              + " already used on thread "
              + command.threadId());
    }
  }

  private void requireValidAppliedTurnStart(ThreadCommand command) {
    UUID appliedTurnStartEntryId = command.appliedTurnStartEntryId();
    if (appliedTurnStartEntryId == null) {
      return;
    }
    Entry turnStart = requireExistingEntry(appliedTurnStartEntryId);
    if (turnStart.payload().type() != EntryType.TURN_START) {
      throw new IllegalArgumentException(
          "appliedTurnStartEntryId must reference a TURN_START entry");
    }
    ThreadState thread =
        findThread(command.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + command.threadId() + " does not exist"));
    if (!turnStart.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "applied turn start must be in the command thread's session");
    }
    TurnStartPayload turnStartPayload = (TurnStartPayload) turnStart.payload();
    if (!command.threadId().equals(turnStartPayload.ownerThreadId())) {
      throw new IllegalArgumentException(
          "applied turn start ownerThreadId must equal the command threadId");
    }
  }

  private static void requireValidCommandLifecycle(ThreadCommand stored, ThreadCommand command) {
    if (stored.appliedTurnStartEntryId() != null || stored.cancelledAt() != null) {
      if (!Objects.equals(stored.appliedTurnStartEntryId(), command.appliedTurnStartEntryId())
          || !Objects.equals(stored.cancelledAt(), command.cancelledAt())) {
        throw new IllegalArgumentException(
            "terminal commands must be updated exactly idempotently");
      }
      return;
    }
    boolean applied = command.appliedTurnStartEntryId() != null;
    boolean cancelled = command.cancelledAt() != null;
    if (applied == cancelled) {
      throw new IllegalArgumentException(
          "queued commands may only transition to APPLIED or CANCELLED");
    }
  }

  private static void requireSameCommandIdentity(ThreadCommand stored, ThreadCommand command) {
    if (!stored.threadId().equals(command.threadId())
        || !stored.payload().equals(command.payload())
        || !stored.idempotencyKey().equals(command.idempotencyKey())
        || !stored.requestHash().equals(command.requestHash())
        || stored.sequence() != command.sequence()
        || !stored.createdAt().equals(command.createdAt())) {
      throw new IllegalArgumentException(
          "command identity (thread/payload/idempotencyKey/requestHash/sequence/createdAt) must not change");
    }
  }

  private void requireUniqueModelTurn(ModelInvocation invocation) {
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_model_invocation
                where thread_id = ? and turn_start_entry_id = ?
            )
            """,
            Boolean.class,
            invocation.threadId(),
            invocation.turnStartEntryId());
    if (Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException(
          "model invocation already exists for thread "
              + invocation.threadId()
              + " turn "
              + invocation.turnStartEntryId());
    }
  }

  private void requireValidModelBranch(ModelInvocation invocation, ThreadState thread) {
    EntryPath requestHeadPath = loadEntryPath(invocation.requestHeadEntryId());
    boolean turnStartOnRequestHeadPath =
        requestHeadPath.entries().stream()
            .anyMatch(entry -> entry.id().equals(invocation.turnStartEntryId()));
    if (!turnStartOnRequestHeadPath) {
      throw new IllegalArgumentException(
          "turnStartEntryId must be on the requestHeadEntryId entry path");
    }
    if (!thread.sessionId().equals(requestHeadPath.head().sessionId())) {
      throw new IllegalArgumentException("thread session must match the request head path session");
    }
  }

  private void requireValidModelResultEntry(ModelInvocation invocation) {
    UUID resultEntryId = invocation.resultEntryId();
    if (resultEntryId == null) {
      return;
    }
    Entry result = requireExistingEntry(resultEntryId);
    boolean compactionInvocation = isCompactionInvocation(invocation);
    if (!isModelResultEntry(result, compactionInvocation)) {
      throw new IllegalArgumentException(
          compactionInvocation
              ? "model resultEntryId must reference a compaction, assistant-error or"
                  + " assistant-aborted entry for a compaction invocation"
              : "model resultEntryId must reference an assistant, assistant-error or"
                  + " assistant-aborted entry");
    }
    if (resultEntryId.equals(invocation.requestHeadEntryId())) {
      throw new IllegalArgumentException(
          "model result entry must be a strict descendant of the request head entry");
    }
    EntryPath resultPath = loadEntryPath(resultEntryId);
    boolean onBasisPath =
        resultPath.entries().stream()
            .anyMatch(entry -> entry.id().equals(invocation.requestHeadEntryId()));
    boolean sameTurnStart =
        resultPath.entries().stream()
            .anyMatch(entry -> entry.id().equals(invocation.turnStartEntryId()));
    if (!onBasisPath || !sameTurnStart) {
      throw new IllegalArgumentException(
          "model result entry must be on the basis path and in the same turn");
    }
    Boolean used =
        queryForObject(
            """
            select exists (
                select 1
                from harness_model_invocation
                where result_entry_id = ? and id <> ?
            )
            """,
            Boolean.class,
            resultEntryId,
            invocation.id());
    if (Boolean.TRUE.equals(used)) {
      throw new IllegalArgumentException(
          "model resultEntryId " + resultEntryId + " is already used");
    }
  }

  private boolean isCompactionInvocation(ModelInvocation invocation) {
    return requireTurnStartPayload(invocation.turnStartEntryId()).compaction() != null;
  }

  private static boolean isModelResultEntry(Entry entry, boolean compactionInvocation) {
    return switch (entry.payload().type()) {
      case ASSISTANT_ERROR, ASSISTANT_ABORTED -> true;
      case MESSAGE -> !compactionInvocation
          && entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT;
      case COMPACTION -> compactionInvocation;
      default -> false;
    };
  }

  private void requireUniqueToolCallIndex(ToolInvocation invocation) {
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_tool_invocation
                where assistant_entry_id = ? and call_index = ?
            )
            """,
            Boolean.class,
            invocation.assistantEntryId(),
            invocation.callIndex());
    if (Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException(
          "tool invocation callIndex "
              + invocation.callIndex()
              + " already used on assistant entry "
              + invocation.assistantEntryId());
    }
  }

  private void requireValidToolReferences(ToolInvocation invocation) {
    ModelInvocation model =
        findModelInvocation(invocation.modelInvocationId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "model invocation " + invocation.modelInvocationId() + " does not exist"));
    if (model.resultEntryId() == null
        || !model.resultEntryId().equals(invocation.assistantEntryId())) {
      throw new IllegalArgumentException(
          "model invocation resultEntryId must equal the tool assistantEntryId");
    }
    Entry assistant = requireExistingEntry(invocation.assistantEntryId());
    if (!isAssistantEntry(assistant)) {
      throw new IllegalArgumentException(
          "assistantEntryId must reference an assistant MESSAGE entry");
    }
    requireMatchingAssistantToolCall(invocation, assistant);
  }

  private static void requireMatchingAssistantToolCall(ToolInvocation invocation, Entry assistant) {
    MessagePayload message = (MessagePayload) assistant.payload();
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (AgentMessageContent content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    if (invocation.callIndex() >= calls.size()) {
      throw new IllegalArgumentException(
          "tool callIndex " + invocation.callIndex() + " exceeds the assistant tool calls");
    }
    ToolCallMessageContent call = calls.get(invocation.callIndex());
    ToolCall requestCall = invocation.call();
    String expectedRendererKey =
        invocation.binding() == null
            ? HistoryPayloadMapper.UNBOUND_RENDERER_KEY
            : invocation.binding().descriptor().rendererKey();
    if (!call.toolCallId().equals(requestCall.id())
        || !call.toolName().equals(requestCall.toolName())
        || !call.rendererKey().equals(expectedRendererKey)
        || !call.argumentsJson().equals(requestCall.argumentsJson())) {
      throw new IllegalArgumentException(
          "tool call/binding must exactly match the assistant tool call at the same callIndex");
    }
  }

  private static boolean isAssistantEntry(Entry entry) {
    return entry.payload().type() == EntryType.MESSAGE
        && entry.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT;
  }

  private void requireWorkOwnerLocked(WorkTarget target) {
    UUID threadId =
        switch (target.type()) {
          case THREAD -> findThread(target.id())
              .orElseThrow(
                  () -> new IllegalArgumentException("work target does not exist: " + target))
              .id();
          case MODEL -> findModelInvocation(target.id())
              .orElseThrow(
                  () -> new IllegalArgumentException("work target does not exist: " + target))
              .threadId();
          case TOOL -> {
            ToolInvocation tool =
                findToolInvocation(target.id())
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException("work target does not exist: " + target));
            yield findModelInvocation(tool.modelInvocationId())
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "model invocation "
                                + tool.modelInvocationId()
                                + " does not exist for work target "
                                + target))
                .threadId();
          }
        };
    requireLocked(LockKey.thread(threadId));
  }

  private void requireTargetExists(WorkTarget target) {
    String table =
        switch (target.type()) {
          case THREAD -> "harness_thread";
          case MODEL -> "harness_model_invocation";
          case TOOL -> "harness_tool_invocation";
        };
    Boolean exists =
        queryForObject(
            "select exists (select 1 from " + table + " where id = ?)", Boolean.class, target.id());
    if (!Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException("work target does not exist: " + target);
    }
  }

  private Work lockedWork(WorkTarget target) {
    return lockWork(target)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "work does not exist for target " + target + " (lost ownership)"));
  }

  private void updateWork(Work work) {
    int updated =
        update(
            """
            update harness_work
            set available_at = ?,
                wake_version = ?,
                lease_token = ?,
                lease_until = ?
            where target_type = ? and target_id = ?
            """,
            PostgresqlHarnessRows.timestamp(work.availableAt()),
            work.wakeVersion(),
            work.leaseToken(),
            PostgresqlHarnessRows.timestamp(work.leaseUntil()),
            work.target().type().name(),
            work.target().id());
    requireSingleUpdate(updated, "work", work.target().id());
  }

  private static String encodeStreamCheckpoint(ModelInvocation invocation) {
    return invocation.streamCheckpoint() == null
        ? null
        : PostgresqlHarnessRows.STREAM_CHECKPOINTS.encode(invocation.streamCheckpoint());
  }

  private static String encodeModelResult(ModelInvocation invocation) {
    return invocation.result() == null
        ? null
        : PostgresqlHarnessRows.MODEL_RESULTS.encode(invocation.result());
  }

  private static String encodeModelError(ModelInvocation invocation) {
    return invocation.error() == null
        ? null
        : PostgresqlHarnessRows.MODEL_ERRORS.encode(invocation.error());
  }

  private static String encodeToolApproval(ToolInvocation invocation) {
    return invocation.approval() == null
        ? null
        : PostgresqlHarnessRows.TOOL_APPROVALS.encode(invocation.approval());
  }

  private static String encodeToolBinding(ToolInvocation invocation) {
    return invocation.binding() == null
        ? null
        : PostgresqlHarnessRows.TOOL_BINDINGS.encode(invocation.binding());
  }

  private static String encodeToolResult(ToolInvocation invocation) {
    return invocation.result() == null ? null : ToolResultJsonCodec.encode(invocation.result());
  }

  private static String encodeToolEffects(ToolInvocation invocation) {
    return PostgresqlHarnessRows.TOOL_EFFECTS.encode(invocation.effects());
  }

  private static String encodeToolError(ToolInvocation invocation) {
    return invocation.error() == null
        ? null
        : PostgresqlHarnessRows.TOOL_ERRORS.encode(invocation.error());
  }

  private void notifyWorkAvailable() {
    boolean sent =
        queryOne(
                "select pg_notify(?, ?) as ignored, true as sent",
                (resultSet, rowNumber) -> resultSet.getBoolean("sent"),
                PostgresqlWorkChannel.NAME,
                "")
            .orElseThrow(() -> new IllegalStateException("pg_notify returned no row"));
    if (!sent) {
      throw new IllegalStateException("pg_notify did not confirm execution");
    }
  }

  private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... arguments) {
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

  private <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... arguments) {
    try {
      return List.copyOf(jdbc.query(sql, mapper, arguments));
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  private <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
    try {
      return jdbc.queryForObject(sql, requiredType, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  private int update(String sql, Object... arguments) {
    try {
      return jdbc.update(sql, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  private <T> Optional<T> writeOne(String sql, RowMapper<T> mapper, Object... arguments) {
    return queryOne(sql, mapper, arguments);
  }

  /** 记录事务内发生的首个数据库异常（poisoning），以防止脏状态下的继续执行或掩盖初始错误。 */
  private <T extends RuntimeException> T remember(T failure) {
    if (databaseFailure == null) {
      databaseFailure = failure;
    }
    return failure;
  }

  private static IllegalArgumentException integrityViolation(
      DataIntegrityViolationException error) {
    return new IllegalArgumentException("PostgreSQL integrity constraint violation", error);
  }

  /** 严格校验单线程约束与事务活跃状态：只有创建该 handle 的线程才可调用，且已关闭后拒绝执行。 */
  private void checkOpen() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("transaction handle may only be used by its owner thread");
    }
    if (closed) {
      throw new IllegalStateException("transaction is closed");
    }
  }

  /** 锁阶梯单向递增防御：禁止在已获取高阶锁之后回退获取低阶锁。 */
  private void requireCanLockRank(LockRank rank) {
    if (highestLockRank != null && rank.ordinal() < highestLockRank.ordinal()) {
      throw new IllegalStateException(
          "lock order violation: cannot acquire " + rank + " after " + highestLockRank);
    }
  }

  /** 同阶梯加锁排序防御：多个 Thread 必须严格按 UUID 升序加锁。 */
  private void requireCanLockThread(UUID threadId) {
    LockKey key = LockKey.thread(threadId);
    if (locked.contains(key)) {
      return;
    }
    requireCanLock(key);
    if (highestThreadId != null && UuidOrder.COMPARATOR.compare(threadId, highestThreadId) <= 0) {
      throw new IllegalStateException(
          "thread locks must be acquired by ascending id: "
              + highestThreadId
              + " before "
              + threadId);
    }
  }

  private void recordThreadLock(UUID threadId) {
    requireCanLockThread(threadId);
    LockKey key = LockKey.thread(threadId);
    if (!locked.contains(key)) {
      lock(key);
      highestThreadId = threadId;
    }
  }

  private void requireCanLock(LockKey key) {
    if (!locked.contains(key)) {
      requireCanLockRank(key.rank());
    }
  }

  private void lock(LockKey key) {
    requireCanLock(key);
    if (locked.add(key)) {
      highestLockRank = key.rank();
    }
  }

  /** 同阶梯加锁排序防御：同一 assistantEntryId 下的多个 ToolInvocation 必须按 callIndex 严格递增加锁。 */
  private void requireCanLockTools(List<ToolInvocation> invocations) {
    Map<UUID, Integer> callIndexes = new HashMap<>(highestToolCallIndexByAssistant);
    for (ToolInvocation invocation : invocations) {
      LockKey key = LockKey.tool(invocation.id());
      if (locked.contains(key)) {
        continue;
      }
      requireCanLock(key);
      Integer previous = callIndexes.put(invocation.assistantEntryId(), invocation.callIndex());
      if (previous != null && invocation.callIndex() <= previous) {
        throw new IllegalStateException(
            "tool invocation locks for assistant entry "
                + invocation.assistantEntryId()
                + " must be acquired by ascending callIndex");
      }
    }
  }

  private void lockTool(ToolInvocation invocation) {
    requireCanLockTools(List.of(invocation));
    LockKey key = LockKey.tool(invocation.id());
    if (!locked.contains(key)) {
      lock(key);
      highestToolCallIndexByAssistant.put(invocation.assistantEntryId(), invocation.callIndex());
    }
  }

  /** 同阶梯加锁排序防御：多个 Work 目标必须严格按 (targetType.ordinal(), targetId) 升序加锁。 */
  private void requireCanLockWork(WorkTarget target) {
    LockKey key = LockKey.work(target);
    if (locked.contains(key)) {
      return;
    }
    requireCanLock(key);
    if (highestWorkTarget != null && WORK_LOCK_ORDER.compare(target, highestWorkTarget) <= 0) {
      throw new IllegalStateException(
          "work locks must be acquired by ascending (type, id): "
              + highestWorkTarget
              + " before "
              + target);
    }
  }

  /** 验证当前事务是否允许执行 claimNextWork：必须先达到 WORK 锁阶梯，且在此之前不能持有任何其他 Work 锁。 */
  private void requireCanClaimWork() {
    requireCanLockRank(LockRank.WORK);
    if (highestWorkTarget != null) {
      throw new IllegalStateException(
          "claimNextWork must be the first Work lock acquisition in a transaction");
    }
  }

  private void recordWorkLock(WorkTarget target) {
    requireCanLockWork(target);
    LockKey key = LockKey.work(target);
    if (!locked.contains(key)) {
      lock(key);
      highestWorkTarget = target;
    }
  }

  private void requireLocked(LockKey key) {
    if (!locked.contains(key)) {
      throw new IllegalStateException(key + " is not locked in this transaction");
    }
  }

  private static void requireSingleUpdate(int updated, String kind, Object id) {
    if (updated != 1) {
      throw new IllegalArgumentException(kind + " " + id + " does not exist");
    }
  }

  private enum LockRank {
    SESSION,
    THREAD,
    COMMAND,
    MODEL,
    TOOL,
    WORK
  }

  private record LockKey(LockRank rank, String key) {
    static LockKey session(UUID id) {
      return new LockKey(LockRank.SESSION, "session:" + id);
    }

    static LockKey thread(UUID id) {
      return new LockKey(LockRank.THREAD, "thread:" + id);
    }

    static LockKey command(UUID threadId, long sequence) {
      return new LockKey(LockRank.COMMAND, "command:" + threadId + ":" + sequence);
    }

    static LockKey model(UUID id) {
      return new LockKey(LockRank.MODEL, "model:" + id);
    }

    static LockKey tool(UUID id) {
      return new LockKey(LockRank.TOOL, "tool:" + id);
    }

    static LockKey work(WorkTarget target) {
      return new LockKey(LockRank.WORK, "work:" + target.type() + ":" + target.id());
    }
  }

  private record CommandSequenceKey(UUID threadId, long sequence) {}

  private record CommandIdempotencyKey(UUID threadId, UUID idempotencyKey) {}

  private record ToolCallIndexKey(UUID assistantEntryId, int callIndex) {}
}

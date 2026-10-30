package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.EntryStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Entry 聚合的 PostgreSQL 实现。 */
final class PostgresqlEntryStore extends PostgresqlHarnessView implements EntryStore {

  /**
   * 单次不可变 EntryPath 递归读取 CTE：以指定 head 为起点沿父链回溯到 ROOT，按 {@code depth desc} 输出 ROOT 到 head 的有序 Entry。
   *
   * <p>父链由只追加写入、同 Session 复合外键与「非 ROOT 必有父、不得指向自身」约束保证终止于唯一 ROOT，因此不做环路检测。
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
      )
      select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state
      from entry_path
      order by depth desc
      """;

  /**
   * 窄查询递归 CTE：仅提取路径上的 head 与指定 contributorId 的 CUSTOM Entry；head 行用于区分「head 不存在」与「没有状态」。
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
      )
      select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state
      from custom_entry_path
      where depth = 0
         or (entry_type = 'CUSTOM' and payload ->> 'contributorId' = ?)
      order by depth desc
      """;

  /**
   * 窄查询递归 CTE：仅提取路径上的 ROOT、head、SETTINGS 及非 COMPACTION 的 TURN_START Entry。
   *
   * <p>用于解析 branch 生效 settings（等价于 {@link EntryPath#baseSettings()}）：递归仍遍历祖先链，但只返回决定 settings
   * 的必要节点， 减少结果传输与 Java 侧完整 EntryPath 物化，其结果绝不回填完整路径缓存。COMPACTION turn 的 settings 只描述压缩执行模型，因此在 SQL
   * 侧就被排除，绝不参与 branch settings 解析；安全边界上 append 的 SETTINGS 快照则与 TURN_START 一样参与解析。
   */
  private static final String LOAD_BRANCH_SETTINGS =
      """
      with recursive branch_settings_path as (
          select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state, 0 as depth
          from harness_entry
          where id = ?
          union all
          select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, e.provider_replay_state, bsp.depth + 1
          from harness_entry e
          join branch_settings_path bsp on e.id = bsp.parent_entry_id
          where bsp.parent_entry_id is not null
      )
      select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state
      from branch_settings_path
      where depth = 0
         or entry_type = 'ROOT'
         or entry_type = 'SETTINGS'
         or (entry_type = 'TURN_START' and payload ->> 'reason' <> 'COMPACTION')
      order by depth desc
      """;

  PostgresqlEntryStore(PostgresqlHarnessTransaction tx) {
    super(tx);
  }

  /**
   * 插入新的 Entry 节点并同步更新事务内 EntryPath 局部缓存。
   *
   * <p>若为非 ROOT Entry，从父节点已缓存的路径直接内存追加派生子节点的 {@link EntryPath} 并写入 {@link
   * #entryPathCache()}，使同事务内后续子节点构建达到 0 次递归 CTE 开销。
   */
  @Override
  public void insert(Entry entry) {
    checkOpen();
    Objects.requireNonNull(entry, "entry");
    if (tx.entries().find(entry.id()).isPresent()) {
      throw new IllegalArgumentException("duplicate entry id " + entry.id());
    }
    if (tx.sessions().find(entry.sessionId()).isEmpty()) {
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
      EntryPath parentPath = tx.entries().loadPath(entry.parentEntryId());
      List<Entry> nextPath = new ArrayList<>(parentPath.entries());
      nextPath.add(entry);
      candidatePath = new EntryPath(nextPath);
    }
    updateJdbc(
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
    entryPathCache().put(entry.id(), candidatePath);
  }

  private static String encodeEntryProviderReplayState(Entry entry) {
    return entry.providerReplayState() == null
        ? null
        : PostgresqlHarnessRows.PROVIDER_REPLAY_STATES.encode(entry.providerReplayState());
  }

  @Override
  public Optional<Entry> find(UUID id) {
    checkOpen();
    return queryOne("select * from harness_entry where id = ?", PostgresqlHarnessRows.ENTRY, id);
  }

  /**
   * 查找指定 Session 的 ROOT Entry。
   *
   * <p>优先复用当前事务局部缓存 {@link #entryPathCache()} 中已有的同 Session 路径根节点；未命中时通过 {@code
   * uk_harness_entry_single_root} 索引点查，并将单节点根路径写回缓存。
   */
  @Override
  public Optional<Entry> findRoot(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    for (EntryPath path : entryPathCache().values()) {
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
    found.ifPresent(root -> entryPathCache().putIfAbsent(root.id(), new EntryPath(List.of(root))));
    return found;
  }

  /**
   * 读取指定 head 的完整不可变 EntryPath。
   *
   * <p>优先从 {@link #entryPathCache()} 读取；未命中时执行单次递归回溯 CTE（{@link #LOAD_ENTRY_PATH}）并存入缓存。
   */
  @Override
  public EntryPath loadPath(UUID headEntryId) {
    checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = entryPathCache().get(headEntryId);
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
    entryPathCache().put(headEntryId, path);
    return path;
  }

  /**
   * 加载指定 head 路径上属于特定 Contributor 的自定义 Entry。
   *
   * <p>若完整路径在 {@link #entryPathCache()} 中已命中，直接在内存中过滤返回；cold cache 则执行窄查询 CTE（{@link
   * #LOAD_CONTRIBUTOR_CUSTOM_ENTRIES_ON_PATH}），部分投影结果绝不写入完整路径缓存。
   */
  @Override
  public List<Entry> loadContributorCustom(UUID headEntryId, String contributorId) {
    checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(contributorId, "contributorId");
    EntryPath cached = entryPathCache().get(headEntryId);
    if (cached != null) {
      return cached.entries().stream()
          .filter(
              entry ->
                  entry.payload() instanceof CustomEntryPayload custom
                      && contributorId.equals(custom.contributorId()))
          .toList();
    }
    List<Entry> rows =
        queryList(
            LOAD_CONTRIBUTOR_CUSTOM_ENTRIES_ON_PATH,
            PostgresqlHarnessRows.ENTRY,
            headEntryId,
            contributorId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    return rows.stream()
        .filter(
            entry ->
                entry.payload() instanceof CustomEntryPayload custom
                    && contributorId.equals(custom.contributorId()))
        .toList();
  }

  /**
   * 读取指定 head 所在 branch 的生效 settings（等价于 {@link EntryPath#baseSettings()}）。
   *
   * <p>若完整路径在 {@link #entryPathCache()} 中已命中，直接在内存中派生；cold cache 则执行窄查询 CTE（{@link
   * #LOAD_BRANCH_SETTINGS}），只返回 ROOT 与非 COMPACTION TURN_START 节点，绝不物化完整 EntryPath、也绝不把部分投影写入完整路径缓存。
   */
  @Override
  public BranchSettings loadBranchSettings(UUID headEntryId) {
    checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = entryPathCache().get(headEntryId);
    if (cached != null) {
      return cached.baseSettings();
    }
    List<Entry> rows = queryList(LOAD_BRANCH_SETTINGS, PostgresqlHarnessRows.ENTRY, headEntryId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    // 结果按 ROOT 到 head 排列：从 head 端回看，首个 settings 载体就是离 head 最近的生效快照。
    for (int i = rows.size() - 1; i >= 0; i--) {
      EntryPayload payload = rows.get(i).payload();
      if (payload instanceof TurnStartPayload start) {
        if (start.reason() != TurnStartReason.COMPACTION) {
          return start.settings();
        }
      } else if (payload instanceof SettingsPayload applied) {
        return applied.settings();
      } else if (payload instanceof RootPayload root) {
        return root.settings();
      }
    }
    throw new IllegalArgumentException("entry path for " + headEntryId + " carries no settings");
  }

  @Override
  public List<Entry> loadBySession(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (tx.sessions().find(sessionId).isEmpty()) {
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
  public Optional<Entry> findToolResultByInvocationId(UUID sessionId, UUID invocationId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(invocationId, "invocationId");
    return queryOne(
        """
        select *
        from harness_entry
        where session_id = ?
          and entry_type = 'MESSAGE'
          and payload -> 'toolResultMetadata' ->> 'invocationId' = ?
        """,
        PostgresqlHarnessRows.ENTRY,
        sessionId,
        invocationId.toString());
  }

  /** 删除指定 Session 下的全部 Entry 节点（叶子优先逐层删除），并同步驱逐事务内缓存。 */
  @Override
  public int delete(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    entryPathCache().values().removeIf(path -> path.root().sessionId().equals(sessionId));
    int total = 0;
    while (true) {
      // 叶子优先：同一语句只删除父不在批内的行（parent FK 顺序天然成立），逐层剥到 ROOT。
      int deleted =
          updateJdbc(
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
}

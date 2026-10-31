package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.EntrySlice;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.EntryStore;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Entry 聚合的 PostgreSQL 实现。 */
final class PostgresqlEntryStore implements EntryStore {

  private final PostgresqlHarnessTransaction tx;

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
   * 单次增量回溯 CTE：从 head 沿父链向上，遇到游标或 ROOT 即停。
   *
   * <p>命中游标时只返回其后的节点，不含游标与 ROOT；走到 ROOT 时返回完整链。结果不回填完整路径缓存。
   */
  private static final String LOAD_ENTRY_SLICE =
      """
      with recursive entry_slice as (
          select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state, 1 as depth
          from harness_entry
          where id = ?
          union all
          select parent.id, parent.session_id, parent.parent_entry_id, parent.entry_type, parent.payload,
                 parent.created_at, parent.provider_replay_state, es.depth + 1
          from entry_slice es
          join lateral (
              select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state
              from harness_entry
              where id = es.parent_entry_id
          ) parent on true
          where es.id is distinct from ?::uuid
            and es.entry_type <> 'ROOT'
      )
      select id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state
      from entry_slice
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

  /**
   * 冷 current 的定位递归：只携带 id、parent、类型和停止所需的 payload 字段，不读取 payload 正文。
   *
   * <p>head、非控制锚点、open 或已关闭 Turn 引用、以及最近真实 settings 齐备后立即停止。COMPACTION 是回合而不是控制 Entry，其 settings
   * 不参与锚点。结果不回填完整路径缓存。
   */
  /**
   * 单语句冷 current 读取：递归只携带定位 id 与 depth，head 也由递归步统一处理。
   *
   * <p>head、非控制锚点、open 或已关闭 Turn 引用、以及最近真实 settings 齐备后立即停止。COMPACTION 是回合而不是控制 Entry，其 settings
   * 不参与真实模型。最终 join 只返回 head 与锚点行的完整列，按 depth 升序（head 在前），结果不回填完整路径缓存。
   */
  private static final String LOAD_CURRENT =
      """
      with recursive current_anchor as (
          select
              cast(null as uuid) as id,
              cast(? as uuid) as parent_entry_id,
              -1 as depth,
              cast(null as uuid) as non_control_id,
              cast(null as uuid) as turn_id,
              cast(null as uuid) as settings_id,
              cast(null as uuid) as needed_ref,
              cast(null as uuid) as reference_id
          union all
          select
              e.id,
              e.parent_entry_id,
              a.depth + 1,
              coalesce(
                  a.non_control_id,
                  case
                      when e.entry_type not in ('NOTIFICATION', 'SETTINGS', 'FORK') then e.id
                  end
              ),
              coalesce(
                  a.turn_id,
                  case
                      when e.entry_type in ('TURN_START', 'TURN_END') then e.id
                  end
              ),
              coalesce(
                  a.settings_id,
                  case
                      when e.entry_type in ('ROOT', 'SETTINGS')
                        or (e.entry_type = 'TURN_START' and e.payload ->> 'reason' <> 'COMPACTION') then e.id
                  end
              ),
              coalesce(
                  a.needed_ref,
                  case
                      when a.non_control_id is null and e.entry_type = 'TURN_END'
                          then cast(e.payload ->> 'turnStartEntryId' as uuid)
                  end
              ),
              coalesce(
                  a.reference_id,
                  case
                      when a.needed_ref is not null and e.id = a.needed_ref and e.entry_type = 'TURN_START'
                          then e.id
                  end
              )
          from current_anchor a
          join lateral (
              select id, parent_entry_id, entry_type, payload
              from harness_entry
              where id = a.parent_entry_id
          ) e on true
          where a.non_control_id is null
             or a.turn_id is null
             or a.settings_id is null
             or (a.needed_ref is not null and a.reference_id is null)
      )
      select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, e.provider_replay_state
      from current_anchor a
      join harness_entry e on e.id = a.id
      where a.depth = 0
         or a.id = a.non_control_id
         or a.id = a.turn_id
         or a.id = a.settings_id
         or a.id = a.reference_id
      order by a.depth asc
      """;

  /**
   * 祖先计数：递归工作表只携带逐行标量，不携带 payload，由种子行统一表达式。
   *
   * <p>INPUT 与 CONTINUATION 计一轮；COMPACTION 与 STOP 不计。工具调用只计 ASSISTANT MESSAGE 中的 {@code tool_call}。
   */
  private static final String LOAD_PATH_METRICS =
      """
      with recursive metric_path as (
          select
              cast(null as uuid) as id,
              cast(? as uuid) as parent_entry_id,
              -1 as depth,
              0 as turn_inc,
              0 as tool_inc
          union all
          select
              e.id,
              e.parent_entry_id,
              m.depth + 1,
              case
                  when e.entry_type = 'TURN_START'
                   and e.payload ->> 'reason' in ('INPUT', 'CONTINUATION') then 1
                  else 0
              end,
              case
                  when e.entry_type = 'MESSAGE' and e.payload #>> '{message,role}' = 'ASSISTANT'
                      then jsonb_array_length(jsonb_path_query_array(e.payload, '$.message.contents[*] ? (@.type == "tool_call")'))
                  else 0
              end
          from metric_path m
          join lateral (
              select id, parent_entry_id, entry_type, payload
              from harness_entry
              where id = m.parent_entry_id
          ) e on true
      )
      select
          coalesce(sum(turn_inc), 0)::int as turn_count,
          coalesce(sum(tool_inc), 0)::int as tool_call_count,
          count(id)::int as entry_count
      from metric_path
      where depth >= 0
      """;

  /**
   * 祖先判定：只比较 id 与 parent，命中 candidate 或到达 ROOT 即停。
   *
   * <p>head 自身也是祖先。外层返回包含计数与祖先命中的聚合行，count=0 时按 head 缺失抛出。
   */
  private static final String LOAD_ANCESTOR =
      """
      with recursive ancestor_path as (
          select id, parent_entry_id
          from harness_entry
          where id = ?
          union all
          select parent.id, parent.parent_entry_id
          from ancestor_path a
          join lateral (
              select id, parent_entry_id, entry_type
              from harness_entry
              where id = a.parent_entry_id
          ) parent on true
          where a.id is distinct from ?::uuid
            and a.parent_entry_id is not null
      )
      select
          count(id)::int as entry_count,
          coalesce(bool_or(id = ?::uuid), false) as is_ancestor
      from ancestor_path
      """;

  PostgresqlEntryStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  /**
   * 插入新的 Entry 节点并同步更新事务内 EntryPath 局部缓存。
   *
   * <p>若为非 ROOT Entry，从父节点已缓存的路径直接内存追加派生子节点的 {@link EntryPath} 并写入事务局部 EntryPath 缓存，使同事务内后续子节点构建达到
   * 0 次递归 CTE 开销。
   */
  @Override
  public void insert(Entry entry) {
    tx.checkOpen();
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
    tx.updateJdbc(
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
    tx.entryPathCache().put(entry.id(), candidatePath);
  }

  private static String encodeEntryProviderReplayState(Entry entry) {
    return entry.providerReplayState() == null
        ? null
        : PostgresqlHarnessRows.PROVIDER_REPLAY_STATES.encode(entry.providerReplayState());
  }

  @Override
  public Optional<Entry> find(UUID id) {
    tx.checkOpen();
    return tx.queryOne("select * from harness_entry where id = ?", PostgresqlHarnessRows.ENTRY, id);
  }

  /**
   * 查找指定 Session 的 ROOT Entry。
   *
   * <p>优先复用事务局部 EntryPath 缓存中已有的同 Session 路径根节点；未命中时通过 {@code uk_harness_entry_single_root}
   * 索引点查，并将单节点根路径写回缓存。
   */
  @Override
  public Optional<Entry> findRoot(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    for (EntryPath path : tx.entryPathCache().values()) {
      if (path.root().sessionId().equals(sessionId)) {
        return Optional.of(path.root());
      }
    }
    Optional<Entry> found =
        tx.queryOne(
            """
            select *
            from harness_entry
            where session_id = ? and entry_type = 'ROOT'
            """,
            PostgresqlHarnessRows.ENTRY,
            sessionId);
    found.ifPresent(
        root -> tx.entryPathCache().putIfAbsent(root.id(), new EntryPath(List.of(root))));
    return found;
  }

  /**
   * 读取指定 head 的完整不可变 EntryPath。
   *
   * <p>优先从事务局部缓存读取；未命中时执行单次递归回溯 CTE（{@link #LOAD_ENTRY_PATH}）并存入缓存。
   */
  @Override
  public EntryPath loadPath(UUID headEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = tx.entryPathCache().get(headEntryId);
    if (cached != null) {
      return cached;
    }
    List<Entry> entries = tx.queryList(LOAD_ENTRY_PATH, PostgresqlHarnessRows.ENTRY, headEntryId);
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
    tx.entryPathCache().put(headEntryId, path);
    return path;
  }

  /**
   * 读取分类所需的当前状态。
   *
   * <p>完整路径已缓存时直接派生。冷缓存先递归定位必要锚点，再按 id 点读这些 Entry 的 payload；不调用 {@link
   * #loadPath(UUID)}，也不把窄结果写入完整路径缓存。
   */
  @Override
  public EntryCurrentState loadCurrent(UUID headEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = tx.entryPathCache().get(headEntryId);
    if (cached != null) {
      return EntryCurrentState.from(cached);
    }
    List<Entry> entries = tx.queryList(LOAD_CURRENT, PostgresqlHarnessRows.ENTRY, headEntryId);
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    Entry head = entries.get(0);
    Entry nonControl = null;
    Entry turnAnchor = null;
    Entry settingsAnchor = null;
    UUID neededRef = null;
    for (Entry entry : entries) {
      if (nonControl == null && !isControl(entry)) {
        nonControl = entry;
        if (entry.payload() instanceof TurnEndPayload end) {
          neededRef = end.turnStartEntryId();
        }
      }
      if (turnAnchor == null
          && (entry.payload() instanceof TurnStartPayload
              || entry.payload() instanceof TurnEndPayload)) {
        turnAnchor = entry;
      }
      if (settingsAnchor == null && settingsOf(entry) != null) {
        settingsAnchor = entry;
      }
    }
    Entry referenced = null;
    if (neededRef != null) {
      for (Entry entry : entries) {
        if (entry.id().equals(neededRef) && entry.payload() instanceof TurnStartPayload) {
          referenced = entry;
          break;
        }
      }
    }
    Entry open =
        turnAnchor != null && turnAnchor.payload() instanceof TurnStartPayload ? turnAnchor : null;
    return new EntryCurrentState(head, nonControl, open, referenced, settingsOf(settingsAnchor));
  }

  /**
   * 聚合 root-to-head 的精确轮次与工具调用计数。
   *
   * <p>完整路径已缓存时使用参考算法。冷缓存由 SQL 聚合标量，不在 Java 物化历史正文，也不写完整路径缓存。
   */
  @Override
  public EntryPathMetrics loadPathMetrics(UUID headEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = tx.entryPathCache().get(headEntryId);
    if (cached != null) {
      return EntryPathMetrics.from(cached.entries());
    }
    MetricsRow metrics =
        tx.queryOne(LOAD_PATH_METRICS, PostgresqlEntryStore::metricsRow, headEntryId).orElseThrow();
    if (metrics.entryCount() == 0) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    return new EntryPathMetrics(metrics.turnCount(), metrics.toolCallCount());
  }

  /**
   * 判断 candidate 是否位于 head 的祖先链上，含 head 自身。
   *
   * <p>完整路径已缓存时直接比较。冷查询命中目标即停；未知或其它分支返回 false，head 缺失沿完整路径读取拒绝。
   */
  @Override
  public boolean isAncestor(UUID headEntryId, UUID candidateEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(candidateEntryId, "candidateEntryId");
    EntryPath cached = tx.entryPathCache().get(headEntryId);
    if (cached != null) {
      for (Entry entry : cached.entries()) {
        if (entry.id().equals(candidateEntryId)) {
          return true;
        }
      }
      return false;
    }
    AncestorRow row =
        tx.queryOne(
                LOAD_ANCESTOR,
                PostgresqlEntryStore::ancestorRow,
                headEntryId,
                candidateEntryId,
                candidateEntryId)
            .orElseThrow();
    if (row.entryCount() == 0) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    return row.isAncestor();
  }

  /**
   * 读取 head 相对游标的增量链。
   *
   * <p>完整路径已在本事务缓存中时直接截取，不查询。冷缓存只执行一条在游标处停止的递归 CTE，不先 {@link #loadPath(UUID)}，也不把部分结果写入完整路径缓存。
   */
  @Override
  public EntrySlice loadSlice(UUID headEntryId, UUID afterEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = tx.entryPathCache().get(headEntryId);
    if (cached != null) {
      return slice(cached.entries(), afterEntryId);
    }
    if (afterEntryId == null) {
      return new EntrySlice(null, loadPath(headEntryId).entries());
    }
    List<Entry> rows =
        tx.queryList(LOAD_ENTRY_SLICE, PostgresqlHarnessRows.ENTRY, headEntryId, afterEntryId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("entry " + headEntryId + " does not exist");
    }
    boolean ancestor = rows.get(0).id().equals(afterEntryId);
    List<Entry> entries = ancestor ? rows.subList(1, rows.size()) : rows;
    return new EntrySlice(ancestor ? afterEntryId : null, entries);
  }

  private static EntrySlice slice(List<Entry> rootToHead, UUID afterEntryId) {
    if (afterEntryId == null) {
      return new EntrySlice(null, rootToHead);
    }
    for (int i = 0; i < rootToHead.size(); i++) {
      if (rootToHead.get(i).id().equals(afterEntryId)) {
        return new EntrySlice(afterEntryId, rootToHead.subList(i + 1, rootToHead.size()));
      }
    }
    return new EntrySlice(null, rootToHead);
  }

  /**
   * 加载指定 head 路径上属于特定 Contributor 的自定义 Entry。
   *
   * <p>若完整路径已在事务局部缓存中命中，直接在内存中过滤返回；cold cache 则执行窄查询 CTE（{@link
   * #LOAD_CONTRIBUTOR_CUSTOM_ENTRIES_ON_PATH}），部分投影结果绝不写入完整路径缓存。
   */
  @Override
  public List<Entry> loadContributorCustom(UUID headEntryId, String contributorId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(contributorId, "contributorId");
    EntryPath cached = tx.entryPathCache().get(headEntryId);
    if (cached != null) {
      return cached.entries().stream()
          .filter(
              entry ->
                  entry.payload() instanceof CustomEntryPayload custom
                      && contributorId.equals(custom.contributorId()))
          .toList();
    }
    List<Entry> rows =
        tx.queryList(
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
   * <p>若完整路径已在事务局部缓存中命中，直接在内存中派生；cold cache 则执行窄查询 CTE（{@link #LOAD_BRANCH_SETTINGS}），只返回 ROOT 与非
   * COMPACTION TURN_START 节点，绝不物化完整 EntryPath、也绝不把部分投影写入完整路径缓存。
   */
  @Override
  public BranchSettings loadBranchSettings(UUID headEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    EntryPath cached = tx.entryPathCache().get(headEntryId);
    if (cached != null) {
      return cached.baseSettings();
    }
    List<Entry> rows = tx.queryList(LOAD_BRANCH_SETTINGS, PostgresqlHarnessRows.ENTRY, headEntryId);
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
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (tx.sessions().find(sessionId).isEmpty()) {
      throw new IllegalArgumentException("session " + sessionId + " does not exist");
    }
    return tx.queryList(
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
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(invocationId, "invocationId");
    return tx.queryOne(
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
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    tx.entryPathCache().values().removeIf(path -> path.root().sessionId().equals(sessionId));
    int total = 0;
    while (true) {
      // 叶子优先：同一语句只删除父不在批内的行（parent FK 顺序天然成立），逐层剥到 ROOT。
      int deleted =
          tx.updateJdbc(
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

  private static boolean isControl(Entry entry) {
    return entry.payload() instanceof NotificationPayload
        || entry.payload() instanceof SettingsPayload
        || entry.payload() instanceof ForkPayload;
  }

  private static BranchSettings settingsOf(Entry entry) {
    if (entry.payload() instanceof TurnStartPayload start
        && start.reason() != TurnStartReason.COMPACTION) {
      return start.settings();
    }
    if (entry.payload() instanceof SettingsPayload applied) {
      return applied.settings();
    }
    if (entry.payload() instanceof RootPayload root) {
      return root.settings();
    }
    return null;
  }

  private static AncestorRow ancestorRow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new AncestorRow(resultSet.getInt("entry_count"), resultSet.getBoolean("is_ancestor"));
  }

  private static MetricsRow metricsRow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new MetricsRow(
        resultSet.getInt("turn_count"),
        resultSet.getInt("tool_call_count"),
        resultSet.getInt("entry_count"));
  }

  private record AncestorRow(int entryCount, boolean isAncestor) {}

  private record MetricsRow(int turnCount, int toolCallCount, int entryCount) {}

  private boolean hasRoot(UUID sessionId) {
    Boolean exists =
        tx.queryForObject(
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

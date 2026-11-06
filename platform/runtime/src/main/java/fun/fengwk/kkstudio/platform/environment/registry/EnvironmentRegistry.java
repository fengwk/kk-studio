package fun.fengwk.kkstudio.platform.environment.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonLeaseStore;
import fun.fengwk.kkstudio.platform.daemon.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.EnvironmentChangeNotifier;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 PostgreSQL 路由租约表 {@code environment_connection} 的多节点 Environment 连接注册表：Environment server core
 * 的 lease-store 适配实现。
 *
 * <p>以 canonical {@link EnvironmentId} 为键。握手准入认证时通过单条 PostgreSQL upsert 语句原子抢占租约：缺少或过期路由以新 {@code
 * leaseToken} 接管；活跃路由返回 {@link LeaseBindResult.RetryLater}。断开连接时保留重连宽限期，允许原连接重新认领。
 *
 * <p>READY、HEARTBEAT 与断开连接均以 {@code (environment_id, owner_node_id, lease_token)} 为围栏更新，
 * 保证只有当前持有该路由租约的本地连接能推进状态；所有围栏更新在租约失效时返回 false（fail-closed）。
 *
 * <p>连接生命周期事件（CONNECTING/READY/DISCONNECTED）在推进状态的同一条围栏语句里原子追加到 {@code recent_events}， 数组始终是 200
 * 条以内的按时间正序窗口；READY 同时把 {@code skill_state} 清空，随后由 Skill 同步编排器全量重建。
 *
 * <p>状态、宿主信息、Skill 投影或运维事件的围栏写与 {@code environment_changed} 在同一事务提交；0 行围栏与拒绝结果静默，
 * 发布失败会回滚围栏写。HEARTBEAT 只续租，不发布通知。内存运行状态变化时先更新快照，再在 READY 租约围栏下发布失效提示。 自然到期没有数据库写事件，仍由读取时判定与截止点对账处理。
 */
@Slf4j
@Component
public class EnvironmentRegistry implements DaemonLeaseStore {

  private static final String TRY_ACQUIRE_SQL =
      """
      with locked_env as (
          select id, (registration_token_hash = :tokenHash) as token_valid
          from environment
          where id = :environmentId
          for update
      ),
      tried_upsert as (
          insert into environment_connection (
              environment_id, owner_node_id, lease_token,
              status, runtime_info, recent_events, last_seen_at, lease_until
          )
          select
              id, :ownerNodeId, :leaseToken,
              'CONNECTING', null, jsonb_build_array(:connectingEvent::jsonb), statement_timestamp(), statement_timestamp() + (:leaseMillis * interval '1 millisecond')
          from locked_env
          where token_valid = true
          on conflict (environment_id) do update
          set owner_node_id = excluded.owner_node_id,
              lease_token = excluded.lease_token,
              status = 'CONNECTING',
              recent_events = case
                  when jsonb_array_length(environment_connection.recent_events) >= :maxRecentEvents
                      then (environment_connection.recent_events - 0) || excluded.recent_events
                      else environment_connection.recent_events || excluded.recent_events
                  end,
              last_seen_at = statement_timestamp(),
              lease_until = statement_timestamp() + (:leaseMillis * interval '1 millisecond')
          where environment_connection.lease_until <= statement_timestamp()
             or (environment_connection.status = 'CONNECTING' and environment_connection.owner_node_id = excluded.owner_node_id)
          returning environment_connection.lease_token, true as acquired
      )
      select lease_token, true as acquired, 'ACQUIRED' as result_type
      from tried_upsert
      union all
      select null::uuid as lease_token, false as acquired,
             case
                when not exists (select 1 from locked_env) then 'NOT_FOUND'
                when not (select token_valid from locked_env) then 'INVALID_TOKEN'
                else 'ACTIVE_ROUTE'
             end as result_type
      from (select 1) as dummy
      where not exists (select 1 from tried_upsert)
      """;

  /**
   * 围栏式登记 READY：推进行状态、写入最近一次被接受的宿主 metadata、清空 Skill 同步投影并追加一条 READY 事件。
   *
   * <p>只有当前 owner + lease 且未过期的连接行才能推进 READY；围栏失效时既不推进状态也不改写 runtime_info。离线或重新 CONNECTING
   * 都不清空该保留事实；skill_state 每次都先清空，随后由 Skill 同步编排器全量重建。
   */
  private static final String MARK_READY_SQL =
      """
      update environment_connection
      set status = 'READY',
          runtime_info = :runtimeInfo::jsonb,
          skill_state = '[]'::jsonb,
          recent_events = case
              when jsonb_array_length(recent_events) >= :maxRecentEvents
                  then (recent_events - 0) || :readyEvent::jsonb
                  else recent_events || :readyEvent::jsonb
              end,
          last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (:leaseMillis * interval '1 millisecond')
      where environment_id = :environmentId
        and owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and lease_until > statement_timestamp()
      """;

  private static final String HEARTBEAT_SQL =
      """
      update environment_connection
      set last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (:leaseMillis * interval '1 millisecond')
      where environment_id = :environmentId
        and owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and lease_until > statement_timestamp()
      """;

  private static final String DISCONNECT_SQL =
      """
      update environment_connection
      set status = 'CONNECTING',
          recent_events = case
              when jsonb_array_length(recent_events) >= :maxRecentEvents
                  then (recent_events - 0) || :disconnectedEvent::jsonb
                  else recent_events || :disconnectedEvent::jsonb
              end,
          last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (:leaseMillis * interval '1 millisecond')
      where environment_id = :environmentId
        and owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and lease_until > statement_timestamp()
      """;

  private static final String RELEASE_SQL =
      """
      update environment_connection
      set status = 'CONNECTING',
          recent_events = case
              when jsonb_array_length(recent_events) >= :maxRecentEvents
                  then (recent_events - 0) || :releasedEvent::jsonb
                  else recent_events || :releasedEvent::jsonb
              end,
          last_seen_at = statement_timestamp() - interval '1 millisecond',
          lease_until = statement_timestamp()
      where environment_id = :environmentId
        and owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and lease_until > statement_timestamp()
      """;

  private static final String FIND_SQL =
      """
      select environment_id, owner_node_id, lease_token,
             status, runtime_info, skill_state, recent_events, last_seen_at, lease_until
      from environment_connection
      where environment_id = :environmentId
      """;

  private static final String HAS_ACTIVE_LEASE_SQL =
      """
      select exists (
          select 1 from environment_connection
          where environment_id = :environmentId
            and lease_until > statement_timestamp()
      )
      """;

  private static final String HOLDS_READY_LEASE_SQL =
      """
      select exists (
          select 1 from environment_connection
          where environment_id = :environmentId
            and owner_node_id = :ownerNodeId
            and lease_token = :leaseToken
            and status = 'READY'
            and lease_until > statement_timestamp()
      )
      """;

  /** 先取得行锁，再按数据库当前时间复核围栏，避免等待锁期间租约过期后仍发布。 */
  private static final String LOCK_READY_LEASE_SQL =
      """
      with locked as materialized (
          select owner_node_id, lease_token, status, lease_until
          from environment_connection
          where environment_id = :environmentId
          for update
      )
      select 1 from locked
      where owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and status = 'READY'
        and lease_until > clock_timestamp()
      """;

  private static final String HAS_ACTIVE_LEASE_TOKEN_SQL =
      """
      select exists (
          select 1 from environment_connection
          where environment_id = :environmentId
            and owner_node_id = :ownerNodeId
            and lease_token = :leaseToken
            and lease_until > statement_timestamp()
      )
      """;

  private static final String LIST_SQL =
      """
      select environment_id, owner_node_id, lease_token,
             status, runtime_info, skill_state, recent_events, last_seen_at, lease_until
      from environment_connection
      order by environment_id asc
      """;

  /** 本节点当前持有未过期 READY 租约的 Environment 行，供 Skill 同步与通知重连对账定位目标。 */
  private static final String LIST_READY_OWNED_SQL =
      """
      select environment_id, owner_node_id, lease_token,
             status, runtime_info, skill_state, recent_events, last_seen_at, lease_until
      from environment_connection
      where owner_node_id = :ownerNodeId
        and status = 'READY'
        and lease_until > statement_timestamp()
      order by environment_id asc
      """;

  /** 只追加一条运维事件的围栏写：锁定的必须是本节点未过期的 READY 租约。 */
  private static final String APPEND_EVENT_SQL =
      """
      update environment_connection
      set recent_events = case
              when jsonb_array_length(recent_events) >= :maxRecentEvents
                  then (recent_events - 0) || :event::jsonb
                  else recent_events || :event::jsonb
              end
      where environment_id = :environmentId
        and owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and status = 'READY'
        and lease_until > statement_timestamp()
      """;

  /** 同一围栏下原子替换 Skill 同步投影并追加一条事件的写。 */
  private static final String REPLACE_SKILL_STATE_SQL =
      """
      update environment_connection
      set skill_state = :skillState::jsonb,
          recent_events = case
              when jsonb_array_length(recent_events) >= :maxRecentEvents
                  then (recent_events - 0) || :event::jsonb
                  else recent_events || :event::jsonb
              end
      where environment_id = :environmentId
        and owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and status = 'READY'
        and lease_until > statement_timestamp()
      """;

  private final JdbcClient jdbcClient;
  private final UUID ownerNodeId;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final EnvironmentChangeNotifier notifier;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final EnvironmentStateCodec stateCodec = new EnvironmentStateCodec();

  public EnvironmentRegistry(
      JdbcClient jdbcClient,
      @Qualifier("nodeInstanceId") UUID ownerNodeId,
      Clock clock,
      PlatformTransactionManager transactionManager,
      EnvironmentChangeNotifier notifier) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  /**
   * 在真实短事务内执行围栏写，只有实际写入（受影响行数 > 0）才在同一事务连接上发布 {@code environment_changed}。
   *
   * <p>围栏失效（0 行）静默；发布抛出的数据库异常会回滚整条事务并向上传播，调用方无法看到「围栏已提交但通知失败」的中间态。
   */
  private boolean fencedWrite(EnvironmentId environmentId, Supplier<Integer> fence) {
    return transactionTemplate.execute(
        status -> {
          int rows = fence.get();
          if (rows > 0) {
            notifier.environmentChanged(environmentId.value());
          }
          return rows > 0;
        });
  }

  public UUID ownerNodeId() {
    return ownerNodeId;
  }

  /** 构造一条连接生命周期事件；{@code message} 必须是程序构造的固定事实，绝不承载 Daemon 输入。 */
  private EnvironmentEvent lifecycleEvent(String level, String type, String message) {
    return new EnvironmentEvent(clock.instant(), level, type, message);
  }

  /**
   * 在握手准入认证时，原子校验 registrationToken 并抢占/续约 {@code environmentId} 的路由租约。
   *
   * @param environmentId 环境 UUID
   * @param registrationToken 注册令牌
   * @param leaseDuration 租约时长
   * @return 类型化绑定结果（Acquired / RetryLater / Rejected）
   */
  @Override
  public LeaseBindResult tryAcquire(
      EnvironmentId environmentId, String registrationToken, Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(registrationToken, "registrationToken");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    UUID newLeaseToken = UUID.randomUUID();
    long millis = leaseDuration.toMillis();
    String connectingEvent =
        stateCodec.encodeEvent(
            lifecycleEvent(
                EnvironmentEvent.LEVEL_INFO,
                EnvironmentEvent.TYPE_CONNECTING,
                "daemon connection accepted"));
    // 只有真实 upsert 成功（Acquired）才在提交前发布；认证拒绝与活跃租约抢占失败都不写行，保持静默。
    Optional<AcquireRow> acquired =
        transactionTemplate.execute(
            status -> {
              List<AcquireRow> rows =
                  jdbcClient
                      .sql(TRY_ACQUIRE_SQL)
                      .param("tokenHash", RegistrationTokens.hash(registrationToken))
                      .param("environmentId", environmentId.value())
                      .param("ownerNodeId", ownerNodeId)
                      .param("leaseToken", newLeaseToken)
                      .param("connectingEvent", connectingEvent)
                      .param("leaseMillis", millis)
                      .param("maxRecentEvents", EnvironmentConnection.MAX_RECENT_EVENTS)
                      .query(
                          (rs, rowNum) ->
                              new AcquireRow(
                                  (UUID) rs.getObject("lease_token"),
                                  rs.getBoolean("acquired"),
                                  rs.getString("result_type")))
                      .list();
              if (rows.isEmpty()) {
                return Optional.<AcquireRow>empty();
              }
              AcquireRow candidate = rows.get(0);
              if (candidate.acquired() && candidate.leaseToken() != null) {
                notifier.environmentChanged(environmentId.value());
              }
              return Optional.of(candidate);
            });

    if (acquired.isEmpty()) {
      return new LeaseBindResult.RetryLater("route acquisition returned no row: " + environmentId);
    }
    AcquireRow row = acquired.get();
    if (row.acquired() && row.leaseToken() != null) {
      return new LeaseBindResult.Acquired(row.leaseToken());
    }
    return switch (row.resultType()) {
      case "NOT_FOUND" -> new LeaseBindResult.Rejected("environment not found: " + environmentId);
      case "INVALID_TOKEN" -> new LeaseBindResult.Rejected(
          "invalid registration token for environment: " + environmentId);
      case "ACTIVE_ROUTE" -> new LeaseBindResult.RetryLater(
          "environment " + environmentId + " is actively held by another connection");
      default -> new LeaseBindResult.RetryLater(
          "route acquisition failed for environment: " + environmentId);
    };
  }

  /**
   * 将环境标记为 READY 并写入 daemon 版本化宿主 metadata。
   *
   * @return 围栏校验成功且更新 1 行返回 true；若租约已被夺取（0 行）返回 false
   */
  @Override
  public boolean markReady(
      EnvironmentId environmentId,
      UUID leaseToken,
      DaemonCapabilities capabilities,
      Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    String runtimeInfoJson = capabilitiesCodec.encode(capabilities);
    String readyEvent =
        stateCodec.encodeEvent(
            lifecycleEvent(
                EnvironmentEvent.LEVEL_INFO, EnvironmentEvent.TYPE_READY, "environment ready"));
    return fencedWrite(
        environmentId,
        () ->
            jdbcClient
                .sql(MARK_READY_SQL)
                .param("runtimeInfo", runtimeInfoJson)
                .param("maxRecentEvents", EnvironmentConnection.MAX_RECENT_EVENTS)
                .param("readyEvent", readyEvent)
                .param("leaseMillis", leaseDuration.toMillis())
                .param("environmentId", environmentId.value())
                .param("ownerNodeId", ownerNodeId)
                .param("leaseToken", leaseToken)
                .update());
  }

  /**
   * 只刷新路由租约的 last_seen_at 与 lease_until，不发布环境变化通知。
   *
   * @return 围栏校验成功且更新 1 行返回 true；若租约已被夺取（0 行）返回 false
   */
  @Override
  public boolean heartbeat(EnvironmentId environmentId, UUID leaseToken, Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    return jdbcClient
            .sql(HEARTBEAT_SQL)
            .param("leaseMillis", leaseDuration.toMillis())
            .param("environmentId", environmentId.value())
            .param("ownerNodeId", ownerNodeId)
            .param("leaseToken", leaseToken)
            .update()
        > 0;
  }

  @Override
  public boolean runtimeStatusChanged(EnvironmentId environmentId, UUID leaseToken) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    return transactionTemplate.execute(
        status -> {
          boolean holdsLease =
              jdbcClient
                  .sql(LOCK_READY_LEASE_SQL)
                  .param("environmentId", environmentId.value())
                  .param("ownerNodeId", ownerNodeId)
                  .param("leaseToken", leaseToken)
                  .query(Integer.class)
                  .optional()
                  .isPresent();
          if (holdsLease) {
            notifier.environmentChanged(environmentId.value());
          }
          return holdsLease;
        });
  }

  /**
   * 连接断开时，围栏式将状态回退为 CONNECTING 并保留重连宽限租约。
   *
   * <p>{@code runtime_info} 保留最近一次被接受的 READY 宿主 metadata，断线不清空。
   *
   * @return 围栏校验成功返回 true
   */
  @Override
  public boolean disconnect(EnvironmentId environmentId, UUID leaseToken, Duration graceDuration) {
    if (environmentId == null || leaseToken == null) {
      return false;
    }
    String disconnectedEvent =
        stateCodec.encodeEvent(
            lifecycleEvent(
                EnvironmentEvent.LEVEL_WARN,
                EnvironmentEvent.TYPE_DISCONNECTED,
                "daemon connection lost"));
    try {
      return fencedWrite(
          environmentId,
          () ->
              jdbcClient
                  .sql(DISCONNECT_SQL)
                  .param("maxRecentEvents", EnvironmentConnection.MAX_RECENT_EVENTS)
                  .param("disconnectedEvent", disconnectedEvent)
                  .param("leaseMillis", graceDuration.toMillis())
                  .param("environmentId", environmentId.value())
                  .param("ownerNodeId", ownerNodeId)
                  .param("leaseToken", leaseToken)
                  .update());
    } catch (DataAccessException failure) {
      // 捕获在事务边界之外：失败时围栏写已随短事务回滚，绝不留下「租约已提交、通知失败」的中间态。
      log.warn(
          "cannot persist environment disconnect for {}: {}",
          environmentId,
          failure.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * 持有者主动放弃路由租约，围栏校验通过后清除活跃租约（lease_until 置为当前时刻），使任何节点都可立即抢占。
   *
   * @return 围栏校验成功且更新 1 行返回 true；若已释放或 token 不匹配返回 false
   */
  @Override
  public boolean release(EnvironmentId environmentId, UUID leaseToken) {
    if (environmentId == null || leaseToken == null) {
      return false;
    }
    String releasedEvent =
        stateCodec.encodeEvent(
            lifecycleEvent(
                EnvironmentEvent.LEVEL_INFO,
                EnvironmentEvent.TYPE_DISCONNECTED,
                "daemon connection released"));
    try {
      return fencedWrite(
          environmentId,
          () ->
              jdbcClient
                  .sql(RELEASE_SQL)
                  .param("maxRecentEvents", EnvironmentConnection.MAX_RECENT_EVENTS)
                  .param("releasedEvent", releasedEvent)
                  .param("environmentId", environmentId.value())
                  .param("ownerNodeId", ownerNodeId)
                  .param("leaseToken", leaseToken)
                  .update());
    } catch (DataAccessException failure) {
      // 围栏写失败按未释放处理；记录异常类型而不回显 SQL。
      log.warn(
          "cannot persist environment release for {}: {}",
          environmentId,
          failure.getClass().getSimpleName());
      return false;
    }
  }

  /** 查询数据库中指定环境的当前路由快照。 */
  public Optional<EnvironmentConnection> find(EnvironmentId environmentId) {
    if (environmentId == null) {
      return Optional.empty();
    }
    List<EnvironmentConnection> results =
        jdbcClient
            .sql(FIND_SQL)
            .param("environmentId", environmentId.value())
            .query(new EnvironmentConnectionRowMapper())
            .list();
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /** 查询当前数据库中所有活跃环境的路由快照列表。 */
  public List<EnvironmentConnection> list() {
    return jdbcClient.sql(LIST_SQL).query(new EnvironmentConnectionRowMapper()).list();
  }

  /**
   * 查询当前节点持有未过期 READY 租约的 Environment 快照。
   *
   * <p>这是 Skill 增量同步与通知重连对账的目标集合：行级 owner 与租约活跃性都由数据库现在时判定，绝不依据进程内缓存。
   */
  public List<EnvironmentConnection> listReadyOwnedByNode() {
    return jdbcClient
        .sql(LIST_READY_OWNED_SQL)
        .param("ownerNodeId", ownerNodeId)
        .query(new EnvironmentConnectionRowMapper())
        .list();
  }

  /**
   * 围栏追加一条运维事件（不改变 {@code skill_state}）。
   *
   * <p>事件写入与连接状态推进共用同一个 owner + lease token + 未过期 READY 围栏；围栏失效时返回 false（fail-closed），
   * 绝不把过期持有者的事件写进新持有者的行。
   */
  public boolean recordEvent(EnvironmentId environmentId, UUID leaseToken, EnvironmentEvent event) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(event, "event");
    String eventJson = stateCodec.encodeEvent(event);
    return fencedWrite(
        environmentId,
        () ->
            jdbcClient
                .sql(APPEND_EVENT_SQL)
                .param("maxRecentEvents", EnvironmentConnection.MAX_RECENT_EVENTS)
                .param("event", eventJson)
                .param("environmentId", environmentId.value())
                .param("ownerNodeId", ownerNodeId)
                .param("leaseToken", leaseToken)
                .update());
  }

  /**
   * 在同一个围栏写中原子替换 Skill 同步投影并追加一条事件。
   *
   * @return 围栏成立且更新 1 行返回 true；租约被夺取、已离线或过期时返回 false，调用方必须放弃该结果
   */
  public boolean replaceSkillState(
      EnvironmentId environmentId,
      UUID leaseToken,
      List<EnvironmentSkillState> skillState,
      EnvironmentEvent event) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(skillState, "skillState");
    Objects.requireNonNull(event, "event");
    String skillStateJson = stateCodec.encodeSkillState(skillState);
    String eventJson = stateCodec.encodeEvent(event);
    return fencedWrite(
        environmentId,
        () ->
            jdbcClient
                .sql(REPLACE_SKILL_STATE_SQL)
                .param("skillState", skillStateJson)
                .param("maxRecentEvents", EnvironmentConnection.MAX_RECENT_EVENTS)
                .param("event", eventJson)
                .param("environmentId", environmentId.value())
                .param("ownerNodeId", ownerNodeId)
                .param("leaseToken", leaseToken)
                .update());
  }

  /** 数据库现在时判定指定环境是否有任意活跃租约（用于 delete 在锁行下的安全准入）。 */
  public boolean hasActiveLease(EnvironmentId environmentId) {
    if (environmentId == null) {
      return false;
    }
    Boolean exists =
        jdbcClient
            .sql(HAS_ACTIVE_LEASE_SQL)
            .param("environmentId", environmentId.value())
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  /** 返回数据库时间下仍有效的 READY 租约截止点，用于读取模型的自然到期回读。 */
  public Optional<Instant> findReadyLeaseUntil(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    return jdbcClient
        .sql(
            """
            select lease_until from environment_connection
            where environment_id = :environmentId
              and status = 'READY' and lease_until > statement_timestamp()
            """)
        .param("environmentId", environmentId.value())
        .query((rs, rowNumber) -> rs.getTimestamp("lease_until").toInstant())
        .optional();
  }

  /** 数据库现在时判定当前节点是否持有有效的 READY 路由租约（用于本地 capability INVOKE 准入）。 */
  @Override
  public boolean holdsReadyLease(EnvironmentId environmentId, UUID leaseToken) {
    if (environmentId == null || leaseToken == null) {
      return false;
    }
    Boolean exists =
        jdbcClient
            .sql(HOLDS_READY_LEASE_SQL)
            .param("environmentId", environmentId.value())
            .param("ownerNodeId", ownerNodeId)
            .param("leaseToken", leaseToken)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  /** 数据库现在时判定当前节点是否持有活跃的连接代币（用于握手准入同节点活跃连接防冲突保护）。 */
  @Override
  public boolean hasActiveLeaseToken(EnvironmentId environmentId, UUID leaseToken) {
    if (environmentId == null || leaseToken == null) {
      return false;
    }
    Boolean exists =
        jdbcClient
            .sql(HAS_ACTIVE_LEASE_TOKEN_SQL)
            .param("environmentId", environmentId.value())
            .param("ownerNodeId", ownerNodeId)
            .param("leaseToken", leaseToken)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  private record AcquireRow(UUID leaseToken, boolean acquired, String resultType) {}

  private final class EnvironmentConnectionRowMapper implements RowMapper<EnvironmentConnection> {
    @Override
    public EnvironmentConnection mapRow(ResultSet rs, int rowNum) throws SQLException {
      EnvironmentId id = EnvironmentId.of((UUID) rs.getObject("environment_id"));
      UUID owner = (UUID) rs.getObject("owner_node_id");
      UUID leaseToken = (UUID) rs.getObject("lease_token");
      LiveEnvironmentStatus status = LiveEnvironmentStatus.valueOf(rs.getString("status"));
      // 保留事实：任何非 null 的 runtime_info 都可解码，与当前 status 无关。
      String runtimeInfoJson = rs.getString("runtime_info");
      DaemonCapabilities capabilities =
          runtimeInfoJson == null ? null : capabilitiesCodec.decode(runtimeInfoJson);
      // 两个 JSONB 投影同样与 status 无关：断线或重新 CONNECTING 都保留最近一次同步事实与最近事件。
      List<EnvironmentSkillState> skillState =
          stateCodec.decodeSkillState(rs.getString("skill_state"));
      List<EnvironmentEvent> recentEvents = stateCodec.decodeEvents(rs.getString("recent_events"));
      Instant lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime.class).toInstant();
      Instant leaseUntil = rs.getObject("lease_until", OffsetDateTime.class).toInstant();
      return new EnvironmentConnection(
          id,
          owner,
          leaseToken,
          status,
          capabilities,
          skillState,
          recentEvents,
          lastSeenAt,
          leaseUntil);
    }
  }
}

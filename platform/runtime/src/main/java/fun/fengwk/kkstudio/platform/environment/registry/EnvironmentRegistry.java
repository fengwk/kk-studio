package fun.fengwk.kkstudio.platform.environment.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadata;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadataCodec;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonLeaseStore;
import fun.fengwk.kkstudio.platform.daemon.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.EnvironmentChangeNotifier;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * leaseToken} 接管；活跃路由返回 {@link LeaseBindResult.RetryLater}。断开连接时保留重连宽限期，允许原连接重新认领。准入事务与连接行映射由同包
 * {@link EnvironmentLeaseAdmission}、{@link EnvironmentConnectionRows} 承担，本类只保留围栏写的锁序与通知时机。
 *
 * <p>READY、HEARTBEAT 与断开连接均以 {@code (environment_id, owner_node_id, lease_token)} 为围栏更新，
 * 保证只有当前持有该路由租约的本地连接能推进状态；所有围栏更新在租约失效时返回 false（fail-closed）。
 *
 * <p>连接生命周期事件（CONNECTING/READY/DISCONNECTED）在推进状态的同一条围栏语句里原子追加到 {@code recent_events}， 数组始终是 200
 * 条以内的按时间正序窗口。
 *
 * <p>状态、宿主信息或运维事件的围栏写与 {@code environment_changed} 在同一事务提交；0 行围栏与拒绝结果静默， 发布失败会回滚围栏写。HEARTBEAT
 * 只续租，不发布通知。内存运行状态变化时先更新快照，再在 READY 租约围栏下发布失效提示。 自然到期没有数据库写事件，仍由读取时判定与截止点对账处理。
 */
@Slf4j
@Component
public class EnvironmentRegistry implements DaemonLeaseStore {

  /**
   * 围栏式登记 READY：推进行状态、写入最近一次被接受的宿主 metadata 并追加一条 READY 事件。
   *
   * <p>只有当前 owner + lease 且未过期的连接行才能推进 READY；围栏失效时既不推进状态也不改写 ready_metadata。
   */
  private static final String MARK_READY_SQL =
      """
      update environment_connection
      set status = 'READY',
          ready_metadata = :readyMetadata::jsonb,
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
          last_seen_at = date_trunc('milliseconds', statement_timestamp()) - interval '1 millisecond',
          lease_until = date_trunc('milliseconds', statement_timestamp()) + (:leaseMillis * interval '1 millisecond')
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
          last_seen_at = date_trunc('milliseconds', statement_timestamp()) - interval '1 millisecond',
          lease_until = date_trunc('milliseconds', statement_timestamp())
      where environment_id = :environmentId
        and owner_node_id = :ownerNodeId
        and lease_token = :leaseToken
        and lease_until > statement_timestamp()
      """;

  private static final String FIND_SQL =
      """
      select environment_id, owner_node_id, lease_token,
             status, ready_metadata, recent_events, last_seen_at, lease_until
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

  private final JdbcClient jdbcClient;
  private final UUID ownerNodeId;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final EnvironmentChangeNotifier notifier;
  private final DaemonReadyMetadataCodec readyMetadataCodec = new DaemonReadyMetadataCodec();
  private final EnvironmentStateCodec stateCodec = new EnvironmentStateCodec();
  private final EnvironmentConnectionRows connectionRows;
  private final EnvironmentLeaseAdmission leaseAdmission;

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
    this.connectionRows = new EnvironmentConnectionRows(readyMetadataCodec, stateCodec);
    this.leaseAdmission =
        new EnvironmentLeaseAdmission(
            jdbcClient, ownerNodeId, clock, transactionTemplate, notifier);
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
  private EnvironmentEvent lifecycleEvent(
      EnvironmentEventLevel level, EnvironmentEventType type, String message) {
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
    return leaseAdmission.bind(environmentId, registrationToken, leaseDuration);
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
      DaemonReadyMetadata readyMetadata,
      Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(readyMetadata, "readyMetadata");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    String readyMetadataJson = readyMetadataCodec.encode(readyMetadata);
    String readyEvent =
        stateCodec.encodeEvent(
            lifecycleEvent(
                EnvironmentEventLevel.INFO, EnvironmentEventType.READY, "environment ready"));
    return fencedWrite(
        environmentId,
        () ->
            jdbcClient
                .sql(MARK_READY_SQL)
                .param("readyMetadata", readyMetadataJson)
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
   * <p>{@code ready_metadata} 保留最近一次被接受的 READY 宿主 metadata，断线不清空。宽限期从释放语句时刻的毫秒边界起算；宽限期为零时租约立即过期，
   * 其他节点可立即接管。
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
                EnvironmentEventLevel.WARN,
                EnvironmentEventType.DISCONNECTED,
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
   * 持有者主动放弃路由租约，围栏校验通过后把 {@code lease_until} 截断到释放语句时刻的毫秒边界，使任何节点都可立即抢占； {@code last_seen_at}
   * 保持严格早于 {@code lease_until}。
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
                EnvironmentEventLevel.INFO,
                EnvironmentEventType.DISCONNECTED,
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
            .query(connectionRows.mapper())
            .list();
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * 围栏追加一条运维事件。
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
}

package fun.fengwk.kkstudio.platform.environment.cluster;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的 {@link ClusterNodeRegistry}：读取 {@code environment_connection} 的 owner/lease
 * 现在时快照，并按 {@code cluster_node} 注册表定位远端 owner 的可达 origin。
 *
 * <p>路由判定完全在数据库现在时进行：只有 {@code status = 'READY'} 且租约未过期的连接行才参与路由；远端 owner 还必须存在未过期的 {@code
 * cluster_node} 注册行且本节点已配置共享秘密。缺失注册、过期注册、本节点缺少秘密或内部地址非法一律返回空，绝不从请求 Host 推断地址。
 *
 * <p>{@code cluster_node} 只承载节点可达 origin 与租约控制元数据，不保存任何环境存活字节；{@link #registerLocalNode} 与 {@link
 * #heartbeatLocalNode} 由本节点生命周期驱动，同一 {@code nodeInstanceId} 单调续约。
 */
public class JdbcClusterNodeRegistry implements ClusterNodeRegistry {

  /**
   * 现有连接行的 owner/lease 快照左连远端 owner 的注册行。
   *
   * <p>所有时间判定都下推到数据库现在时（{@code statement_timestamp()}），避免本进程时钟与数据库时钟偏移导致误判： {@code local_owner} 与
   * {@code node_usable} 直接由数据库表达式计算。过期连接与过期注册都不会被选中。
   */
  private static final String ROUTE_SQL =
      """
      select c.owner_node_id as owner_node_id,
             (c.owner_node_id = :localNodeId) as local_owner,
             n.internal_url as internal_url,
             (n.lease_until > statement_timestamp()) as node_usable
      from environment_connection c
      left join cluster_node n on n.instance_id = c.owner_node_id
      where c.environment_id = :environmentId
        and c.status = 'READY'
        and c.lease_until > statement_timestamp()
      """;

  /** 本节点注册/续约：同一 instanceId upsert，internal_url 与租约一起刷新。 */
  private static final String REGISTER_SQL =
      """
      insert into cluster_node (instance_id, internal_url, lease_until, last_seen)
      values (:instanceId, :internalUrl,
          statement_timestamp() + (:leaseMillis * interval '1 millisecond'),
          statement_timestamp())
      on conflict (instance_id) do update
      set internal_url = excluded.internal_url,
          lease_until = excluded.lease_until,
          last_seen = excluded.last_seen
      """;

  /** 仅推进本节点注册行的 last_seen 与 lease_until，不改动 origin。 */
  private static final String HEARTBEAT_SQL =
      """
      update cluster_node
      set last_seen = statement_timestamp(),
          lease_until = statement_timestamp() + (:leaseMillis * interval '1 millisecond')
      where instance_id = :instanceId
      """;

  private final JdbcClient jdbcClient;
  private final UUID localNodeId;
  private final ClusterProperties properties;

  public JdbcClusterNodeRegistry(
      JdbcClient jdbcClient, UUID localNodeId, ClusterProperties properties) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /** 本节点的实例身份，供路由判定与生命周期使用。 */
  public UUID localNodeId() {
    return localNodeId;
  }

  @Override
  public Optional<ClusterIdeOwnerRoute> route(EnvironmentId environmentId) {
    if (environmentId == null) {
      return Optional.empty();
    }
    List<OwnerRow> rows =
        jdbcClient
            .sql(ROUTE_SQL)
            .param("localNodeId", localNodeId)
            .param("environmentId", environmentId.value())
            .query(OwnerRowMapper.INSTANCE)
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    OwnerRow row = rows.get(0);
    if (row.localOwner()) {
      // 本地路由同样由连接行 READY + 未过期租约确认，不因本节点身份绕过。
      return Optional.of(ClusterIdeOwnerRoute.local(localNodeId));
    }
    if (!properties.remoteRoutingConfigured()) {
      // 缺少共享秘密时无法对远端签名，明确不可用，绝不回退到请求 Host。
      return Optional.empty();
    }
    if (!Boolean.TRUE.equals(row.nodeUsable()) || row.internalUrl() == null) {
      return Optional.empty();
    }
    return parseOrigin(row.internalUrl())
        .map(origin -> ClusterIdeOwnerRoute.remote(row.ownerNodeId(), origin));
  }

  /** 以当前时间建立/刷新本节点注册行；只有多节点配置存在时由生命周期调用。 */
  public void registerLocalNode(URI internalOrigin, Duration lease) {
    Objects.requireNonNull(internalOrigin, "internalOrigin");
    Objects.requireNonNull(lease, "lease");
    jdbcClient
        .sql(REGISTER_SQL)
        .param("instanceId", localNodeId)
        .param("internalUrl", internalOrigin.toString())
        .param("leaseMillis", lease.toMillis())
        .update();
  }

  /**
   * 续约本节点注册行。
   *
   * @return 命中本节点注册行并更新 1 行返回 true；注册行缺失返回 false，调用方应重新注册
   */
  public boolean heartbeatLocalNode(Duration lease) {
    Objects.requireNonNull(lease, "lease");
    return jdbcClient
            .sql(HEARTBEAT_SQL)
            .param("leaseMillis", lease.toMillis())
            .param("instanceId", localNodeId)
            .update()
        > 0;
  }

  private static Optional<URI> parseOrigin(String raw) {
    URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException error) {
      return Optional.empty();
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      return Optional.empty();
    }
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      return Optional.empty();
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !path.equals("/")) {
      return Optional.empty();
    }
    return Optional.of(URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getAuthority()));
  }

  private record OwnerRow(
      UUID ownerNodeId, boolean localOwner, String internalUrl, Boolean nodeUsable) {}

  private static final class OwnerRowMapper implements RowMapper<OwnerRow> {

    private static final OwnerRowMapper INSTANCE = new OwnerRowMapper();

    @Override
    public OwnerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
      UUID owner = rs.getObject("owner_node_id", UUID.class);
      boolean localOwner = rs.getBoolean("local_owner");
      String internalUrl = rs.getString("internal_url");
      Boolean nodeUsable = (Boolean) rs.getObject("node_usable");
      return new OwnerRow(owner, localOwner, internalUrl, nodeUsable);
    }
  }
}

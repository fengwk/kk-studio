package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.server.DaemonChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.DaemonResourceTicketService;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.server.EnvironmentServerConfiguration;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 验证基于 PostgreSQL 的多节点 Environment 路由租约、原子认领、状态围栏。 */
class PostgresEnvironmentRoutingIntegrationTest extends PostgresSchemaSupport {

  private static final EnvironmentId DEV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String REGISTRATION_TOKEN = "test-token";

  /** 本测试只模拟单个 daemon 进程身份：重连由同一身份表达。 */
  private static final String DAEMON_INSTANCE_ID = "22222222-2222-2222-2222-222222222222";

  private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          0,
          List.of());

  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();

  /** 本测试只验证路由租约，不涉及资源上传；任何票据请求都是测试自身的错误。 */
  private static final DaemonResourceTicketService UNUSED_TICKET_SERVICE =
      new DaemonResourceTicketService() {
        @Override
        public Ticket reserve(
            EnvironmentId environmentId, String invocationId, TransferRequest request) {
          throw new AssertionError("routing test must not reserve an upload");
        }

        @Override
        public Ticket commit(EnvironmentId environmentId, String invocationId, UUID uploadId) {
          throw new AssertionError("routing test must not commit an upload");
        }

        @Override
        public void release(EnvironmentId environmentId, String invocationId, UUID uploadId) {
          throw new AssertionError("routing test must not release an upload");
        }
      };

  private JdbcTemplate jdbcTemplate;
  private UUID node1;
  private UUID node2;
  private EnvironmentRegistry registryNode1;
  private EnvironmentRegistry registryNode2;
  private EnvironmentDaemonServer serverNode1;
  private EnvironmentDaemonServer serverNode2;
  private EnvironmentRepository environmentRepository;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }

    SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    this.jdbcTemplate = new JdbcTemplate(ds);

    // 播种稳定 environment 卡片
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version) values (?, 'dev-env', ?, 0)",
        DEV.value(),
        REGISTRATION_TOKEN);

    this.environmentRepository =
        new EnvironmentRepository() {
          @Override
          public List<Environment> listNewestFirst() {
            throw new UnsupportedOperationException();
          }

          @Override
          public Environment getById(UUID id) {
            List<Environment> list =
                jdbcTemplate.query(
                    "select id, name, registration_token, version, created_at as create_time, updated_at as update_time from environment where id = ?",
                    (rs, rowNum) -> {
                      Environment e = new Environment();
                      e.setId((UUID) rs.getObject("id"));
                      e.setName(rs.getString("name"));
                      e.setRegistrationToken(rs.getString("registration_token"));
                      e.setVersion(rs.getLong("version"));
                      return e;
                    },
                    id);
            return list.isEmpty() ? null : list.get(0);
          }

          @Override
          public Environment getByName(String name) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Environment getByRegistrationToken(String token) {
            List<Environment> list =
                jdbcTemplate.query(
                    "select id, name, registration_token, version, created_at as create_time, updated_at as update_time from environment where registration_token = ?",
                    (rs, rowNum) -> {
                      Environment e = new Environment();
                      e.setId((UUID) rs.getObject("id"));
                      e.setName(rs.getString("name"));
                      e.setRegistrationToken(rs.getString("registration_token"));
                      e.setVersion(rs.getLong("version"));
                      return e;
                    },
                    token);
            return list.isEmpty() ? null : list.get(0);
          }

          @Override
          public Environment lockById(UUID id) {
            return getById(id);
          }

          @Override
          public Environment lockForKeyShare(UUID id) {
            return getById(id);
          }

          @Override
          public boolean existsByName(String name) {
            return false;
          }

          @Override
          public boolean create(Environment environment) {
            return false;
          }

          @Override
          public boolean updateById(Environment environment, long expectedVersion) {
            return false;
          }

          @Override
          public boolean deleteById(UUID id, long expectedVersion) {
            return false;
          }
        };

    this.node1 = UUID.randomUUID();
    this.node2 = UUID.randomUUID();

    this.registryNode1 = new EnvironmentRegistry(jdbcTemplate, node1);
    this.registryNode2 = new EnvironmentRegistry(jdbcTemplate, node2);

    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    EnvironmentSessionListener sessionListener = env -> {};
    EnvironmentServerConfiguration serverConfiguration = new EnvironmentServerConfiguration();
    this.serverNode1 =
        serverConfiguration.environmentDaemonServer(
            registryNode1, environmentRepository, sessionListener, UNUSED_TICKET_SERVICE, snapshot);
    this.serverNode2 =
        serverConfiguration.environmentDaemonServer(
            registryNode2, environmentRepository, sessionListener, UNUSED_TICKET_SERVICE, snapshot);
  }

  @Test
  void localHelloAcquiresRouteAndAllowsReady() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    serverNode1.open(conn1);

    serverNode1.receive(conn1.connectionId(), hello(REGISTRATION_TOKEN));

    EnvironmentConnection env = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertEquals(node1, env.ownerNodeId());
    assertNotNull(env.leaseToken());

    // 检查是否回复 WELCOME
    List<DaemonEnvelope> envelopes = conn1.envelopes();
    assertEquals(1, envelopes.size());
    assertEquals(DaemonMessageType.WELCOME, envelopes.get(0).messageType());
    assertEquals(DEV, envelopes.get(0).environmentId());

    // 发送 READY
    serverNode1.receive(conn1.connectionId(), ready(DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());
    assertNotNull(readyEnv.daemonCapabilities());
  }

  @Test
  void invalidRegistrationTokenFailsTerminal() {
    FakeConnection conn1 = new FakeConnection("conn-invalid");
    serverNode1.open(conn1);
    serverNode1.receive(conn1.connectionId(), hello("wrong-token"));

    List<DaemonEnvelope> envelopes = conn1.envelopes();
    assertEquals(1, envelopes.size());
    assertEquals(DaemonMessageType.ERROR, envelopes.get(0).messageType());
    assertTrue(
        envelopes.get(0).payloadJson().contains(DaemonProtocol.ERROR_CODE_REGISTRATION_REJECTED));
    assertTrue(conn1.closed);
  }

  @Test
  void retryLaterWhenActiveRouteExists() {
    // 1. Node 1 成功绑定 DEV
    FakeConnection conn1 = new FakeConnection("conn-1");
    serverNode1.open(conn1);
    serverNode1.receive(conn1.connectionId(), hello(REGISTRATION_TOKEN));
    serverNode1.receive(conn1.connectionId(), ready(DEV));

    // 2. 另一个连接（如 Node 2）尝试绑定 DEV -> RETRY_LATER
    FakeConnection conn2 = new FakeConnection("conn-2");
    serverNode2.open(conn2);
    serverNode2.receive(conn2.connectionId(), hello(REGISTRATION_TOKEN));

    List<DaemonEnvelope> envelopes2 = conn2.envelopes();
    assertEquals(1, envelopes2.size());
    assertEquals(DaemonMessageType.ERROR, envelopes2.get(0).messageType());
    assertTrue(envelopes2.get(0).payloadJson().contains(DaemonProtocol.ERROR_CODE_RETRY_LATER));
    assertTrue(conn2.closed);

    // Node 1 的路由依然完好
    EnvironmentConnection env = registryNode1.find(DEV).orElseThrow();
    assertEquals(node1, env.ownerNodeId());
  }

  @Test
  void expiredLeaseCanBeTakenOver() {
    // 1. Node 1 绑定 DEV
    FakeConnection conn1 = new FakeConnection("conn-1");
    serverNode1.open(conn1);
    serverNode1.receive(conn1.connectionId(), hello(REGISTRATION_TOKEN));
    serverNode1.receive(conn1.connectionId(), ready(DEV));

    // 2. 模拟租约在数据库中过期
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());

    // 3. Node 2 尝试绑定 DEV -> 成功接管
    FakeConnection conn2 = new FakeConnection("conn-2");
    serverNode2.open(conn2);
    serverNode2.receive(conn2.connectionId(), hello(REGISTRATION_TOKEN));

    EnvironmentConnection env = registryNode2.find(DEV).orElseThrow();
    assertEquals(node2, env.ownerNodeId());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());

    // 检查 conn2 收到 WELCOME
    assertEquals(1, conn2.envelopes().size());
    assertEquals(DaemonMessageType.WELCOME, conn2.envelopes().get(0).messageType());
  }

  @Test
  void routeFenceRejectsStaleTokenUpdates() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    serverNode1.open(conn1);
    serverNode1.receive(conn1.connectionId(), hello(REGISTRATION_TOKEN));

    // 在数据库中篡改 lease_token
    jdbcTemplate.update(
        "update environment_connection set lease_token = ? where environment_id = ?",
        UUID.randomUUID(),
        DEV.value());

    // 发送 READY -> 应当因为 fence 失败而被关闭
    serverNode1.receive(conn1.connectionId(), ready(DEV));

    assertTrue(conn1.closed);
  }

  @Test
  void disconnectPreservesConnectingWithGracePeriod() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    serverNode1.open(conn1);
    serverNode1.receive(conn1.connectionId(), hello(REGISTRATION_TOKEN));
    serverNode1.receive(conn1.connectionId(), ready(DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());

    // 断开连接
    serverNode1.close(conn1.connectionId());

    EnvironmentConnection disconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, disconnectedEnv.status());
    assertNull(disconnectedEnv.daemonCapabilities());
    assertEquals(readyEnv.leaseToken(), disconnectedEnv.leaseToken());
    assertEquals(node1, disconnectedEnv.ownerNodeId());
  }

  /**
   * 测试意图：证明连接断开进入宽限期后，同节点 daemon 无需手工回拨 lease 即可重新发起 HELLO 成功 并换发新 leaseToken 成为
   * CONNECTING；而异节点在宽限期内尝试 HELLO 必须被返回 RETRY_LATER 并断开。
   */
  @Test
  void gracePeriodAllowsSameNodeReconnectWithoutManualLeaseRewindWhileBlockingOtherNode() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    serverNode1.open(conn1);
    serverNode1.receive(conn1.connectionId(), hello(REGISTRATION_TOKEN));
    serverNode1.receive(conn1.connectionId(), ready(DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());

    // 断开连接，进入宽限期
    serverNode1.close(conn1.connectionId());

    EnvironmentConnection disconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, disconnectedEnv.status());
    // 宽限期依然在未来
    assertTrue(disconnectedEnv.leaseUntil().isAfter(Instant.now()));

    // 异节点 Node 2 在宽限期内尝试连接 -> 必须被拒绝（收到 RETRY_LATER 并关闭）
    FakeConnection conn2 = new FakeConnection("conn-2");
    serverNode2.open(conn2);
    serverNode2.receive(conn2.connectionId(), hello(REGISTRATION_TOKEN));
    assertTrue(conn2.closed);
    assertEquals(DaemonMessageType.ERROR, conn2.envelopes().get(0).messageType());
    assertTrue(
        conn2.envelopes().get(0).payloadJson().contains(DaemonProtocol.ERROR_CODE_RETRY_LATER));

    // 同节点 Node 1 在宽限期内重新连接（不手工回拨 lease_until）-> 必须成功收到 WELCOME
    FakeConnection conn1b = new FakeConnection("conn-1b");
    serverNode1.open(conn1b);
    serverNode1.receive(conn1b.connectionId(), hello(REGISTRATION_TOKEN));
    assertFalse(conn1b.closed);
    assertEquals(1, conn1b.envelopes().size());
    assertEquals(DaemonMessageType.WELCOME, conn1b.envelopes().get(0).messageType());

    // 路由成功被换发新 leaseToken
    EnvironmentConnection reconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(node1, reconnectedEnv.ownerNodeId());
    assertNotEquals(readyEnv.leaseToken(), reconnectedEnv.leaseToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, reconnectedEnv.status());
  }

  private static String hello(String token) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.HELLO,
            null,
            null,
            "{\"protocolVersion\":"
                + DaemonProtocol.VERSION
                + ",\"capabilityCatalogVersion\":\""
                + EnvironmentCapabilityCatalog.version()
                + "\",\"registrationToken\":\""
                + token
                + "\",\"daemonInstanceId\":\""
                + DAEMON_INSTANCE_ID
                + "\"}"));
  }

  private static String ready(EnvironmentId environmentId) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.READY,
            environmentId,
            null,
            CAPABILITIES_CODEC.encode(CAPABILITIES)));
  }

  private static String heartbeat(EnvironmentId environmentId) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION, DaemonMessageType.HEARTBEAT, environmentId, null, "{}"));
  }

  private static final class FakeConnection implements DaemonChannel {
    private final String connectionId;
    private final List<DaemonEnvelope> envelopes = new ArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private boolean closed;

    private FakeConnection(String connectionId) {
      this.connectionId = connectionId;
    }

    @Override
    public String connectionId() {
      return connectionId;
    }

    @Override
    public boolean isOpen() {
      return open.get() && !closed;
    }

    @Override
    public synchronized DaemonOfferResult offerText(String text) {
      if (!isOpen()) {
        return DaemonOfferResult.CLOSED;
      }
      envelopes.add(ENVELOPE_CODEC.decode(text));
      return DaemonOfferResult.ACCEPTED;
    }

    @Override
    public void closeAfterFlush() {
      close();
    }

    @Override
    public synchronized void close() {
      closed = true;
      open.set(false);
    }

    private synchronized List<DaemonEnvelope> envelopes() {
      return List.copyOf(envelopes);
    }
  }

  private static final DaemonCapabilitiesCodec CAPABILITIES_CODEC = new DaemonCapabilitiesCodec();
}

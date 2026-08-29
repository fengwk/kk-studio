package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentGatewayProperties;
import fun.fengwk.kkstudio.platform.environment.query.EnvironmentQueryCoordinator;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 验证基于 PostgreSQL 的多节点 Environment 路由租约、原子认领、状态围栏与跨节点只读 environment_query 信箱。 */
class PostgresEnvironmentRoutingIntegrationTest extends PostgresSchemaSupport {

  private static final EnvironmentName DEV = new EnvironmentName("dev-env");
  private static final String GATEWAY_TOKEN = "test-token";
  private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          List.of(),
          List.of());

  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonDirectoryCodec DIRECTORY_CODEC = new DaemonDirectoryCodec();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private JdbcTemplate jdbcTemplate;
  private UUID node1;
  private UUID node2;
  private LiveEnvironmentRegistry registryNode1;
  private LiveEnvironmentRegistry registryNode2;
  private EnvironmentQueryCoordinator coordinatorNode1;
  private EnvironmentQueryCoordinator coordinatorNode2;
  private EnvironmentDaemonGateway gatewayNode1;
  private EnvironmentDaemonGateway gatewayNode2;

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

    this.node1 = UUID.randomUUID();
    this.node2 = UUID.randomUUID();

    this.registryNode1 = new LiveEnvironmentRegistry(jdbcTemplate, node1);
    this.registryNode2 = new LiveEnvironmentRegistry(jdbcTemplate, node2);

    this.coordinatorNode1 = new EnvironmentQueryCoordinator(jdbcTemplate, OBJECT_MAPPER, node1);
    this.coordinatorNode2 = new EnvironmentQueryCoordinator(jdbcTemplate, OBJECT_MAPPER, node2);

    EnvironmentGatewayProperties props = new EnvironmentGatewayProperties();
    props.setDaemonToken(GATEWAY_TOKEN);

    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);
    Clock clock = Clock.systemUTC();

    this.gatewayNode1 =
        new EnvironmentDaemonGateway(
            registryNode1, props, snapshot, clock, env -> {}, coordinatorNode1);
    this.gatewayNode2 =
        new EnvironmentDaemonGateway(
            registryNode2, props, snapshot, clock, env -> {}, coordinatorNode2);
  }

  @Test
  void helloAtomicAcquireNewRoute() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));

    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(conn1.envelopes()));

    LiveEnvironment env = registryNode1.find(DEV).orElseThrow();
    assertEquals(DEV, env.name());
    assertEquals("daemon-1", env.daemonId());
    assertEquals(node1, env.ownerNodeId());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertNull(env.daemonCapabilities());
    assertFalse(env.isReady(Instant.now()));
  }

  @Test
  void activeRouteSameDaemonRetriesLaterDifferentDaemonConflicts() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));

    // 1. 同一 daemon 实例在活跃期尝试再次绑定 -> RETRY_LATER
    FakeConnection connSame = new FakeConnection("conn-same");
    gatewayNode2.open(connSame);
    gatewayNode2.receive(connSame.connectionId(), hello(0, "daemon-1", DEV));

    assertTrue(connSame.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connSame.envelopes()));
    assertEquals(
        "RETRY_LATER",
        ENVELOPE_CODEC.readPayload(connSame.envelopes().get(0)).path("code").asText());

    // 2. 不同 daemon 实例在活跃期尝试绑定 -> 终态 ENVIRONMENT_NAME_CONFLICT
    FakeConnection connDiff = new FakeConnection("conn-diff");
    gatewayNode2.open(connDiff);
    gatewayNode2.receive(connDiff.connectionId(), hello(0, "daemon-2", DEV));

    assertTrue(connDiff.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connDiff.envelopes()));
    assertEquals(
        DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT,
        ENVELOPE_CODEC.readPayload(connDiff.envelopes().get(0)).path("code").asText());

    // 原持有者保持不受干扰
    LiveEnvironment env = registryNode1.find(DEV).orElseThrow();
    assertEquals("daemon-1", env.daemonId());
    assertEquals(node1, env.ownerNodeId());
  }

  @Test
  void expiredRouteAtomicallyTakenOverByAnotherNode() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    assertTrue(registryNode1.find(DEV).orElseThrow().isReady(Instant.now()));

    // 模拟租约在数据库中过期
    jdbcTemplate.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        DEV.value());

    // Node 2 上的 daemon-2 发起 HELLO -> 原子接管
    FakeConnection conn2 = new FakeConnection("conn-2");
    gatewayNode2.open(conn2);
    gatewayNode2.receive(conn2.connectionId(), hello(0, "daemon-2", DEV));

    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(conn2.envelopes()));

    LiveEnvironment env = registryNode2.find(DEV).orElseThrow();
    assertEquals("daemon-2", env.daemonId());
    assertEquals(node2, env.ownerNodeId());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
  }

  @Test
  void readyAndHeartbeatFencing() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    LiveEnvironment env = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, env.status());
    assertNotNull(env.daemonCapabilities());
    assertTrue(env.isReady(Instant.now()));

    // Node 1 正常心跳
    gatewayNode1.receive(conn1.connectionId(), heartbeat(2, DEV));
    assertFalse(conn1.closed);

    // 模拟租约被偷走（route_token 变动）
    jdbcTemplate.update(
        "update live_environment set route_token = ? where environment_name = ?",
        UUID.randomUUID(),
        DEV.value());

    // 旧连接发送心跳 -> 0 rows updated -> 围栏失效并关闭连接
    gatewayNode1.receive(conn1.connectionId(), heartbeat(3, DEV));
    assertTrue(conn1.closed);
  }

  @Test
  void disconnectFenceRevertsConnectingAndPreservesGraceLease() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    LiveEnvironment readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());

    // 断开连接
    gatewayNode1.close(conn1.connectionId());

    LiveEnvironment disconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, disconnectedEnv.status());
    assertNull(disconnectedEnv.daemonCapabilities());
    assertEquals(readyEnv.routeToken(), disconnectedEnv.routeToken());
    assertEquals(node1, disconnectedEnv.ownerNodeId());
  }

  @Test
  void queryLocalRouteDirectly() throws Exception {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode1.listDirectory(DEV, ".", Duration.ofSeconds(5));

    // 检查本地 daemon 是否收到了 LIST_DIRECTORY 帧
    List<DaemonEnvelope> envelopes = conn1.envelopes();
    assertEquals(2, envelopes.size()); // WELCOME + LIST_DIRECTORY
    DaemonEnvelope listEnvelope = envelopes.get(1);
    assertEquals(DaemonMessageType.LIST_DIRECTORY, listEnvelope.messageType());

    DaemonDirectoryCodec.ListDirectoryRequest req =
        DIRECTORY_CODEC.decodeRequest(listEnvelope.payloadJson());
    assertEquals(".", req.path());

    // daemon 回复 DIRECTORY_LISTED
    DaemonDirectoryCodec.DirectoryListed listed =
        new DaemonDirectoryCodec.DirectoryListed(
            req.requestId(),
            ".",
            ".",
            ".",
            false,
            "main",
            List.of(new DaemonDirectoryCodec.DirectoryEntry("src", "src")));
    String listedJson = DIRECTORY_CODEC.encodeListed(listed);
    gatewayNode1.receive(
        conn1.connectionId(),
        ENVELOPE_CODEC.encode(
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.DIRECTORY_LISTED,
                DEV,
                null,
                2,
                listedJson)));

    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);
    EnvironmentDirectoryListResult.Loaded loaded =
        assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, result);
    EnvironmentDirectoryDTO dto = loaded.listing();
    assertEquals(".", dto.getPath());
    assertEquals("main", dto.getGitBranch());
    assertEquals(1, dto.getEntries().size());
    assertEquals("src", dto.getEntries().get(0).getName());
  }

  @Test
  void queryCrossNodeRouteViaMailboxAndNotify() throws Exception {
    // 1. Node 1 拥有 DEV 环境
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. Node 2 发起对 DEV 的目录查询
    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode2.listDirectory(DEV, "src", Duration.ofSeconds(5));

    assertFalse(future.isDone());

    // 3. Node 1 收到 REQUEST_CHANNEL 通知（或 resync）
    coordinatorNode1.onRequestNotification(DEV.value());

    // 检查 Node 1 上的 daemon 是否收到了 LIST_DIRECTORY 帧
    List<DaemonEnvelope> envelopes = conn1.envelopes();
    assertEquals(2, envelopes.size()); // WELCOME + LIST_DIRECTORY
    DaemonEnvelope listEnvelope = envelopes.get(1);
    assertEquals(DaemonMessageType.LIST_DIRECTORY, listEnvelope.messageType());

    DaemonDirectoryCodec.ListDirectoryRequest req =
        DIRECTORY_CODEC.decodeRequest(listEnvelope.payloadJson());
    assertEquals("src", req.path());

    // 4. Node 1 的 daemon 返回结果
    DaemonDirectoryCodec.DirectoryListed listed =
        new DaemonDirectoryCodec.DirectoryListed(
            req.requestId(),
            "src",
            "src",
            ".",
            false,
            "main",
            List.of(new DaemonDirectoryCodec.DirectoryEntry("Main.java", "src/Main.java")));
    gatewayNode1.receive(
        conn1.connectionId(),
        ENVELOPE_CODEC.encode(
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.DIRECTORY_LISTED,
                DEV,
                null,
                2,
                DIRECTORY_CODEC.encodeListed(listed))));

    // 5. Node 2 收到 RESPONSE_CHANNEL 通知
    List<UUID> queryIds =
        jdbcTemplate.query(
            "select id from environment_query where environment_name = ?",
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            DEV.value());
    assertEquals(1, queryIds.size());
    UUID queryId = queryIds.get(0);

    coordinatorNode2.onResponseNotification(queryId.toString());

    // 6. Node 2 的 future 成功解析
    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);
    EnvironmentDirectoryListResult.Loaded loaded =
        assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, result);
    assertEquals("src", loaded.listing().getPath());
    assertEquals("Main.java", loaded.listing().getEntries().get(0).getName());
  }

  @Test
  void staleOwnerCannotClaimCrossNodeQuery() {
    // 1. Node 1 曾经拥有 DEV 环境
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. Node 2 发起查询
    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode2.listDirectory(DEV, ".", Duration.ofSeconds(5));

    // 3. 模拟 Node 1 租约在数据库中过期/被接管
    jdbcTemplate.update(
        "update live_environment set owner_node_id = ? where environment_name = ?",
        node2,
        DEV.value());

    // Node 1 收到通知尝试认领 -> 0 rows claimed
    coordinatorNode1.onRequestNotification(DEV.value());

    // Node 1 的 daemon 不会收到 LIST_DIRECTORY 帧
    assertEquals(1, conn1.envelopes().size()); // 仅 WELCOME
    assertFalse(future.isDone());
  }

  @Test
  void queryTimeoutAndExpiry() throws Exception {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 发起超短超时查询
    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode2.listDirectory(DEV, ".", Duration.ofMillis(50));

    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);
    EnvironmentDirectoryListResult.Failed failed =
        assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(EnvironmentDirectoryFailureCode.TIMEOUT, failed.code());

    // 模拟数据库中将 expires_at 设为过去，并触发 resync 清理
    jdbcTemplate.update(
        "update environment_query set created_at = statement_timestamp() - interval '100 seconds', expires_at = statement_timestamp() - interval '1 second'");
    coordinatorNode2.onResync();

    String status =
        jdbcTemplate.queryForObject("select status from environment_query limit 1", String.class);
    assertEquals("EXPIRED", status);
  }

  private static String hello(long sequence, String daemonId, EnvironmentName environmentName) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.HELLO,
            environmentName,
            null,
            sequence,
            "{\"daemonId\":\""
                + daemonId
                + "\",\"protocolVersion\":"
                + DaemonProtocol.VERSION
                + ",\"capabilityCatalogVersion\":\""
                + EnvironmentCapabilityCatalog.version()
                + "\",\"gatewayToken\":\""
                + GATEWAY_TOKEN
                + "\"}"));
  }

  private static String ready(long sequence, EnvironmentName environmentName) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.READY,
            environmentName,
            null,
            sequence,
            CAPABILITIES_CODEC.encode(CAPABILITIES)));
  }

  private static String heartbeat(long sequence, EnvironmentName environmentName) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.HEARTBEAT,
            environmentName,
            null,
            sequence,
            "{}"));
  }

  private static final class FakeConnection implements EnvironmentDaemonConnection {
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
    public synchronized boolean sendText(String text) {
      if (!isOpen()) {
        return false;
      }
      envelopes.add(ENVELOPE_CODEC.decode(text));
      return true;
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

  private static List<DaemonMessageType> messageTypes(List<DaemonEnvelope> envelopes) {
    return envelopes.stream().map(DaemonEnvelope::messageType).toList();
  }

  private static final DaemonCapabilitiesCodec CAPABILITIES_CODEC = new DaemonCapabilitiesCodec();
}

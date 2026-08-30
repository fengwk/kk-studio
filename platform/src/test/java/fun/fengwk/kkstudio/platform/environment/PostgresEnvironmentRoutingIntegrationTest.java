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

import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceStore;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryEntry;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;
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
  private static final DaemonCapabilityInvokeCodec INVOKE_CODEC = new DaemonCapabilityInvokeCodec();
  private static final DaemonCapabilityResultCodec RESULT_CODEC = new DaemonCapabilityResultCodec();
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

    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();
    properties.setDaemonToken(GATEWAY_TOKEN);
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);

    this.gatewayNode1 =
        new EnvironmentDaemonGateway(
            registryNode1, properties, snapshot, Clock.systemUTC(), env -> {}, coordinatorNode1);

    this.gatewayNode2 =
        new EnvironmentDaemonGateway(
            registryNode2, properties, snapshot, Clock.systemUTC(), env -> {}, coordinatorNode2);
  }

  @Test
  void localHelloAcquiresRouteAndAllowsReady() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);

    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));

    LiveEnvironment env = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertEquals("daemon-1", env.daemonId());
    assertEquals(node1, env.ownerNodeId());
    assertNotNull(env.routeToken());

    // 检查是否回复 WELCOME
    List<DaemonEnvelope> envelopes = conn1.envelopes();
    assertEquals(1, envelopes.size());
    assertEquals(DaemonMessageType.WELCOME, envelopes.get(0).messageType());

    // 发送 READY
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    LiveEnvironment readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());
    assertNotNull(readyEnv.daemonCapabilities());
  }

  @Test
  void conflictingHelloFromDifferentDaemonIdFailsTerminal() {
    // 1. Node 1 成功绑定 DEV (daemon-1)
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. Node 2 收到同一 DEV 的 HELLO (daemon-2)
    FakeConnection conn2 = new FakeConnection("conn-2");
    gatewayNode2.open(conn2);
    gatewayNode2.receive(conn2.connectionId(), hello(0, "daemon-2", DEV));

    // 检查 conn2 是否收到 ERROR (ENVIRONMENT_NAME_CONFLICT) 并被关闭
    List<DaemonEnvelope> envelopes2 = conn2.envelopes();
    assertEquals(1, envelopes2.size());
    assertEquals(DaemonMessageType.ERROR, envelopes2.get(0).messageType());
    assertTrue(
        envelopes2
            .get(0)
            .payloadJson()
            .contains(DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT));
    assertTrue(conn2.closed);

    // Node 1 的路由依然完好
    LiveEnvironment env = registryNode1.find(DEV).orElseThrow();
    assertEquals(node1, env.ownerNodeId());
    assertEquals("daemon-1", env.daemonId());
  }

  @Test
  void retryLaterFromSameDaemonIdWhenActive() {
    // 1. Node 1 成功绑定 DEV (daemon-1)
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. 另一个连接（或 Node 2）使用相同的 daemon-1 尝试绑定 DEV
    FakeConnection conn2 = new FakeConnection("conn-2");
    gatewayNode2.open(conn2);
    gatewayNode2.receive(conn2.connectionId(), hello(0, "daemon-1", DEV));

    // 检查 conn2 是否收到 ERROR (RETRY_LATER)
    List<DaemonEnvelope> envelopes2 = conn2.envelopes();
    assertEquals(1, envelopes2.size());
    assertEquals(DaemonMessageType.ERROR, envelopes2.get(0).messageType());
    assertTrue(envelopes2.get(0).payloadJson().contains(DaemonProtocol.ERROR_CODE_RETRY_LATER));
    assertTrue(conn2.closed);
  }

  @Test
  void expiredLeaseCanBeTakenOverByNewDaemon() {
    // 1. Node 1 绑定 DEV
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. 模拟租约在数据库中过期
    jdbcTemplate.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        DEV.value());

    // 3. Node 2 上的 daemon-2 尝试绑定 DEV -> 成功接管
    FakeConnection conn2 = new FakeConnection("conn-2");
    gatewayNode2.open(conn2);
    gatewayNode2.receive(conn2.connectionId(), hello(0, "daemon-2", DEV));

    LiveEnvironment env = registryNode2.find(DEV).orElseThrow();
    assertEquals(node2, env.ownerNodeId());
    assertEquals("daemon-2", env.daemonId());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());

    // 检查 conn2 收到 WELCOME
    assertEquals(1, conn2.envelopes().size());
    assertEquals(DaemonMessageType.WELCOME, conn2.envelopes().get(0).messageType());
  }

  @Test
  void routeFenceRejectsStaleTokenUpdates() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));

    // 在数据库中篡改 route_token
    jdbcTemplate.update(
        "update live_environment set route_token = ? where environment_name = ?",
        UUID.randomUUID(),
        DEV.value());

    // 发送 READY -> 应当因为 fence 失败而被关闭
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    assertTrue(conn1.closed);
  }

  @Test
  void disconnectPreservesConnectingWithGracePeriod() {
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

    // 检查本地 daemon 是否收到了 INVOKE 帧
    List<DaemonEnvelope> envelopes = conn1.envelopes();
    assertEquals(2, envelopes.size()); // WELCOME + INVOKE
    DaemonEnvelope invokeEnvelope = envelopes.get(1);
    assertEquals(DaemonMessageType.INVOKE, invokeEnvelope.messageType());

    DaemonCapabilityInvokeCodec.InvokeRequest req =
        INVOKE_CODEC.decode(invokeEnvelope.payloadJson());
    assertEquals(EnvironmentCapabilityIds.FS_LIST_DIRECTORY, req.capabilityId());

    // daemon 回复 COMPLETED
    EnvironmentDirectoryListing listing =
        new EnvironmentDirectoryListing(
            ".", ".", ".", false, "main", List.of(new EnvironmentDirectoryEntry("src", "src")));
    String listingJson = OBJECT_MAPPER.writeValueAsString(listing);
    EnvironmentCapabilityResult capResult =
        EnvironmentCapabilityResult.json(invokeEnvelope.invocationId(), listingJson);
    String completedJson = RESULT_CODEC.encodeCompleted(capResult, DUMMY_STORE);
    gatewayNode1.receive(
        conn1.connectionId(),
        ENVELOPE_CODEC.encode(
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.COMPLETED,
                DEV,
                invokeEnvelope.invocationId(),
                2,
                completedJson)));

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

    // 检查 Node 1 上的 daemon 是否收到了 INVOKE 帧
    List<DaemonEnvelope> envelopes = conn1.envelopes();
    assertEquals(2, envelopes.size()); // WELCOME + INVOKE
    DaemonEnvelope invokeEnvelope = envelopes.get(1);
    assertEquals(DaemonMessageType.INVOKE, invokeEnvelope.messageType());

    DaemonCapabilityInvokeCodec.InvokeRequest req =
        INVOKE_CODEC.decode(invokeEnvelope.payloadJson());
    assertEquals(EnvironmentCapabilityIds.FS_LIST_DIRECTORY, req.capabilityId());

    // 4. Node 1 的 daemon 返回结果
    EnvironmentDirectoryListing listing =
        new EnvironmentDirectoryListing(
            "src",
            "src",
            ".",
            false,
            "main",
            List.of(new EnvironmentDirectoryEntry("Main.java", "src/Main.java")));
    String listingJson = OBJECT_MAPPER.writeValueAsString(listing);
    EnvironmentCapabilityResult capResult =
        EnvironmentCapabilityResult.json(invokeEnvelope.invocationId(), listingJson);
    String completedJson = RESULT_CODEC.encodeCompleted(capResult, DUMMY_STORE);
    gatewayNode1.receive(
        conn1.connectionId(),
        ENVELOPE_CODEC.encode(
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.COMPLETED,
                DEV,
                invokeEnvelope.invocationId(),
                2,
                completedJson)));

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
  void crossNodeMailboxSerializesQueriesPerEnvironment() throws Exception {
    FakeConnection conn1 = new FakeConnection("conn-serial");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "daemon-1", DEV));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    CompletableFuture<EnvironmentDirectoryListResult> rootFuture =
        gatewayNode2.listDirectory(DEV, ".", Duration.ofSeconds(5));
    CompletableFuture<EnvironmentDirectoryListResult> srcFuture =
        gatewayNode2.listDirectory(DEV, "src", Duration.ofSeconds(5));

    // 单次通知应启动 drain，但同一 Environment 在首个 terminal 前只能发送一个 INVOKE。
    coordinatorNode1.onRequestNotification(DEV.value());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(conn1.envelopes()));
    DaemonEnvelope firstInvoke = conn1.envelopes().get(1);

    completeDirectoryInvocation(conn1, firstInvoke, 2);

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.INVOKE),
        messageTypes(conn1.envelopes()));
    DaemonEnvelope secondInvoke = conn1.envelopes().get(2);
    assertFalse(firstInvoke.invocationId().equals(secondInvoke.invocationId()));

    completeDirectoryInvocation(conn1, secondInvoke, 3);

    List<UUID> queryIds =
        jdbcTemplate.query(
            "select id from environment_query where environment_name = ? order by created_at, id",
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            DEV.value());
    assertEquals(2, queryIds.size());
    queryIds.forEach(id -> coordinatorNode2.onResponseNotification(id.toString()));

    EnvironmentDirectoryListResult.Loaded root =
        assertInstanceOf(
            EnvironmentDirectoryListResult.Loaded.class, rootFuture.get(5, TimeUnit.SECONDS));
    EnvironmentDirectoryListResult.Loaded src =
        assertInstanceOf(
            EnvironmentDirectoryListResult.Loaded.class, srcFuture.get(5, TimeUnit.SECONDS));
    assertEquals(".", root.listing().getPath());
    assertEquals("src", src.listing().getPath());
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

    // Node 1 的 daemon 不会收到 INVOKE 帧
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

  private void completeDirectoryInvocation(
      FakeConnection connection, DaemonEnvelope invokeEnvelope, long sequence) throws Exception {
    DaemonCapabilityInvokeCodec.InvokeRequest request =
        INVOKE_CODEC.decode(invokeEnvelope.payloadJson());
    String path = OBJECT_MAPPER.readTree(request.argumentsJson()).path("path").asText();
    String displayPath = ".".equals(path) ? "." : path.substring(path.lastIndexOf('/') + 1);
    String parentPath;
    if (".".equals(path) || path.lastIndexOf('/') < 0) {
      parentPath = ".";
    } else {
      parentPath = path.substring(0, path.lastIndexOf('/'));
    }
    EnvironmentDirectoryListing listing =
        new EnvironmentDirectoryListing(path, displayPath, parentPath, false, null, List.of());
    String listingJson = OBJECT_MAPPER.writeValueAsString(listing);
    String completedJson =
        RESULT_CODEC.encodeCompleted(
            EnvironmentCapabilityResult.json(invokeEnvelope.invocationId(), listingJson),
            DUMMY_STORE);
    gatewayNode1.receive(
        connection.connectionId(),
        ENVELOPE_CODEC.encode(
            new DaemonEnvelope(
                DaemonProtocol.VERSION,
                DaemonMessageType.COMPLETED,
                DEV,
                invokeEnvelope.invocationId(),
                sequence,
                completedJson)));
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
  private static final DaemonResourceStore DUMMY_STORE =
      new DaemonResourceStore() {
        @Override
        public DaemonResourceRef store(byte[] bytes, String mediaType) {
          return new DaemonResourceRef("file:///dummy", mediaType, null, (long) bytes.length, "00");
        }

        @Override
        public byte[] read(DaemonResourceRef ref) {
          return new byte[0];
        }
      };
}

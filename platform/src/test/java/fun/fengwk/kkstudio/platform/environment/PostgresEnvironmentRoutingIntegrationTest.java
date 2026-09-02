package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
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
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
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

/** 验证基于 PostgreSQL 的多节点 Environment 路由租约、原子认领、状态围栏与跨节点只读弱交付目录查询信箱。 */
class PostgresEnvironmentRoutingIntegrationTest extends PostgresSchemaSupport {

  private static final EnvironmentId DEV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String REGISTRATION_TOKEN = "test-token";
  private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          List.of());

  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonCapabilityInvokeCodec INVOKE_CODEC = new DaemonCapabilityInvokeCodec();
  private static final DaemonCapabilityResultCodec RESULT_CODEC = new DaemonCapabilityResultCodec();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private JdbcTemplate jdbcTemplate;
  private UUID node1;
  private UUID node2;
  private EnvironmentRegistry registryNode1;
  private EnvironmentRegistry registryNode2;
  private EnvironmentQueryCoordinator coordinatorNode1;
  private EnvironmentQueryCoordinator coordinatorNode2;
  private EnvironmentDaemonGateway gatewayNode1;
  private EnvironmentDaemonGateway gatewayNode2;
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
          public boolean existsByNameExcludingId(String name, UUID excludeId) {
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

    this.coordinatorNode1 = new EnvironmentQueryCoordinator(jdbcTemplate, OBJECT_MAPPER, node1);
    this.coordinatorNode2 = new EnvironmentQueryCoordinator(jdbcTemplate, OBJECT_MAPPER, node2);

    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettings.DEFAULT);

    this.gatewayNode1 =
        new EnvironmentDaemonGateway(
            registryNode1,
            environmentRepository,
            properties,
            snapshot,
            Clock.systemUTC(),
            env -> {},
            coordinatorNode1);

    this.gatewayNode2 =
        new EnvironmentDaemonGateway(
            registryNode2,
            environmentRepository,
            properties,
            snapshot,
            Clock.systemUTC(),
            env -> {},
            coordinatorNode2);
  }

  @Test
  void localHelloAcquiresRouteAndAllowsReady() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);

    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));

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
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());
    assertNotNull(readyEnv.daemonCapabilities());
  }

  @Test
  void invalidRegistrationTokenFailsTerminal() {
    FakeConnection conn1 = new FakeConnection("conn-invalid");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, "wrong-token"));

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
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. 另一个连接（如 Node 2）尝试绑定 DEV -> RETRY_LATER
    FakeConnection conn2 = new FakeConnection("conn-2");
    gatewayNode2.open(conn2);
    gatewayNode2.receive(conn2.connectionId(), hello(0, REGISTRATION_TOKEN));

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
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. 模拟租约在数据库中过期
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());

    // 3. Node 2 尝试绑定 DEV -> 成功接管
    FakeConnection conn2 = new FakeConnection("conn-2");
    gatewayNode2.open(conn2);
    gatewayNode2.receive(conn2.connectionId(), hello(0, REGISTRATION_TOKEN));

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
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));

    // 在数据库中篡改 lease_token
    jdbcTemplate.update(
        "update environment_connection set lease_token = ? where environment_id = ?",
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
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());

    // 断开连接
    gatewayNode1.close(conn1.connectionId());

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
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());

    // 断开连接，进入宽限期
    gatewayNode1.close(conn1.connectionId());

    EnvironmentConnection disconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, disconnectedEnv.status());
    // 宽限期依然在未来
    assertTrue(disconnectedEnv.leaseUntil().isAfter(Instant.now()));

    // 异节点 Node 2 在宽限期内尝试连接 -> 必须被拒绝（收到 RETRY_LATER 并关闭）
    FakeConnection conn2 = new FakeConnection("conn-2");
    gatewayNode2.open(conn2);
    gatewayNode2.receive(conn2.connectionId(), hello(0, REGISTRATION_TOKEN));
    assertTrue(conn2.closed);
    assertEquals(DaemonMessageType.ERROR, conn2.envelopes().get(0).messageType());
    assertTrue(
        conn2.envelopes().get(0).payloadJson().contains(DaemonProtocol.ERROR_CODE_RETRY_LATER));

    // 同节点 Node 1 在宽限期内重新连接（不手工回拨 lease_until）-> 必须成功收到 WELCOME
    FakeConnection conn1b = new FakeConnection("conn-1b");
    gatewayNode1.open(conn1b);
    gatewayNode1.receive(conn1b.connectionId(), hello(0, REGISTRATION_TOKEN));
    assertFalse(conn1b.closed);
    assertEquals(1, conn1b.envelopes().size());
    assertEquals(DaemonMessageType.WELCOME, conn1b.envelopes().get(0).messageType());

    // 路由成功被换发新 leaseToken
    EnvironmentConnection reconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(node1, reconnectedEnv.ownerNodeId());
    assertNotEquals(readyEnv.leaseToken(), reconnectedEnv.leaseToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, reconnectedEnv.status());
  }

  @Test
  void queryLocalRouteDirectly() throws Exception {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
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
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 2. Node 2 发起对 DEV 的目录查询
    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode2.listDirectory(DEV, "src", Duration.ofSeconds(5));

    assertFalse(future.isDone());

    // 3. Node 1 收到 REQUEST_CHANNEL 通知
    coordinatorNode1.onRequestNotification(DEV.toString());

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
            "select id from environment_directory_query where environment_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            DEV.value());
    assertEquals(1, queryIds.size());
    UUID queryId = queryIds.get(0);

    coordinatorNode2.onResponseNotification(queryId.toString());

    // 6. Node 2 的 future 成功解析（并且由 DELETE RETURNING 消费后，数据库行被物理删除）
    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);
    EnvironmentDirectoryListResult.Loaded loaded =
        assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, result);
    assertEquals("src", loaded.listing().getPath());
    assertEquals("Main.java", loaded.listing().getEntries().get(0).getName());

    // 确认已 DELETE RETURNING 消费
    Integer remainingCount =
        jdbcTemplate.queryForObject(
            "select count(1) from environment_directory_query where id = ?",
            Integer.class,
            queryId);
    assertEquals(0, remainingCount);
  }

  @Test
  void staleOwnerCannotClaimCrossNodeQuery() {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // Node 2 发起查询
    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode2.listDirectory(DEV, ".", Duration.ofSeconds(5));

    // 模拟 Node 1 租约在数据库中被接管 (owner_node_id 改变)
    jdbcTemplate.update(
        "update environment_connection set owner_node_id = ? where environment_id = ?",
        node2,
        DEV.value());

    // Node 1 收到通知尝试认领 -> 0 rows claimed
    coordinatorNode1.onRequestNotification(DEV.toString());

    // Node 1 的 daemon 不会收到 INVOKE 帧
    assertEquals(1, conn1.envelopes().size()); // 仅 WELCOME
    assertFalse(future.isDone());
  }

  @Test
  void queryTimeoutAndCleanup() throws Exception {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 发起超短超时查询
    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode2.listDirectory(DEV, ".", Duration.ofMillis(50));

    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);
    EnvironmentDirectoryListResult.Failed failed =
        assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(EnvironmentDirectoryFailureCode.TIMEOUT, failed.code());

    // 超时后行已被直接 DELETE
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(1) from environment_directory_query where environment_id = ?",
            Integer.class,
            DEV.value());
    assertEquals(0, count);
  }

  /**
   * 测试意图：证明当已认领的 RUNNING 目录查询在完成回调前 deadline 被置为过去（超时）时， 迟到的 complete 或 fail 不得更新终态（受 deadline_at >
   * statement_timestamp() 保护）， 也不得向响应信道发送通知，过期行由 cleanQuery 删除。
   */
  @Test
  void runningMailboxQueryWithExpiredDeadlineRejectsLateCompletionAndFailure() throws Exception {
    FakeConnection conn1 = new FakeConnection("conn-1");
    gatewayNode1.open(conn1);
    gatewayNode1.receive(conn1.connectionId(), hello(0, REGISTRATION_TOKEN));
    gatewayNode1.receive(conn1.connectionId(), ready(1, DEV));

    // 提交一个查询，设置较长的 30s 初始超时
    CompletableFuture<EnvironmentDirectoryListResult> future =
        gatewayNode2.listDirectory(DEV, ".", Duration.ofSeconds(30));

    // Node 1 认领该查询
    coordinatorNode1.onRequestNotification(DEV.toString());

    // 验证 Node 1 的 daemon 收到了 INVOKE
    assertEquals(2, conn1.envelopes().size());
    DaemonEnvelope invokeEnvelope = conn1.envelopes().get(1);
    assertEquals(DaemonMessageType.INVOKE, invokeEnvelope.messageType());

    // 验证数据库中该 query 状态为 RUNNING
    String status =
        jdbcTemplate.queryForObject(
            "select status from environment_directory_query where environment_id = ?",
            String.class,
            DEV.value());
    assertEquals("RUNNING", status);

    // 人工将该 RUNNING query 的 deadline_at 调整为过去（同时保证 deadline_at > created_at 满足约束）
    jdbcTemplate.update(
        "update environment_directory_query set created_at = statement_timestamp() - interval '10 seconds', deadline_at = statement_timestamp() - interval '5 seconds' where environment_id = ?",
        DEV.value());

    // daemon 迟到的 COMPLETED 结果到达
    completeDirectoryInvocation(conn1, invokeEnvelope, 2);

    // 状态必须仍然是 RUNNING，绝不能被迟到的 callback 改成 SUCCEEDED
    String statusAfterLate =
        jdbcTemplate.queryForObject(
            "select status from environment_directory_query where environment_id = ?",
            String.class,
            DEV.value());
    assertEquals("RUNNING", statusAfterLate);

    // 执行过期清理 onResync -> 该 query 必须被清理删除
    coordinatorNode1.onResync();
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(1) from environment_directory_query where environment_id = ?",
            Integer.class,
            DEV.value());
    assertEquals(0, count);
  }

  private static String hello(long sequence, String token) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.HELLO,
            null,
            null,
            sequence,
            "{\"protocolVersion\":"
                + DaemonProtocol.VERSION
                + ",\"capabilityCatalogVersion\":\""
                + EnvironmentCapabilityCatalog.version()
                + "\",\"registrationToken\":\""
                + token
                + "\"}"));
  }

  private static String ready(long sequence, EnvironmentId environmentId) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.READY,
            environmentId,
            null,
            sequence,
            CAPABILITIES_CODEC.encode(CAPABILITIES)));
  }

  private static String heartbeat(long sequence, EnvironmentId environmentId) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.HEARTBEAT,
            environmentId,
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

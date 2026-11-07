package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.Architecture;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.EnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.OperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.Ready;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeBody;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonAdmission;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonHandshake;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonOfferResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSession;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.daemon.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.server.EnvironmentServerConfiguration;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
          "1.0.9",
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX,
              "UTC",
              "dev",
              "/home/dev",
              "Linux environment.",
              "/tmp/kk-studio",
              "amd64"));

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
    JdbcClient jdbcClient = JdbcClient.create(ds);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(ds);
    EnvironmentChangeNotifier notifier = new EnvironmentChangeNotifier(jdbcClient);

    // 播种稳定 environment 卡片
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token_hash, version) values (?, 'dev-env', encode(sha256(convert_to(?, 'UTF8')), 'hex'), 0)",
        DEV.value(),
        REGISTRATION_TOKEN);

    // 路由只允许按注册令牌读取；误用其它仓库操作必须立即失败。
    this.environmentRepository =
        mock(
            EnvironmentRepository.class,
            invocation -> {
              throw new UnsupportedOperationException("routing test only reads registration");
            });
    EnvironmentRepository reads = new EnvironmentRepository(jdbcClient, notifier);
    doAnswer(invocation -> reads.getByRegistrationTokenHash(invocation.getArgument(0)))
        .when(environmentRepository)
        .getByRegistrationTokenHash(anyString());

    this.node1 = UUID.randomUUID();
    this.node2 = UUID.randomUUID();

    this.registryNode1 =
        new EnvironmentRegistry(jdbcClient, node1, Clock.systemUTC(), transactionManager, notifier);
    this.registryNode2 =
        new EnvironmentRegistry(jdbcClient, node2, Clock.systemUTC(), transactionManager, notifier);

    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT);
    EnvironmentSessionListener sessionListener = env -> {};
    EnvironmentServerConfiguration serverConfiguration = new EnvironmentServerConfiguration();
    this.serverNode1 =
        serverConfiguration.environmentDaemonServer(
            registryNode1,
            environmentRepository,
            sessionListener,
            new StaticObjectProvider<>(null),
            UNUSED_TICKET_SERVICE,
            snapshot);
    this.serverNode2 =
        serverConfiguration.environmentDaemonServer(
            registryNode2,
            environmentRepository,
            sessionListener,
            new StaticObjectProvider<>(null),
            UNUSED_TICKET_SERVICE,
            snapshot);
  }

  @Test
  void localHelloAcquiresRouteAndAllowsReady() {
    DaemonAdmission admission =
        serverNode1.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission);
    DaemonAdmission.Admitted admitted = (DaemonAdmission.Admitted) admission;

    EnvironmentConnection env = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertEquals(node1, env.ownerNodeId());
    assertNotNull(env.leaseToken());

    FakeControlChannel conn1 = new FakeControlChannel("conn-1");
    DaemonSession session = serverNode1.open(admitted, conn1, null);

    // 检查是否回复 SETTINGS
    List<Envelope> envelopes = conn1.envelopes();
    assertEquals(1, envelopes.size());
    assertEquals(MessageType.SETTINGS, envelopes.get(0).getMessageType());
    assertEquals(DEV.value().toString(), envelopes.get(0).getEnvironmentId());

    // 发送 READY
    session.receive(readyEnvelope(DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());
    assertNotNull(readyEnv.daemonCapabilities());
  }

  @Test
  void invalidRegistrationTokenFailsTerminal() {
    DaemonAdmission admission =
        serverNode1.admit(new DaemonHandshake("wrong-token", DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Rejected.class, admission);
  }

  @Test
  void retryLaterWhenActiveRouteExists() {
    // 1. Node 1 成功绑定 DEV
    DaemonAdmission admission1 =
        serverNode1.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission1);
    DaemonAdmission.Admitted admitted1 = (DaemonAdmission.Admitted) admission1;
    FakeControlChannel conn1 = new FakeControlChannel("conn-1");
    DaemonSession session1 = serverNode1.open(admitted1, conn1, null);
    session1.receive(readyEnvelope(DEV));

    // 2. 另一个节点（Node 2）尝试绑定 DEV -> RetryLater（映射 503 暂不可准入）
    DaemonAdmission admission2 =
        serverNode2.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.RetryLater.class, admission2);

    // Node 1 的路由依然完好
    EnvironmentConnection env = registryNode1.find(DEV).orElseThrow();
    assertEquals(node1, env.ownerNodeId());
  }

  @Test
  void expiredLeaseCanBeTakenOver() {
    // 1. Node 1 绑定 DEV
    DaemonAdmission admission1 =
        serverNode1.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission1);
    DaemonAdmission.Admitted admitted1 = (DaemonAdmission.Admitted) admission1;
    FakeControlChannel conn1 = new FakeControlChannel("conn-1");
    DaemonSession session1 = serverNode1.open(admitted1, conn1, null);
    session1.receive(readyEnvelope(DEV));

    // 2. 模拟租约在数据库中过期
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());

    // 3. Node 2 尝试绑定 DEV -> 成功接管
    DaemonAdmission admission2 =
        serverNode2.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission2);
    DaemonAdmission.Admitted admitted2 = (DaemonAdmission.Admitted) admission2;
    FakeControlChannel conn2 = new FakeControlChannel("conn-2");
    DaemonSession session2 = serverNode2.open(admitted2, conn2, null);

    EnvironmentConnection env = registryNode2.find(DEV).orElseThrow();
    assertEquals(node2, env.ownerNodeId());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());

    // 检查 conn2 收到 SETTINGS
    assertEquals(1, conn2.envelopes().size());
    assertEquals(MessageType.SETTINGS, conn2.envelopes().get(0).getMessageType());
  }

  @Test
  void routeFenceRejectsStaleTokenUpdates() {
    DaemonAdmission admission1 =
        serverNode1.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission1);
    DaemonAdmission.Admitted admitted1 = (DaemonAdmission.Admitted) admission1;
    FakeControlChannel conn1 = new FakeControlChannel("conn-1");
    DaemonSession session1 = serverNode1.open(admitted1, conn1, null);

    // 在数据库中篡改 lease_token
    jdbcTemplate.update(
        "update environment_connection set lease_token = ? where environment_id = ?",
        UUID.randomUUID(),
        DEV.value());

    // 发送 READY -> 应当因为 fence 失败而被关闭
    session1.receive(readyEnvelope(DEV));

    assertTrue(conn1.closed);
  }

  /**
   * 测试意图：断开连接只回退 status 并保留宽限租约，最近一次被接受的宿主 metadata 仍随连接行保留，Environment 与 Prompt 据此可继续投影 OS、时区、备注与
   * root path。
   */
  @Test
  void disconnectPreservesConnectingWithGracePeriodAndRetainsHostMetadata() {
    DaemonAdmission admission1 =
        serverNode1.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission1);
    DaemonAdmission.Admitted admitted1 = (DaemonAdmission.Admitted) admission1;
    FakeControlChannel conn1 = new FakeControlChannel("conn-1");
    DaemonSession session1 = serverNode1.open(admitted1, conn1, null);
    session1.receive(readyEnvelope(DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());

    // 断开连接
    session1.close();

    EnvironmentConnection disconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, disconnectedEnv.status());
    assertEquals(readyEnv.daemonCapabilities(), disconnectedEnv.daemonCapabilities());
    assertEquals(readyEnv.leaseToken(), disconnectedEnv.leaseToken());
    assertEquals(node1, disconnectedEnv.ownerNodeId());
  }

  /**
   * 测试意图：证明连接断开进入宽限期后，同节点 daemon 无需手工回拨 lease 即可重新发起握手准入成功并换发新 leaseToken 成为
   * CONNECTING；而异节点在宽限期内尝试握手必须被返回 RetryLater（503 暂不可准入）并断开。
   */
  @Test
  void gracePeriodAllowsSameNodeReconnectWithoutManualLeaseRewindWhileBlockingOtherNode() {
    DaemonAdmission admission1 =
        serverNode1.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission1);
    DaemonAdmission.Admitted admitted1 = (DaemonAdmission.Admitted) admission1;
    FakeControlChannel conn1 = new FakeControlChannel("conn-1");
    DaemonSession session1 = serverNode1.open(admitted1, conn1, null);
    session1.receive(readyEnvelope(DEV));

    EnvironmentConnection readyEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, readyEnv.status());

    // 断开连接，进入宽限期
    session1.close();

    EnvironmentConnection disconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, disconnectedEnv.status());
    // 宽限期依然在未来
    assertTrue(disconnectedEnv.leaseUntil().isAfter(Instant.now()));

    // 异节点 Node 2 在宽限期内尝试连接 -> 必须被拒绝（返回 RetryLater）
    DaemonAdmission admission2 =
        serverNode2.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.RetryLater.class, admission2);

    // 同节点 Node 1 在宽限期内重新连接（不手工回拨 lease_until）-> 必须成功收到 SETTINGS
    DaemonAdmission admission1b =
        serverNode1.admit(new DaemonHandshake(REGISTRATION_TOKEN, DAEMON_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Admitted.class, admission1b);
    DaemonAdmission.Admitted admitted1b = (DaemonAdmission.Admitted) admission1b;
    FakeControlChannel conn1b = new FakeControlChannel("conn-1b");
    DaemonSession session1b = serverNode1.open(admitted1b, conn1b, null);
    assertFalse(conn1b.closed);
    assertEquals(1, conn1b.envelopes().size());
    assertEquals(MessageType.SETTINGS, conn1b.envelopes().get(0).getMessageType());

    // 路由成功被换发新 leaseToken
    EnvironmentConnection reconnectedEnv = registryNode1.find(DEV).orElseThrow();
    assertEquals(node1, reconnectedEnv.ownerNodeId());
    assertNotEquals(readyEnv.leaseToken(), reconnectedEnv.leaseToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, reconnectedEnv.status());
  }

  /**
   * 测试意图：release 严格带 token 围栏，成功释放后其他节点可立即 tryAcquire； 对已释放或 token 不匹配的情况返回 false，不改动任何行，且保留
   * runtime_info 等宿主元数据。
   */
  @Test
  void releaseFencesByTokenAllowsImmediateAcquisitionByOtherNodesAndPreservesMetadata() {
    var bindResult = registryNode1.tryAcquire(DEV, REGISTRATION_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.Acquired.class, bindResult);
    UUID leaseToken = ((LeaseBindResult.Acquired) bindResult).leaseToken();
    assertTrue(registryNode1.markReady(DEV, leaseToken, CAPABILITIES, LEASE_DURATION));

    EnvironmentConnection beforeRelease = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, beforeRelease.status());
    assertNotNull(beforeRelease.daemonCapabilities());

    // 异节点或错误 token 释放被围栏拒绝
    UUID foreignToken = UUID.randomUUID();
    assertFalse(registryNode1.release(DEV, foreignToken), "token 不匹配应返回 false");
    assertFalse(registryNode2.release(DEV, leaseToken), "非持有者节点尝试 release 应返回 false");

    // 持有者 release 成功
    assertTrue(registryNode1.release(DEV, leaseToken), "持有者 release 应成功");

    // 已释放的租约重复 release 返回 false
    assertFalse(registryNode1.release(DEV, leaseToken), "已释放的租约重复 release 应返回 false");

    // 释放后状态回退为 CONNECTING，但保留原有的 runtime_info
    EnvironmentConnection afterRelease = registryNode1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, afterRelease.status());
    assertNotNull(afterRelease.daemonCapabilities(), "释放后应保留原有的 runtime_info 元数据");

    // 其他节点立即 tryAcquire 成功
    var node2Bind = registryNode2.tryAcquire(DEV, REGISTRATION_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.Acquired.class, node2Bind);
    UUID node2Token = ((LeaseBindResult.Acquired) node2Bind).leaseToken();
    assertNotNull(node2Token);
    assertNotEquals(leaseToken, node2Token);

    EnvironmentConnection node2Env = registryNode2.find(DEV).orElseThrow();
    assertEquals(node2, node2Env.ownerNodeId());
  }

  /**
   * 测试意图：主动释放必须把租约截止点写为不晚于释放语句时刻，重复 release 立即被围栏拒绝，其他节点立即 tryAcquire 成功。
   *
   * <p>循环数百次放大 {@code timestamptz(3)} 的毫秒舍入窗口：修复前只要某次释放被向上取整到未来，重复 release 就会被接受或接管被阻塞； 修复后每次都必须
   * 通过。
   */
  @Test
  void releasedLeaseIsImmediatelyExpiredForRepeatReleaseAndOtherNodeTakeover() {
    int iterations = 300;
    int repeatReleaseAccepted = 0;
    int blockedTakeovers = 0;
    for (int i = 0; i < iterations; i++) {
      var node1Bind = registryNode1.tryAcquire(DEV, REGISTRATION_TOKEN, LEASE_DURATION);
      assertInstanceOf(LeaseBindResult.Acquired.class, node1Bind, "iteration " + i);
      UUID node1Token = ((LeaseBindResult.Acquired) node1Bind).leaseToken();

      assertTrue(registryNode1.release(DEV, node1Token), "持有者 release 应成功，iteration " + i);
      if (registryNode1.release(DEV, node1Token)) {
        repeatReleaseAccepted++;
      }

      var node2Bind = registryNode2.tryAcquire(DEV, REGISTRATION_TOKEN, LEASE_DURATION);
      if (node2Bind instanceof LeaseBindResult.Acquired acquired) {
        assertNotEquals(node1Token, acquired.leaseToken());
        assertTrue(registryNode2.release(DEV, acquired.leaseToken()), "iteration " + i);
        if (registryNode2.release(DEV, acquired.leaseToken())) {
          repeatReleaseAccepted++;
        }
      } else {
        blockedTakeovers++;
      }
    }

    int repeatReleases = repeatReleaseAccepted;
    int blocked = blockedTakeovers;
    assertAll(
        () -> assertEquals(0, repeatReleases, "已释放租约的重复 release 必须全部返回 false"),
        () -> assertEquals(0, blocked, "释放后其他节点必须全部能立即 tryAcquire"));
  }

  /**
   * 测试意图：宽限期为零的 disconnect 必须让租约立即过期，重复 disconnect 立即被围栏拒绝，其他节点立即 tryAcquire 成功。
   *
   * <p>这是 handshake 准入后未建立会话的 abandon 路径；循环放大毫秒舍入窗口，修复前只要租约截止点被向上取整到未来就会失败。
   */
  @Test
  void zeroGraceDisconnectIsImmediatelyExpiredForRepeatAndOtherNodeTakeover() {
    int iterations = 300;
    int repeatDisconnectAccepted = 0;
    int blockedTakeovers = 0;
    for (int i = 0; i < iterations; i++) {
      var node1Bind = registryNode1.tryAcquire(DEV, REGISTRATION_TOKEN, LEASE_DURATION);
      assertInstanceOf(LeaseBindResult.Acquired.class, node1Bind, "iteration " + i);
      UUID node1Token = ((LeaseBindResult.Acquired) node1Bind).leaseToken();

      assertTrue(
          registryNode1.disconnect(DEV, node1Token, Duration.ZERO),
          "零宽限期 disconnect 应成功，iteration " + i);
      if (registryNode1.disconnect(DEV, node1Token, Duration.ZERO)) {
        repeatDisconnectAccepted++;
      }

      var node2Bind = registryNode2.tryAcquire(DEV, REGISTRATION_TOKEN, LEASE_DURATION);
      if (node2Bind instanceof LeaseBindResult.Acquired acquired) {
        assertNotEquals(node1Token, acquired.leaseToken());
        assertTrue(registryNode2.disconnect(DEV, acquired.leaseToken(), Duration.ZERO));
        if (registryNode2.disconnect(DEV, acquired.leaseToken(), Duration.ZERO)) {
          repeatDisconnectAccepted++;
        }
      } else {
        blockedTakeovers++;
      }
    }

    int repeats = repeatDisconnectAccepted;
    int blocked = blockedTakeovers;
    assertAll(
        () -> assertEquals(0, repeats, "零宽限期 disconnect 的重复调用必须全部返回 false"),
        () -> assertEquals(0, blocked, "零宽限期断开后其他节点必须全部能立即 tryAcquire"));
  }

  private static Envelope readyEnvelope(EnvironmentId environmentId) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.READY)
        .setEnvironmentId(environmentId.value().toString())
        .setReady(
            Ready.newBuilder()
                .setDaemonVersion("1.0.9")
                .setEnvironment(
                    EnvironmentInfo.newBuilder()
                        .setOperatingSystem(OperatingSystem.LINUX)
                        .setTimeZone("UTC")
                        .setUserName("dev")
                        .setHomeDirectory("/home/dev")
                        .setNote("Linux environment.")
                        .setTempDirectory("/tmp/kk-studio")
                        .setArchitecture(Architecture.AMD64)
                        .build())
                .setRuntimeStatus(
                    RuntimeSnapshot.newBuilder()
                        .setRevision(1L)
                        .setStatus(RuntimeBody.newBuilder().setDaemonVersion("1.0.9").build())
                        .build())
                .build())
        .build();
  }

  private static final class FakeControlChannel implements DaemonControlChannel {
    private final String connectionId;
    private final List<Envelope> envelopes = new ArrayList<>();
    private boolean closed;

    private FakeControlChannel(String connectionId) {
      this.connectionId = connectionId;
    }

    public String connectionId() {
      return connectionId;
    }

    @Override
    public boolean isOpen() {
      return !closed;
    }

    @Override
    public synchronized DaemonOfferResult offer(Envelope envelope) {
      if (closed) {
        return DaemonOfferResult.CLOSED;
      }
      envelopes.add(envelope);
      return DaemonOfferResult.ACCEPTED;
    }

    @Override
    public synchronized void drain() {
      closed = true;
    }

    @Override
    public synchronized void closeAfterFlush() {
      closed = true;
    }

    @Override
    public synchronized void close() {
      closed = true;
    }

    private synchronized List<Envelope> envelopes() {
      return List.copyOf(envelopes);
    }
  }

  /** 固定取值的 {@link ObjectProvider}：测试用它表达「没有装配受管更新监听器」。 */
  private static final class StaticObjectProvider<T> implements ObjectProvider<T> {

    private final T value;

    private StaticObjectProvider(T value) {
      this.value = value;
    }

    @Override
    public T getObject() {
      if (value == null) {
        throw new NoSuchBeanDefinitionException(Object.class);
      }
      return value;
    }

    @Override
    public T getObject(Object... args) {
      return getObject();
    }

    @Override
    public T getIfAvailable() {
      return value;
    }
  }
}

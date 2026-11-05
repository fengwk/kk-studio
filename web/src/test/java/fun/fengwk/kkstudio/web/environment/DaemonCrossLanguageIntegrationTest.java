package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonAdmission;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonHandshake;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonLeaseStore;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonOfferResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonRegistration;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonRegistrationDirectory;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSession;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSessions;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentServerSettings;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentUpdateListener;
import fun.fengwk.kkstudio.platform.daemon.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Studio（Java）与 Environment Daemon（Node 24）的跨语言端到端集成测试。
 *
 * <p>Java 侧使用真实 Jetty 12 容器承载 {@link EnvironmentDaemonAdmissionController}， 内部组装生产级 {@link
 * EnvironmentDaemonServer}（会话核心、通道协调与调用跟踪）； Node 侧启动由 {@code daemon/dist/main.js} 支撑的独立进程，两端经物理
 * WebSocket 与 HTTP/2 双向互通。
 */
class DaemonCrossLanguageIntegrationTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String VALID_TOKEN = "valid-registration-token-123456";
  private static final String INVALID_TOKEN = "invalid-registration-token-999999";
  private static final String DAEMON_VERSION = "2.0.0";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private Server server;
  private ServerConnector connector;
  private Scheduler scheduler;
  private ExecutorService executor;
  private ByteBufferPool bufferPool;

  private FakeLeaseStore leaseStore;
  private EnvironmentDaemonServer daemonServer;
  private RecordingSessions recordingSessions;
  private DaemonRegistrationDirectory registrationDirectory;
  private EnvironmentSessionListener sessionListener;

  private CountDownLatch readyLatch;
  private CountDownLatch heartbeatLatch;
  private AtomicReference<DaemonRuntimeSnapshot> lastHeartbeatSnapshot;
  private AtomicReference<EnvironmentServerSettings> networkSettings;

  private Path testDataDir;
  private DaemonProcess daemonProcess;

  @BeforeEach
  void setUp() throws Exception {
    scheduler = new ScheduledExecutorScheduler("test-transport-", false);
    scheduler.start();
    executor = Executors.newVirtualThreadPerTaskExecutor();
    bufferPool = new ArrayByteBufferPool();

    leaseStore = new FakeLeaseStore();
    readyLatch = new CountDownLatch(1);
    heartbeatLatch = new CountDownLatch(1);
    lastHeartbeatSnapshot = new AtomicReference<>();

    registrationDirectory =
        token -> {
          if (VALID_TOKEN.equals(token)) {
            return Optional.of(new DaemonRegistration(ENVIRONMENT_ID, "env-crosslang"));
          }
          return Optional.empty();
        };

    sessionListener =
        new EnvironmentSessionListener() {
          @Override
          public void onEnvironmentReady(EnvironmentId environmentId) {
            readyLatch.countDown();
          }

          @Override
          public void onHeartbeat(
              EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {
            heartbeatLatch.countDown();
          }
        };

    EnvironmentUpdateListener updateListener = (environmentId, result) -> {};
    DaemonResourceTicketService ticketService = new FakeTicketService();
    networkSettings =
        new AtomicReference<>(
            new EnvironmentServerSettings(
                Duration.ofSeconds(60), 16L * 1024 * 1024, 259200L, 1800L, null, ""));
    Supplier<EnvironmentServerSettings> settingsSupplier = networkSettings::get;

    daemonServer =
        new EnvironmentDaemonServer(
            leaseStore,
            registrationDirectory,
            sessionListener,
            updateListener,
            ticketService,
            settingsSupplier);

    recordingSessions = new RecordingSessions(daemonServer);

    EnvironmentDaemonAdmissionController controller =
        new EnvironmentDaemonAdmissionController(
            recordingSessions,
            new StaticListableBeanFactory().getBeanProvider(DaemonReleaseProvider.class),
            scheduler,
            executor,
            bufferPool);

    server = new Server();
    connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler("/");
    JettyWebSocketServletContainerInitializer.configure(context, null);

    context.addServlet(
        new HttpServlet() {
          @Override
          protected void doGet(HttpServletRequest req, HttpServletResponse resp)
              throws IOException {
            controller.handle(req, resp);
          }
        },
        EnvironmentDaemonAdmissionController.PATH);

    server.setHandler(context);
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    DaemonProcess currentProc = daemonProcess;
    daemonProcess = null;
    if (currentProc != null) {
      currentProc.close();
    }

    Path dir = testDataDir;
    testDataDir = null;
    if (dir != null) {
      deleteRecursively(dir);
    }

    if (server != null) {
      server.stop();
    }
    if (scheduler != null) {
      scheduler.stop();
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /**
   * 范围 1：握手契约。 Daemon 带请求头准入 → Studio 发送 SETTINGS → Daemon 回回复 READY（版本正确）→
   * EnvironmentTunnels.local 可见。
   */
  @Test
  void handshakeAdmittedAndReadyAndTunnelVisible() throws Exception {
    startDaemon(VALID_TOKEN);
    awaitReady();

    assertNotNull(leaseStore.readyCapabilities(), "markReady must record capabilities");
    assertEquals(
        DAEMON_VERSION,
        leaseStore.readyCapabilities().daemonVersion(),
        "daemon_version in READY must match daemon version");

    EnvironmentTunnel tunnel = daemonServer.local(ENVIRONMENT_ID).orElse(null);
    assertNotNull(tunnel, "EnvironmentTunnel must be exposed in EnvironmentTunnels.local");
    assertEquals(ENVIRONMENT_ID, tunnel.identity().environmentId());
    assertNotNull(tunnel.identity().daemonInstanceId());
    assertFalse(tunnel.identity().daemonInstanceId().isBlank());

    boolean hasReadyInbound =
        recordingSessions.inboundEnvelopes().stream()
            .anyMatch(
                env ->
                    env.getMessageType() == MessageType.READY
                        && env.hasReady()
                        && DAEMON_VERSION.equals(env.getReady().getDaemonVersion()));
    assertTrue(hasReadyInbound, "READY envelope with correct daemon_version must be received");
  }

  /** Java SETTINGS 经真实 WebSocket/Node 运行时作用于进程，在线修改和清除无需重连。 */
  @Test
  void proxySettingsReachSubprocessesAcrossLanguages() throws Exception {
    networkSettings.set(network("http://first.invalid:8080", "localhost"));
    startDaemon(VALID_TOKEN);
    awaitReady();
    assertTrue(
        proxyEnvironment()
            .contains("http://first.invalid:8080|http://first.invalid:8080|localhost|unset|unset"));

    networkSettings.set(network("http://second.invalid:8080", "internal"));
    daemonServer.reconcileNetworkSettings();
    assertTrue(
        proxyEnvironment()
            .contains(
                "http://second.invalid:8080|http://second.invalid:8080|internal|unset|unset"));

    networkSettings.set(network(null, ""));
    daemonServer.reconcileNetworkSettings();
    assertTrue(proxyEnvironment().contains("unset|unset||unset|unset"));
    assertEquals(
        1,
        recordingSessions.inboundEnvelopes().stream()
            .filter(envelope -> envelope.getMessageType() == MessageType.READY)
            .count());
  }

  private EnvironmentServerSettings network(String url, String bypass) {
    return new EnvironmentServerSettings(
        Duration.ofSeconds(60), 16L * 1024 * 1024, 259200L, 1800L, url, bypass);
  }

  private String proxyEnvironment() throws Exception {
    String command =
        "printf '%s|%s|%s|%s|%s' \"${HTTP_PROXY-unset}\" \"${HTTPS_PROXY-unset}\" "
            + "\"${NO_PROXY-unset}\" \"${http_proxy-unset}\" \"${ALL_PROXY-unset}\"";
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
            new EnvironmentCapabilityCall(
                UUID.randomUUID().toString(),
                "{\"command\":\""
                    + escapeJson(command)
                    + "\",\"workdir\":\""
                    + escapeJson(testDataDir.toAbsolutePath().toString())
                    + "\"}"),
            Duration.ofSeconds(10));
    TestListener listener = new TestListener();
    daemonServer.invoke(ENVIRONMENT_ID, request, listener);
    EnvironmentCapabilityResult result = listener.awaitCompleted(TIMEOUT);
    assertFalse(result.error());
    return extractCombinedText(result);
  }

  /**
   * 范围 2：能力调用生命周期。 调用 fs.read 收到 STARTED 与 COMPLETED 且结果正确；调用 process.exec 时取消收到 CANCELLED 且 reason
   * 非空白。
   */
  @Test
  void capabilityLifecycleFsReadAndProcessExecCancel() throws Exception {
    startDaemon(VALID_TOKEN);
    awaitReady();

    // 1. fs.read
    Path sampleFile = testDataDir.resolve("greeting.txt");
    String sampleContent = "hello-cross-language-kk-studio-" + UUID.randomUUID();
    Files.writeString(sampleFile, sampleContent);

    UUID readInvocationId = UUID.randomUUID();
    EnvironmentCapabilityExecutionRequest readRequest =
        new EnvironmentCapabilityExecutionRequest(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ),
            new EnvironmentCapabilityCall(
                readInvocationId.toString(),
                "{\"path\":\"" + escapeJson(sampleFile.toAbsolutePath().toString()) + "\"}"),
            Duration.ofSeconds(10));

    TestListener readListener = new TestListener();
    daemonServer.invoke(ENVIRONMENT_ID, readRequest, readListener);

    EnvironmentCapabilityResult readResult = readListener.awaitCompleted(TIMEOUT);
    assertNotNull(readResult, "fs.read result must complete");
    assertFalse(readResult.error(), "fs.read must succeed");

    String combinedText = extractCombinedText(readResult);
    assertTrue(
        combinedText.contains(sampleContent),
        "fs.read result must contain file content; got: " + combinedText);

    assertTrue(
        hasInboundEnvelope(MessageType.STARTED, readInvocationId),
        "fs.read must receive STARTED envelope on control stream");
    assertTrue(
        hasInboundEnvelope(MessageType.COMPLETED, readInvocationId),
        "fs.read must receive COMPLETED envelope on control stream");

    // 2. process.exec 取消
    UUID execInvocationId = UUID.randomUUID();
    EnvironmentCapabilityExecutionRequest execRequest =
        new EnvironmentCapabilityExecutionRequest(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
            new EnvironmentCapabilityCall(
                execInvocationId.toString(),
                "{\"command\":\"sleep 10\",\"workdir\":\""
                    + escapeJson(testDataDir.toAbsolutePath().toString())
                    + "\"}"),
            Duration.ofSeconds(15));

    TestListener cancelListener = new TestListener();
    CountDownLatch startedLatch = new CountDownLatch(1);
    recordingSessions.addInboundListener(
        env -> {
          if (env.getMessageType() == MessageType.STARTED
              && execInvocationId.toString().equals(env.getInvocationId())) {
            startedLatch.countDown();
          }
        });

    EnvironmentCapabilityExecutionHandle handle =
        daemonServer.invoke(ENVIRONMENT_ID, execRequest, cancelListener);

    assertTrue(
        startedLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
        "process.exec must report STARTED before cancel");

    handle.cancel();

    EnvironmentCapabilityResult cancelResult = cancelListener.awaitCompleted(TIMEOUT);
    assertNotNull(
        cancelResult,
        "Cancelled invocation must yield completed result under dev cooperative semantics");
    assertTrue(cancelResult.error(), "Cancelled process execution must report error: true");
    assertNotNull(cancelResult.detailsJson(), "Result must include structured details");
    assertTrue(
        cancelResult.detailsJson().contains("\"outcome\":\"CANCELLED\""),
        "Process outcome must be CANCELLED in details; got: " + cancelResult.detailsJson());
    assertEquals(
        1, cancelListener.completedCount.get(), "Completed callback must fire exactly once");
    assertEquals(
        0, cancelListener.errorCount.get(), "No error callback under cooperative completion");

    assertTrue(
        hasInboundEnvelope(MessageType.COMPLETED, execInvocationId),
        "process.exec must receive COMPLETED envelope on control stream");
  }

  /** 范围 3：重连恢复。 强制断开物理 WebSocket 后，同一 Daemon 实例重连，在途调用只发 RECOVER、不重发 INVOKE，最终终态唯一交付。 */
  @Test
  void reconnectionRecoveryReplaysRecoverAndCompletesInFlightCall() throws Exception {
    startDaemon(VALID_TOKEN);
    awaitReady();

    UUID inFlightInvocationId = UUID.randomUUID();
    EnvironmentCapabilityExecutionRequest sleepRequest =
        new EnvironmentCapabilityExecutionRequest(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
            new EnvironmentCapabilityCall(
                inFlightInvocationId.toString(),
                "{\"command\":\"sleep 3\",\"workdir\":\""
                    + escapeJson(testDataDir.toAbsolutePath().toString())
                    + "\"}"),
            Duration.ofSeconds(15));

    TestListener sleepListener = new TestListener();
    CountDownLatch startedLatch = new CountDownLatch(1);
    recordingSessions.addInboundListener(
        env -> {
          if (env.getMessageType() == MessageType.STARTED
              && inFlightInvocationId.toString().equals(env.getInvocationId())) {
            startedLatch.countDown();
          }
        });

    daemonServer.invoke(ENVIRONMENT_ID, sleepRequest, sleepListener);

    assertTrue(
        startedLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
        "Sleep call must start before forcing disconnect");

    int outboundBeforeDisconnect = recordingSessions.outboundEnvelopes().size();

    CountDownLatch recoverSentLatch = new CountDownLatch(1);
    recordingSessions.addOutboundListener(
        env -> {
          if (env.getMessageType() == MessageType.RECOVER
              && inFlightInvocationId.toString().equals(env.getInvocationId())) {
            recoverSentLatch.countDown();
          }
        });

    disconnectAllConnections();

    assertTrue(
        recoverSentLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
        "Studio must send RECOVER for the in-flight invocation upon reconnect" + diagnosticsSafe());

    List<Envelope> reconnectedOutbound =
        recordingSessions
            .outboundEnvelopes()
            .subList(outboundBeforeDisconnect, recordingSessions.outboundEnvelopes().size());

    boolean sentInvoke =
        reconnectedOutbound.stream()
            .anyMatch(
                env ->
                    env.getMessageType() == MessageType.INVOKE
                        && inFlightInvocationId.toString().equals(env.getInvocationId()));

    assertFalse(sentInvoke, "Studio must NOT resend INVOKE for the in-flight invocation");

    EnvironmentCapabilityResult result = sleepListener.awaitCompleted(TIMEOUT);
    assertNotNull(result, "In-flight invocation must complete successfully after reconnection");
    assertFalse(result.error());

    assertEquals(
        1,
        sleepListener.completedCount.get(),
        "Terminal completion must be delivered exactly once");
    assertEquals(
        0, sleepListener.errorCount.get(), "No error must be delivered for recovered call");
  }

  /** 范围 4：准入拒绝。 凭据错误返回 401，Daemon 进入终态失败，子进程按约定退出。 */
  @Test
  void admissionRejectedOnInvalidTokenTerminatesProcess() throws Exception {
    startDaemon(INVALID_TOKEN);

    boolean exited = daemonProcess.waitFor(TIMEOUT);
    assertTrue(
        exited,
        "Daemon process must exit after registration rejected with 401" + diagnosticsSafe());

    assertEquals(
        1, daemonProcess.exitValue(), "Daemon exit code must be 1 on registration failure");
    String stderr = String.join("\n", daemonProcess.stderrLines());
    assertTrue(
        stderr.contains("environment registration is rejected"),
        "Daemon stderr must report terminal registration failure, got:\n" + stderr);
  }

  /**
   * 范围 5：数据面路由打通。 经 EnvironmentTunnel.openHttp 与 openWebSocket，以 /ide/<viewId>/<epoch>/ 请求不存在视图，均得
   * 404。
   */
  @Test
  void dataPlaneRoutingHttpAndExtendedConnectReturn404ForNonexistentView() throws Exception {
    startDaemon(VALID_TOKEN);
    awaitReady();

    EnvironmentTunnel tunnel = daemonServer.local(ENVIRONMENT_ID).orElse(null);
    assertNotNull(tunnel, "EnvironmentTunnel must be available for data plane");

    UUID viewId = UUID.randomUUID();
    long epoch = 1L;
    String nonexistentPath = "/ide/" + viewId + "/" + epoch + "/";

    // 1. HTTP 路由
    TunnelHttpRequest httpReq = new TunnelHttpRequest("GET", nonexistentPath, Map.of());
    TunnelExchange httpExchange = tunnel.openHttp(httpReq);
    TunnelExchange.Response httpResp =
        httpExchange
            .responseHeaders()
            .toCompletableFuture()
            .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals(
        HttpStatus.NOT_FOUND_404,
        httpResp.status(),
        "HTTP request to nonexistent view must return 404" + diagnosticsSafe());
    httpExchange.close();

    // 2. extended CONNECT 路由
    TunnelHttpRequest wsReq = new TunnelHttpRequest("GET", nonexistentPath, Map.of());
    TunnelExchange wsExchange = tunnel.openWebSocket(wsReq);
    TunnelExchange.Response wsResp =
        wsExchange
            .responseHeaders()
            .toCompletableFuture()
            .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals(
        HttpStatus.NOT_FOUND_404,
        wsResp.status(),
        "extended CONNECT request to nonexistent view must return 404" + diagnosticsSafe());
    wsExchange.close();
  }

  /** 范围 6：运行状态与心跳。 Daemon 上报的 HEARTBEAT/RUNTIME_STATUS 被会话核心接受，连接保持 READY 至少一个心跳周期。 */
  @Test
  void runtimeStatusAndHeartbeatPreservesReadySession() throws Exception {
    startDaemon(VALID_TOKEN);
    awaitReady();

    boolean heartbeatReceived = heartbeatLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertTrue(
        heartbeatReceived, "Daemon must emit HEARTBEAT within one interval" + diagnosticsSafe());

    assertTrue(
        leaseStore.heartbeatCalls.get() > 0,
        "leaseStore.heartbeat must be triggered by received HEARTBEAT");

    Envelope heartbeatEnv =
        recordingSessions.inboundEnvelopes().stream()
            .filter(env -> env.getMessageType() == MessageType.HEARTBEAT)
            .findFirst()
            .orElse(null);
    assertNotNull(heartbeatEnv, "HEARTBEAT envelope must be recorded");
    assertTrue(heartbeatEnv.hasHeartbeat(), "HEARTBEAT must have heartbeat payload");
    assertTrue(
        heartbeatEnv.getHeartbeat().hasRuntimeStatus(), "HEARTBEAT must carry runtimeStatus");
    assertEquals(
        DAEMON_VERSION,
        heartbeatEnv.getHeartbeat().getRuntimeStatus().getStatus().getDaemonVersion(),
        "HEARTBEAT runtimeStatus must report matching daemonVersion");

    EnvironmentTunnel tunnel = daemonServer.local(ENVIRONMENT_ID).orElse(null);
    assertNotNull(tunnel, "EnvironmentTunnel must remain READY after heartbeat");

    DaemonRuntimeSnapshot snapshot = daemonServer.runtimeStatus(ENVIRONMENT_ID).orElse(null);
    assertNotNull(snapshot, "Server core must maintain DaemonRuntimeSnapshot");
    assertEquals(DAEMON_VERSION, snapshot.status().daemonVersion());
  }

  /**
   * 范围 7：跨实例 attach 恢复。 Node Daemon 正在执行耗时任务时，第一个 server 实例调用 drain() 优雅排空并释放租约； 第二个 server
   * 实例与第一个实例共用同一个 leaseStore 接管路由准入并进入 READY； 第二个 server 实例通过 attach(environmentId, invocationId,
   * listener) 挂接在途调用， 触发 RECOVER，真实 Node Daemon 查找到 journal 并重新交付 COMPLETED 终态。
   */
  @Test
  void crossInstanceAttachReattachesInFlightCallAndCompletes() throws Exception {
    startDaemon(VALID_TOKEN);
    awaitReady();

    UUID inFlightInvocationId = UUID.randomUUID();
    EnvironmentCapabilityExecutionRequest sleepRequest =
        new EnvironmentCapabilityExecutionRequest(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
            new EnvironmentCapabilityCall(
                inFlightInvocationId.toString(),
                "{\"command\":\"sleep 3\",\"workdir\":\""
                    + escapeJson(testDataDir.toAbsolutePath().toString())
                    + "\"}"),
            Duration.ofSeconds(15));

    TestListener initialListener = new TestListener();
    CountDownLatch startedLatch = new CountDownLatch(1);
    recordingSessions.addInboundListener(
        env -> {
          if (env.getMessageType() == MessageType.STARTED
              && inFlightInvocationId.toString().equals(env.getInvocationId())) {
            startedLatch.countDown();
          }
        });

    daemonServer.invoke(ENVIRONMENT_ID, sleepRequest, initialListener);

    assertTrue(
        startedLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
        "Sleep call must start before drain/handoff");

    // 新 server 实例与第一个实例共用同一个 leaseStore，但自身内存无旧调用记录
    EnvironmentDaemonServer newServer =
        new EnvironmentDaemonServer(
            leaseStore,
            registrationDirectory,
            sessionListener,
            (envId, res) -> {},
            new FakeTicketService(),
            () ->
                new EnvironmentServerSettings(
                    Duration.ofSeconds(60), 16L * 1024 * 1024, 259200L, 1800L, null, ""));
    recordingSessions.setDelegate(newServer);

    // 第一个 server 实例执行 drain()：排空控制流并释放 FakeLeaseStore 租约
    readyLatch = new CountDownLatch(1);
    daemonServer.drain();
    disconnectAllConnections();

    // 等待 Daemon 重新连接并被新 server 准入与进入 READY
    awaitReady();

    // 在新 server 上发起 attach
    TestListener attachListener = new TestListener();
    EnvironmentCapabilityExecutionHandle handle =
        newServer.attach(ENVIRONMENT_ID, inFlightInvocationId, attachListener);
    assertNotNull(handle);

    // 验证新 server 向 Node daemon 发送了 RECOVER
    boolean sentRecover =
        recordingSessions.outboundEnvelopes().stream()
            .anyMatch(
                env ->
                    env.getMessageType() == MessageType.RECOVER
                        && inFlightInvocationId.toString().equals(env.getInvocationId()));
    assertTrue(sentRecover, "New server must emit RECOVER upon attach");

    // 真实 Node daemon 收到 RECOVER 后完成并交付 COMPLETED
    EnvironmentCapabilityResult result = attachListener.awaitCompleted(TIMEOUT);
    assertNotNull(result, "In-flight invocation must complete via attach on new server");
    assertFalse(result.error());
    assertEquals(1, attachListener.completedCount.get());
  }

  private void startDaemon(String token) throws IOException {
    testDataDir = Files.createTempDirectory("kk-daemon-crosslang-");
    daemonProcess = DaemonProcess.start(repoRoot(), connector.getLocalPort(), token, testDataDir);
  }

  private void awaitReady() throws InterruptedException {
    if (!readyLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new AssertionError("Timed out waiting for Daemon READY" + diagnosticsSafe());
    }
  }

  private void disconnectAllConnections() {
    for (EndPoint ep : connector.getConnectedEndPoints()) {
      ep.close();
    }
  }

  private String diagnosticsSafe() {
    DaemonProcess proc = daemonProcess;
    return proc != null ? proc.diagnostics() : "\n[daemon process not started]";
  }

  private boolean hasInboundEnvelope(MessageType type, UUID invocationId) {
    return recordingSessions.inboundEnvelopes().stream()
        .anyMatch(
            env ->
                env.getMessageType() == type
                    && invocationId.toString().equals(env.getInvocationId()));
  }

  private Envelope findInboundEnvelope(MessageType type, UUID invocationId) {
    return recordingSessions.inboundEnvelopes().stream()
        .filter(
            env ->
                env.getMessageType() == type
                    && invocationId.toString().equals(env.getInvocationId()))
        .findFirst()
        .orElse(null);
  }

  private static String extractCombinedText(EnvironmentCapabilityResult result) {
    StringBuilder sb = new StringBuilder();
    for (ResultContent content : result.contents()) {
      if (content instanceof TextResultContent text) {
        sb.append(text.text()).append("\n");
      }
    }
    return sb.toString();
  }

  private static String escapeJson(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static Path repoRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("daemon"))
          && Files.isDirectory(current.resolve("web"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found from user.dir");
  }

  private static void deleteRecursively(Path root) {
    try {
      if (Files.exists(root)) {
        try (Stream<Path> stream = Files.walk(root)) {
          stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
      }
    } catch (IOException ignored) {
    }
  }

  /** 管理真实运行的 Node 24 Daemon 子进程。 */
  private static final class DaemonProcess implements AutoCloseable {

    private final Process process;
    private final Path dataDir;
    private final List<String> stdoutLines = new CopyOnWriteArrayList<>();
    private final List<String> stderrLines = new CopyOnWriteArrayList<>();
    private final Thread stdoutThread;
    private final Thread stderrThread;
    private volatile boolean closed;

    private DaemonProcess(Process process, Path dataDir) {
      this.process = process;
      this.dataDir = dataDir;
      this.stdoutThread = Thread.ofVirtual().name("daemon-stdout-reader").start(this::readStdout);
      this.stderrThread = Thread.ofVirtual().name("daemon-stderr-reader").start(this::readStderr);
    }

    static DaemonProcess start(Path repoRoot, int studioPort, String token, Path dataDir)
        throws IOException {
      Path daemonDist = repoRoot.resolve("daemon/dist");
      Path mainScript = daemonDist.resolve("main.js");
      if (!Files.isRegularFile(mainScript)) {
        throw new IllegalStateException(
            "daemon/dist/main.js not found. Run `npm --prefix daemon ci && npm --prefix daemon run build` first.");
      }

      boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
      if (!isWindows) {
        Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"));
      }

      Path configFile = dataDir.resolve("daemon.json");
      String configJson = "{\"studioUrl\":\"http://127.0.0.1:" + studioPort + "\"}";
      Files.writeString(configFile, configJson, StandardCharsets.UTF_8);

      Path tokenFile = dataDir.resolve("daemon.token");
      Files.writeString(tokenFile, token, StandardCharsets.UTF_8);
      if (!isWindows) {
        Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
      }

      ProcessBuilder pb =
          new ProcessBuilder(
              resolveNodeBinary(),
              mainScript.toAbsolutePath().toString(),
              "--config",
              configFile.toAbsolutePath().toString());
      pb.directory(repoRoot.toFile());
      pb.environment().put("http_proxy", "http://127.0.0.1:1");
      pb.environment().put("ALL_PROXY", "http://127.0.0.1:1");
      Process process = pb.start();
      return new DaemonProcess(process, dataDir);
    }

    private static String resolveNodeBinary() {
      String env = System.getenv("NODE_BIN");
      if (env != null && !env.isBlank()) {
        return env;
      }
      String prop = System.getProperty("node.bin");
      if (prop != null && !prop.isBlank()) {
        return prop;
      }
      return "node";
    }

    boolean waitFor(Duration timeout) throws InterruptedException {
      return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    int exitValue() {
      return process.exitValue();
    }

    List<String> stderrLines() {
      return List.copyOf(stderrLines);
    }

    String diagnostics() {
      List<String> out = tail(stdoutLines);
      List<String> err = tail(stderrLines);
      return "\n--- Daemon stdout (tail 20) ---\n"
          + String.join("\n", out)
          + "\n--- Daemon stderr (tail 20) ---\n"
          + String.join("\n", err)
          + "\n-------------------------------";
    }

    private static List<String> tail(List<String> lines) {
      int from = Math.max(0, lines.size() - 20);
      return List.copyOf(lines.subList(from, lines.size()));
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (process.isAlive()) {
        process.destroy();
        try {
          if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          process.destroyForcibly();
        }
      }
      try {
        stdoutThread.join(1000L);
        stderrThread.join(1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void readStdout() {
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          stdoutLines.add(line);
        }
      } catch (IOException ignored) {
      }
    }

    private void readStderr() {
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          stderrLines.add(line);
        }
      } catch (IOException ignored) {
      }
    }
  }

  /** 包装 DaemonSessions 以捕获双向 Envelope 和支持重连断言。 */
  private static final class RecordingSessions implements DaemonSessions {

    private volatile EnvironmentDaemonServer delegate;
    private final List<Envelope> inboundEnvelopes = new CopyOnWriteArrayList<>();
    private final List<Envelope> outboundEnvelopes = new CopyOnWriteArrayList<>();
    private final List<Consumer<Envelope>> inboundListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Envelope>> outboundListeners = new CopyOnWriteArrayList<>();

    RecordingSessions(EnvironmentDaemonServer delegate) {
      this.delegate = delegate;
    }

    void setDelegate(EnvironmentDaemonServer delegate) {
      this.delegate = delegate;
    }

    List<Envelope> inboundEnvelopes() {
      return Collections.unmodifiableList(inboundEnvelopes);
    }

    List<Envelope> outboundEnvelopes() {
      return Collections.unmodifiableList(outboundEnvelopes);
    }

    void addInboundListener(Consumer<Envelope> listener) {
      inboundListeners.add(listener);
    }

    void addOutboundListener(Consumer<Envelope> listener) {
      outboundListeners.add(listener);
    }

    @Override
    public DaemonAdmission admit(DaemonHandshake handshake) {
      return delegate.admit(handshake);
    }

    @Override
    public void abandon(DaemonAdmission.Admitted admitted) {
      delegate.abandon(admitted);
    }

    @Override
    public DaemonSession open(
        DaemonAdmission.Admitted admitted, DaemonControlChannel control, EnvironmentTunnel tunnel) {
      DaemonControlChannel recordingControl =
          new DaemonControlChannel() {
            @Override
            public boolean isOpen() {
              return control.isOpen();
            }

            @Override
            public DaemonOfferResult offer(Envelope envelope) {
              outboundEnvelopes.add(envelope);
              for (Consumer<Envelope> l : outboundListeners) {
                l.accept(envelope);
              }
              return control.offer(envelope);
            }

            @Override
            public void drain() {
              control.drain();
            }

            @Override
            public void closeAfterFlush() {
              control.closeAfterFlush();
            }

            @Override
            public void close() {
              control.close();
            }
          };

      DaemonSession underlying = delegate.open(admitted, recordingControl, tunnel);
      return new DaemonSession() {
        @Override
        public void receive(Envelope envelope) {
          System.out.println(
              ">>> INBOUND: " + envelope.getMessageType() + " inv=" + envelope.getInvocationId());
          inboundEnvelopes.add(envelope);
          for (Consumer<Envelope> l : inboundListeners) {
            l.accept(envelope);
          }
          underlying.receive(envelope);
        }

        @Override
        public void close() {
          underlying.close();
        }

        @Override
        public void closed() {
          underlying.closed();
        }
      };
    }
  }

  /** 测试用租约存储替身。 */
  private static final class FakeLeaseStore implements DaemonLeaseStore {

    private final AtomicInteger heartbeatCalls = new AtomicInteger(0);
    private volatile DaemonCapabilities readyCapabilities;
    private volatile UUID currentLeaseToken;
    private volatile boolean released;

    DaemonCapabilities readyCapabilities() {
      return readyCapabilities;
    }

    @Override
    public LeaseBindResult tryAcquire(
        EnvironmentId environmentId, String registrationToken, Duration leaseDuration) {
      if (!VALID_TOKEN.equals(registrationToken)) {
        return new LeaseBindResult.Rejected("Invalid token");
      }
      UUID token = UUID.randomUUID();
      this.currentLeaseToken = token;
      this.released = false;
      return new LeaseBindResult.Acquired(token);
    }

    @Override
    public boolean markReady(
        EnvironmentId environmentId,
        UUID leaseToken,
        DaemonCapabilities capabilities,
        Duration leaseDuration) {
      this.readyCapabilities = capabilities;
      return true;
    }

    @Override
    public boolean heartbeat(EnvironmentId environmentId, UUID leaseToken, Duration leaseDuration) {
      heartbeatCalls.incrementAndGet();
      return true;
    }

    @Override
    public boolean runtimeStatusChanged(EnvironmentId environmentId, UUID leaseToken) {
      return holdsReadyLease(environmentId, leaseToken);
    }

    @Override
    public boolean disconnect(
        EnvironmentId environmentId, UUID leaseToken, Duration graceDuration) {
      return true;
    }

    @Override
    public boolean holdsReadyLease(EnvironmentId environmentId, UUID leaseToken) {
      return currentLeaseToken != null && currentLeaseToken.equals(leaseToken) && !released;
    }

    @Override
    public boolean hasActiveLeaseToken(EnvironmentId environmentId, UUID leaseToken) {
      return currentLeaseToken != null && currentLeaseToken.equals(leaseToken) && !released;
    }

    @Override
    public boolean release(EnvironmentId environmentId, UUID leaseToken) {
      if (currentLeaseToken != null && currentLeaseToken.equals(leaseToken)) {
        released = true;
        currentLeaseToken = null;
        return true;
      }
      return false;
    }
  }

  private static final class FakeTicketService implements DaemonResourceTicketService {
    @Override
    public Ticket reserve(
        EnvironmentId environmentId, String invocationId, TransferRequest request) {
      return new Ticket.Failed("not supported in test");
    }

    @Override
    public Ticket commit(EnvironmentId environmentId, String invocationId, UUID uploadId) {
      return new Ticket.Failed("not supported in test");
    }

    @Override
    public void release(EnvironmentId environmentId, String invocationId, UUID uploadId) {}
  }

  /** 收集 capability 异步执行结果的监听器。 */
  private static final class TestListener implements EnvironmentCapabilityExecutionListener {

    private final CompletableFuture<EnvironmentCapabilityResult> completedFuture =
        new CompletableFuture<>();
    private final CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      completedCount.incrementAndGet();
      completedFuture.complete(result);
    }

    @Override
    public void onError(Throwable error) {
      errorCount.incrementAndGet();
      errorFuture.complete(error);
    }

    @Override
    public void onExitConfirmed() {}

    EnvironmentCapabilityResult awaitCompleted(Duration timeout)
        throws InterruptedException, ExecutionException, TimeoutException {
      return completedFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    Throwable awaitError(Duration timeout)
        throws InterruptedException, ExecutionException, TimeoutException {
      return errorFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }
}

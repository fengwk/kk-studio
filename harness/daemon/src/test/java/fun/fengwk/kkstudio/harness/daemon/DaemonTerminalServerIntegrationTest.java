package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.terminal.TerminalLaunchSpec;
import fun.fengwk.kkstudio.harness.daemon.terminal.TerminalRuntimeFixtureMain;
import fun.fengwk.kkstudio.harness.daemon.terminal.TerminalWriter;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.server.DaemonChannel;
import fun.fengwk.kkstudio.harness.environment.server.DaemonLeaseStore;
import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.DaemonRegistration;
import fun.fengwk.kkstudio.harness.environment.server.DaemonResourceTicketService;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentServerSettings;
import fun.fengwk.kkstudio.harness.environment.server.LeaseBindResult;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDispatch;
import fun.fengwk.kkstudio.harness.environment.terminal.OperationOutcome;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalStatus;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.WriterGrant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link DaemonRuntime} 与真实 {@link EnvironmentDaemonServer} 的 v3 协议桥接 + 真实 PTY 端到端验收。
 *
 * <p>桥接的两个方向都只做本地入队，由单线程 loop 异步投递：Daemon 的 {@code sendText} 绝不内联进入 server，server 的 {@code
 * offerText} 也绝不内联进入 Daemon 的 {@code onMessage}，因此真实握手（HELLO→WELCOME→READY）不会因为 native transport 的
 * 非阻塞语义被同步重入破坏。server 使用 public 端口与内存 fake lease/registration/ticket，不接触任何真实共享数据库。
 *
 * <p>覆盖：OPEN→ATTACHED+RESET→VIEW_APPLIED→CLAIM→INPUT 的真实 WRITE ACK（seq/digest/grant/mode 围栏）与经 PTY
 * 回传的画面、 断连同 environment 重连后 ATTACH 保持 terminalId、CLOSE 后退出与资源收敛。
 */
class DaemonTerminalServerIntegrationTest {

  private static final long WAIT_SECONDS = 60L;
  private static final long MAX_RESOURCE_BYTES = 16L * 1024 * 1024;

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID APP_NODE = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID VIEWER = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String REGISTRATION_TOKEN = "integration-registration-token";

  @TempDir Path testRoot;

  private final FakeLeaseStore leaseStore = new FakeLeaseStore();
  private final List<TerminalResponse> responses = new CopyOnWriteArrayList<>();
  private final AtomicInteger readyCount = new AtomicInteger();

  private DaemonRuntime runtime;
  private EnvironmentDaemonServer server;
  private BridgeTransport transport;
  private ExecutorService bridgeLoop;

  @AfterEach
  void tearDown() {
    try {
      if (runtime != null) {
        runtime.close();
      }
    } finally {
      if (bridgeLoop != null) {
        bridgeLoop.shutdownNow();
      }
    }
  }

  @Test
  void fullShellLifecycleOverRealServerAndPty() throws Exception {
    server = server();
    bridgeLoop =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "daemon-server-bridge");
              thread.setDaemon(true);
              return thread;
            });
    transport = new BridgeTransport(server, bridgeLoop);

    List<String> command = TerminalRuntimeFixtureMain.fixtureCommand("echo");
    TerminalLaunchSpec spec =
        new TerminalLaunchSpec(
            command.get(0), command.subList(1, command.size()), testRoot.toAbsolutePath());
    runtime =
        new DaemonRuntime(
            new DaemonConfig(
                URI.create("ws://localhost/gateway"),
                registrationTokenFile(),
                Duration.ofMinutes(1),
                Duration.ofMillis(50),
                Duration.ofSeconds(1),
                null,
                testRoot.resolve("daemon-data"),
                spec),
            transport,
            new DaemonCapabilityRegistry(),
            new InMemoryDaemonInvocationJournal(),
            Executors.newSingleThreadScheduledExecutor(),
            Executors.newVirtualThreadPerTaskExecutor());

    runtime.start();
    awaitServerReady(1);
    String firstConnectionId = transport.currentConnectionId();

    // OPEN：真实启动 PTY，得到 ATTACHED 与首帧 RESET。
    assertAccepted(send(new TerminalCommand.Open(null)));
    TerminalResponse attachedResponse = awaitEvent(TerminalEvent.Type.ATTACHED, 0);
    TerminalEvent.Attached attached = (TerminalEvent.Attached) attachedResponse.event().payload();
    TerminalIdentity identity = attachedResponse.event().identity();
    UUID streamId = attached.streamId();
    assertEquals(TerminalStatus.RUNNING, attached.status());
    assertTrue(attached.inputModeRevision() >= 1L);

    TerminalViewUpdate reset = awaitReset(streamId);
    assertEquals(TerminalViewUpdate.Kind.RESET, reset.type());

    // 浏览器确认基线，随后才能申请控制权与提交输入。
    assertAccepted(send(new TerminalCommand.ViewApplied(identity, streamId, 1L)));

    assertAccepted(send(new TerminalCommand.Claim(identity, streamId, null)));
    WriterGrant grant = awaitGrant();
    assertNotNull(grant);

    byte[] input = "hello\r".getBytes(StandardCharsets.UTF_8);
    assertAccepted(
        send(
            new TerminalCommand.Input(
                identity, streamId, grant, 1L, attached.inputModeRevision(), input)));
    TerminalEvent.OpAck ack = awaitOpAck();
    assertEquals(OperationOutcome.WRITTEN, ack.result().outcome());
    assertEquals(
        TerminalWriter.inputDigest(input, attached.inputModeRevision()), ack.result().digest());
    assertEquals(grant.epoch(), ack.writerEpoch());

    assertTrue(
        awaitScreenContains(streamId, identity, "ECHO:hello"), "命令必须读到写入字节并把回显经真实 PTY 传回内核画面");

    // 物理断开：server 丢弃连接代际并关闭 channel，Daemon 观察到断开后同实例重连。
    transport.disconnectCurrent();
    awaitServerReady(2);
    String secondConnectionId = transport.currentConnectionId();
    assertFalse(firstConnectionId.equals(secondConnectionId), "重连必须使用新的真实 connectionId");

    // 重连后 ATTACH 同一 identity：terminalId 必须保持，shell 仍存活。
    assertAccepted(send(new TerminalCommand.Attach(identity)));
    TerminalResponse reattachedResponse = awaitEvent(TerminalEvent.Type.ATTACHED, 1);
    TerminalIdentity reattachedIdentity = reattachedResponse.event().identity();
    TerminalEvent.Attached reattached =
        (TerminalEvent.Attached) reattachedResponse.event().payload();
    assertEquals(identity.terminalId(), reattachedIdentity.terminalId());
    assertEquals(TerminalStatus.RUNNING, reattached.status());
    assertTrue(reattachedResponse.route().connectionId().equals(secondConnectionId));

    // CLOSE：用重连后观察到的真实 writer epoch 关闭终端，等待退出事件。
    assertAccepted(send(new TerminalCommand.Close(identity, reattached.writer().writerEpoch())));
    awaitEvent(TerminalEvent.Type.EXITED, 0);

    // 资源收敛：runtime 关闭后 transport 与执行资源都必须收尾。
    runtime.close();
    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertTrue(transport.isClosed(), "runtime 关闭后 transport 必须关闭");
  }

  // ------------------------------------------------------------------ 桥接

  private EnvironmentDaemonServer server() {
    return new EnvironmentDaemonServer(
        leaseStore,
        token ->
            REGISTRATION_TOKEN.equals(token)
                ? Optional.of(new DaemonRegistration(ENVIRONMENT, "integration-env"))
                : Optional.empty(),
        environmentId -> readyCount.incrementAndGet(),
        (leaseToken, daemonInstanceId, response) -> responses.add(response),
        new FakeTicketService(),
        () -> new EnvironmentServerSettings(Duration.ofSeconds(60), MAX_RESOURCE_BYTES));
  }

  private void awaitServerReady(int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (readyCount.get() >= expected) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(5L);
    }
    throw new AssertionError("server 未在预算内进入 READY #" + expected + "，当前 " + readyCount.get());
  }

  private DaemonOfferResult send(TerminalCommand.Payload payload) {
    TerminalRequest request =
        new TerminalRequest(
            new TerminalRoute(APP_NODE, transport.currentConnectionId()),
            new TerminalCommand(UUID.randomUUID(), ENVIRONMENT.value(), VIEWER, payload));
    return server.sendShell(new TerminalDispatch(leaseStore.currentLeaseToken(), request));
  }

  private static void assertAccepted(DaemonOfferResult result) {
    assertEquals(DaemonOfferResult.ACCEPTED, result, "帧必须进入真实 server 传输队列");
  }

  // ------------------------------------------------------------------ 事件与画面

  private TerminalResponse awaitEvent(TerminalEvent.Type type, int index)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      List<TerminalResponse> matches = ofType(type);
      if (matches.size() > index) {
        return matches.get(index);
      }
      TimeUnit.MILLISECONDS.sleep(5L);
    }
    throw new AssertionError("未在预算内观察到事件 " + type + " #" + index);
  }

  private TerminalViewUpdate awaitReset(UUID streamId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      for (TerminalResponse response : ofType(TerminalEvent.Type.VIEW_UPDATE)) {
        TerminalViewUpdate update = viewUpdate(response);
        if (update.streamId().equals(streamId) && update.type() == TerminalViewUpdate.Kind.RESET) {
          return update;
        }
      }
      TimeUnit.MILLISECONDS.sleep(5L);
    }
    throw new AssertionError("未在预算内观察到 RESET 画面");
  }

  private WriterGrant awaitGrant() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      for (TerminalResponse response : ofType(TerminalEvent.Type.WRITER_CHANGED)) {
        TerminalEvent.WriterChanged changed =
            (TerminalEvent.WriterChanged) response.event().payload();
        // 公开广播不带 result（不泄漏 grant secret）；只有直接回执才携带授予结果。
        if (changed.result() != null && changed.result().isGranted()) {
          return changed.result().grant();
        }
      }
      TimeUnit.MILLISECONDS.sleep(5L);
    }
    throw new AssertionError("未在预算内观察到授予控制权的 WRITER_CHANGED");
  }

  private TerminalEvent.OpAck awaitOpAck() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      for (TerminalResponse response : ofType(TerminalEvent.Type.OP_ACK)) {
        TerminalEvent.OpAck ack = (TerminalEvent.OpAck) response.event().payload();
        if (ack.result().seq() == 1L) {
          return ack;
        }
      }
      TimeUnit.MILLISECONDS.sleep(5L);
    }
    throw new AssertionError("未在预算内观察到 INPUT 的 OP_ACK");
  }

  /** 确认所有尚未确认的画面版本并重建活动屏文本；只读取真实 VIEW_UPDATE，不伪造屏幕。 */
  private boolean awaitScreenContains(UUID streamId, TerminalIdentity identity, String expected)
      throws Exception {
    long acked = 0L;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      for (TerminalResponse response : ofType(TerminalEvent.Type.VIEW_UPDATE)) {
        TerminalViewUpdate update = viewUpdate(response);
        if (update.streamId().equals(streamId) && update.version() > acked) {
          assertAccepted(
              send(new TerminalCommand.ViewApplied(identity, streamId, update.version())));
          acked = update.version();
        }
      }
      if (mirrorText(streamId).contains(expected)) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(10L);
    }
    return false;
  }

  private String mirrorText(UUID streamId) {
    Map<Integer, TerminalView.Line> rows = new HashMap<>();
    int rowCount = 0;
    for (TerminalResponse response : ofType(TerminalEvent.Type.VIEW_UPDATE)) {
      TerminalViewUpdate update = viewUpdate(response);
      if (!update.streamId().equals(streamId)) {
        continue;
      }
      rowCount = update.rows();
      if (update.type() == TerminalViewUpdate.Kind.RESET) {
        rows.clear();
      }
      for (TerminalViewUpdate.RowChange change : update.screenRows()) {
        rows.put(change.row(), change.line());
      }
    }
    StringBuilder text = new StringBuilder();
    for (int row = 0; row < rowCount; row++) {
      TerminalView.Line line = rows.get(row);
      if (line != null) {
        for (TerminalView.Slot slot : line.slots()) {
          if (slot.kind() != TerminalView.SlotKind.DWC) {
            text.append((char) slot.code());
          }
        }
      }
      text.append('\n');
    }
    return text.toString();
  }

  private List<TerminalResponse> ofType(TerminalEvent.Type type) {
    List<TerminalResponse> matches = new ArrayList<>();
    for (TerminalResponse response : responses) {
      if (response.event().payload().type() == type) {
        matches.add(response);
      }
    }
    return matches;
  }

  private static TerminalViewUpdate viewUpdate(TerminalResponse response) {
    return ((TerminalEvent.ViewUpdate) response.event().payload()).update();
  }

  // ------------------------------------------------------------------ 内存 fake 端口

  /** 内存租约存储：只表达核心依赖的围栏返回值，绝不接触真实数据库。 */
  private static final class FakeLeaseStore implements DaemonLeaseStore {

    private volatile UUID leaseToken;

    UUID currentLeaseToken() {
      return leaseToken;
    }

    @Override
    public LeaseBindResult tryAcquire(
        EnvironmentId environmentId, String registrationToken, Duration leaseDuration) {
      leaseToken = UUID.randomUUID();
      return new LeaseBindResult.Acquired(leaseToken);
    }

    @Override
    public boolean markReady(
        EnvironmentId environmentId,
        UUID leaseToken,
        DaemonCapabilities capabilities,
        Duration leaseDuration) {
      return true;
    }

    @Override
    public boolean heartbeat(EnvironmentId environmentId, UUID leaseToken, Duration leaseDuration) {
      return true;
    }

    @Override
    public boolean disconnect(
        EnvironmentId environmentId, UUID leaseToken, Duration graceDuration) {
      return true;
    }

    @Override
    public boolean holdsReadyLease(EnvironmentId environmentId, UUID leaseToken) {
      return true;
    }

    @Override
    public boolean hasActiveLeaseToken(EnvironmentId environmentId, UUID leaseToken) {
      return false;
    }
  }

  /** 内存票据服务：本用例不驱动资源上传，任何调用都显式失败而不是伪造成功。 */
  private static final class FakeTicketService implements DaemonResourceTicketService {

    @Override
    public Ticket reserve(
        EnvironmentId environmentId, String invocationId, TransferRequest request) {
      throw new UnsupportedOperationException("resource upload is not exercised by this test");
    }

    @Override
    public Ticket commit(EnvironmentId environmentId, String invocationId, UUID uploadId) {
      throw new UnsupportedOperationException("resource upload is not exercised by this test");
    }

    @Override
    public void release(EnvironmentId environmentId, String invocationId, UUID uploadId) {}
  }

  // ------------------------------------------------------------------ transport 桥接

  /** 内存 transport：connect 注册一条真实 server channel；两个方向都由单线程 loop 异步投递。 */
  private final class BridgeTransport implements DaemonTransport {

    private final EnvironmentDaemonServer server;
    private final ExecutorService loop;
    private final List<BridgeConnection> connections = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile BridgeConnection current;

    private BridgeTransport(EnvironmentDaemonServer server, ExecutorService loop) {
      this.server = server;
      this.loop = loop;
    }

    @Override
    public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
      BridgeConnection connection =
          new BridgeConnection("conn-" + ids.incrementAndGet(), listener, loop, server);
      connections.add(connection);
      current = connection;
      server.open(connection.channel());
      return CompletableFuture.completedFuture(connection);
    }

    @Override
    public void close() {
      closed.set(true);
    }

    private String currentConnectionId() {
      return current.connectionId();
    }

    private void disconnectCurrent() {
      server.close(current.connectionId());
    }

    private boolean isClosed() {
      return closed.get();
    }
  }

  private final class BridgeConnection implements DaemonConnection {

    private final String connectionId;
    private final DaemonTransportListener listener;
    private final ExecutorService loop;
    private final EnvironmentDaemonServer server;
    private final BridgeChannel channel = new BridgeChannel();
    private final AtomicBoolean open = new AtomicBoolean(true);

    private BridgeConnection(
        String connectionId,
        DaemonTransportListener listener,
        ExecutorService loop,
        EnvironmentDaemonServer server) {
      this.connectionId = connectionId;
      this.listener = listener;
      this.loop = loop;
      this.server = server;
    }

    @Override
    public CompletionStage<Void> sendText(String message) {
      // 只本地入队：绝不在调用线程内联进入 server，避免 native transport 的非阻塞语义被同步重入破坏。
      CompletableFuture<Void> delivered = new CompletableFuture<>();
      loop.execute(
          () -> {
            try {
              server.receive(connectionId, message);
              delivered.complete(null);
            } catch (RuntimeException error) {
              delivered.completeExceptionally(error);
            }
          });
      return delivered;
    }

    @Override
    public void close() {
      channel.close();
    }

    @Override
    public boolean isOpen() {
      return open.get();
    }

    private String connectionId() {
      return connectionId;
    }

    private DaemonChannel channel() {
      return channel;
    }

    private final class BridgeChannel implements DaemonChannel {

      @Override
      public String connectionId() {
        return connectionId;
      }

      @Override
      public boolean isOpen() {
        return open.get();
      }

      @Override
      public DaemonOfferResult offerText(String text) {
        if (!open.get()) {
          return DaemonOfferResult.CLOSED;
        }
        // 只本地入队：绝不在 server 的调用线程内联进入 Daemon 的 onMessage。
        loop.execute(
            () -> {
              if (open.get()) {
                listener.onMessage(text);
              }
            });
        return DaemonOfferResult.ACCEPTED;
      }

      @Override
      public void closeAfterFlush() {
        close();
      }

      @Override
      public void close() {
        if (open.compareAndSet(true, false)) {
          loop.execute(() -> listener.onDisconnected(new IOException("bridge connection closed")));
        }
      }
    }
  }

  // ------------------------------------------------------------------ 临时资源

  private Path registrationTokenFile() {
    Path directory = testRoot.resolve("credentials");
    Path tokenFile = directory.resolve("daemon.token");
    if (!Files.exists(tokenFile)) {
      try {
        Files.createDirectories(directory);
        Files.writeString(tokenFile, REGISTRATION_TOKEN);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
          Files.setPosixFilePermissions(
              tokenFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }
    return tokenFile;
  }
}

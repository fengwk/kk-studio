package fun.fengwk.kkstudio.harness.daemon;

import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.ERROR;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.HELLO;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.READY;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.SHELL_EVENT;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.WELCOME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.terminal.TerminalCoordinator;
import fun.fengwk.kkstudio.harness.daemon.terminal.TerminalLaunchSpec;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端 v3 wire 协议接入 {@link DaemonRuntime} 的定向测试。
 *
 * <p>覆盖：绑定闸门、迟到 WELCOME 不覆盖新绑定、READY 递交失败的握手收敛、作用域/代际校验、真实 mailbox 满的 NOT_EXECUTED
 * 回执、受理后失败的生命周期失败，以及关闭失败面与并发关闭的幂等性。真实 PTY 端到端由独立集成测试覆盖。
 */
class DaemonRuntimeShellTest {

  private static final long ASYNC_TEST_TIMEOUT_SECONDS = 5;

  private static final long MAX_RESOURCE_BYTES = 1024L * 1024L;

  /** WELCOME 通告的临时资源保留期；测试用短周期。 */
  private static final long TEMPORARY_RESOURCE_TTL_SECONDS = 259_200L;

  /** WELCOME 通告的临时资源扫描间隔；测试用短周期。 */
  private static final long TEMPORARY_RESOURCE_CLEANUP_INTERVAL_SECONDS = 1_800L;

  private static final EnvironmentId ENVIRONMENT_A =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId ENVIRONMENT_B =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

  private static final UUID APP_NODE = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID VIEWER = UUID.fromString("44444444-4444-4444-4444-444444444444");

  private static final String REGISTRATION_TOKEN = "test-registration-token";
  private static final TerminalLaunchSpec TERMINAL =
      new TerminalLaunchSpec("sh", List.of(), Path.of("").toAbsolutePath());

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();
  private final TerminalControlCodec controlCodec = new TerminalControlCodec();

  private DaemonRuntime runtime;
  private ExecutorService ownedOwner;
  private boolean expectedCloseFailure;

  @TempDir Path testRoot;

  @AfterEach
  void tearDown() {
    try {
      if (runtime != null) {
        if (expectedCloseFailure) {
          assertThrows(IllegalStateException.class, runtime::close);
        } else {
          runtime.close();
        }
      }
    } finally {
      if (ownedOwner != null) {
        ownedOwner.shutdownNow();
      }
    }
  }

  // ------------------------------------------------------------------ 绑定闸门与迟到 WELCOME

  /** 绑定完成前绝不 READY：owner 被占用时 WELCOME 只排队，释放后才放行 READY。 */
  @Test
  void bindGateHoldsReadyUntilBindCompletes() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocking = new CountDownLatch(1);
    ownedOwner.submit(
        () -> {
          blocking.countDown();
          awaitQuietly(release);
          return null;
        });
    assertTrue(blocking.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);

    connection.receive(welcome(ENVIRONMENT_A));

    try {
      // 绑定因 owner 被占用而仍在排队：不得出现 READY，也不得进入假 READY。
      assertFalse(connection.snapshot().stream().anyMatch(e -> e.messageType() == READY));
      assertEquals(DaemonRuntimeState.CONNECTING, runtime.state());
    } finally {
      release.countDown();
    }

    connection.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);
    assertEquals(ENVIRONMENT_A, runtime.boundEnvironmentId());
  }

  /** 迟到的旧连接 WELCOME 不得覆盖新连接的绑定，也不得给旧连接放行 READY。 */
  @Test
  void staleWelcomeDoesNotOverwriteNewBinding() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection first = transport.connection(0);
    first.awaitMessageType(HELLO);

    Object firstActive = runtime.activeConnection();
    assertNotNull(firstActive);

    // 持旧连接 monitor，让旧 WELCOME 阻塞在 handleWelcome 的连接临界区入口。
    Thread blockedLateWelcome = new Thread(() -> first.receive(welcome(ENVIRONMENT_A)));
    blockedLateWelcome.setDaemon(true);
    synchronized (firstActive) {
      blockedLateWelcome.start();
      awaitBlocked(blockedLateWelcome);

      first.disconnect();
      transport.awaitConnections(1);
      FakeConnection second = transport.connection(1);
      second.awaitMessageType(HELLO);
      second.receive(welcome(ENVIRONMENT_B));
      second.awaitMessageType(READY);
      awaitState(DaemonRuntimeState.READY);
      assertEquals(ENVIRONMENT_B, runtime.boundEnvironmentId());
    }

    blockedLateWelcome.join(TimeUnit.SECONDS.toMillis(ASYNC_TEST_TIMEOUT_SECONDS));
    assertFalse(blockedLateWelcome.isAlive(), "迟到的旧 WELCOME 未收敛");

    // 旧 WELCOME 复检失败：全局绑定仍属新连接，旧连接没有得到任何 READY。
    assertEquals(ENVIRONMENT_B, runtime.boundEnvironmentId());
    assertEquals(DaemonRuntimeState.READY, runtime.state());
    assertNotSame(firstActive, runtime.activeConnection());
    assertNotNull(runtime.activeConnection());
    assertFalse(hasReady(first));
  }

  /** 旧代际的入站命令与断开不影响当前连接。 */
  @Test
  void staleGenerationEventsDoNotAffectCurrentConnection() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection first = transport.connection(0);
    first.awaitMessageType(HELLO);
    first.receive(welcome(ENVIRONMENT_A));
    first.awaitMessageType(READY);

    first.disconnect();
    transport.awaitConnections(1);
    FakeConnection second = transport.connection(1);
    second.awaitMessageType(HELLO);
    second.receive(welcome(ENVIRONMENT_B));
    second.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);

    // 旧代际命令：generation 失配直接丢弃，不产生回执也不影响新连接。
    first.receive(shellCommand(ENVIRONMENT_B, detach()));
    first.disconnect();
    TimeUnit.MILLISECONDS.sleep(50);

    assertEquals(DaemonRuntimeState.READY, runtime.state());
    assertEquals(ENVIRONMENT_B, runtime.boundEnvironmentId());
    assertFalse(hasReady(first));
    assertFalse(second.snapshot().stream().anyMatch(e -> e.messageType() != READY));
  }

  // ------------------------------------------------------------------ READY 递交失败收敛

  /** 重连绑定积压满时关闭未就绪握手；释放 owner 后正常重连，不保留半绑定 READY。 */
  @Test
  void bindBackpressureClosesHandshakeAndRecovers() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocking = new CountDownLatch(1);
    ownedOwner.submit(
        () -> {
          blocking.countDown();
          awaitQuietly(release);
        });
    assertTrue(blocking.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    runtime.start();
    int rejectedIndex = -1;
    try {
      for (int index = 0; index < 400; index++) {
        transport.awaitConnections(1);
        FakeConnection connection = transport.connection(index);
        connection.awaitMessageType(HELLO);
        connection.receive(welcome(ENVIRONMENT_A));
        assertFalse(hasReady(connection));
        if (connection.awaitClosed(Duration.ZERO)) {
          rejectedIndex = index;
          break;
        }
        connection.disconnect();
      }
      assertTrue(rejectedIndex >= 0, "绑定积压满必须关闭未就绪握手");
    } finally {
      release.countDown();
    }

    ownedOwner.submit(() -> {}).get(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    transport.awaitConnections(1);
    FakeConnection recovered = transport.connection(rejectedIndex + 1);
    recovered.awaitMessageType(HELLO);
    recovered.receive(welcome(ENVIRONMENT_A));
    recovered.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);
  }

  /** READY 同步发送抛异常时关闭失败握手并由既有重连恢复，绝不悬挂或保留假 READY。 */
  @Test
  void readySendFailureClosesHandshakeAndReconnects() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection first = transport.connection(0);
    first.awaitMessageType(HELLO);
    first.throwNextSend();

    first.receive(welcome(ENVIRONMENT_A));

    transport.awaitConnections(1);
    FakeConnection second = transport.connection(1);
    second.awaitMessageType(HELLO);
    second.receive(welcome(ENVIRONMENT_A));
    second.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);

    assertFalse(hasReady(first));
    assertTrue(first.awaitClosed(Duration.ofSeconds(ASYNC_TEST_TIMEOUT_SECONDS)));
  }

  /** READY 以失败 future 完成时同样收敛为断开重连。 */
  @Test
  void readySendFailureFutureClosesHandshakeAndReconnects() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection first = transport.connection(0);
    first.awaitMessageType(HELLO);
    first.failNextSend();

    first.receive(welcome(ENVIRONMENT_A));

    transport.awaitConnections(1);
    FakeConnection second = transport.connection(1);
    second.awaitMessageType(HELLO);
    second.receive(welcome(ENVIRONMENT_A));
    second.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);

    assertFalse(hasReady(first));
  }

  // ------------------------------------------------------------------ 作用域与代际校验

  /** 入站 SHELL_EVENT 是协议违规，回执 ERROR 而不是被静默接受。 */
  @Test
  void rejectsInboundShellEvent() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);
    connection.receive(welcome(ENVIRONMENT_A));
    connection.awaitMessageType(READY);

    connection.receive(
        new DaemonEnvelope(DaemonProtocol.VERSION, SHELL_EVENT, ENVIRONMENT_A, null, "{}"));

    assertEquals(ERROR, connection.awaitMessageType(ERROR).messageType());
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** 未 READY 连接上的 SHELL_COMMAND 被拒。 */
  @Test
  void rejectsShellCommandBeforeReady() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);

    connection.receive(shellCommand(ENVIRONMENT_A, detach()));

    assertEquals(ERROR, connection.awaitMessageType(ERROR).messageType());
    assertEquals(DaemonRuntimeState.CONNECTING, runtime.state());
  }

  /** 内层 command.environmentId 与 envelope scope 不一致被拒，绝不进入协调器。 */
  @Test
  void rejectsShellCommandWithMismatchedEnvironment() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);
    connection.receive(welcome(ENVIRONMENT_A));
    connection.awaitMessageType(READY);

    // envelope scope 仍是已绑定环境，但内层 command.environmentId 不同：必须命中内层一致性校验。
    connection.receive(shellCommand(ENVIRONMENT_A, ENVIRONMENT_B, detach()));

    assertEquals(ERROR, connection.awaitMessageType(ERROR).messageType());
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** SHELL_COMMAND 载荷无法解码时以协议错误拒绝。 */
  @Test
  void rejectsShellCommandWithInvalidPayload() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);
    connection.receive(welcome(ENVIRONMENT_A));
    connection.awaitMessageType(READY);

    connection.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION, DaemonMessageType.SHELL_COMMAND, ENVIRONMENT_A, null, "{}"));

    assertEquals(ERROR, connection.awaitMessageType(ERROR).messageType());
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** 同一连接重复 WELCOME 是协议违规，回执 ERROR 且不覆盖既有绑定。 */
  @Test
  void rejectsDuplicateWelcomeOnSameConnection() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);
    connection.receive(welcome(ENVIRONMENT_A));
    connection.awaitMessageType(READY);

    connection.receive(welcome(ENVIRONMENT_A));

    assertEquals(ERROR, connection.awaitMessageType(ERROR).messageType());
    assertEquals(ENVIRONMENT_A, runtime.boundEnvironmentId());
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** HELLO 同步发送抛异常时同样关闭失败握手并由既有重连恢复。 */
  @Test
  void helloSendSynchronousThrowClosesHandshakeAndReconnects() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    transport.throwNextHello();
    runtime.start();

    transport.awaitConnections(1);
    transport.awaitConnections(1);
    FakeConnection second = transport.connection(1);
    second.awaitMessageType(HELLO);
    second.receive(welcome(ENVIRONMENT_A));
    second.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);

    assertTrue(transport.connection(0).awaitClosed(Duration.ofSeconds(ASYNC_TEST_TIMEOUT_SECONDS)));
  }

  // ------------------------------------------------------------------ mailbox 满 NOT_EXECUTED

  /** 真实 mailbox 满被映射为确定未执行的 BACKPRESSURE/NOT_EXECUTED 回执。 */
  @Test
  void mailboxOverflowEmitsBackpressureNotExecuted() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);
    connection.receive(welcome(ENVIRONMENT_A));
    connection.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);

    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocking = new CountDownLatch(1);
    ownedOwner.submit(
        () -> {
          blocking.countDown();
          awaitQuietly(release);
          return null;
        });
    assertTrue(blocking.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    // owner 被占用，mailbox 只进不出；持续递交直到出现确定未受理的回执。
    TerminalEvent error;
    try {
      for (int i = 0; i < 400; i++) {
        connection.receive(shellCommand(ENVIRONMENT_A, detach()));
      }
      error = connection.awaitShellError(ErrorCode.BACKPRESSURE);
    } finally {
      release.countDown();
    }
    assertEquals(
        ErrorDisposition.NOT_EXECUTED,
        ((TerminalEvent.ErrorPayload) error.payload()).disposition());

    awaitState(DaemonRuntimeState.READY);
  }

  /** 未受理回执本身发送失败（同步抛错）时关闭该代际握手并由既有重连恢复，绝不静默丢弃。 */
  @Test
  void shellAdmissionFailureSendErrorClosesHandshakeAndReconnects() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection first = transport.connection(0);
    first.awaitMessageType(HELLO);
    first.receive(welcome(ENVIRONMENT_A));
    first.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);

    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocking = new CountDownLatch(1);
    ownedOwner.submit(
        () -> {
          blocking.countDown();
          awaitQuietly(release);
          return null;
        });
    assertTrue(blocking.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    // owner 被占用：mailbox 只进不出，溢出回执的下一次发送同步抛错，触发既有断开路径。
    first.throwNextSend();
    try {
      for (int i = 0; i < 400; i++) {
        first.receive(shellCommand(ENVIRONMENT_A, detach()));
      }
      transport.awaitConnections(1);
    } finally {
      release.countDown();
    }

    // 先排空被阻塞的 owner 积压，避免把仍满的 mailbox 当作重连握手的第二个故障。
    ownedOwner.submit(() -> {}).get(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    FakeConnection second = transport.connection(1);
    second.awaitMessageType(HELLO);
    second.receive(welcome(ENVIRONMENT_A));
    second.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);
    assertTrue(first.awaitClosed(Duration.ofSeconds(ASYNC_TEST_TIMEOUT_SECONDS)));
  }

  /** 只把 mailbox 满/协调器关闭映射为 NOT_EXECUTED，其余失败必须走生命周期失败。 */
  @Test
  void notExecutedCodeForMapsOnlyDeterministicAdmissionFailures() throws Exception {
    ExecutorService blockedOwner = Executors.newSingleThreadExecutor();
    ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
    ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocking = new CountDownLatch(1);
    blockedOwner.submit(
        () -> {
          blocking.countDown();
          awaitQuietly(release);
          return null;
        });
    assertTrue(blocking.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    TerminalCoordinator coordinator =
        new TerminalCoordinator(
            UUID.randomUUID(), TERMINAL, Map.of(), blockedOwner, vt, io, scheduler, response -> {});
    try {
      Throwable overflow = null;
      for (int i = 0; i < 400 && overflow == null; i++) {
        overflow = failureOf(coordinator.bind(UUID.randomUUID(), 0));
      }
      assertNotNull(overflow, "mailbox 应被填满");
      assertInstanceOf(TerminalCoordinator.MailboxOverflowException.class, overflow);
      assertEquals(
          ErrorCode.BACKPRESSURE,
          DaemonRuntime.notExecutedCodeFor(new CompletionException(overflow)));

      coordinator.shutdown();
      Throwable closed = failureOf(coordinator.bind(UUID.randomUUID(), 0));
      assertNotNull(closed, "关闭后的请求应被拒绝");
      assertInstanceOf(TerminalCoordinator.CoordinatorClosedException.class, closed);
      assertEquals(ErrorCode.ROUTE_UNAVAILABLE, DaemonRuntime.notExecutedCodeFor(closed));

      // 受理后的未知失败绝不伪装成 NOT_EXECUTED。
      assertNull(
          DaemonRuntime.notExecutedCodeFor(new IllegalStateException("post-admission failure")));
    } finally {
      release.countDown();
      blockedOwner.shutdownNow();
      vt.shutdownNow();
      io.shutdownNow();
      scheduler.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ shutdown 失败面与并发关闭

  /** 协调器收敛失败显式收敛为 FAILED，close 抛固定去敏失败，资源仍被收尾。 */
  @Test
  void shutdownFailureIsExplicitAndConvergesResources() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);

    // owner 失效后 WELCOME 绑定失败，协调器不可收敛：运行时必须以固定去敏失败终态收敛。
    ownedOwner.shutdown();
    connection.receive(welcome(ENVIRONMENT_A));

    assertEquals(DaemonRuntimeState.FAILED, runtime.awaitTermination());
    assertEquals("daemon shutdown did not converge", runtime.failureReason());
    assertTrue(transport.isClosed(), "清理后 transport 必须关闭");
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> runtime.close());
    assertEquals("daemon shutdown did not converge", failure.getMessage());
    expectedCloseFailure = true;
  }

  /** 并发 close 幂等且全部在预算内返回，终态确定。 */
  @Test
  void concurrentCloseIsIdempotent() throws Exception {
    FakeTransport transport = new FakeTransport();
    ownedOwner = Executors.newSingleThreadExecutor();
    runtime = runtime(transport, ownedOwner);

    runtime.start();
    transport.awaitConnections(1);
    FakeConnection connection = transport.connection(0);
    connection.awaitMessageType(HELLO);
    connection.receive(welcome(ENVIRONMENT_A));
    connection.awaitMessageType(READY);
    awaitState(DaemonRuntimeState.READY);

    int threads = 4;
    ExecutorService callers = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            CompletableFuture.runAsync(
                () -> {
                  awaitQuietly(start);
                  runtime.close();
                },
                callers));
      }
      start.countDown();
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
          .get(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      callers.shutdownNow();
    }

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertTrue(transport.isClosed());
  }

  // ------------------------------------------------------------------ 辅助

  private DaemonRuntime runtime(FakeTransport transport, ExecutorService ownerOverride) {
    return new DaemonRuntime(
        config(),
        transport,
        new DaemonCapabilityRegistry(),
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor(),
        ownerOverride);
  }

  private DaemonConfig config() {
    return new DaemonConfig(
        URI.create("ws://localhost/gateway"),
        registrationTokenFile(),
        Duration.ofMinutes(1),
        Duration.ZERO,
        Duration.ofSeconds(1),
        null,
        dataDir(),
        TERMINAL);
  }

  private Path dataDir() {
    return testRoot.resolve("daemon-data");
  }

  private Path registrationTokenFile() {
    Path directory = testRoot.resolve("credentials");
    Path tokenFile = directory.resolve("registration.token");
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

  private DaemonEnvelope welcome(EnvironmentId environmentId) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        WELCOME,
        environmentId,
        null,
        "{\"maxResourceBytes\":"
            + MAX_RESOURCE_BYTES
            + ",\"temporaryResourceTtlSeconds\":"
            + TEMPORARY_RESOURCE_TTL_SECONDS
            + ",\"temporaryResourceCleanupIntervalSeconds\":"
            + TEMPORARY_RESOURCE_CLEANUP_INTERVAL_SECONDS
            + "}");
  }

  private DaemonEnvelope shellCommand(EnvironmentId scope, TerminalCommand.Payload payload) {
    return shellCommand(scope, scope, payload);
  }

  /** envelope scope 与内层 command.environmentId 可不同，用于验证两者一致性校验。 */
  private DaemonEnvelope shellCommand(
      EnvironmentId scope, EnvironmentId commandEnvironment, TerminalCommand.Payload payload) {
    TerminalCommand command =
        new TerminalCommand(UUID.randomUUID(), commandEnvironment.value(), VIEWER, payload);
    TerminalRequest request = new TerminalRequest(new TerminalRoute(APP_NODE, "conn-1"), command);
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.SHELL_COMMAND,
        scope,
        null,
        controlCodec.encodeRequest(request));
  }

  private static TerminalCommand.Payload detach() {
    return new TerminalCommand.Detach(
        new TerminalIdentity(UUID.randomUUID(), UUID.randomUUID()), UUID.randomUUID());
  }

  private static Throwable failureOf(CompletionStage<Void> stage) {
    CompletableFuture<Void> future = stage.toCompletableFuture();
    if (!future.isCompletedExceptionally()) {
      return null;
    }
    try {
      future.join();
      return null;
    } catch (CompletionException error) {
      return error.getCause() == null ? error : error.getCause();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private void awaitState(DaemonRuntimeState expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TEST_TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (runtime.state() == expected) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(1);
    }
    fail("daemon 未在预算内进入 " + expected + "，当前为 " + runtime.state());
  }

  private static void awaitBlocked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TEST_TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.BLOCKED) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(1);
    }
    fail("线程未进入阻塞等待");
  }

  private boolean hasReady(FakeConnection connection) {
    return connection.snapshot().stream().anyMatch(e -> e.messageType() == READY);
  }

  /** 内存 transport：每条连接持有独立的监听器与出站队列，可注入发送失败。 */
  private final class FakeTransport implements DaemonTransport {

    private final Semaphore connections = new Semaphore(0);
    private final List<FakeConnection> created = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean throwNextHello = new AtomicBoolean();

    @Override
    public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
      FakeConnection connection = new FakeConnection(listener);
      if (throwNextHello.getAndSet(false)) {
        connection.throwNextSend();
      }
      created.add(connection);
      connections.release();
      return CompletableFuture.completedFuture(connection);
    }

    @Override
    public void close() {
      closed.set(true);
    }

    /** 让下一条连接的首次发送（HELLO）同步抛异常。 */
    private void throwNextHello() {
      throwNextHello.set(true);
    }

    private void awaitConnections(int expected) throws InterruptedException {
      assertTrue(connections.tryAcquire(expected, ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private FakeConnection connection(int index) {
      return created.get(index);
    }

    private boolean isClosed() {
      return closed.get();
    }
  }

  private final class FakeConnection implements DaemonConnection {

    private final DaemonTransportListener listener;
    private final LinkedBlockingQueue<DaemonEnvelope> sent = new LinkedBlockingQueue<>();
    private final AtomicBoolean throwNextSend = new AtomicBoolean();
    private final AtomicBoolean failNextSend = new AtomicBoolean();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean open = true;

    private FakeConnection(DaemonTransportListener listener) {
      this.listener = listener;
    }

    @Override
    public CompletionStage<Void> sendText(String message) {
      if (throwNextSend.compareAndSet(true, false)) {
        throw new IllegalStateException("send threw");
      }
      if (failNextSend.compareAndSet(true, false)) {
        open = false;
        return CompletableFuture.failedFuture(new IllegalStateException("send failed"));
      }
      sent.add(codec.decode(message));
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      open = false;
      closed.countDown();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    private void throwNextSend() {
      throwNextSend.set(true);
    }

    private void failNextSend() {
      failNextSend.set(true);
    }

    private void receive(DaemonEnvelope envelope) {
      listener.onMessage(codec.encode(envelope));
    }

    private void disconnect() {
      open = false;
      listener.onDisconnected(null);
    }

    private List<DaemonEnvelope> snapshot() {
      return new ArrayList<>(sent);
    }

    private boolean awaitClosed(Duration timeout) throws InterruptedException {
      return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private DaemonEnvelope awaitMessageType(DaemonMessageType expected)
        throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TEST_TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        DaemonEnvelope envelope = sent.poll(50, TimeUnit.MILLISECONDS);
        if (envelope == null) {
          continue;
        }
        if (envelope.messageType() == expected) {
          return envelope;
        }
      }
      fail("未在预算内观察到 " + expected);
      return null;
    }

    private TerminalEvent awaitShellError(ErrorCode code) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TEST_TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        DaemonEnvelope envelope = sent.poll(50, TimeUnit.MILLISECONDS);
        if (envelope == null || envelope.messageType() != SHELL_EVENT) {
          continue;
        }
        TerminalResponse response = controlCodec.decodeResponse(envelope.payloadJson());
        if (response.event().payload() instanceof TerminalEvent.ErrorPayload error
            && error.code() == code) {
          return response.event();
        }
      }
      fail("未在预算内观察到 " + code + " 回执");
      return null;
    }
  }
}

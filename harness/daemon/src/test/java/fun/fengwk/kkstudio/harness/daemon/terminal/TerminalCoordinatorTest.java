package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.process.ProcessScope;
import fun.fengwk.kkstudio.harness.environment.terminal.AdmissionResult;
import fun.fengwk.kkstudio.harness.environment.terminal.ControlResult;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.OperationOutcome;
import fun.fengwk.kkstudio.harness.environment.terminal.Recovery;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalStatus;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.Kind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.RowChange;
import fun.fengwk.kkstudio.harness.environment.terminal.WriterGrant;
import fun.fengwk.kkstudio.harness.environment.terminal.WriterState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * {@link TerminalCoordinator} 的确定性回归：单例 OPEN、generation fence、观察流 credit 与撤销、writer 控制与 secret
 * 去敏、真实 Runtime 决议（WRITTEN/STALE_MODE/OUTCOME_UNKNOWN）、有界 mailbox、emitter/启动/收敛失败与 shutdown 资源。
 *
 * <p>除真实 PTY 用例直接使用 {@link TerminalRuntime#start} 外，其余用例通过包内 {@code RuntimeFactory} 注入「真实 {@link
 * TerminalRuntime} + 可控 PTY 数据流」的夹具：scope 生命周期由真进程持有，数据面完全由测试流控制，因此断言不依赖时序运气。
 */
class TerminalCoordinatorTest {

  private static final UUID DAEMON = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID ENV_A = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
  private static final UUID ENV_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID APP_NODE = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
  private static final UUID VIEWER_A = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
  private static final UUID VIEWER_B = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
  private static final long GEN = 1L;
  private static final long WAIT_SECONDS = 20L;

  @TempDir Path workdir;

  private final List<ExecutorService> executors = new ArrayList<>();
  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();
  private final List<TerminalCoordinator> coordinators = new ArrayList<>();
  private final AtomicLong nanos = new AtomicLong();

  @AfterEach
  void cleanUp() {
    for (TerminalCoordinator coordinator : coordinators) {
      try {
        coordinator.close();
      } catch (RuntimeException ignored) {
        // 已显式验证 shutdown 失败语义的用例无需再次抛出。
      }
    }
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
    }
    for (ScheduledExecutorService scheduler : schedulers) {
      scheduler.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ 单例与 attach

  @Test
  void openStartsSingleSessionAndAttaches() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    Attached first = harness.openAndAwait(VIEWER_A);
    assertEquals(TerminalStatus.RUNNING, first.status());
    assertEquals(1, factory.starts(), "首个 OPEN 只启动一次 native");

    Attached second = harness.openAndAwait(VIEWER_A);
    assertEquals(first.identity().terminalId(), second.identity().terminalId());
    assertEquals(1, factory.starts(), "重复 OPEN 不得再次启动");
  }

  @Test
  void concurrentOpenOnlyOneStartup() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    harness.send(harness.request(VIEWER_B, new TerminalCommand.Open(null)));
    factory.releaseStart();
    Attached first = harness.awaitAttached(0);
    Attached second = harness.awaitAttached(1);
    assertEquals(
        first.identity().terminalId(), second.identity().terminalId(), "并发 OPEN 收敛到同一 terminalId");
    assertEquals(1, factory.starts(), "并发 OPEN 只能有一个 native startup");
  }

  @Test
  void attachRequiresMatchingExistingIdentity() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    TerminalIdentity absent = new TerminalIdentity(DAEMON, UUID.randomUUID());
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Attach(absent)));
    assertEquals(ErrorCode.TERMINAL_NOT_FOUND, harness.awaitError(0).code());
    assertEquals(0, factory.starts(), "ATTACH 不创建终端");

    Attached attached = harness.openAndAwait(VIEWER_A);
    TerminalIdentity wrongDaemon =
        new TerminalIdentity(UUID.randomUUID(), attached.identity().terminalId());
    harness.send(harness.request(VIEWER_B, new TerminalCommand.Attach(wrongDaemon)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(1).code());
  }

  // ------------------------------------------------------------------ writer 控制与 INPUT/RESIZE 决议

  @Test
  void claimThenInputWritesAndAcksWritten() throws Exception {
    RecordingOutputStream output = new RecordingOutputStream();
    Harness harness = scripted(scriptedFactory(new BlockingInputStream(), output));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(harness.input(session, grant, 1L, session.revision, "hello"));
    TerminalEvent.OpAck ack = harness.awaitOpAck(0);
    assertEquals(OperationOutcome.WRITTEN, ack.result().outcome());
    assertNull(ack.code());
    assertEquals("hello", output.capturedAsString(), "ACCEPTED 之后的真实写入必须整帧到达 PTY");
  }

  @Test
  void inputRequiresAppliedViewBaseline() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    // 另一个 viewer 附着但未确认 RESET，基线尚未建立，任何控制都不准入。
    Attached other = harness.attachAndAwait(VIEWER_B, session.identity);
    harness.send(
        harness.request(
            VIEWER_B,
            new TerminalCommand.Input(
                session.identity, other.streamId(), grant, 1L, session.revision, bytes("x"))));
    assertEquals(ErrorCode.VIEW_NOT_APPLIED, harness.awaitError(0).code());
  }

  @Test
  void staleInputModeMapsToNotWrittenWithStaleMode() throws Exception {
    RecordingOutputStream output = new RecordingOutputStream();
    Harness harness = scripted(scriptedFactory(new BlockingInputStream(), output));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(harness.input(session, grant, 1L, session.revision + 1000L, "x"));
    TerminalEvent.OpAck ack = harness.awaitOpAck(0);
    assertEquals(ErrorCode.STALE_MODE, ack.code());
    assertEquals(OperationOutcome.NOT_WRITTEN, ack.result().outcome());
    assertEquals("", output.capturedAsString(), "过期模式不得写入");
  }

  @Test
  void unknownWriteFreezesWriterAndStopsSession() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new FailingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    TerminalEvent.OpAck ack = harness.awaitOpAck(0);
    assertEquals(ErrorCode.OUTCOME_UNKNOWN, ack.code());
    assertEquals(OperationOutcome.OUTCOME_UNKNOWN, ack.result().outcome());
    harness.awaitExited(0);
    Attached attached = harness.attachAndAwait(VIEWER_A, session.identity);
    assertTrue(attached.writer().frozen(), "结果不确定必须冻结 writer");
    assertNull(attached.writer().writerEpoch());
  }

  @Test
  void duplicatePendingInputIsNotExecutedTwice() throws Exception {
    GatedOutputStream output = new GatedOutputStream();
    Harness harness = scripted(scriptedFactory(new BlockingInputStream(), output));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS), "首个写入必须进入 native");
    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    assertEquals(AdmissionResult.Kind.PENDING, harness.awaitOpAck(0).result().kind());
    output.release();
    assertEquals(OperationOutcome.WRITTEN, harness.awaitOpAck(1).result().outcome());
    assertEquals("x", output.capturedAsString(), "同一 seq 的重复 INPUT 只能执行一次");
  }

  @Test
  void resizeResolvesWritten() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(
        harness.request(
            VIEWER_A,
            new TerminalCommand.Resize(session.identity, session.streamId, grant, 1L, 100, 40)));
    assertEquals(OperationOutcome.WRITTEN, harness.awaitOpAck(0).result().outcome());
  }

  @Test
  void writerChangedBroadcastCarriesNoGrant() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.claim(session);
    List<TerminalResponse> changes = harness.emitter.ofType(TerminalEvent.Type.WRITER_CHANGED);
    assertTrue(changes.size() >= 2, "claim 必须广播后再单独回执");
    assertNull(
        ((TerminalEvent.WriterChanged) changes.get(0).event().payload()).result(),
        "公开广播不得携带 ControlResult/grant secret");
    ControlResult direct =
        ((TerminalEvent.WriterChanged) changes.get(1).event().payload()).result();
    assertTrue(direct.isGranted());
  }

  @Test
  void viewerCannotObtainAnotherWritersSecret() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.claim(session);
    Attached other = harness.attachAndAwait(VIEWER_B, session.identity);
    harness.send(
        harness.request(
            VIEWER_B, new TerminalCommand.ViewApplied(session.identity, other.streamId(), 1L)));
    harness.send(
        harness.request(
            VIEWER_B, new TerminalCommand.Claim(session.identity, other.streamId(), null)));
    ControlResult result = harness.lastControlResult();
    assertEquals(ControlResult.Status.REJECTED, result.status());
    assertEquals(ControlResult.RejectReason.NOT_OWNER, result.reason());
    assertNull(result.grant());
  }

  @Test
  void keepaliveRefreshesObserverAndRenewsGrant() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    int before = harness.emitter.ofType(TerminalEvent.Type.WRITER_CHANGED).size();
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Keepalive(session.identity, session.streamId, null)));
    assertEquals(
        before,
        harness.emitter.ofType(TerminalEvent.Type.WRITER_CHANGED).size(),
        "无 grant 的 KEEPALIVE 只刷新 observer，不产生控制回执");
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Keepalive(session.identity, session.streamId, grant)));
    assertEquals(ControlResult.Status.RENEWED, harness.lastControlResult().status());
  }

  @Test
  void detachRemovesOnlyExactStream() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Detach(session.identity, UUID.randomUUID())));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(0).code(), "旧 streamId 不得移除新流");
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Detach(session.identity, session.streamId)));
    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(1).code());
  }

  // ------------------------------------------------------------------ generation fence / bind /
  // disconnect

  @Test
  void staleGenerationReceiveHasNoSideEffect() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    harness
        .coordinator
        .receive(GEN + 5, harness.request(VIEWER_A, new TerminalCommand.Open(null)))
        .get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertEquals(0, factory.starts(), "过期代际不得启动");
    assertEquals(0, harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size());
  }

  @Test
  void disconnectClearsObserversButKeepsShell() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.coordinator.disconnect(GEN).get(WAIT_SECONDS, TimeUnit.SECONDS);
    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(0).code());
    assertFalse(factory.runtime(0).termination().isDone(), "disconnect 不得杀 shell");
    Attached attached = harness.attachAndAwait(VIEWER_A, session.identity);
    assertEquals(TerminalStatus.RUNNING, attached.status());
    assertFalse(factory.runtime(0).termination().isDone());
  }

  @Test
  void bindSameEnvironmentNewGenerationKeepsShellAndClearsObservers() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.coordinator.bind(ENV_A, GEN + 1).get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertFalse(factory.runtime(0).termination().isDone(), "同环境新代际保留 shell");
    harness.send(GEN + 1, harness.input(session, grant, 1L, session.revision, "x"));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(0).code(), "新代际清观察 route");
  }

  @Test
  void bindDifferentEnvironmentStopsOldSessionAndRebinds() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.coordinator.bind(ENV_B, GEN + 1).get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(factory.runtime(0).termination().isDone(), "不同环境必须先停旧 runtime");
    harness
        .coordinator
        .receive(GEN + 1, harness.request(ENV_A, VIEWER_A, new TerminalCommand.Open(null)))
        .get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, factory.starts(), "旧环境命令不得启动");
    Attached reborn = harness.openAndAwait(ENV_B, GEN + 1, VIEWER_A);
    assertNotEquals(session.identity.terminalId(), reborn.identity().terminalId());
    assertEquals(2, factory.starts());
  }

  @Test
  void lateStartIsFencedByDifferentEnvironmentBind() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    // 忽略 StartGate：确定性模拟「create 已返回 runtime 的瞬间启动被放弃」的迟到 runtime。
    factory.ignoreGate();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();
    harness.awaitTrue(() -> factory.starts() == 1);

    // 在途启动尚未产出 runtime 时重绑：必须等待该启动决议，期间命令一律 ROUTE_UNAVAILABLE。
    CompletableFuture<Void> rebind = harness.coordinator.bind(ENV_B, GEN + 1);
    owner.runAll();
    assertFalse(rebind.isDone(), "在途启动未收敛前重绑不得完成");
    harness.coordinator.receive(GEN + 1, harness.request(ENV_B, VIEWER_A, keepalive()));
    owner.runAll();
    assertEquals(ErrorCode.ROUTE_UNAVAILABLE, harness.awaitError(0).code());

    // 迟到的旧 runtime 必须被停止并汇合，重绑才完成，且旧会话不得附着。
    factory.releaseStart();
    owner.pumpUntil(rebind::isDone);
    assertTrue(factory.runtime(0).termination().isDone(), "迟到的旧 runtime 必须停止并汇合");
    assertEquals(0, harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size(), "旧环境会话不得附着");
    harness.coordinator.receive(
        GEN + 1, harness.request(ENV_B, VIEWER_A, new TerminalCommand.Open(null)));
    owner.pumpUntil(() -> harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size() == 1);
    assertEquals(2, factory.starts(), "新环境必须重新启动");
    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
  }

  @Test
  void openOnFullObserverSetIsRejectedWithBackpressure() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Attached first = harness.openAndAwait(VIEWER_A);
    for (int index = 1; index < TerminalCoordinator.MAX_OBSERVERS; index++) {
      harness.attachAndAwait(UUID.randomUUID(), first.identity());
    }
    harness.send(harness.request(UUID.randomUUID(), new TerminalCommand.Open(null)));
    assertEquals(ErrorCode.BACKPRESSURE, harness.awaitError(0).code());
  }

  @Test
  void failedSessionRejectsAttachAndMismatchedExpectedExited() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.failStarts(true);
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    assertEquals(ErrorCode.RUNTIME_FAILED, harness.awaitError(0).code());
    TerminalIdentity failed = harness.errorIdentity(0);

    harness.send(harness.request(VIEWER_A, new TerminalCommand.Attach(failed)));
    assertEquals(ErrorCode.RUNTIME_FAILED, harness.awaitError(1).code(), "FAILED 会话不得附着");

    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Open(new TerminalIdentity(DAEMON, UUID.randomUUID()))));
    assertEquals(
        ErrorCode.REQUEST_CONFLICT, harness.awaitError(2).code(), "expectedExited 身份不符必须冲突");
    assertEquals(1, factory.starts(), "被拒绝的 CAS 不得启动 native");
  }

  @Test
  void ownerRejectionAfterAttachFailsCoordinatorAndReleasesStreams() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.pumpUntil(() -> harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size() == 1);
    owner.shutdown();
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> harness.coordinator.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof RejectedExecutionException);
    assertTrue(factory.runtime(0).termination().isDone(), "fatal 必须停止并汇合 runtime");
  }

  // ------------------------------------------------------------------ 观察流撤销 / credit

  @Test
  void slowObserverIsRevokedByViewAppliedTimeout() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    // 附着后不确认 RESET，流保持在途；推进虚拟时钟超过 10s 后由 tick 撤销该流。
    Attached attached = harness.openAndAwait(VIEWER_A);
    harness.awaitTrue(() -> !harness.emitter.ofType(TerminalEvent.Type.VIEW_UPDATE).isEmpty());
    Session session = harness.session(attached, VIEWER_A);
    nanos.addAndGet(TerminalCoordinator.VIEW_APPLIED_TIMEOUT_NANOS + 1_000_000_000L);
    sleep(400L);
    assertTrue(harness.observerGone(session), "慢观察者只被撤流");
    assertFalse(factory.runtime(0).termination().isDone(), "撤流不得杀 shell");
  }

  @Test
  void idleObserverIsRevokedAfterFifteenSeconds() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Attached attached = harness.openAndAwait(VIEWER_A);
    Session session = harness.session(attached, VIEWER_A);
    nanos.addAndGet(TerminalCoordinator.IDLE_REVOKE_NANOS + 1_000_000_000L);
    sleep(400L);
    assertTrue(harness.observerGone(session), "闲置观察者必须被撤销");
  }

  @Test
  void observerCapacityRejectsNinthViewer() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Attached first = harness.openAndAwait(VIEWER_A);
    for (int index = 1; index < TerminalCoordinator.MAX_OBSERVERS; index++) {
      harness.attachAndAwait(UUID.randomUUID(), first.identity());
    }
    harness.send(harness.request(UUID.randomUUID(), new TerminalCommand.Attach(first.identity())));
    assertEquals(ErrorCode.BACKPRESSURE, harness.awaitError(0).code());
  }

  // ------------------------------------------------------------------ CLOSE

  @Test
  void closeRejectsWrongEpochAndPendingThenStops() throws Exception {
    GatedOutputStream output = new GatedOutputStream();
    Harness harness = scripted(scriptedFactory(new BlockingInputStream(), output));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Close(session.identity, UUID.randomUUID())));
    assertEquals(ErrorCode.REQUEST_CONFLICT, harness.awaitError(0).code(), "错误的 epoch CAS 必须拒绝");

    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    assertTrue(output.awaitFirstWrite(WAIT_SECONDS, TimeUnit.SECONDS));
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Close(session.identity, grant.epoch())));
    assertEquals(ErrorCode.BUSY, harness.awaitError(1).code(), "有在途 native 写时 CLOSE 必须 BUSY");

    output.release();
    harness.awaitOpAck(0);
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Close(session.identity, grant.epoch())));
    harness.awaitExited(0);
  }

  // ------------------------------------------------------------------ 有界 mailbox

  @Test
  void mailboxOverflowRejectsImmediatelyWithoutDroppingReceived() throws Exception {
    ManualExecutor owner = manualExecutor();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();

    List<CompletableFuture<Void>> admitted = new ArrayList<>();
    for (int index = 0; index < TerminalCoordinator.MAILBOX_CAPACITY; index++) {
      admitted.add(harness.coordinator.receive(GEN, harness.request(VIEWER_A, keepalive())));
    }
    CompletableFuture<Void> overflow =
        harness.coordinator.receive(GEN, harness.request(VIEWER_A, keepalive()));
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> overflow.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof TerminalCoordinator.MailboxOverflowException);
    for (CompletableFuture<Void> future : admitted) {
      assertFalse(future.isDone(), "已收请求不得因满队列被丢弃");
    }
    owner.runAll();
    for (CompletableFuture<Void> future : admitted) {
      future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
  }

  @Test
  void mailboxByteBudgetBoundsLargeInputsBeforeCapacity() throws Exception {
    ManualExecutor owner = manualExecutor();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();

    int admitted = 0;
    boolean overflow = false;
    for (int index = 0; index < TerminalCoordinator.MAILBOX_CAPACITY; index++) {
      CompletableFuture<Void> future =
          harness.coordinator.receive(GEN, harness.request(VIEWER_A, largeInput()));
      if (future.isDone()) {
        ExecutionException failure =
            assertThrows(
                ExecutionException.class, () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TerminalCoordinator.MailboxOverflowException);
        overflow = true;
        break;
      }
      admitted++;
    }
    assertTrue(overflow, "字节预算必须先在项数上限之前触发");
    assertTrue(admitted < TerminalCoordinator.MAILBOX_CAPACITY, "字节预算应早于项数上限");
    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
  }

  // ------------------------------------------------------------------ 失败与资源

  @Test
  void startFailureKeepsFailedIdentityAndRequiresExactExitedToRetry() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.failStarts(true);
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    TerminalEvent.ErrorPayload error = harness.awaitError(0);
    assertEquals(ErrorCode.RUNTIME_FAILED, error.code());
    assertEquals(ErrorDisposition.NOT_EXECUTED, error.disposition());
    TerminalIdentity failed = harness.errorIdentity(0);

    // FAILED 身份被保留：普通重复 OPEN 不得自动重试，只返回固定 RUNTIME_FAILED。
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    assertEquals(ErrorCode.RUNTIME_FAILED, harness.awaitError(1).code());
    assertEquals(1, factory.starts(), "无 CAS 的重复 OPEN 不得再次启动 native");

    // 只有精确 expectedExited CAS 才允许新建。
    factory.failStarts(false);
    Attached retried = harness.openAndAwaitExpected(VIEWER_A, failed);
    assertEquals(TerminalStatus.RUNNING, retried.status());
    assertEquals(2, factory.starts());
  }

  @Test
  void emitterFailureRemovesRecipientObserverWithoutKillingShell() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.emitter.failFor(harness.route());
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Claim(session.identity, session.streamId, null)));
    harness.emitter.failFor(null);
    assertTrue(harness.observerGone(session), "emitter 失败必须移除该收件人");
    assertEquals(
        TerminalStatus.RUNNING,
        harness.attachAndAwait(VIEWER_A, session.identity).status(),
        "只移除收件人，不杀 shell");
  }

  @Test
  void shutdownIsIdempotentAndReportsConvergence() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    harness.openSession(VIEWER_A);
    CompletableFuture<Void> first = harness.coordinator.shutdown();
    CompletableFuture<Void> second = harness.coordinator.shutdown();
    first.get(WAIT_SECONDS, TimeUnit.SECONDS);
    second.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(harness.coordinator.termination().isDone());
    assertTrue(factory.runtime(0).termination().isDone());
  }

  @Test
  void receiveAfterShutdownIsRejected() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    harness.coordinator.shutdown().get(WAIT_SECONDS, TimeUnit.SECONDS);
    CompletableFuture<Void> future =
        harness.coordinator.receive(GEN, harness.request(VIEWER_A, keepalive()));
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof TerminalCoordinator.CoordinatorClosedException);
  }

  // ------------------------------------------------------------------ 真实 PTY

  @Test
  void realPtyInputReachesCommandAndOutputReturns() throws Exception {
    Harness harness = realHarness(TerminalRuntimeFixtureMain.fixtureCommand("echo"));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(harness.input(session, grant, 1L, session.revision, "hello\r"));
    assertEquals(OperationOutcome.WRITTEN, harness.awaitOpAck(0).result().outcome());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    boolean echoed = false;
    while (System.nanoTime() < deadline) {
      // 浏览器必须确认每条更新才能归还 credit 并接收后续增量。
      harness.ackUpdates(session);
      if (harness.mirrorText().contains("ECHO:hello")) {
        echoed = true;
        break;
      }
      sleep(10L);
    }
    assertTrue(echoed, "命令必须读到写入的字节并把回显经 PTY 传回内核画面");
  }

  @Test
  void realPtyNaturalExitPreservesExitCodeAndFinalScreen() throws Exception {
    Harness harness = realHarness(TerminalRuntimeFixtureMain.fixtureCommand("exit-code", "7"));
    harness.bind(ENV_A, GEN);
    Attached attached = harness.openAndAwait(VIEWER_A);
    TerminalEvent.Exited exited = harness.awaitExited(0);
    assertEquals(TerminalStatus.EXITED, exited.status());
    assertEquals(7, exited.exitCode());
    Attached reattached = harness.attachAndAwait(VIEWER_A, attached.identity());
    assertEquals(TerminalStatus.EXITED, reattached.status());
    harness.awaitTrue(() -> harness.mirrorText().contains(TerminalRuntimeFixtureMain.MARKER));
  }

  @Test
  void realPtyDisconnectKeepsShellAliveAndReattaches() throws Exception {
    Harness harness =
        realHarness(
            TerminalRuntimeFixtureMain.fixtureCommand(
                "hold", workdir.resolve("hold-" + UUID.randomUUID() + ".pid").toString()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.coordinator.disconnect(GEN).get(WAIT_SECONDS, TimeUnit.SECONDS);
    Attached attached = harness.attachAndAwait(VIEWER_A, session.identity);
    assertEquals(TerminalStatus.RUNNING, attached.status());
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Keepalive(session.identity, attached.streamId(), null)));
    assertEquals(0, harness.emitter.ofType(TerminalEvent.Type.EXITED).size(), "保活路径不得结束 shell");
  }

  @Test
  void restartRequiresExactExpectedExitedOnce() throws Exception {
    RealPtyFactory factory = new RealPtyFactory();
    List<String> command = TerminalRuntimeFixtureMain.fixtureCommand("exit-code", "7");
    Harness harness =
        new Harness(
            ownerExecutor(),
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            new TerminalLaunchSpec(
                command.get(0), command.subList(1, command.size()), workdir.toAbsolutePath()),
            new RecordingEmitter());
    harness.bind(ENV_A, GEN);
    Attached first = harness.openAndAwait(VIEWER_A);
    harness.awaitExited(0);

    TerminalIdentity expected = first.identity();
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(expected)));
    Attached second = harness.awaitAttached(1);
    assertEquals(TerminalStatus.RUNNING, second.status());
    assertNotEquals(first.identity().terminalId(), second.identity().terminalId());
    assertEquals(2, factory.starts());

    // 迟到 restart 携带已结束的旧 identity：必须固定 REQUEST_CONFLICT，不得改变新 session 的观察流。
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(expected)));
    assertEquals(ErrorCode.REQUEST_CONFLICT, harness.awaitError(0).code());
    assertEquals(2, factory.starts(), "过期 identity 不得触发再次启动");
    Attached third = harness.openAndAwait(VIEWER_A);
    assertEquals(second.identity().terminalId(), third.identity().terminalId());
  }

  // ------------------------------------------------------------------ writer 控制 / 幂等 / 围栏

  @Test
  void takeoverAndReleaseTransferSingleWriterWithEpochCas() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant first = harness.claim(session);
    Attached other = harness.attachAndAwait(VIEWER_B, session.identity);
    harness.applyView(VIEWER_B, session, other);

    int before = harness.emitter.ofType(TerminalEvent.Type.WRITER_CHANGED).size();
    harness.send(
        harness.request(
            VIEWER_B,
            new TerminalCommand.Takeover(session.identity, other.streamId(), first.epoch())));
    ControlResult taken = harness.lastControlResult();
    assertEquals(ControlResult.Status.GRANTED, taken.status());
    assertTrue(harness.emitter.ofType(TerminalEvent.Type.WRITER_CHANGED).size() > before);
    WriterGrant second = taken.grant();
    assertNotEquals(first.token(), second.token());

    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Claim(session.identity, session.streamId, null)));
    assertEquals(ControlResult.RejectReason.NOT_OWNER, harness.lastControlResult().reason());

    harness.send(
        harness.request(
            VIEWER_A,
            new TerminalCommand.Takeover(session.identity, session.streamId, first.epoch())));
    assertEquals(ControlResult.Status.REJECTED, harness.lastControlResult().status());
    assertEquals(ControlResult.RejectReason.CAS_FAILED, harness.lastControlResult().reason());

    harness.send(
        harness.request(
            VIEWER_B, new TerminalCommand.Release(session.identity, other.streamId(), second)));
    assertEquals(ControlResult.Status.RELEASED, harness.lastControlResult().status());

    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Claim(session.identity, session.streamId, null)));
    assertTrue(harness.lastControlResult().isGranted(), "释放后必须可再次获取控制权");
  }

  @Test
  void writerControlFencesForeignDaemonAndUnknownStream() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    TerminalIdentity foreign =
        new TerminalIdentity(UUID.randomUUID(), session.identity.terminalId());
    UUID absent = UUID.randomUUID();

    harness.send(harness.request(VIEWER_A, new TerminalCommand.Detach(foreign, session.streamId)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(0).code());
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Detach(session.identity, absent)));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(1).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Claim(foreign, session.streamId, null)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(2).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Claim(session.identity, absent, null)));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(3).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Takeover(foreign, session.streamId, null)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(4).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Takeover(session.identity, absent, null)));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(5).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Release(foreign, session.streamId, grant)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(6).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Release(session.identity, absent, grant)));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(7).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.ViewApplied(foreign, session.streamId, 1L)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(8).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.ViewApplied(session.identity, absent, 1L)));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(9).code());
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Keepalive(session.identity, absent, null)));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(10).code());
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Close(foreign, null)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(11).code());
    harness.send(
        harness.request(
            VIEWER_A,
            new TerminalCommand.Input(foreign, session.streamId, grant, 1L, 1L, bytes("x"))));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(12).code());
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Resize(foreign, session.streamId, grant, 1L, 100, 40)));
    assertEquals(ErrorCode.DAEMON_MISMATCH, harness.awaitError(13).code());
    harness.send(
        harness.request(
            VIEWER_A,
            new TerminalCommand.Input(session.identity, absent, grant, 1L, 1L, bytes("x"))));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(14).code());
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Resize(session.identity, absent, grant, 1L, 100, 40)));
    assertEquals(ErrorCode.STREAM_NOT_FOUND, harness.awaitError(15).code());
  }

  @Test
  void controlAfterExitIsBusyAndExpiredGrantCannotRenew() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);
    harness.send(
        harness.request(VIEWER_A, new TerminalCommand.Close(session.identity, grant.epoch())));
    harness.awaitExited(0);

    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Claim(session.identity, session.streamId, null)));
    assertEquals(ErrorCode.BUSY, harness.awaitError(0).code());
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Takeover(session.identity, session.streamId, null)));
    assertEquals(ErrorCode.BUSY, harness.awaitError(1).code());
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Release(session.identity, session.streamId, grant)));
    assertEquals(ControlResult.Status.REJECTED, harness.lastControlResult().status());
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Keepalive(session.identity, session.streamId, grant)));
    assertEquals(
        ControlResult.Status.REJECTED,
        harness.lastControlResult().status(),
        "EXITED 后 grant 失效不得 KEEPALIVE renew 冒充 RUNNING");
  }

  @Test
  void inputAdmissionRejectsNonOwnerAndSeqGap() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);

    WriterGrant bogus = new WriterGrant(UUID.randomUUID(), UUID.randomUUID());
    harness.send(harness.input(session, bogus, 1L, session.revision, "x"));
    TerminalEvent.OpAck denied = harness.awaitOpAck(0);
    assertEquals(ErrorCode.BUSY, denied.code());
    assertEquals(AdmissionResult.RejectReason.NOT_OWNER, denied.result().reason());

    harness.send(harness.input(session, grant, 5L, session.revision, "x"));
    TerminalEvent.OpAck gap = harness.awaitOpAck(1);
    assertEquals(ErrorCode.REQUEST_CONFLICT, gap.code());
    assertEquals(AdmissionResult.RejectReason.SEQ_GAP, gap.result().reason());
  }

  @Test
  void resizeAdmissionRejectsNonOwner() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.claim(session);
    WriterGrant bogus = new WriterGrant(UUID.randomUUID(), UUID.randomUUID());
    harness.send(
        harness.request(
            VIEWER_A,
            new TerminalCommand.Resize(session.identity, session.streamId, bogus, 1L, 100, 40)));
    TerminalEvent.OpAck ack = harness.awaitOpAck(0);
    assertEquals(ErrorCode.BUSY, ack.code());
    assertEquals(AdmissionResult.RejectReason.NOT_OWNER, ack.result().reason());
  }

  @Test
  void claimRecoveryRechecksPreviousGrant() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    WriterGrant grant = harness.claim(session);

    harness.send(
        harness.request(
            VIEWER_A,
            new TerminalCommand.Claim(
                session.identity, session.streamId, new Recovery(grant, 0L, null))));
    ControlResult recovered = harness.lastControlResult();
    assertEquals(ControlResult.Status.GRANTED, recovered.status());
    assertNull(recovered.recovered());
    assertNotEquals(grant.epoch(), recovered.grant().epoch(), "recovery 必须旋转 epoch");

    harness.send(
        harness.request(
            VIEWER_A,
            new TerminalCommand.Claim(
                session.identity, session.streamId, new Recovery(grant, 0L, null))));
    assertEquals(ControlResult.Status.REJECTED, harness.lastControlResult().status());
    assertEquals(
        ControlResult.RejectReason.RECOVERY_MISMATCH, harness.lastControlResult().reason());
  }

  @Test
  void duplicateOpenDuringPendingAttachStaysIdempotent() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    TerminalRequest open = harness.request(VIEWER_A, new TerminalCommand.Open(null));
    harness.send(open);
    // 启动被 hold 期间的重复请求必须命中已处理 slot：不再旋转流、不再启动第二个 native。
    harness.send(open);
    factory.releaseStart();
    Attached attached = harness.awaitAttached(0);
    assertEquals(TerminalStatus.RUNNING, attached.status());
    assertEquals(1, harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size());
    assertEquals(1, factory.starts());
    harness.applyView(VIEWER_A, harness.session(attached, VIEWER_A), attached);
    assertFalse(
        harness.streamMissing(VIEWER_A, attached.identity(), attached.streamId()), "已确认的观察流必须仍然有效");
  }

  @Test
  void duplicateRequestIdWithDifferentSignatureConflicts() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    TerminalRequest conflicting =
        harness.requestWithSameId(
            harness.request(VIEWER_A, new TerminalCommand.Open(null)),
            new TerminalCommand.Attach(session.identity));
    harness.send(
        harness.requestWithId(
            conflicting.command().requestId(), VIEWER_A, new TerminalCommand.Open(null)));
    harness.send(conflicting);
    boolean conflicted = false;
    for (int index = 0; index < harness.emitter.ofType(TerminalEvent.Type.ERROR).size(); index++) {
      if (harness.emitter.ofType(TerminalEvent.Type.ERROR).get(index).event().payload()
              instanceof TerminalEvent.ErrorPayload error
          && error.code() == ErrorCode.REQUEST_CONFLICT) {
        conflicted = true;
      }
    }
    assertTrue(conflicted, "同 requestId 换签名必须固定 REQUEST_CONFLICT");
  }

  @Test
  void duplicateRequestAfterAttachedReplaysSameStream() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    TerminalRequest open = harness.request(VIEWER_A, new TerminalCommand.Open(null));
    harness.send(open);
    Attached attached = harness.awaitAttached(0);
    harness.send(open);
    Attached replayed = harness.awaitAttached(1);
    assertEquals(attached.streamId(), replayed.streamId(), "重放必须复用同一观察流");
  }

  // ------------------------------------------------------------------ 队列计费 / 公平 / 失败收敛

  @Test
  void mailboxReservationIncludesActiveItemUntilProcessingFinishes() throws Exception {
    ManualExecutor owner = manualExecutor();
    RecordingEmitter recorder = new RecordingEmitter();
    GatedEmitter gated = new GatedEmitter(recorder);
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
            defaultSpec(),
            recorder,
            gated);
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();

    List<CompletableFuture<Void>> admitted = new ArrayList<>();
    for (int index = 0; index < TerminalCoordinator.MAILBOX_CAPACITY; index++) {
      admitted.add(harness.coordinator.receive(GEN, harness.request(VIEWER_A, keepalive())));
    }
    CompletableFuture<Void> overflow =
        harness.coordinator.receive(GEN, harness.request(VIEWER_A, keepalive()));
    ExecutionException rejected =
        assertThrows(ExecutionException.class, () -> overflow.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(rejected.getCause() instanceof TerminalCoordinator.MailboxOverflowException);

    Thread pump = new Thread(owner::runAll);
    pump.start();
    assertTrue(gated.awaitEntered(), "active 项必须进入处理");
    CompletableFuture<Void> whileBlocked =
        harness.coordinator.receive(GEN, harness.request(VIEWER_A, keepalive()));
    ExecutionException blocked =
        assertThrows(
            ExecutionException.class, () -> whileBlocked.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(
        blocked.getCause() instanceof TerminalCoordinator.MailboxOverflowException,
        "active 项在 process 结束前必须继续占额度");
    gated.release();
    pump.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
  }

  @Test
  void tickRevokesIdleObserverEvenUnderMailboxBacklog() throws Exception {
    ManualExecutor owner = manualExecutor();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    TerminalRequest open = harness.request(VIEWER_A, new TerminalCommand.Open(null));
    harness.coordinator.receive(GEN, open);
    owner.pumpUntil(() -> harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size() >= 1);
    Attached attached = harness.awaitAttached(0);
    Session session = harness.session(attached, VIEWER_A);
    harness.coordinator.receive(
        GEN,
        harness.request(
            VIEWER_A, new TerminalCommand.ViewApplied(session.identity, session.streamId, 1L)));
    owner.runAll();

    nanos.addAndGet(TerminalCoordinator.IDLE_REVOKE_NANOS + 1_000_000_000L);
    for (int index = 0; index < 40; index++) {
      harness.coordinator.receive(
          GEN,
          harness.request(
              VIEWER_A, new TerminalCommand.Claim(session.identity, session.streamId, null)));
    }
    sleep(80L);
    owner.runAll();

    assertEquals(
        39,
        harness.emitter.ofType(TerminalEvent.Type.ERROR).size(),
        "tick 必须在 mailbox 积压中先撤流：只有首个 CLAIM 生效");
    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
  }

  @Test
  void rejectingOwnerExecutorFailsCoordinatorAndTerminatesFutures() throws Exception {
    RejectingExecutor owner = new RejectingExecutor();
    executors.add(owner);
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
            defaultSpec(),
            new RecordingEmitter());
    CompletableFuture<Void> bound = harness.coordinator.bind(ENV_A, GEN);

    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> harness.coordinator.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof RejectedExecutionException);
    ExecutionException bindFailure =
        assertThrows(ExecutionException.class, () -> bound.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(
        bindFailure.getCause() instanceof TerminalCoordinator.CoordinatorClosedException,
        "owner 拒绝后已受理的 future 不得永久悬空");
    ExecutionException closed =
        assertThrows(
            ExecutionException.class,
            () ->
                harness
                    .coordinator
                    .receive(GEN, harness.request(VIEWER_A, keepalive()))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(closed.getCause() instanceof TerminalCoordinator.CoordinatorClosedException);
  }

  @Test
  void rejectingSchedulerFailsConstruction() {
    RejectingScheduler scheduler = new RejectingScheduler();
    assertThrows(
        IllegalStateException.class,
        () ->
            new TerminalCoordinator(
                DAEMON,
                defaultSpec(),
                System.getenv(),
                ownerExecutor(),
                vtExecutor(),
                ioExecutor(),
                scheduler,
                new RecordingEmitter(),
                scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
                nanos::get));
  }

  @Test
  void shutdownWhileStartingConvergesAndInterruptedCloseFails() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();

    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.runAll();
    Thread.currentThread().interrupt();
    IllegalStateException interrupted =
        assertThrows(IllegalStateException.class, harness.coordinator::close);
    assertNotNull(interrupted.getMessage());
    Thread.interrupted();

    factory.releaseStart();
    owner.pumpUntil(shutdown::isDone);
    assertTrue(harness.coordinator.termination().isDone());
  }

  @Test
  void staleGenerationBindDoesNotRegressCurrentBinding() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.coordinator.bind(ENV_A, GEN - 1).get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertFalse(factory.runtime(0).termination().isDone(), "过期 generation 不得倒退绑定或杀 shell");
    WriterGrant grant = harness.claim(session);
    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    assertEquals(OperationOutcome.WRITTEN, harness.awaitOpAck(0).result().outcome());
  }

  @Test
  void bindAdmittedBeforeShutdownIsRejectedExplicitly() throws Exception {
    ManualExecutor owner = manualExecutor();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
            defaultSpec(),
            new RecordingEmitter());
    CompletableFuture<Void> bind = harness.coordinator.bind(ENV_A, GEN);
    harness.coordinator.shutdown();
    owner.runAll();
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> bind.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof TerminalCoordinator.CoordinatorClosedException);
    harness.coordinator.termination().get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void rejectedIoExecutorFailsStartWithFixedError() throws Exception {
    RejectingExecutor io = new RejectingExecutor();
    executors.add(io);
    Harness harness =
        new Harness(
            ownerExecutor(),
            vtExecutor(),
            io,
            scheduler(),
            scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()),
            defaultSpec(),
            new RecordingEmitter());
    harness.bind(ENV_A, GEN);
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    assertEquals(ErrorCode.RUNTIME_FAILED, harness.awaitError(0).code());
  }

  @Test
  void negativeMonotonicOriginStillRevokesInflightObserver() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    // nanoTime 合法可为负：起点无关紧要，只有差值有意义。
    nanos.set(-5_000_000_000L);
    harness.bind(ENV_A, GEN);
    Attached attached = harness.openAndAwait(VIEWER_A);
    // 等首帧在途更新落地（in-flight 起始时刻取自当前时钟）后再推进虚拟时钟，避免竞态。
    harness.awaitTrue(() -> !harness.emitter.ofType(TerminalEvent.Type.VIEW_UPDATE).isEmpty());
    Session session = harness.session(attached, VIEWER_A);
    nanos.addAndGet(TerminalCoordinator.VIEW_APPLIED_TIMEOUT_NANOS + 1_000_000_000L);
    sleep(400L);
    assertTrue(harness.observerGone(session), "负起点下 in-flight 超时仍必须撤销");
    assertFalse(factory.runtime(0).termination().isDone(), "撤流不得杀 shell");
  }

  @Test
  void leaseExpiryBroadcastsPublicWriterEpochChange() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.claim(session);
    int before = harness.emitter.ofType(TerminalEvent.Type.WRITER_CHANGED).size();
    nanos.addAndGet(TerminalWriter.LEASE_NANOS + 1_000_000_000L);
    sleep(400L);
    List<TerminalResponse> changes = harness.emitter.ofType(TerminalEvent.Type.WRITER_CHANGED);
    assertTrue(changes.size() > before, "租期到期必须广播公开 writerEpoch 变化");
    WriterState state =
        ((TerminalEvent.WriterChanged) changes.get(changes.size() - 1).event().payload()).writer();
    assertNull(state.writerEpoch(), "旁观者必须看到 null epoch 才能 CAS 接管");
  }

  @Test
  void emitterFailureDuringAttachRemovesObserverWithoutAttached() throws Exception {
    Harness harness =
        scripted(scriptedFactory(new BlockingInputStream(), new RecordingOutputStream()));
    harness.bind(ENV_A, GEN);
    harness.emitter.failFor(harness.route());
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    harness.emitter.failFor(null);
    assertEquals(
        0,
        harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size(),
        "ATTACHED 发送失败不得留下永远拿不到结果的 observer");
    Attached attached = harness.openAndAwait(VIEWER_A);
    assertEquals(TerminalStatus.RUNNING, attached.status());
  }

  @Test
  void productionConstructorStartsRealRuntime() throws Exception {
    List<String> command =
        TerminalRuntimeFixtureMain.fixtureCommand(
            "hold", workdir.resolve("hold-" + UUID.randomUUID() + ".pid").toString());
    Harness harness =
        productionHarness(
            new TerminalLaunchSpec(
                command.get(0), command.subList(1, command.size()), workdir.toAbsolutePath()));
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    assertEquals(
        TerminalStatus.RUNNING, harness.attachAndAwait(VIEWER_A, session.identity).status());
    harness.coordinator.shutdown().get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  // ------------------------------------------------------------------ 第二轮竞态收敛

  @Test
  void shutdownDuringHandedInStartStopsRuntimeAndConverges() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    factory.ignoreGate();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();
    harness.awaitTrue(() -> factory.starts() == 1);
    factory.releaseStart();
    // create 已返回：runtime 已 handIn 并离开 pendingStarts，但 owner 的 onStarted 回调仍在排队。
    harness.awaitTrue(() -> factory.finishedStarts() == 1);
    sleep(50L);
    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
    assertTrue(factory.runtime(0).termination().isDone(), "已 handIn 的迟到 runtime 必须被停止并汇合");
    assertTrue(harness.coordinator.termination().isDone());
  }

  @Test
  void ownerFatalThenFactoryFailureStillConvergesTermination() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    factory.failStarts(true);
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();
    harness.awaitTrue(() -> factory.starts() == 1);
    owner.shutdown();
    // fatal 已发生：owner 回调被丢弃；已受理的请求必须立即明确失败。
    ExecutionException closed =
        assertThrows(
            ExecutionException.class,
            () ->
                harness
                    .coordinator
                    .receive(GEN, harness.request(VIEWER_A, keepalive()))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(closed.getCause() instanceof TerminalCoordinator.CoordinatorClosedException);
    factory.releaseStart();
    // 启动决议仍必须独立触发终止汇合，不能因 owner 回调被丢弃而悬空。
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> harness.coordinator.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof RejectedExecutionException);
  }

  @Test
  void lateReturnedTerminatedRuntimeStillConverges() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    factory.ignoreGate();
    factory.stopRuntimeOnCreate();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();
    harness.awaitTrue(() -> factory.starts() == 1);
    CompletableFuture<Void> rebind = harness.coordinator.bind(ENV_B, GEN + 1);
    owner.runAll();
    assertFalse(rebind.isDone(), "在途启动未收敛前重绑不得完成");
    factory.releaseStart();
    owner.pumpUntil(rebind::isDone);
    rebind.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(factory.runtime(0).termination().isDone(), "返回时已终止的迟到 runtime 必须直接汇合");
    assertEquals(0, harness.emitter.ofType(TerminalEvent.Type.ATTACHED).size(), "旧会话不得附着");
    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
  }

  @Test
  void fatalDuringPendingRebindTerminatesAcceptedRebindFuture() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();
    harness.awaitTrue(() -> factory.starts() == 1);
    CompletableFuture<Void> rebind = harness.coordinator.bind(ENV_B, GEN + 1);
    owner.runAll();
    assertFalse(rebind.isDone(), "在途启动未收敛前重绑不得完成");
    owner.shutdown();
    ExecutionException rebindFailure =
        assertThrows(ExecutionException.class, () -> rebind.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(
        rebindFailure.getCause() instanceof TerminalCoordinator.CoordinatorClosedException,
        "fatal 必须终结已受理的重绑 future");
    factory.releaseStart();
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> harness.coordinator.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof RejectedExecutionException);
  }

  @Test
  void abandonedStartCleanupFailureFailsRebindAndCoordinator() throws Exception {
    ManualExecutor owner = manualExecutor();
    ExecutorService io = ioExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    factory.ignoreGate();
    Harness harness =
        new Harness(
            owner, vtExecutor(), io, scheduler(), factory, defaultSpec(), new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();
    harness.awaitTrue(() -> factory.starts() == 1);
    CompletableFuture<Void> rebind = harness.coordinator.bind(ENV_B, GEN + 1);
    owner.runAll();
    assertFalse(rebind.isDone(), "在途启动未收敛前重绑不得完成");
    factory.releaseStart();
    harness.awaitTrue(() -> factory.finishedStarts() == 1);
    // 放弃启动后的 cleanup 失败：中断该 runtime 的阻塞 I/O owner，使其收敛显式失败。
    io.shutdownNow();
    owner.pumpUntil(rebind::isDone);
    ExecutionException rebindFailure =
        assertThrows(ExecutionException.class, () -> rebind.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertNotNull(rebindFailure.getCause(), "放弃启动的 cleanup 失败必须让重绑失败");
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> harness.coordinator.termination().get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertNotNull(failure.getCause(), "协调器失败边界必须报告 cleanup 失败");
  }

  @Test
  void bindDuringPendingRebindIsRejectedWithFixedRebinding() throws Exception {
    ManualExecutor owner = manualExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    factory.holdStart();
    Harness harness =
        new Harness(
            owner,
            vtExecutor(),
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.coordinator.bind(ENV_A, GEN);
    owner.runAll();
    harness.coordinator.receive(GEN, harness.request(VIEWER_A, new TerminalCommand.Open(null)));
    owner.runAll();
    harness.awaitTrue(() -> factory.starts() == 1);
    CompletableFuture<Void> rebind = harness.coordinator.bind(ENV_B, GEN + 1);
    owner.runAll();
    assertFalse(rebind.isDone());
    // 未完成重绑期间：同 Environment 的新 bind 也必须固定 REBINDING，不能提前宣称已绑定。
    CompletableFuture<Void> second = harness.coordinator.bind(ENV_B, GEN + 2);
    owner.runAll();
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> second.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof IllegalStateException);
    assertEquals(TerminalCoordinator.REBINDING_MESSAGE, failure.getCause().getMessage());
    assertFalse(rebind.isDone(), "被拒绝的新 bind 不得推进原重绑");
    factory.releaseStart();
    owner.pumpUntil(rebind::isDone);
    CompletableFuture<Void> shutdown = harness.coordinator.shutdown();
    owner.pumpUntil(shutdown::isDone);
  }

  @Test
  void equalGenerationDifferentEnvironmentDoesNotRebind() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    Session session = harness.openSession(VIEWER_A);
    harness.coordinator.bind(ENV_B, GEN).get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertFalse(factory.runtime(0).termination().isDone(), "同代际换 Environment 不得重绑或杀 shell");
    WriterGrant grant = harness.claim(session);
    harness.send(harness.input(session, grant, 1L, session.revision, "x"));
    assertEquals(OperationOutcome.WRITTEN, harness.awaitOpAck(0).result().outcome());
  }

  @Test
  void openWithExpectedExitedWithoutSessionConflicts() throws Exception {
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness = scripted(factory);
    harness.bind(ENV_A, GEN);
    harness.send(
        harness.request(
            VIEWER_A, new TerminalCommand.Open(new TerminalIdentity(DAEMON, UUID.randomUUID()))));
    assertEquals(ErrorCode.REQUEST_CONFLICT, harness.awaitError(0).code());
    assertEquals(ErrorDisposition.NOT_EXECUTED, harness.awaitError(0).disposition());
    assertEquals(0, factory.starts(), "没有旧 session 时旧 CAS 不得启动 native");
  }

  @Test
  void snapshotFailureEmitsFixedErrorAndRejectsLaterAttach() throws Exception {
    ExecutorService vt = vtExecutor();
    ScriptedRuntimeFactory factory =
        scriptedFactory(new BlockingInputStream(), new RecordingOutputStream());
    Harness harness =
        new Harness(
            ownerExecutor(),
            vt,
            ioExecutor(),
            scheduler(),
            factory,
            defaultSpec(),
            new RecordingEmitter());
    harness.bind(ENV_A, GEN);
    // 已确认首帧 RESET：观察流有 credit，tick 才会尝试捕获。
    Session session = harness.openSession(VIEWER_A);
    // 中断 VT owner：内核终止且无法再捕获末屏。
    vt.shutdownNow();
    harness.awaitTrue(() -> !harness.emitter.ofType(TerminalEvent.Type.EXITED).isEmpty());
    assertTrue(factory.runtime(0).termination().isCompletedExceptionally(), "内核失效必须显式失败");
    assertNull(factory.runtime(0).finalView(), "内核失效时无法保留末屏");
    harness.awaitTrue(() -> harness.sawError(ErrorCode.RUNTIME_FAILED));
    // 已终止且没有保留末屏：之后的 ATTACH 必须拿到固定 ERROR，而不是永远 pending。
    int before = harness.emitter.ofType(TerminalEvent.Type.ERROR).size();
    harness.send(harness.request(VIEWER_A, new TerminalCommand.Attach(session.identity)));
    harness.awaitTrue(() -> harness.emitter.ofType(TerminalEvent.Type.ERROR).size() > before);
    TerminalEvent.ErrorPayload last =
        harness.awaitError(harness.emitter.ofType(TerminalEvent.Type.ERROR).size() - 1);
    assertEquals(ErrorCode.RUNTIME_FAILED, last.code());
    assertEquals(ErrorDisposition.NOT_EXECUTED, last.disposition());
    assertEquals(
        TerminalStatus.FAILED, harness.awaitExited(0).status(), "画面捕获失败必须按 FAILED 收敛，不靠自然退出解释");
  }

  // ------------------------------------------------------------------ 夹具

  private Harness scripted(ScriptedRuntimeFactory factory) {
    return new Harness(
        ownerExecutor(),
        vtExecutor(),
        ioExecutor(),
        scheduler(),
        factory,
        defaultSpec(),
        new RecordingEmitter());
  }

  private Harness realHarness(List<String> command) {
    TerminalLaunchSpec spec =
        new TerminalLaunchSpec(
            command.get(0), command.subList(1, command.size()), workdir.toAbsolutePath());
    return new Harness(
        ownerExecutor(),
        vtExecutor(),
        ioExecutor(),
        scheduler(),
        new RealPtyFactory(),
        spec,
        new RecordingEmitter());
  }

  private Harness productionHarness(TerminalLaunchSpec spec) {
    return new Harness(
        ownerExecutor(),
        vtExecutor(),
        ioExecutor(),
        scheduler(),
        null,
        spec,
        new RecordingEmitter());
  }

  private TerminalLaunchSpec defaultSpec() {
    return new TerminalLaunchSpec("/bin/sh", List.of(), workdir.toAbsolutePath());
  }

  private ScriptedRuntimeFactory scriptedFactory(InputStream input, OutputStream output) {
    return new ScriptedRuntimeFactory(workdir, input, output);
  }

  private static TerminalCommand.Payload keepalive() {
    return new TerminalCommand.Keepalive(
        new TerminalIdentity(DAEMON, UUID.randomUUID()), UUID.randomUUID(), null);
  }

  private static TerminalCommand.Payload largeInput() {
    return new TerminalCommand.Input(
        new TerminalIdentity(DAEMON, UUID.randomUUID()),
        UUID.randomUUID(),
        new WriterGrant(UUID.randomUUID(), UUID.randomUUID()),
        1L,
        1L,
        new byte[4096]);
  }

  private ManualExecutor manualExecutor() {
    ManualExecutor executor = new ManualExecutor();
    executors.add(executor);
    return executor;
  }

  private ExecutorService ownerExecutor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return executor;
  }

  private ExecutorService vtExecutor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return executor;
  }

  private ExecutorService ioExecutor() {
    ExecutorService executor = Executors.newCachedThreadPool();
    executors.add(executor);
    return executor;
  }

  private ScheduledExecutorService scheduler() {
    ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor();
    schedulers.add(scheduled);
    return scheduled;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** 一次 ATTACHED 事件视图：事件根 identity/viewerId 与载荷便捷访问。 */
  private record Attached(
      TerminalIdentity identity, UUID viewerId, TerminalEvent.Attached payload) {

    private UUID streamId() {
      return payload.streamId();
    }

    private TerminalStatus status() {
      return payload.status();
    }

    private Integer exitCode() {
      return payload.exitCode();
    }

    private long inputModeRevision() {
      return payload.inputModeRevision();
    }

    private WriterState writer() {
      return payload.writer();
    }
  }

  /** 单个 terminal 的测试视图：identity、streamId、viewer、已确认的输入模式版本与当前 grant。 */
  private static final class Session {

    private final TerminalIdentity identity;
    private final UUID streamId;
    private final UUID viewer;
    private final long revision;
    private WriterGrant grant;
    private long ackedVersion;

    private Session(TerminalIdentity identity, UUID streamId, UUID viewer, long revision) {
      this.identity = identity;
      this.streamId = streamId;
      this.viewer = viewer;
      this.revision = revision;
    }
  }

  /** 用例驱动器：封装绑定、命令构造、事件等待与镜面投影。 */
  private final class Harness {

    private final TerminalCoordinator coordinator;
    private final RecordingEmitter emitter;

    private Harness(
        ExecutorService owner,
        ExecutorService vt,
        ExecutorService io,
        ScheduledExecutorService scheduler,
        TerminalCoordinator.RuntimeFactory factory,
        TerminalLaunchSpec spec,
        RecordingEmitter emitter) {
      this(owner, vt, io, scheduler, factory, spec, emitter, emitter);
    }

    private Harness(
        ExecutorService owner,
        ExecutorService vt,
        ExecutorService io,
        ScheduledExecutorService scheduler,
        TerminalCoordinator.RuntimeFactory factory,
        TerminalLaunchSpec spec,
        RecordingEmitter emitter,
        Consumer<TerminalResponse> outbound) {
      this.emitter = emitter;
      this.coordinator =
          factory == null
              ? new TerminalCoordinator(
                  DAEMON, spec, System.getenv(), owner, vt, io, scheduler, outbound)
              : new TerminalCoordinator(
                  DAEMON,
                  spec,
                  System.getenv(),
                  owner,
                  vt,
                  io,
                  scheduler,
                  outbound,
                  factory,
                  nanos::get);
      coordinators.add(coordinator);
    }

    private TerminalRoute route() {
      return new TerminalRoute(APP_NODE, "conn-1");
    }

    private void bind(UUID environment, long generation) throws Exception {
      coordinator.bind(environment, generation).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private TerminalRequest request(UUID viewer, TerminalCommand.Payload payload) {
      return request(ENV_A, viewer, payload);
    }

    private TerminalRequest request(
        UUID environment, UUID viewer, TerminalCommand.Payload payload) {
      return new TerminalRequest(
          route(), new TerminalCommand(UUID.randomUUID(), environment, viewer, payload));
    }

    private TerminalRequest input(
        Session session, WriterGrant grant, long seq, long revision, String text) {
      return request(
          session.viewer,
          new TerminalCommand.Input(
              session.identity, session.streamId, grant, seq, revision, bytes(text)));
    }

    private void send(TerminalRequest request) throws Exception {
      send(GEN, request);
    }

    private void send(long generation, TerminalRequest request) throws Exception {
      coordinator.receive(generation, request).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private Attached openAndAwait(UUID viewer) throws Exception {
      return openAndAwait(ENV_A, GEN, viewer);
    }

    private Attached openAndAwait(UUID environment, long generation, UUID viewer) throws Exception {
      int index = emitter.ofType(TerminalEvent.Type.ATTACHED).size();
      coordinator
          .receive(generation, request(environment, viewer, new TerminalCommand.Open(null)))
          .get(WAIT_SECONDS, TimeUnit.SECONDS);
      return awaitAttached(index);
    }

    private Attached attachAndAwait(UUID viewer, TerminalIdentity identity) throws Exception {
      int index = emitter.ofType(TerminalEvent.Type.ATTACHED).size();
      send(request(viewer, new TerminalCommand.Attach(identity)));
      return awaitAttached(index);
    }

    private Session openSession(UUID viewer) throws Exception {
      Attached attached = openAndAwait(viewer);
      Session session = session(attached, viewer);
      send(
          request(viewer, new TerminalCommand.ViewApplied(session.identity, session.streamId, 1L)));
      return session;
    }

    private Session session(Attached attached, UUID viewer) {
      return new Session(
          attached.identity(), attached.streamId(), viewer, attached.inputModeRevision());
    }

    private WriterGrant claim(Session session) throws Exception {
      send(
          request(
              session.viewer, new TerminalCommand.Claim(session.identity, session.streamId, null)));
      ControlResult result = lastControlResult();
      assertTrue(result.isGranted(), "CLAIM 必须授予控制权");
      session.grant = result.grant();
      return result.grant();
    }

    /** 确认所有尚未确认的 VIEW_UPDATE，归还 credit 使后续增量可继续下发。 */
    private void ackUpdates(Session session) throws Exception {
      for (TerminalResponse response : emitter.ofType(TerminalEvent.Type.VIEW_UPDATE)) {
        TerminalViewUpdate update =
            ((TerminalEvent.ViewUpdate) response.event().payload()).update();
        if (update.streamId().equals(session.streamId) && update.version() > session.ackedVersion) {
          send(
              request(
                  session.viewer,
                  new TerminalCommand.ViewApplied(
                      session.identity, session.streamId, update.version())));
          session.ackedVersion = update.version();
        }
      }
    }

    /** 通过一次不刷新 lastActivity 的 DETACH 探测观察者是否已被撤销。 */
    private boolean observerGone(Session session) throws Exception {
      int before = emitter.ofType(TerminalEvent.Type.ERROR).size();
      send(request(session.viewer, new TerminalCommand.Detach(session.identity, session.streamId)));
      List<TerminalResponse> errors = emitter.ofType(TerminalEvent.Type.ERROR);
      return errors.size() > before
          && ((TerminalEvent.ErrorPayload) errors.get(errors.size() - 1).event().payload()).code()
              == ErrorCode.STREAM_NOT_FOUND;
    }

    private Attached awaitAttached(int index) {
      TerminalResponse response = awaitNth(TerminalEvent.Type.ATTACHED, index);
      return new Attached(
          response.event().identity(),
          response.event().viewerId(),
          (TerminalEvent.Attached) response.event().payload());
    }

    private TerminalEvent.OpAck awaitOpAck(int index) {
      return (TerminalEvent.OpAck) awaitNth(TerminalEvent.Type.OP_ACK, index).event().payload();
    }

    private TerminalEvent.Exited awaitExited(int index) {
      return (TerminalEvent.Exited) awaitNth(TerminalEvent.Type.EXITED, index).event().payload();
    }

    private Attached openAndAwaitExpected(UUID viewer, TerminalIdentity expectedExited)
        throws Exception {
      int index = emitter.ofType(TerminalEvent.Type.ATTACHED).size();
      send(request(viewer, new TerminalCommand.Open(expectedExited)));
      return awaitAttached(index);
    }

    private TerminalIdentity errorIdentity(int index) {
      return emitter.ofType(TerminalEvent.Type.ERROR).get(index).event().identity();
    }

    /** 确认一条观察流首帧 RESET 已被浏览器应用。 */
    private void applyView(UUID viewer, Session session, Attached attached) throws Exception {
      send(
          request(
              viewer, new TerminalCommand.ViewApplied(session.identity, attached.streamId(), 1L)));
    }

    /** 以显式 requestId 构造请求，用于验证幂等重放。 */
    private TerminalRequest requestWithId(
        UUID requestId, UUID viewer, TerminalCommand.Payload payload) {
      return new TerminalRequest(route(), new TerminalCommand(requestId, ENV_A, viewer, payload));
    }

    /** 用同一 requestId 发送一个不同签名的请求。 */
    private TerminalRequest requestWithSameId(
        TerminalRequest original, TerminalCommand.Payload payload) {
      return requestWithId(original.command().requestId(), original.command().viewerId(), payload);
    }

    /** 观察流是否已被撤销：用该 streamId 探测应得到 STREAM_NOT_FOUND。 */
    private boolean streamMissing(UUID viewer, TerminalIdentity identity, UUID streamId)
        throws Exception {
      int before = emitter.ofType(TerminalEvent.Type.ERROR).size();
      send(request(viewer, new TerminalCommand.Keepalive(identity, streamId, null)));
      List<TerminalResponse> errors = emitter.ofType(TerminalEvent.Type.ERROR);
      return errors.size() > before
          && ((TerminalEvent.ErrorPayload) errors.get(errors.size() - 1).event().payload()).code()
              == ErrorCode.STREAM_NOT_FOUND;
    }

    private TerminalEvent.ErrorPayload awaitError(int index) {
      return (TerminalEvent.ErrorPayload)
          awaitNth(TerminalEvent.Type.ERROR, index).event().payload();
    }

    /** 是否已出现指定固定错误的出站响应。 */
    private boolean sawError(ErrorCode code) {
      for (TerminalResponse response : emitter.ofType(TerminalEvent.Type.ERROR)) {
        if (((TerminalEvent.ErrorPayload) response.event().payload()).code() == code) {
          return true;
        }
      }
      return false;
    }

    private ControlResult lastControlResult() {
      List<TerminalResponse> changes = emitter.ofType(TerminalEvent.Type.WRITER_CHANGED);
      return ((TerminalEvent.WriterChanged) changes.get(changes.size() - 1).event().payload())
          .result();
    }

    private TerminalResponse awaitNth(TerminalEvent.Type type, int index) {
      awaitTrue(() -> emitter.ofType(type).size() > index);
      return emitter.ofType(type).get(index);
    }

    private void awaitTrue(BooleanSupplier condition) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
      while (System.nanoTime() < deadline) {
        if (condition.getAsBoolean()) {
          return;
        }
        sleep(5L);
      }
      throw new AssertionError(
          "condition not satisfied within " + WAIT_SECONDS + "s; events=" + eventSummary());
    }

    private String eventSummary() {
      Map<String, Integer> counts = new HashMap<>();
      for (TerminalResponse response : emitter.all()) {
        String type = response.event().payload().type().name();
        counts.merge(type, 1, Integer::sum);
      }
      return counts.toString();
    }

    private String mirrorText() {
      Map<Integer, TerminalView.Line> rows = new HashMap<>();
      int rowCount = 0;
      for (TerminalResponse response : emitter.all()) {
        if (response.event().payload() instanceof TerminalEvent.ViewUpdate viewUpdate) {
          TerminalViewUpdate update = viewUpdate.update();
          rowCount = update.rows();
          if (update.type() == Kind.RESET) {
            rows.clear();
          }
          for (RowChange change : update.screenRows()) {
            rows.put(change.row(), change.line());
          }
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
  }

  /** 记录全部出站响应，并可对指定 route 注入抛错以验证收件人移除。 */
  private static final class RecordingEmitter implements Consumer<TerminalResponse> {

    private final List<TerminalResponse> responses = new CopyOnWriteArrayList<>();
    private volatile TerminalRoute failingRoute;

    @Override
    public void accept(TerminalResponse response) {
      TerminalRoute failing = failingRoute;
      if (failing != null && response.route().equals(failing)) {
        throw new IllegalStateException("fixture emitter failure");
      }
      responses.add(response);
    }

    private void failFor(TerminalRoute route) {
      failingRoute = route;
    }

    private List<TerminalResponse> all() {
      return List.copyOf(responses);
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
  }

  /** 真实 {@link TerminalRuntime} + 可控 PTY 数据流的工厂：scope 生命周期由真进程持有。 */
  private static final class ScriptedRuntimeFactory implements TerminalCoordinator.RuntimeFactory {

    private final Path workdir;
    private final List<ProcessScope> scopes = new CopyOnWriteArrayList<>();
    private final List<TerminalRuntime> runtimes = new CopyOnWriteArrayList<>();
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile boolean fail;
    private volatile boolean ignoreGate;
    private volatile boolean stopOnCreate;
    private volatile CountDownLatch startLatch;
    private int starts;
    private int finishedStarts;

    private ScriptedRuntimeFactory(Path workdir, InputStream input, OutputStream output) {
      this.workdir = workdir;
      this.input = input;
      this.output = output;
    }

    @Override
    public TerminalRuntime create(
        TerminalLaunchSpec spec,
        int columns,
        int rows,
        int maxHistoryLines,
        Map<String, String> environment,
        ProcessScope.StartGate gate,
        ExecutorService vtExecutor,
        ExecutorService ioExecutor,
        ScheduledExecutorService scheduler)
        throws IOException {
      synchronized (this) {
        starts++;
      }
      CountDownLatch latch = startLatch;
      if (latch != null) {
        await(latch);
      }
      try {
        if (fail || (!ignoreGate && !gate.allowStart())) {
          throw new IOException("fixture startup failure");
        }
        ProcessScope scope =
            ProcessScope.startPty(
                workdir,
                TerminalRuntimeFixtureMain.fixtureCommand(
                    "hold", workdir.resolve("hold-" + UUID.randomUUID() + ".pid").toString()),
                columns,
                rows,
                System.getenv(),
                () -> true);
        scopes.add(scope);
        TerminalRuntime runtime =
            new TerminalRuntime(
                scope,
                input,
                output,
                columns,
                rows,
                maxHistoryLines,
                vtExecutor,
                ioExecutor,
                scheduler);
        runtimes.add(runtime);
        if (stopOnCreate) {
          // 构造「迟到返回的 runtime 其 termination 已完成」的场景。
          runtime.stop();
        }
        return runtime;
      } finally {
        synchronized (this) {
          finishedStarts++;
        }
      }
    }

    private void holdStart() {
      startLatch = new CountDownLatch(1);
    }

    private void releaseStart() {
      CountDownLatch latch = startLatch;
      if (latch != null) {
        latch.countDown();
      }
    }

    private void failStarts(boolean value) {
      fail = value;
    }

    /** 让 create 忽略 StartGate，用于确定性构造「create 已在途、随后才被放弃」的迟到 runtime。 */
    private void ignoreGate() {
      ignoreGate = true;
    }

    /** create 内立即停止 runtime，构造「返回时 termination 已完成」的迟到 runtime。 */
    private void stopRuntimeOnCreate() {
      stopOnCreate = true;
    }

    private int starts() {
      synchronized (this) {
        return starts;
      }
    }

    private int finishedStarts() {
      synchronized (this) {
        return finishedStarts;
      }
    }

    private TerminalRuntime runtime(int index) {
      return runtimes.get(index);
    }

    private static void await(CountDownLatch latch) throws IOException {
      try {
        latch.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("start interrupted");
      }
    }
  }

  /** 真实 PTY 工厂：直接调用 {@link TerminalRuntime#start}。 */
  private static final class RealPtyFactory implements TerminalCoordinator.RuntimeFactory {

    private int starts;

    @Override
    public TerminalRuntime create(
        TerminalLaunchSpec spec,
        int columns,
        int rows,
        int maxHistoryLines,
        Map<String, String> environment,
        ProcessScope.StartGate gate,
        ExecutorService vtExecutor,
        ExecutorService ioExecutor,
        ScheduledExecutorService scheduler)
        throws IOException {
      synchronized (this) {
        starts++;
      }
      return TerminalRuntime.start(
          spec,
          columns,
          rows,
          maxHistoryLines,
          environment,
          gate,
          vtExecutor,
          ioExecutor,
          scheduler);
    }

    private int starts() {
      synchronized (this) {
        return starts;
      }
    }
  }

  /** 手动驱动的 owner executor：确定性构造满 mailbox，并可按需泵出队列。 */
  private static final class ManualExecutor extends AbstractExecutorService {

    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean shutdown;

    private void runAll() {
      while (true) {
        Runnable task;
        synchronized (this) {
          task = queue.pollFirst();
        }
        if (task == null) {
          return;
        }
        task.run();
      }
    }

    private void pumpUntil(BooleanSupplier condition) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
      while (!condition.getAsBoolean()) {
        if (System.nanoTime() >= deadline) {
          throw new AssertionError("manual executor did not converge");
        }
        runAll();
        try {
          Thread.sleep(2L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      runAll();
    }

    @Override
    public synchronized void execute(Runnable command) {
      if (shutdown) {
        throw new RejectedExecutionException("fixture shutdown");
      }
      queue.addLast(command);
    }

    @Override
    public synchronized void shutdown() {
      shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
      shutdown = true;
      List<Runnable> pending = new ArrayList<>(queue);
      queue.clear();
      return pending;
    }

    @Override
    public synchronized boolean isShutdown() {
      return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
      return shutdown && queue.isEmpty();
    }

    @Override
    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }
  }

  /** 永久阻塞，直到读任务被中断。 */
  private static final class BlockingInputStream extends InputStream {

    private final CountDownLatch gate = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      return block();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      return block();
    }

    private int block() throws IOException {
      try {
        gate.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("read interrupted");
      }
      return -1;
    }
  }

  /** 记录每次写入的原始字节。 */
  private static final class RecordingOutputStream extends OutputStream {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @Override
    public synchronized void write(int value) {
      captured.write(value);
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) {
      captured.write(data, offset, length);
    }

    private synchronized String capturedAsString() {
      return new String(captured.toByteArray(), StandardCharsets.ISO_8859_1);
    }
  }

  /** 首次 native 写阻塞在闸门上，用于确定性地构造「写者被占住」的窗口。 */
  private static final class GatedOutputStream extends OutputStream {

    private final CountDownLatch firstWrite = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @Override
    public void write(int value) throws IOException {
      await();
      synchronized (this) {
        captured.write(value);
      }
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      await();
      synchronized (this) {
        captured.write(data, offset, length);
      }
    }

    private void await() throws IOException {
      firstWrite.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("write interrupted");
      }
    }

    private boolean awaitFirstWrite(long timeout, TimeUnit unit) throws InterruptedException {
      return firstWrite.await(timeout, unit);
    }

    private void release() {
      release.countDown();
    }

    private synchronized String capturedAsString() {
      return new String(captured.toByteArray(), StandardCharsets.ISO_8859_1);
    }
  }

  /** 首次写入即抛异常，构造结果不确定。 */
  private static final class FailingOutputStream extends OutputStream {

    @Override
    public void write(int value) throws IOException {
      throw new IOException("fixture write failure");
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      throw new IOException("fixture write failure");
    }
  }

  /** 首次 accept 阻塞在闸门上，用于确定性地构造「active 项仍占额度」的窗口。 */
  private static final class GatedEmitter implements Consumer<TerminalResponse> {

    private final RecordingEmitter delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean gated = new AtomicBoolean(true);

    private GatedEmitter(RecordingEmitter delegate) {
      this.delegate = delegate;
    }

    @Override
    public void accept(TerminalResponse response) {
      if (gated.compareAndSet(true, false)) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      delegate.accept(response);
    }

    private boolean awaitEntered() throws InterruptedException {
      return entered.await(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private void release() {
      release.countDown();
    }
  }

  /** 一律拒绝调度的 executor，用于验证 owner 拒绝后的失败收敛边界。 */
  private static final class RejectingExecutor extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("fixture rejection");
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }

  /** 拒绝 scheduleAtFixedRate 的 scheduler，用于验证 ticker 预约失败的显式失败。 */
  private static final class RejectingScheduler extends AbstractExecutorService
      implements ScheduledExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("fixture scheduler rejection");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new RejectedExecutionException("fixture scheduler rejection");
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new RejectedExecutionException("fixture scheduler rejection");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new RejectedExecutionException("fixture scheduler rejection");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new RejectedExecutionException("fixture scheduler rejection");
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }
}

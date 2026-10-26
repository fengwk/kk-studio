package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.FakeRun;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stop 之后活动执行体尚未退出时，不得把子 Thread 投影成已空闲，也不得提前交付其 NOTIFY_PARENT Join。
 *
 * <p>真实 ToolProcessor 已进入工具 body；工具观察到 Stop 中断后，仍由 latch 挡住退出。执行体还在父 gate 内时必须保持 RUNNING 且 Join
 * 未交付。无论断言成败都通过 TWR 释放并等待执行体退出，后续清理独立尝试且不覆盖主体失败；释放后的 Studio 退出确认必须只交付一次。
 */
class HarnessRuntimeStopExitContractTest {

  @Test
  void stopKeepsRunningStatusAndWithholdsJoinUntilTheEnteredToolExits() throws Exception {
    UUID joinId = UUID.randomUUID();
    UUID parentId;
    UUID[] executionSeen = new UUID[1];
    HarnessRuntime runtime;
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    try (AutoCloseable scheduler = schedulerCleanup(fixture.scheduler);
        AutoCloseable fixtureProcessor = fixture.processor) {
      fixture.gateway.queuePreflightAllow();
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch interrupted = new CountDownLatch(1);
      FakeRun run =
          fixture.gateway.queueRun(
              progress -> {
                while (true) {
                  try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("test did not release the tool body");
                    }
                    return new ToolGateway.Cancelled(
                        new ToolInvocationError("CANCELLED", "cancelled"));
                  } catch (InterruptedException expected) {
                    // 中断已送达，但执行体还没退出；用独立 gate 保持这个确定性窗口。
                    interrupted.countDown();
                  }
                }
              });
      try (AutoCloseable execution =
          () -> {
            release.countDown();
            if (run.executionThread != null) {
              assertTrue(
                  run.executionThread.join(Duration.ofSeconds(5)),
                  "released tool execution must exit, including its cleanup");
              assertEquals(0, run.exited.getCount(), "tool body really returned");
              assertEquals(1, run.closes.get(), "run closes after the real body returns");
              assertTrue(run.exitedBeforeClose);
            }
          }) {
        runtime =
            new HarnessRuntime(
                fixture.store,
                (threadId, candidatePath, preparation) -> null,
                () -> CompactionConfig.DEFAULT,
                null,
                mock(ModelProcessor.class),
                fixture.processor);
        parentId = insertSourceAndJoin(fixture, joinId);
        ClaimedWork claim =
            ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW);
        assertEquals(ProcessResult.STARTED, fixture.processor.process(claim));
        executionSeen[0] =
            fixture.store.transaction(
                tx ->
                    tx.loadUnconfirmedExecutions(fixture.baseline.threadId()).get(0).executionId());
        assertTrue(run.awaitExecuting(), "tool body must be entered before stop");
        assertEquals(0, run.closes.get(), "body has not finished");
        ThreadSnapshot before = runtime.getThreadSnapshot(fixture.baseline.threadId());
        assertEquals(ThreadRuntimeStatus.RUNNING, before.runtimeStatus());
        assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
        assertFalse(parentNotified(fixture, parentId));

        runtime.stop(
            new StopCommand(
                fixture.baseline.threadId(), TestIds.id(71), before.thread().version()));

        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Stop must reach the entered tool");
        assertEquals(
            1,
            run.exited.getCount(),
            "stop must be observed while the real tool body is still inside the parent gate");
        assertEquals(0, run.closes.get(), "close follows body exit");
        ThreadSnapshot child = runtime.getThreadSnapshot(fixture.baseline.threadId());
        ThreadJoin join = fixture.store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
        assertEquals(ThreadExecutionControl.STOPPED, child.thread().executionControl());
        boolean notified = parentNotified(fixture, parentId);
        assertAll(
            () -> assertEquals(ThreadRuntimeStatus.RUNNING, child.runtimeStatus()),
            () ->
                assertFalse(
                    join.matched(), "NOTIFY_PARENT join must stay unmatched until the body exits"),
            () -> assertFalse(notified, "parent must not receive an early notification"));
      }
    }
    // 真实 body 返回且 close 成功后，Studio 退出确认必须把 STOPPED 子线程收敛为 IDLE，并只交付一次 Join。
    ThreadSnapshot settled = runtime.getThreadSnapshot(fixture.baseline.threadId());
    ThreadJoin delivered = runtime.findJoin(joinId).orElseThrow();
    assertEquals(ThreadRuntimeStatus.IDLE, settled.runtimeStatus());
    assertEquals(ThreadExecutionControl.STOPPED, settled.thread().executionControl());
    assertTrue(delivered.matched(), "studio exit confirmation delivers the withheld join once");
    assertEquals(1, notificationCount(fixture, parentId));
    ExecutionExitFact confirmed =
        fixture.store.transaction(
            tx -> {
              assertTrue(
                  tx.loadUnconfirmedExecutions(fixture.baseline.threadId()).isEmpty(),
                  "the entered generation is confirmed after close");
              return tx.findExecution(executionSeen[0]).orElseThrow();
            });
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(ExecutionExitKind.TOOL, confirmed.kind());
    assertEquals(fixture.baseline.threadId(), confirmed.threadId());
  }

  @Test
  void remoteAttemptUsesItsOwnIdentityAndAMismatchedAcknowledgementDoesNotConfirmIt()
      throws Exception {
    // 测试意图：每次远端调用使用独立 UUID；身份不符的 ACK 抛出且不改行，匹配 ACK 幂等。
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    try (AutoCloseable scheduler = schedulerCleanup(fixture.scheduler);
        AutoCloseable fixtureProcessor = fixture.processor) {
      fixture.gateway.queuePreflightAllow();
      UUID remoteId = UUID.randomUUID();
      AtomicReference<Boolean> started = new AtomicReference<>();
      FakeRun run =
          fixture.gateway.queueRun(
              progress -> {
                ToolGateway.Execution execution = fixture.gateway.executions.get(0);
                started.set(execution.remoteExecutions().tryStart(remoteId));
                execution.remoteExecutions().confirmExit(remoteId);
                execution.remoteExecutions().confirmExit(remoteId);
                return new ToolGateway.Cancelled(new ToolInvocationError("CANCELLED", "cancelled"));
              });
      try (AutoCloseable execution =
          () -> {
            if (run.executionThread != null) {
              assertTrue(run.executionThread.join(Duration.ofSeconds(5)), "execution must exit");
            }
          }) {
        ClaimedWork claim =
            ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW);
        assertEquals(ProcessResult.STARTED, fixture.processor.process(claim));
        assertTrue(run.awaitExited());
        assertTrue(run.executionThread.join(Duration.ofSeconds(5)), "the real finally must finish");
        assertEquals(Boolean.TRUE, started.get(), "remote registration must succeed");
        ExecutionExitFact remote =
            fixture.store.transaction(tx -> tx.findExecution(remoteId).orElseThrow());
        assertEquals(ExecutionExitKind.REMOTE, remote.kind());
        assertEquals(ExecutionExitState.REMOTE_ACKNOWLEDGED, remote.state());
        IllegalArgumentException mismatch =
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    fixture.store.transaction(
                        tx -> {
                          tx.lockThread(remote.threadId());
                          return tx.confirmExecution(
                              new ExecutionExitFact(
                                  remote.executionId(),
                                  remote.threadId(),
                                  remote.invocationId(),
                                  remote.attempt(),
                                  "other-lease",
                                  remote.kind(),
                                  remote.parentExecutionId(),
                                  ExecutionExitState.UNCONFIRMED,
                                  remote.registeredAt(),
                                  null),
                              ExecutionExitState.NOT_STARTED);
                        }));
        assertEquals(
            "execution " + remoteId + " identity cannot be replaced", mismatch.getMessage());
        assertEquals(
            ExecutionExitState.REMOTE_ACKNOWLEDGED,
            fixture.store.transaction(tx -> tx.findExecution(remoteId).orElseThrow().state()));
      }
    }
  }

  @Test
  void stopWaitsForEveryRegisteredRemoteAcknowledgementWithoutEarlyJoin() throws Exception {
    // 测试意图：真实 Tool body 登记两个 remote 后被 Stop；Studio 退出与首个 remote ACK 都不能提前交付 Join，只有最后一个真实 ACK 才收敛。
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    try (AutoCloseable scheduler = schedulerCleanup(fixture.scheduler);
        AutoCloseable fixtureProcessor = fixture.processor) {
      fixture.gateway.queuePreflightAllow();
      UUID acknowledgedRemote = UUID.randomUUID();
      UUID notStartedRemote = UUID.randomUUID();
      CountDownLatch registered = new CountDownLatch(1);
      CountDownLatch interrupted = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicReference<ToolGateway.RemoteExecutionObserver> observer = new AtomicReference<>();
      AtomicReference<List<Boolean>> starts = new AtomicReference<>();
      AtomicReference<Boolean> released = new AtomicReference<>(false);
      FakeRun run =
          fixture.gateway.queueRun(
              progress -> {
                ToolGateway.RemoteExecutionObserver remote =
                    fixture.gateway.executions.get(0).remoteExecutions();
                observer.set(remote);
                starts.set(
                    List.of(
                        remote.tryStart(acknowledgedRemote), remote.tryStart(notStartedRemote)));
                registered.countDown();
                while (true) {
                  try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                      return new ToolGateway.Cancelled(
                          new ToolInvocationError("CANCELLED", "cancelled"));
                    }
                    released.set(true);
                    return new ToolGateway.Cancelled(
                        new ToolInvocationError("CANCELLED", "cancelled"));
                  } catch (InterruptedException expected) {
                    // Stop 已送达中断，但执行体还没退出；继续等最终 release，让主线程观察真实的未退出窗口。
                    interrupted.countDown();
                  }
                }
              });
      try (AutoCloseable execution =
          executionCleanup(release, run, "released remote execution must exit")) {
        HarnessRuntime runtime =
            new HarnessRuntime(
                fixture.store,
                (threadId, candidatePath, preparation) -> null,
                () -> CompactionConfig.DEFAULT,
                null,
                mock(ModelProcessor.class),
                fixture.processor);
        UUID leafId = fixture.baseline.threadId();
        UUID joinId = UUID.randomUUID();
        UUID parentId = insertSourceAndJoin(fixture, joinId);
        UUID localExecutionId;
        ClaimedWork claim =
            ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW);
        assertEquals(ProcessResult.STARTED, fixture.processor.process(claim));
        assertTrue(run.awaitExecuting(), "remote tool body must be entered before stop");
        assertTrue(registered.await(5, TimeUnit.SECONDS), "both remote executions must register");
        assertEquals(List.of(true, true), starts.get());
        int unconfirmedBeforeStop =
            fixture.store.transaction(tx -> tx.loadUnconfirmedExecutions(leafId).size());
        assertEquals(
            3, unconfirmedBeforeStop, "the local generation and both remotes are unconfirmed");
        localExecutionId =
            fixture.store.transaction(
                tx ->
                    tx.loadUnconfirmedExecutions(leafId).stream()
                        .filter(fact -> fact.kind() == ExecutionExitKind.TOOL)
                        .map(ExecutionExitFact::executionId)
                        .findFirst()
                        .orElseThrow());
        ThreadSnapshot before = runtime.getThreadSnapshot(leafId);
        assertEquals(ThreadRuntimeStatus.RUNNING, before.runtimeStatus());
        assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
        assertFalse(parentNotified(fixture, parentId));

        runtime.stop(new StopCommand(leafId, TestIds.id(71), before.thread().version()));

        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Stop must reach the remote tool body");
        release.countDown();
        assertTrue(run.executionThread.join(Duration.ofSeconds(5)), "remote tool body must exit");
        assertTrue(released.get(), "the test must release the body, not hit its timeout");
        assertEquals(0, run.exited.getCount(), "tool body really returned");
        assertEquals(1, run.closes.get(), "run closes after the real body returns");
        assertTrue(run.exitedBeforeClose);

        assertAll(
            () ->
                assertEquals(
                    ExecutionExitState.STUDIO_EXITED, executionState(fixture, localExecutionId)),
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED, executionState(fixture, acknowledgedRemote)),
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED, executionState(fixture, notStartedRemote)),
            () ->
                assertEquals(
                    ExecutionExitKind.REMOTE, execution(fixture, acknowledgedRemote).kind()),
            () ->
                assertEquals(
                    localExecutionId, execution(fixture, acknowledgedRemote).parentExecutionId()),
            () ->
                assertEquals(
                    ThreadRuntimeStatus.RUNNING,
                    runtime.getThreadSnapshot(leafId).runtimeStatus(),
                    "an unconfirmed remote keeps the stopped expando RUNNING"),
            () ->
                assertEquals(
                    ThreadExecutionControl.STOPPED,
                    runtime.getThreadSnapshot(leafId).thread().executionControl()),
            () ->
                assertFalse(
                    runtime.findJoin(joinId).orElseThrow().matched(),
                    "studio exit alone must not deliver the join"),
            () -> assertEquals(0, notificationCount(fixture, parentId)));

        observer.get().confirmExit(acknowledgedRemote);
        assertAll(
            () ->
                assertEquals(
                    ExecutionExitState.REMOTE_ACKNOWLEDGED,
                    executionState(fixture, acknowledgedRemote)),
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED, executionState(fixture, notStartedRemote)),
            () ->
                assertEquals(
                    ThreadRuntimeStatus.RUNNING,
                    runtime.getThreadSnapshot(leafId).runtimeStatus(),
                    "one remote remains unconfirmed"),
            () -> assertFalse(runtime.findJoin(joinId).orElseThrow().matched()),
            () -> assertEquals(0, notificationCount(fixture, parentId)));

        long versionAfterFirstAck = version(fixture.store, leafId);
        long wakeAfterFirstAck = threadWakeVersion(fixture.store, leafId);
        observer.get().confirmExit(acknowledgedRemote);
        assertAll(
            () ->
                assertEquals(
                    versionAfterFirstAck,
                    version(fixture.store, leafId),
                    "duplicate ack must not bump version"),
            () ->
                assertEquals(
                    wakeAfterFirstAck,
                    threadWakeVersion(fixture.store, leafId),
                    "duplicate ack must not wake work"),
            () ->
                assertEquals(
                    0, notificationCount(fixture, parentId), "duplicate ack must not notify"),
            () ->
                assertEquals(
                    ExecutionExitState.REMOTE_ACKNOWLEDGED,
                    executionState(fixture, acknowledgedRemote)),
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED, executionState(fixture, notStartedRemote)),
            () ->
                assertEquals(
                    ThreadRuntimeStatus.RUNNING,
                    runtime.getThreadSnapshot(leafId).runtimeStatus()));

        observer.get().confirmNotStarted(notStartedRemote);
        ThreadSnapshot settled = runtime.getThreadSnapshot(leafId);
        ThreadJoin delivered = runtime.findJoin(joinId).orElseThrow();
        assertAll(
            () ->
                assertEquals(
                    ExecutionExitState.NOT_STARTED, executionState(fixture, notStartedRemote)),
            () ->
                assertTrue(
                    fixture
                        .store
                        .transaction(tx -> tx.loadUnconfirmedExecutions(leafId))
                        .isEmpty()),
            () -> assertEquals(versionAfterFirstAck + 1, version(fixture.store, leafId)),
            () -> assertEquals(ThreadRuntimeStatus.IDLE, settled.runtimeStatus()),
            () -> assertEquals(ThreadExecutionControl.STOPPED, settled.thread().executionControl()),
            () -> assertTrue(delivered.matched(), "the last remote ack delivers the withheld join"),
            () ->
                assertEquals(
                    1,
                    notificationCount(fixture, parentId),
                    "the parent is notified exactly once"));

        UUID afterStopRemote = UUID.randomUUID();
        assertFalse(
            observer.get().tryStart(afterStopRemote), "Stop already won; no remote may start");
        assertTrue(fixture.store.transaction(tx -> tx.findExecution(afterStopRemote)).isEmpty());
      }
    }
  }

  @Test
  void remoteAcknowledgementRejectsUnknownAndForeignParentIdentitiesWithoutChangingLegalRows()
      throws Exception {
    // 测试意图：observer 只能确认本代次登记的 remote；未登记 ACK 或挂到另一合法父代次的 ACK 必须抛且不改任何已有事实。
    AtomicReference<Boolean> released = new AtomicReference<>(false);
    UUID localExecutionId = null;
    UUID legalRemoteId = UUID.randomUUID();
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    try (AutoCloseable scheduler = schedulerCleanup(fixture.scheduler);
        AutoCloseable fixtureProcessor = fixture.processor) {
      fixture.gateway.queuePreflightAllow();
      CountDownLatch registered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicReference<ToolGateway.RemoteExecutionObserver> observer = new AtomicReference<>();
      AtomicReference<Boolean> started = new AtomicReference<>();
      FakeRun run =
          fixture.gateway.queueRun(
              progress -> {
                ToolGateway.RemoteExecutionObserver remote =
                    fixture.gateway.executions.get(0).remoteExecutions();
                observer.set(remote);
                started.set(remote.tryStart(legalRemoteId));
                registered.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    return new ToolGateway.Cancelled(
                        new ToolInvocationError("CANCELLED", "cancelled"));
                  }
                  released.set(true);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return new ToolGateway.Unknown(
                      new ToolInvocationError("INTERRUPTED", "interrupted"));
                }
                return new ToolGateway.Cancelled(new ToolInvocationError("CANCELLED", "cancelled"));
              });
      try (AutoCloseable execution =
          executionCleanup(release, run, "released remote execution must exit")) {
        UUID leafId = fixture.baseline.threadId();
        assertEquals(
            ProcessResult.STARTED,
            fixture.processor.process(
                ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW)));
        assertTrue(run.awaitExecuting(), "remote tool body must be entered");
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the legal remote must register");
        assertEquals(Boolean.TRUE, started.get());

        ExecutionExitFact local =
            fixture.store.transaction(
                tx ->
                    tx.loadUnconfirmedExecutions(leafId).stream()
                        .filter(fact -> fact.kind() == ExecutionExitKind.TOOL)
                        .findFirst()
                        .orElseThrow());
        localExecutionId = local.executionId();
        // 另一份来源合法的同代 TOOL/REMOTE 事实：identity 相同但 executionId 与父执行不同，用于构造错误 parent 的 ACK。
        ExecutionExitFact foreignParent =
            new ExecutionExitFact(
                UUID.randomUUID(),
                leafId,
                local.invocationId(),
                local.attempt(),
                local.ownerLeaseToken(),
                ExecutionExitKind.TOOL,
                null,
                ExecutionExitState.UNCONFIRMED,
                local.registeredAt(),
                null);
        ExecutionExitFact foreignRemote =
            new ExecutionExitFact(
                UUID.randomUUID(),
                leafId,
                local.invocationId(),
                local.attempt(),
                local.ownerLeaseToken(),
                ExecutionExitKind.REMOTE,
                foreignParent.executionId(),
                ExecutionExitState.UNCONFIRMED,
                NOW,
                null);
        fixture.store.transaction(
            tx -> {
              tx.lockThread(leafId);
              tx.registerExecution(foreignParent);
              tx.registerExecution(foreignRemote);
              return null;
            });

        UUID unknown = UUID.randomUUID();
        IllegalStateException missing =
            assertThrows(
                IllegalStateException.class,
                () -> observer.get().confirmExit(unknown),
                "an unregistered remote acknowledgement must fail");
        assertEquals("remote execution " + unknown + " remains unconfirmed", missing.getMessage());
        IllegalArgumentException foreign =
            assertThrows(
                IllegalArgumentException.class,
                () -> observer.get().confirmExit(foreignRemote.executionId()),
                "an acknowledgement owned by another parent generation must fail");
        assertEquals(
            "execution " + foreignRemote.executionId() + " identity cannot be replaced",
            foreign.getMessage());
        assertAll(
            () -> assertTrue(fixture.store.transaction(tx -> tx.findExecution(unknown)).isEmpty()),
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED, executionState(fixture, legalRemoteId)),
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED,
                    executionState(fixture, foreignRemote.executionId())),
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED, executionState(fixture, local.executionId())));
      }
    }
    assertTrue(released.get(), "the test must release the body, not hit its timeout");
    assertEquals(ExecutionExitState.STUDIO_EXITED, executionState(fixture, localExecutionId));
    assertEquals(ExecutionExitState.UNCONFIRMED, executionState(fixture, legalRemoteId));
  }

  /** 有界停止 fixture 私有 scheduler，避免 ExecutorService.close 的潜在无界等待。 */
  private static AutoCloseable schedulerCleanup(ScheduledExecutorService scheduler) {
    return () -> {
      scheduler.shutdownNow();
      assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
    };
  }

  /** 释放 gate 并等待真实 worker 退出；失败作为 TWR 抑制异常，不覆盖主体失败。 */
  private static AutoCloseable executionCleanup(
      CountDownLatch release, FakeRun run, String failureMessage) {
    return () -> {
      release.countDown();
      if (run.executionThread != null) {
        assertTrue(run.executionThread.join(Duration.ofSeconds(5)), failureMessage);
      }
    };
  }

  private static UUID insertSourceAndJoin(Fixture fixture, UUID joinId) {
    return fixture.store.transaction(
        tx -> {
          ThreadState child = tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          MessagePayload input =
              (MessagePayload) tx.findEntry(fixture.baseline.userEntryId()).orElseThrow().payload();
          ThreadCommand source =
              new ThreadCommand(
                  child.id(),
                  1L,
                  new UserMessageCommandPayload(input.message()),
                  tx.nextId(),
                  CREATION_REQUEST_HASH,
                  null,
                  null,
                  null,
                  NOW);
          tx.updateThread(child.reserveCommandSequences(1, NOW));
          tx.insertCommands(List.of(source));
          tx.updateCommands(List.of(source.markApplied(fixture.baseline.turnStartEntryId())));
          child = tx.lockThread(child.id()).orElseThrow();
          tx.updateThread(
              child.advanceHeadAndInputThroughSequence(
                  fixture.baseline.assistantEntryId(), 1L, NOW));
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  child.parentThreadId(),
                  fixture.baseline.threadId(),
                  1L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW,
                  JoinDelivery.NOTIFY_PARENT,
                  null));
          return child.parentThreadId();
        });
  }

  private static boolean parentNotified(Fixture fixture, UUID parentId) {
    return notificationCount(fixture, parentId) > 0;
  }

  /** 父线程上的通知命令与已物化通知各计一次，用来确认 Join 只交付一次。 */
  private static int notificationCount(Fixture fixture, UUID parentId) {
    return fixture.store.transaction(
        tx -> {
          int count = 0;
          for (ThreadCommand command : loadCommandsByThread(tx, parentId)) {
            if (command.type().isNotification()) {
              count++;
            }
          }
          ThreadState parent = tx.findThread(parentId).orElseThrow();
          EntryPath path = tx.loadEntryPath(parent.headEntryId());
          for (Entry entry : path.entries()) {
            if (entry.payload() instanceof NotificationPayload) {
              count++;
            }
          }
          return count;
        });
  }

  private static ExecutionExitFact execution(Fixture fixture, UUID executionId) {
    return fixture.store.transaction(tx -> tx.findExecution(executionId).orElseThrow());
  }

  private static ExecutionExitState executionState(Fixture fixture, UUID executionId) {
    return execution(fixture, executionId).state();
  }

  private static long version(HarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow().version());
  }

  /** 当前 THREAD Work 的唤醒代次；不存在时用 -1 表示，便于确认重复 ACK 不产生 wake。 */
  private static long threadWakeVersion(HarnessStore store, UUID threadId) {
    return store.transaction(
        tx ->
            tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId))
                .map(Work::wakeVersion)
                .orElse(-1L));
  }
}

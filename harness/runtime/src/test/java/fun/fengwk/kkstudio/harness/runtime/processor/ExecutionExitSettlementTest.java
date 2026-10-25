package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.FakeRun;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 执行代次退出确认必须由真实 body/finally 路径完成，确认前不得交付 Join。 */
class ExecutionExitSettlementTest {

  @Test
  void failedToolContinuationIsNotSettledByStudioExit() throws Exception {
    // 测试意图：失败 Tool 的 TURN_END 是 continueModel 义务，真实 finally 确认后 Join 仍不交付，只保留 THREAD Work 供续作。
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    fixture.gateway.queuePreflightAllow();
    FakeRun delegate =
        fixture.gateway.queueRun(
            progress -> new ToolGateway.Failed(new ToolInvocationError("FAILED", "failed"), false));
    Held held = holdClose(delegate);
    fixture.gateway.admitResults.clear();
    fixture.gateway.queueAdmit(new ToolGateway.Admitted(held.run()));
    HarnessRuntime runtime = runtime(fixture);
    UUID leafId = fixture.baseline.threadId();
    UUID parentId = parentOf(fixture.store, leafId);
    UUID joinId = UUID.randomUUID();
    insertAppliedJoin(fixture.store, leafId, joinId);
    try {
      assertEquals(
          ProcessResult.STARTED,
          fixture.processor.process(
              ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW)));
      assertTrue(held.closeEntered().await(5, TimeUnit.SECONDS), "body returned and close is held");
      driveTerminal(fixture, leafId);
      EntryPath closed =
          fixture.store.transaction(
              tx -> tx.loadEntryPath(tx.findThread(leafId).orElseThrow().headEntryId()));
      assertTrue(
          closed.head().payload() instanceof TurnEndPayload end && end.continueModel(),
          () -> "head=" + closed.head().payload().type() + " payload=" + closed.head().payload());
      assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
      assertTrue(runtime.getThreadSnapshot(leafId).hasUnconfirmedExecution());
      assertEquals(ThreadRuntimeStatus.RUNNING, runtime.getThreadSnapshot(leafId).runtimeStatus());
      UUID executionId =
          fixture.store.transaction(
              tx -> tx.loadUnconfirmedExecutions(leafId).get(0).executionId());
      long versionBeforeExit = version(fixture.store, leafId);

      held.release().countDown();
      assertTrue(delegate.executionThread.join(Duration.ofSeconds(5)), "execution thread exits");

      assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
      assertEquals(0, notificationCount(fixture.store, parentId));
      assertEquals(versionBeforeExit + 1, version(fixture.store, leafId));
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          fixture.store.transaction(tx -> tx.findExecution(executionId).orElseThrow().state()));
      assertEquals(ThreadRuntimeStatus.RUNNING, runtime.getThreadSnapshot(leafId).runtimeStatus());
      Optional<Work> continuation =
          fixture.store.transaction(
              tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, leafId)));
      assertTrue(continuation.isPresent(), "continueModel obligation must request THREAD work");
      Optional<ModelInvocation> model =
          fixture.store.transaction(tx -> tx.findModelInvocation(fixture.modelInvocationId));
      assertTrue(model.isEmpty());
    } finally {
      held.release().countDown();
      try {
        if (delegate.executionThread != null) {
          assertTrue(
              delegate.executionThread.join(Duration.ofSeconds(5)), "tool execution must exit");
        }
      } finally {
        try {
          fixture.processor.close();
        } finally {
          fixture.scheduler.shutdownNow();
          assertTrue(fixture.scheduler.awaitTermination(5, TimeUnit.SECONDS));
        }
      }
    }
  }

  @Test
  void stoppingTheTreeKeepsAllJoinsUntilTheEnteredToolExitsAndThenSettlesEachLevelOnce()
      throws Exception {
    // 测试意图：三层整树 Stop 后叶子执行体未退出时两层 Join 都不交付；真实 finally 确认后自底向上各通知一次，且不启动模型。
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 2);
    fixture.gateway.queuePreflightAllow();
    FakeRun delegate =
        fixture.gateway.queueRun(
            progress -> new ToolGateway.Failed(new ToolInvocationError("FAILED", "failed"), false));
    Held held = holdClose(delegate);
    fixture.gateway.admitResults.clear();
    fixture.gateway.queueAdmit(new ToolGateway.Admitted(held.run()));
    HarnessRuntime runtime = runtime(fixture);
    UUID leafId = fixture.baseline.threadId();
    UUID parentId = parentOf(fixture.store, leafId);
    UUID rootId =
        fixture.store.transaction(tx -> tx.findThread(leafId).orElseThrow().rootThreadId());
    UUID leafJoinId = UUID.randomUUID();
    UUID parentJoinId = UUID.randomUUID();
    insertAppliedJoin(fixture.store, leafId, leafJoinId);
    insertAppliedJoin(fixture.store, parentId, parentJoinId);
    try {
      assertEquals(
          ProcessResult.STARTED,
          fixture.processor.process(
              ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW)));
      assertTrue(held.closeEntered().await(5, TimeUnit.SECONDS), "body returned and close is held");

      long rootVersion = runtime.getThreadSnapshot(rootId).thread().version();
      runtime.stop(new StopCommand(rootId, TestIds.id(71), rootVersion));

      assertFalse(runtime.findJoin(leafJoinId).orElseThrow().matched());
      assertFalse(runtime.findJoin(parentJoinId).orElseThrow().matched());
      assertEquals(0, notificationCount(fixture.store, parentId));
      assertEquals(0, notificationCount(fixture.store, rootId));
      assertEquals(
          ThreadExecutionControl.STOPPED,
          runtime.getThreadSnapshot(leafId).thread().executionControl());
      assertEquals(ThreadRuntimeStatus.RUNNING, runtime.getThreadSnapshot(leafId).runtimeStatus());
      long leafVersionAfterStop = version(fixture.store, leafId);
      long rootVersionAfterStop = version(fixture.store, rootId);

      held.release().countDown();
      assertTrue(delegate.executionThread.join(Duration.ofSeconds(5)), "execution thread exits");

      assertTrue(runtime.findJoin(leafJoinId).orElseThrow().matched());
      assertTrue(runtime.findJoin(parentJoinId).orElseThrow().matched());
      assertEquals(1, notificationCount(fixture.store, parentId));
      assertEquals(1, notificationCount(fixture.store, rootId));
      assertEquals(leafVersionAfterStop + 1, version(fixture.store, leafId));
      assertEquals(rootVersionAfterStop + 1, version(fixture.store, rootId));
      assertFalse(runtime.getThreadSnapshot(parentId).unconsumedAppliedNotifications());
      assertFalse(runtime.getThreadSnapshot(rootId).unconsumedAppliedNotifications());
      assertEquals(ThreadRuntimeStatus.IDLE, runtime.getThreadSnapshot(parentId).runtimeStatus());
      assertEquals(ThreadRuntimeStatus.IDLE, runtime.getThreadSnapshot(rootId).runtimeStatus());
      Optional<ModelInvocation> model =
          fixture.store.transaction(tx -> tx.findModelInvocation(fixture.modelInvocationId));
      assertTrue(model.isEmpty());
    } finally {
      held.release().countDown();
      try {
        if (delegate.executionThread != null) {
          assertTrue(
              delegate.executionThread.join(Duration.ofSeconds(5)), "tool execution must exit");
        }
      } finally {
        try {
          fixture.processor.close();
        } finally {
          fixture.scheduler.shutdownNow();
          assertTrue(fixture.scheduler.awaitTermination(5, TimeUnit.SECONDS));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stopAfterHeldToolCloseKeepsTheGenerationUnconfirmedOnlyWhenExitPersistenceFails(
      boolean failConfirm) throws Exception {
    // 测试意图：真实 Tool body 已返回、close 被 latch 挡住时 Stop 封账；释放后 close 成功但退出确认持久化失败，
    // 该代次必须保留 UNCONFIRMED（RUNNING/Join 未交付/父无通知）；同一路径不注入故障时正常收敛。
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    fixture.gateway.queuePreflightAllow();
    FakeRun delegate =
        fixture.gateway.queueRun(
            progress ->
                new ToolGateway.Cancelled(new ToolInvocationError("CANCELLED", "cancelled")));
    Held held = holdClose(delegate);
    fixture.gateway.admitResults.clear();
    fixture.gateway.queueAdmit(new ToolGateway.Admitted(held.run()));
    AtomicInteger confirmAttempts = new AtomicInteger();
    HarnessStore processorStore =
        ExecutionExitFailureStore.wrapping(fixture.store, confirmAttempts, failConfirm);
    ToolProcessor processor =
        new ToolProcessor(
            processorStore,
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run);
    HarnessRuntime runtime =
        new HarnessRuntime(
            fixture.store,
            (threadId, candidatePath, preparation) -> null,
            () -> CompactionConfig.DEFAULT,
            null,
            mock(ModelProcessor.class),
            processor);
    UUID leafId = fixture.baseline.threadId();
    UUID parentId = parentOf(fixture.store, leafId);
    UUID joinId = UUID.randomUUID();
    insertAppliedJoin(fixture.store, leafId, joinId);
    UUID executionId = null;
    ExitLogCapture exitLogs = ExitLogCapture.attach(ToolExecution.class);
    try {
      assertEquals(
          ProcessResult.STARTED,
          processor.process(
              ToolProcessorTestSupport.claim(fixture.store, fixture.toolInvocationId, NOW)));
      assertTrue(held.closeEntered().await(5, TimeUnit.SECONDS), "body returned and close is held");
      assertEquals(0, delegate.closes.get(), "close is still held after the real body returned");
      executionId =
          fixture.store.transaction(
              tx -> tx.loadUnconfirmedExecutions(leafId).get(0).executionId());
      assertEquals(0, confirmAttempts.get(), "Stop must not attempt exit confirmation");
      ThreadSnapshot before = runtime.getThreadSnapshot(leafId);
      assertEquals(ThreadRuntimeStatus.RUNNING, before.runtimeStatus());
      assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
      assertEquals(0, notificationCount(fixture.store, parentId));

      runtime.stop(new StopCommand(leafId, TestIds.id(71), before.thread().version()));

      assertTrue(
          fixture
              .store
              .transaction(tx -> tx.findToolInvocation(fixture.toolInvocationId))
              .isEmpty(),
          "Stop deletes the terminal Tool invocation");
      assertTrue(
          fixture
              .store
              .transaction(tx -> tx.findModelInvocation(fixture.modelInvocationId))
              .isEmpty(),
          "Stop deletes the parent Model invocation");
      assertTrue(
          fixture
              .store
              .transaction(
                  tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId)))
              .isEmpty());
      UUID heldExecutionId = executionId;
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          fixture.store.transaction(tx -> tx.findExecution(heldExecutionId).orElseThrow().state()));
      assertEquals(ThreadRuntimeStatus.RUNNING, runtime.getThreadSnapshot(leafId).runtimeStatus());
      assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
      assertEquals(0, notificationCount(fixture.store, parentId));

      held.release().countDown();
      assertTrue(
          delegate.executionThread.join(Duration.ofSeconds(5)),
          "released tool execution must exit, including exit confirmation");
      assertEquals(1, delegate.closes.get(), "successful close runs exactly once");
      assertEquals(1, confirmAttempts.get(), "exactly one exit confirmation persistence attempt");
      ThreadSnapshot after = runtime.getThreadSnapshot(leafId);
      ThreadJoin join = runtime.findJoin(joinId).orElseThrow();
      ExecutionExitState state =
          fixture.store.transaction(tx -> tx.findExecution(heldExecutionId).orElseThrow().state());
      if (failConfirm) {
        assertAll(
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED,
                    state,
                    "failed confirmation must preserve the exit fact"),
            () -> assertEquals(ThreadRuntimeStatus.RUNNING, after.runtimeStatus()),
            () -> assertEquals(ThreadExecutionControl.STOPPED, after.thread().executionControl()),
            () -> assertFalse(join.matched(), "unconfirmed exit must withhold the join"),
            () -> assertEquals(0, notificationCount(fixture.store, parentId)));
        exitLogs.assertSingleSafeFailure(
            "cannot confirm tool execution exit",
            "TOOL_EXIT_CONFIRM",
            ExecutionExitFailureStore.InjectedPersistenceFailure.class,
            ExecutionExitFailureStore.SYNTHETIC_SECRET);
      } else {
        assertAll(
            () -> assertEquals(ExecutionExitState.STUDIO_EXITED, state),
            () -> assertEquals(ThreadRuntimeStatus.IDLE, after.runtimeStatus()),
            () -> assertEquals(ThreadExecutionControl.STOPPED, after.thread().executionControl()),
            () -> assertTrue(join.matched(), "confirmed exit delivers the withheld join"),
            () -> assertEquals(1, notificationCount(fixture.store, parentId)));
        exitLogs.assertNoFailure("cannot confirm tool execution exit");
      }
    } finally {
      held.release().countDown();
      try {
        if (delegate.executionThread != null) {
          assertTrue(
              delegate.executionThread.join(Duration.ofSeconds(5)),
              "released tool execution must exit in cleanup");
        }
      } finally {
        try {
          processor.close();
        } finally {
          try {
            fixture.processor.close();
          } finally {
            try {
              fixture.scheduler.shutdownNow();
              assertTrue(fixture.scheduler.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
              exitLogs.close();
            }
          }
        }
      }
    }
  }

  @Test
  void stoppedParentRejectsResumeWhileADirectChildJoinIsOpenButAcceptsSettings() {
    // 测试意图：STOPPED 且直接子 Join 未收敛时拒绝新的恢复输入；SET 维护输入不恢复执行，因此不受此限制。
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    HarnessRuntime runtime = runtime(fixture);
    UUID leafId = fixture.baseline.threadId();
    UUID parentId = parentOf(fixture.store, leafId);
    insertAppliedJoin(fixture.store, leafId, UUID.randomUUID());
    fixture.store.transaction(
        tx -> {
          ThreadState parent = tx.lockThread(parentId).orElseThrow();
          tx.updateThread(parent.changeExecutionControl(ThreadExecutionControl.STOPPED, NOW));
          return null;
        });
    AcceptCommandsCommand resume =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(parentId),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(AgentMessage.user("resume")), TestIds.id(91))));
    HarnessRuntimeConflictException rejected =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.acceptCommands(resume, AcceptancePreflight.IDENTITY));
    assertEquals(HarnessRuntimeConflictException.Reason.EXIT_UNCONFIRMED, rejected.reason());
    ThreadState current = runtime.getThreadSnapshot(parentId).thread();
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                parentId, current.headEntryId(), current.nextCommandSequence()),
            List.of(new NewThreadCommand(new SetAgentCommandPayload("assistant"), TestIds.id(92)))),
        AcceptancePreflight.IDENTITY);
    assertEquals(
        ThreadExecutionControl.STOPPED,
        runtime.getThreadSnapshot(parentId).thread().executionControl());
  }

  private static HarnessRuntime runtime(Fixture fixture) {
    return new HarnessRuntime(
        fixture.store,
        (threadId, path, preparation) -> null,
        () -> CompactionConfig.DEFAULT,
        null,
        mock(ModelProcessor.class),
        fixture.processor);
  }

  /** body 返回后挡住 close，让终态物化与 Join 观察发生在真实退出确认之前。 */
  private static Held holdClose(ToolGateway.Run delegate) {
    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ToolGateway.Run run =
        new ToolGateway.Run() {
          @Override
          public ToolGateway.Completion execute(Consumer<ToolResult> progress) {
            return delegate.execute(progress);
          }

          @Override
          public void close() {
            closeEntered.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the tool close");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
            delegate.close();
          }
        };
    return new Held(run, closeEntered, release);
  }

  private static void driveTerminal(Fixture fixture, UUID threadId) {
    ThreadProcessor processor =
        new ThreadProcessor(
            fixture.store,
            (threadId1, path, preparation) -> null,
            new ThreadProcessorConfig(Duration.ofSeconds(1), () -> CompactionConfig.DEFAULT));
    ClaimedWork claim = ThreadProcessorTestSupport.claimThreadWork(fixture.store, threadId, NOW);
    assertEquals(ThreadProcessResult.COMPLETED, processor.process(claim));
  }

  private static UUID parentOf(HarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow().parentThreadId());
  }

  private static long version(HarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow().version());
  }

  /** 为子 Thread 插入一个已应用源命令与指向直接父的 NOTIFY_PARENT Join；源身份取自该 Thread 当前路径上的真实 TURN_START 与 USER 消息。 */
  private static void insertAppliedJoin(HarnessStore store, UUID childId, UUID joinId) {
    store.transaction(
        tx -> {
          ThreadState child = tx.lockThread(childId).orElseThrow();
          Entry assistant =
              tx.loadEntriesBySessionId(child.sessionId()).stream()
                  .filter(
                      entry ->
                          entry.payload() instanceof MessagePayload message
                              && message.message().role() == AgentMessageRole.ASSISTANT)
                  .reduce((first, second) -> second)
                  .orElseThrow();
          if (!child.headEntryId().equals(assistant.id())) {
            tx.updateThread(child.advanceHead(assistant.id(), NOW));
            child = tx.lockThread(child.id()).orElseThrow();
          }
          EntryPath path = tx.loadEntryPath(child.headEntryId());
          Entry start =
              path.entries().stream()
                  .filter(
                      entry ->
                          entry.payload() instanceof TurnStartPayload turn
                              && childId.equals(turn.ownerThreadId()))
                  .findFirst()
                  .orElseThrow();
          MessagePayload input =
              path.entries().stream()
                  .filter(
                      entry ->
                          entry.payload() instanceof MessagePayload message
                              && message.message().role() == AgentMessageRole.USER)
                  .map(entry -> (MessagePayload) entry.payload())
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "no user message on path "
                                  + path.entries().stream()
                                      .map(entry -> entry.payload().type())
                                      .toList()));
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
          tx.updateCommands(List.of(source.markApplied(start.id())));
          child = tx.lockThread(child.id()).orElseThrow();
          tx.updateThread(child.advanceInputThroughSequence(1L, NOW));
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  child.parentThreadId(),
                  child.id(),
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
          return null;
        });
  }

  /** 同一通知在命令与历史 Entry 上只计一次。 */
  private static int notificationCount(HarnessStore store, UUID parentId) {
    return store.transaction(
        tx -> {
          Set<UUID> notificationIds = new HashSet<>();
          for (ThreadCommand command : tx.loadCommandsByThread(parentId)) {
            if (command.type().isNotification()) {
              notificationIds.add(
                  ((NotificationCommandPayload) command.payload()).notificationId());
            }
          }
          EntryPath path = tx.loadEntryPath(tx.findThread(parentId).orElseThrow().headEntryId());
          for (Entry entry : path.entries()) {
            if (entry.payload() instanceof NotificationPayload notification) {
              notificationIds.add(notification.notificationId());
            }
          }
          return notificationIds.size();
        });
  }

  private record Held(ToolGateway.Run run, CountDownLatch closeEntered, CountDownLatch release) {}
}

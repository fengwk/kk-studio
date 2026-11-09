package fun.fengwk.kkstudio.harness.builtin.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ExecutionExitFailureStore;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ExitLogCapture;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionLifecycle;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionLifecycle;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 准入、请求物化与 stale lease 恢复：READY 准入结果、materialization 围栏、过期 RUNNING 重试与 terminal cleanup。 */
class ModelProcessorAdmissionTest extends ModelProcessorTestSupport {
  @Test
  void rejectsNonModelClaim() {
    Fixture fixture = fixture();
    ClaimedWork threadClaim =
        fixture
            .store
            .<Optional<ClaimedWork>>transaction(
                tx ->
                    tx.work()
                        .claimNext(
                            WorkTargetType.THREAD,
                            token(fixture.invocationId, "thread"),
                            Duration.ofSeconds(60)))
            .orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(threadClaim));
  }

  /** READY admission Admitted：一次短事务 READY -&gt; RUNNING + version+1，保留 heartbeat 与本地执行线程。 */
  @Test
  void admissionStartedMarksRunningAndKeepsLocalExecution() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.admitCalls);
    ModelGateway.Execution execution = fixture.gateway.executions.get(0);
    assertEquals(fixture.invocationId, execution.invocationId());
    assertEquals(1, execution.proposedAttempt());
    assertEquals(fixture.requestSpec.providerType(), execution.providerType());
    assertEquals(materialized(fixture), execution.request());
    assertFalse(fixture.gateway.controlled(fixture.invocationId).interrupted.get());
    assertTrue(fixture.processor.hasActiveExecution());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(token(fixture.invocationId), modelWork.leaseToken());
  }

  /**
   * Model 终态先物化、close 后释放：确认前 Join 不交付，真实 finally 确认在同一事务内补结算一次；与普通终止路径一致，只有正常完成冻结最终回答，截断与过滤的
   * FAILED 回合即使以 ASSISTANT 消息结尾也不冻结。
   */
  @ParameterizedTest
  @EnumSource(
      value = GenerationStopReason.class,
      names = {"COMPLETE", "LENGTH", "FILTERED"})
  void modelTerminalSettlesJoinOnlyAfterAdmissionClose(GenerationStopReason stopReason)
      throws Exception {
    Fixture fixture = fixture();
    deleteModelWork(fixture);
    fixture.store.transaction(
        tx -> {
          tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          tx.work().delete(new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()));
          return null;
        });
    UUID parentId = UUID.randomUUID();
    UserBasis basis = seedUserBasis(fixture.store, parentId);
    UUID invocationId = seedUserBasisInvocation(fixture.store, basis, requestSpec(), NOW);
    UUID joinId = UUID.randomUUID();
    insertAppliedJoin(fixture.store, basis.threadId(), parentId, joinId);

    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);
    AtomicReference<Thread> runner = new AtomicReference<>();
    AtomicInteger closes = new AtomicInteger();
    fixture.gateway.queue(
        new ModelGateway.Admitted() {
          @Override
          public ModelGateway.Outcome run(ModelGateway.Sink sink) {
            runner.set(Thread.currentThread());
            return new ModelGateway.Succeeded(
                new ProviderCompletion(response("final answer", stopReason), null));
          }

          @Override
          public void close() {
            closes.incrementAndGet();
            closeEntered.countDown();
            try {
              if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release admitted close");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
          }
        });
    try {
      assertEquals(
          ProcessResult.STARTED, fixture.processor.process(claim(fixture.store, invocationId)));
      assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "terminal committed and close is held");
      UUID executionId =
          fixture.store.transaction(
              tx -> tx.exits().loadUnconfirmed(basis.threadId()).get(0).executionId());

      ThreadProcessor threadProcessor =
          new ThreadProcessor(
              fixture.store,
              new DriverRegistry(
                  List.of(
                      HarnessRuntimeTestSupport.driver(
                          fixture.store, (threadId, rootSessionId, path, preparation) -> null))),
              new ThreadProcessorConfig(Duration.ofSeconds(1), () -> CompactionConfig.DEFAULT));
      assertEquals(
          ThreadProcessResult.COMPLETED,
          threadProcessor.process(
              ThreadProcessorTestSupport.claimThreadWork(fixture.store, basis.threadId(), NOW)));
      ThreadJoin pending = fixture.store.transaction(tx -> tx.joins().find(joinId).orElseThrow());
      assertFalse(pending.matched(), "join must wait for studio exit confirmation");
      EntryPath materializedPath =
          fixture.store.transaction(
              tx ->
                  tx.entries()
                      .loadPath(tx.threads().find(basis.threadId()).orElseThrow().headEntryId()));
      boolean completed = stopReason == GenerationStopReason.COMPLETE;
      TurnEndOutcome expectedOutcome = completed ? TurnEndOutcome.COMPLETED : TurnEndOutcome.FAILED;
      assertTrue(
          materializedPath.head().payload() instanceof TurnEndPayload end
              && !end.continueModel()
              && end.outcome() == expectedOutcome,
          () -> "head=" + materializedPath.head().payload());
      assertTrue(
          materializedPath.entries().get(materializedPath.entries().size() - 2).payload()
                  instanceof MessagePayload message
              && message.message().role() == AgentMessageRole.ASSISTANT,
          "the closed turn ends with the ASSISTANT response in every case");
      long versionBeforeExit = thread(fixture.store, basis.threadId()).version();

      releaseClose.countDown();
      assertTrue(runner.get().join(Duration.ofSeconds(5)), "model execution thread exits");

      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          fixture.store.transaction(tx -> tx.exits().find(executionId).orElseThrow().state()),
          "real finally confirmation commits before checking Join");
      ThreadJoin settled = fixture.store.transaction(tx -> tx.joins().find(joinId).orElseThrow());
      assertTrue(settled.matched());
      assertEquals(materializedPath.head().id(), settled.terminalEntryId());
      if (completed) {
        assertNotNull(settled.finalAnswerEntryId());
        Entry answer =
            fixture.store.transaction(
                tx -> tx.entries().find(settled.finalAnswerEntryId()).orElseThrow());
        assertTrue(
            answer.payload() instanceof MessagePayload message
                && message.message().role() == AgentMessageRole.ASSISTANT);
      } else {
        assertNull(settled.finalAnswerEntryId(), "a FAILED turn freezes no final answer");
      }
      assertEquals(versionBeforeExit + 1, thread(fixture.store, basis.threadId()).version());
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          fixture.store.transaction(tx -> tx.exits().find(executionId).orElseThrow().state()));
      assertEquals(1, closes.get());
      assertEquals(1, notificationCount(fixture.store, parentId));
    } finally {
      releaseClose.countDown();
      try {
        if (runner.get() != null) {
          assertTrue(
              runner.get().join(Duration.ofSeconds(5)), "model execution must exit in cleanup");
        }
      } finally {
        fixture.processor.close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stopAfterHeldModelCloseKeepsTheGenerationUnconfirmedOnlyWhenExitPersistenceFails(
      boolean failConfirm) throws Exception {
    // 测试意图：真实 Model body 已返回、admission close 被 latch 挡住时 Stop 封账；释放后 close 成功但退出确认
    // 持久化失败，该代次必须保留 UNCONFIRMED（RUNNING/Join 未交付/父无通知）；同一路径不注入故障时正常收敛。
    Fixture fixture = fixture();
    deleteModelWork(fixture);
    UUID parentId = UUID.randomUUID();
    UserBasis basis = seedUserBasis(fixture.store, parentId);
    UUID invocationId = seedUserBasisInvocation(fixture.store, basis, requestSpec(), NOW);
    UUID joinId = UUID.randomUUID();
    insertAppliedJoin(fixture.store, basis.threadId(), parentId, joinId);
    AtomicInteger confirmAttempts = new AtomicInteger();
    HarnessStore processorStore =
        ExecutionExitFailureStore.wrapping(fixture.store, confirmAttempts, failConfirm);
    ModelProcessor processor =
        new ModelProcessor(
            processorStore,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(
                LEASE_CONFIG,
                () -> NO_RETRY,
                FALLBACK_DELAY,
                ModelStreamConfig.DEFAULT,
                null,
                providerName -> ModelHttpErrorPolicy.DEFAULT),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(processorStore, fixture.clock));
    fixture.gateway.owner = processor;
    HarnessRuntime runtime =
        new HarnessRuntime(
            fixture.store,
            new DriverRegistry(
                List.of(
                    HarnessRuntimeTestSupport.driver(
                        fixture.store,
                        (threadId, rootSessionId, candidatePath, preparation) -> null))),
            () -> CompactionConfig.DEFAULT,
            processor::cancel,
            mock(ToolProcessor.class)::cancel);

    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);
    AtomicReference<Thread> runner = new AtomicReference<>();
    AtomicInteger closes = new AtomicInteger();
    fixture.gateway.queue(
        new ModelGateway.Admitted() {
          @Override
          public ModelGateway.Outcome run(ModelGateway.Sink sink) {
            runner.set(Thread.currentThread());
            return new ModelGateway.Succeeded(
                new ProviderCompletion(
                    response("final answer", GenerationStopReason.COMPLETE), null));
          }

          @Override
          public void close() {
            closes.incrementAndGet();
            closeEntered.countDown();
            try {
              if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release admitted close");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
          }
        });
    UUID executionId = null;
    ExitLogCapture exitLogs = ExitLogCapture.attach(ExecutionLifecycle.class);
    try {
      assertEquals(ProcessResult.STARTED, processor.process(claim(fixture.store, invocationId)));
      assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "terminal committed and close is held");
      executionId =
          fixture.store.transaction(
              tx -> tx.exits().loadUnconfirmed(basis.threadId()).get(0).executionId());
      assertEquals(0, confirmAttempts.get(), "Stop must not attempt exit confirmation");
      ThreadSnapshot before = runtime.getThreadSnapshot(basis.threadId());
      assertEquals(ThreadRuntimeStatus.RUNNING, before.runtimeStatus());
      assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
      assertEquals(0, notificationCount(fixture.store, parentId));

      runtime.stop(new StopCommand(basis.threadId(), id(71), before.thread().version()));

      assertTrue(
          fixture
              .store
              .transaction(tx -> builtin(fixture.store, tx).findModelInvocation(invocationId))
              .isEmpty(),
          "Stop deletes the completed Model invocation");
      assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, invocationId)));
      UUID heldExecutionId = executionId;
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          fixture.store.transaction(tx -> tx.exits().find(heldExecutionId).orElseThrow().state()));
      assertEquals(
          ThreadRuntimeStatus.EXIT_PENDING,
          runtime.getThreadSnapshot(basis.threadId()).runtimeStatus());
      assertFalse(runtime.findJoin(joinId).orElseThrow().matched());
      assertEquals(0, notificationCount(fixture.store, parentId));

      releaseClose.countDown();
      assertTrue(
          runner.get().join(Duration.ofSeconds(5)),
          "released model execution must exit, including exit confirmation");
      assertEquals(1, closes.get(), "successful close runs exactly once");
      assertEquals(1, confirmAttempts.get(), "exactly one exit confirmation persistence attempt");
      ThreadSnapshot after = runtime.getThreadSnapshot(basis.threadId());
      ThreadJoin join = runtime.findJoin(joinId).orElseThrow();
      ExecutionExitState state =
          fixture.store.transaction(tx -> tx.exits().find(heldExecutionId).orElseThrow().state());
      if (failConfirm) {
        assertAll(
            () ->
                assertEquals(
                    ExecutionExitState.UNCONFIRMED,
                    state,
                    "failed confirmation must preserve the exit fact"),
            () -> assertEquals(ThreadRuntimeStatus.EXIT_PENDING, after.runtimeStatus()),
            () -> assertEquals(ThreadExecutionControl.STOPPED, after.thread().executionControl()),
            () -> assertFalse(join.matched(), "unconfirmed exit must withhold the join"),
            () -> assertEquals(0, notificationCount(fixture.store, parentId)));
        exitLogs.assertSingleSafeFailure(
            "local execution exit is not proven",
            "EXECUTION_EXIT",
            ExecutionExitFailureStore.InjectedPersistenceFailure.class,
            ExecutionExitFailureStore.SYNTHETIC_SECRET);
      } else {
        assertAll(
            () -> assertEquals(ExecutionExitState.STUDIO_EXITED, state),
            () -> assertEquals(ThreadRuntimeStatus.IDLE, after.runtimeStatus()),
            () -> assertEquals(ThreadExecutionControl.STOPPED, after.thread().executionControl()),
            () -> assertTrue(join.matched(), "confirmed exit delivers the withheld join"),
            () -> assertEquals(1, notificationCount(fixture.store, parentId)));
        exitLogs.assertNoFailure("cannot confirm model execution exit");
      }
    } finally {
      releaseClose.countDown();
      try {
        if (runner.get() != null) {
          assertTrue(
              runner.get().join(Duration.ofSeconds(5)),
              "released model execution must exit in cleanup");
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

  /** 父 Thread 上的通知命令只计一次。 */
  private static int notificationCount(HarnessStore store, UUID parentId) {
    return store.transaction(
        tx ->
            (int)
                loadCommandsByThread(tx, parentId).stream()
                    .filter(command -> command.type().isNotification())
                    .count());
  }

  /** 为子 Thread 插入已应用源命令与指向直接父的 NOTIFY_PARENT Join，使 Stop/确认可以结算真实委派。 */
  private static void insertAppliedJoin(
      HarnessStore store, UUID childId, UUID parentId, UUID joinId) {
    store.transaction(
        tx -> {
          ThreadState child = tx.threads().lock(childId).orElseThrow();
          UUID turnStartEntryId =
              tx.entries().loadPath(child.headEntryId()).openTurnStart().orElseThrow().id();
          ThreadCommand source =
              new ThreadCommand(
                  child.id(),
                  1L,
                  new UserMessageCommandPayload(AgentMessage.user("delegate")),
                  tx.nextId(),
                  ThreadProcessorTestSupport.CREATION_REQUEST_HASH,
                  null,
                  null,
                  null,
                  NOW);
          tx.threads().update(child.reserveCommandSequences(1, NOW));
          tx.commands().insert(List.of(source));
          tx.commands().update(List.of(source.markApplied(turnStartEntryId)));
          child = tx.threads().lock(child.id()).orElseThrow();
          tx.threads()
              .update(child.advanceHeadAndInputThroughSequence(child.headEntryId(), 1L, NOW));
          tx.joins()
              .insert(
                  new ThreadJoin(
                      joinId,
                      ThreadProcessorTestSupport.CREATION_REQUEST_HASH,
                      parentId,
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

  /** Busy：肯定未发出请求，invocation 保持 READY 且不写 Thread，按 retryAfter reschedule，attempt 不变。 */
  @Test
  void busyAdmissionBouncesToReadyWithoutAdvancingAttempt() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(0, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** admit 抛异常（契约：肯定未接受）：同样只重排 Work，使用构造注入的 fallback delay。 */
  @Test
  void startExceptionBouncesWithFallbackDelay() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new RuntimeException("gateway down"));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
  }

  /** Rejected：READY -&gt; FAILED + version+1 + THREAD wake + complete MODEL Work，attempt 不变。 */
  @Test
  void rejectedAdmissionFailsInvocationAndWakesThread() {
    Fixture fixture = fixture();
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "model rejected");
    fixture.gateway.queue(new ModelGateway.Rejected(error));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(0, model.attempt());
    assertEquals(error, model.error());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 历史物化遇到被移除的工具与 opaque replay：放弃不兼容 replay、按合法语义降级投影，网关收到该请求并 start。 */
  @Test
  void incompatibleOpaqueReplayProjectsSemanticFallbackThenStarts() {
    ReplayHistory history = seedReplayHistory();
    InMemoryBuiltinHarnessStore store = history.store();
    UserBasis basis = history.basis();
    UUID invocationId = history.invocationId();
    FakeGateway gateway = new FakeGateway();
    gateway.queueControlled();
    ModelProcessor processor = projectionProcessor(store, gateway);

    assertEquals(ProcessResult.STARTED, processor.process(claim(store, invocationId)));

    assertEquals(1, gateway.admitCalls);
    ModelGateway.Execution execution = gateway.executions.get(0);
    assertEquals(invocationId, execution.invocationId());
    assertEquals(1, execution.proposedAttempt());
    // 降级语义投影：保留 assistant 文本，原 bash 调用并入 USER 上下文并逐字保留 arguments，无 native replay。
    List<ProviderMessage> messages = execution.request().messages();
    assertEquals(3, messages.size());
    assertEquals(
        List.of(ProviderMessageRole.USER, ProviderMessageRole.ASSISTANT, ProviderMessageRole.USER),
        messages.stream().map(ProviderMessage::role).toList());
    assertEquals("answer", ((ProviderTextBlock) messages.get(1).contents().get(0)).text());
    String fallback = ((ProviderTextBlock) messages.get(2).contents().get(0)).text();
    assertTrue(fallback.startsWith("Previous context:"));
    assertTrue(fallback.contains("{\"secret\":1}"));
    assertTrue(messages.stream().noneMatch(ProviderMessage::hasReplayState));

    ModelInvocation running = model(store, invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, running.status());
    assertEquals(1, running.attempt());
    assertNull(running.error());
    assertEquals(2, thread(store, basis.threadId()).version());
    assertEquals(
        1, work(store, new WorkTarget(WorkTargetType.THREAD, basis.threadId())).wakeVersion());
    assertTrue(processor.hasActiveExecution());
  }

  /** 加载历史后丢失 claim：即使随后投影要放弃 replay，也不得把别人的执行标记为 FAILED；准入名额被释放，零运行、零 mutation。 */
  @Test
  void ownershipLossDuringMaterializationDoesNotWriteOrDispatch() {
    ReplayHistory history = seedReplayHistory();
    InMemoryBuiltinHarnessStore store = history.store();
    WorkTarget target = new WorkTarget(BuiltinWorkType.MODEL, history.invocationId());
    FakeGateway gateway = new FakeGateway();
    HarnessStore hooked =
        afterEntryPathLoad(
            store,
            () ->
                store.transaction(
                    tx -> {
                      tx.threads().lock(history.basis().threadId()).orElseThrow();
                      tx.work().delete(target);
                      return null;
                    }));
    ModelProcessor processor = projectionProcessor(hooked, gateway);
    gateway.queueControlled();

    assertEquals(
        ProcessResult.LOST_OWNERSHIP, processor.process(claim(store, history.invocationId())));
    assertEquals(ModelInvocationStatus.READY, model(store, history.invocationId()).status());
    assertEquals(0, model(store, history.invocationId()).attempt());
    assertEquals(1, thread(store, history.basis().threadId()).version());
    assertNull(work(store, target));
    assertEquals(0, gateway.runCalls.get());
    assertTrue(gateway.runs.get(history.invocationId()).closedWithoutRun.get());
    assertFalse(processor.hasActiveExecution());
  }

  /** Store 物化阶段报告无效历史：归类 INVALID_REQUEST 并清理本地执行，而非毒化工作循环。 */
  @Test
  void invalidHistoryDuringMaterializationRejectsWithoutGateway() {
    Fixture fixture = fixture();
    HarnessStore hooked =
        afterEntryPathLoad(
            fixture.store,
            () -> {
              throw new IllegalStateException("invalid entry path");
            });
    ModelProcessor processor = projectionProcessor(hooked, fixture.gateway);

    assertEquals(
        ProcessResult.TERMINATED, processor.process(claim(fixture.store, fixture.invocationId)));
    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failed.error().kind());
    assertEquals("invalid entry path", failed.error().message());
    assertEquals(0, failed.attempt());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(processor.hasActiveExecution());
  }

  // ---------------------------------------------------------------------------------------------
  // prepare 恢复 / cleanup
  // ---------------------------------------------------------------------------------------------

  /**
   * 新 claim 遇到租约过期的 RUNNING：接入既有重试策略记录一次失败 attempt 后回到 READY 并按策略延迟重排 MODEL Work，attempt 保持已确认值、先前
   * partial 只留作尝试审计，绝不调用 Gateway，也不 request THREAD wake。
   */
  @Test
  void staleRunningLeaseRecoveryRetriesWithExistingPolicy() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    StreamCheckpoint checkpoint = new StreamCheckpoint(1, 7, "durable partial", "thinking");
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.checkpoint(checkpoint, NOW.plusSeconds(1)));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertNull(model.error());
    assertEquals(1, model.failedAttempts().size());
    assertEquals(1, model.failedAttempts().getFirst().attempt());
    assertEquals(7L, model.failedAttempts().getFirst().sequence());
    assertEquals("durable partial", model.failedAttempts().getFirst().text());
    assertEquals(ProviderErrorKind.TRANSIENT, model.failedAttempts().getFirst().error().kind());
    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(fixture.clock.instant().plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * 新 claim 遇到租约过期的 RUNNING 且重试预算耗尽：FAILED 保留已确认 attempt，唤醒 THREAD 并 complete MODEL
   * Work，明确报告失败而不是无限重试。
   */
  @Test
  void staleRunningLeaseRecoveryExhaustedTerminatesFailed() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertTrue(model.failedAttempts().isEmpty());
    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }

  /** 恢复预检后丢失 claim：重试与预算耗尽两条路径的 tentative 写入都必须回滚，不能推进 attempt 或唤醒 THREAD。 */
  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void recoveryLosingClaimAfterPrecheckRollsBackAllMutations(int maxRetries) {
    InvocationRetryPolicy policy =
        new InvocationRetryPolicy(
            maxRetries,
            InvocationRetryBackoffStrategy.FIXED,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
    Fixture fixture = fixture(policy);
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);
    ModelInvocation beforeModel = model(fixture.store, fixture.invocationId);
    ThreadState beforeThread = thread(fixture.store, fixture.baseline.threadId());
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId());
    Work beforeWake = work(fixture.store, threadTarget);
    AtomicInteger transactions = new AtomicInteger();
    HarnessStore interleaved =
        (HarnessStore)
            Proxy.newProxyInstance(
                HarnessStore.class.getClassLoader(),
                new Class<?>[] {HarnessStore.class, BuiltinStore.class},
                (proxy, method, args) -> {
                  Object result = invokeUnchecked(fixture.store, method, args);
                  if ("transaction".equals(method.getName())
                      && transactions.incrementAndGet() == 2) {
                    // 确定性交错：前置 claimOwned 与 admission guard verifier 都已读到有效租约，
                    // 在真正恢复事务前由另一个事务删除 Work，验证恢复写入全部回滚。
                    deleteModelWork(fixture);
                  }
                  return result;
                });
    try (ModelProcessor processor =
        new ModelProcessor(
            interleaved,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> policy, FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            Runnable::run,
            TestExecutionLifecycle.create(fixture.store, fixture.clock))) {
      assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(recovered));
      assertEquals(beforeModel, model(fixture.store, fixture.invocationId));
      assertEquals(beforeThread, thread(fixture.store, fixture.baseline.threadId()));
      assertEquals(beforeWake, work(fixture.store, threadTarget));
      assertNull(work(fixture.store, recovered.target()));
      assertEquals(0, fixture.gateway.admitCalls);
      assertFalse(processor.hasActiveExecution());
    }
  }

  /** terminal 行且 resultEntryId 仍 null：确保 THREAD wake 后 complete，不重复 bump version。 */
  @Test
  void terminalCleanupWakesThreadWithoutVersionBump() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.resultEntryId());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** terminal 行且 resultEntryId 已链接：只 complete MODEL Work，不请求 THREAD wake。 */
  @Test
  void terminalCleanupSkipsThreadWakeWhenResultEntryIsLinked() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    UUID assistantEntryId =
        fixture.store.transaction(
            tx -> {
              UUID userId = tx.nextId();
              UUID id = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          userId,
                          fixture.baseline.sessionId(),
                          fixture.baseline.turnStartEntryId(),
                          userMessagePayload(),
                          NOW.plusMillis(2)));
              tx.entries()
                  .insert(
                      new Entry(
                          id,
                          fixture.baseline.sessionId(),
                          userId,
                          assistantPayload(),
                          NOW.plusMillis(3)));
              return id;
            });
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.attachResultEntry(assistantEntryId, NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(claimed));

    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }
}

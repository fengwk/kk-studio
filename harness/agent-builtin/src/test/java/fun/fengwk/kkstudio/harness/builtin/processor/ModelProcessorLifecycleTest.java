package fun.fengwk.kkstudio.harness.builtin.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ClaimOwnershipTestSupport;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ExecutionExitFailureStore;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ExitLogCapture;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionLifecycle;
import fun.fengwk.kkstudio.harness.runtime.execution.MemoryExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkHeartbeat;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 进程生命周期与所有权围栏：恢复 / 重复 claim、close / cancel、heartbeat margin、租约丢失与配置校验。 */
class ModelProcessorLifecycleTest extends ModelProcessorTestSupport {
  @Test
  void sameJvmRecoverySupersedesStaleLocalExecution() throws Exception {
    Fixture fixture = fixture();
    ControlledRun oldRun = new ControlledRun();
    fixture.gateway.queue(oldRun);
    try {
      assertEquals(
          ProcessResult.STARTED,
          fixture.processor.process(
              claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "old"))));
      assertTrue(
          oldRun.entered.await(5, TimeUnit.SECONDS),
          "old model body must enter before the lease is reclaimed");
      fixture.clock.advance(Duration.ofSeconds(61));
      ClaimedWork recovered =
          claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "new"));

      assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));
      awaitExecutionExit(fixture);

      ModelInvocation model = model(fixture.store, fixture.invocationId);
      assertEquals(ModelInvocationStatus.FAILED, model.status());
      assertEquals(1, model.attempt());
      assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
      assertEquals(1, fixture.gateway.admitCalls);
      assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
      assertEquals(
          2,
          work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
              .wakeVersion());
      assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
      assertFalse(fixture.processor.hasActiveExecution());
    } finally {
      // close 请求中断；只 join 真正运行过 body 的线程，registry 是否清空不作为退出证据。
      try {
        fixture.processor.close();
      } finally {
        for (Thread executionThread : List.copyOf(fixture.gateway.executionThreads)) {
          assertTrue(
              executionThread.join(Duration.ofSeconds(5)),
              "model execution thread must exit during cleanup");
        }
      }
    }
  }

  /** 恢复路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void recoveryWithDeletedWorkIsLost() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** terminal cleanup 路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void terminalCleanupWithDeletedWorkIsLost() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  // ---------------------------------------------------------------------------------------------
  // Handle fencing / duplicate admission / null start / lease margin / close
  // ---------------------------------------------------------------------------------------------

  /** 顺序重复投递同一 claim（同 token）：LOST no-op，不 cancel、不 mutation，合法 RUNNING 不受影响。 */
  @Test
  void duplicateSequentialProcessWithSameClaimIsLostNoOp() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    long version = thread(fixture.store, fixture.baseline.threadId()).version();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(version, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.admitCalls);
    assertTrue(fixture.processor.hasActiveExecution());
  }

  /** 首个 process 的执行线程仍在准入时重复投递同一 claim：后到者 LOST no-op，绝不二次准入或双发 Provider。 */
  @Test
  void duplicateConcurrentProcessWithSameClaimIsLostNoOp() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch inAdmit = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.beforeReturn =
        execution -> {
          inAdmit.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    assertTrue(inAdmit.await(5, TimeUnit.SECONDS));
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    release.countDown();
    ControlledRun run = fixture.gateway.controlled(fixture.invocationId);
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertFalse(run.interrupted.get());
  }

  @Test
  void nearExpiryClaimGetsFullLeaseMarginBeforeDispatch() {
    Fixture fixture = fixture();
    ClaimedWork claimed =
        fixture
            .store
            .transaction(
                tx ->
                    tx.work()
                        .claimNext(
                            BuiltinWorkType.MODEL,
                            token(fixture.invocationId, "near-expiry"),
                            Duration.ofSeconds(1)))
            .orElseThrow();
    fixture.gateway.queueControlled();

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(NOW.plusSeconds(30), modelWork.leaseUntil());
    assertEquals(token(fixture.invocationId, "near-expiry"), modelWork.leaseToken());
  }

  /** claim lease 剩余充足：不额外 renew，保持 dispatcher 写入的 lease。 */
  @Test
  void sufficientLeaseMarginIsNotRenewed() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    fixture.gateway.queueControlled();

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(claimed.leaseUntil(), modelWork.leaseUntil());
  }

  /** close 后拒绝新 process；close 幂等且不 shutdown 注入的 scheduler。 */
  @Test
  void closedProcessorRejectsProcessAndCloseIsIdempotent() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    fixture.processor.close();
    fixture.processor.close();

    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claimed));
    assertFalse(fixture.processor.hasActiveExecution());
    assertFalse(fixture.scheduler.isShutdown());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
  }

  // ---------------------------------------------------------------------------------------------
  // 最终并发收口：claimOwned 前置校验 / close-race / 准入竞态
  // ---------------------------------------------------------------------------------------------

  /**
   * active RUNNING + 伪造不同 token 的 claim：Work-only 前置校验（claimOwned）必须先于 guard 替换 / supersede—— 伪造
   * token 与 stored leaseToken 不匹配 → LOST no-op，合法 active execution 不被 cancel、durable 不变。
   */
  @Test
  void forgedDifferentTokenWhileActiveRunningIsLostWithoutCancelling() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    ClaimedWork forged =
        new ClaimedWork(
            claimed.target(),
            claimed.claimedWakeVersion(),
            token(fixture.invocationId, "forged"),
            claimed.leaseUntil());
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(forged));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.admitCalls);
    assertTrue(fixture.processor.hasActiveExecution());
    assertEquals(
        token(fixture.invocationId),
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId))
            .leaseToken());
  }

  /**
   * admit 期间 close：execution 已被 abandon，准入返回后释放名额、绝不持久化 RUNNING 也不运行；invocation 保持 READY 并按兜底延迟重排。
   */
  @Test
  void closeDuringAdmissionReleasesTheSlotWithoutRunning() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn = execution -> fixture.processor.close();
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertTrue(fixture.gateway.runs.get(fixture.invocationId).closedWithoutRun.get());
    assertEquals(0, fixture.gateway.runCalls.get());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * 持 store monitor 使 process 阻塞在所有权校验期间，再关闭 processor；迟到准入不得启动 Gateway，仍 owned 的 READY Work
   * 必须重排且不留下 execution。
   */
  @Test
  void closeBeforeRegistryInsertBouncesReadyWithoutStartingGateway() throws Exception {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled(); // 不应被消费
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holdStore =
        new Thread(
            () ->
                fixture.store.transaction(
                    ignored -> {
                      holding.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    holdStore.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread processThread = new Thread(() -> result.set(fixture.processor.process(claimed)));
    processThread.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (processThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(
        System.nanoTime() < deadline, "process must block on the store monitor (claimOwned)");

    fixture.processor.close(); // 所有权前置校验尚未完成，guard 尚未准入。
    release.countDown();
    holdStore.join(5000);
    processThread.join(5000);

    assertEquals(ProcessResult.RESCHEDULED, result.get());
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * close / 本地 cancel 在 heartbeat 启动窗口内获胜（startHeartbeat 返回后、准入前）：abandoned 检查必须拦截，不调用 Gateway，保持
   * READY 并 reschedule 仍 owned 的 Work。确定性注入：包装 scheduler，在 scheduleAtFixedRate 提交期间取消本地 execution。
   */
  @Test
  void abandonDuringHeartbeatStartupBouncesWithoutStartingGateway() {
    AtomicReference<ModelProcessor> processorRef = new AtomicReference<>();
    AtomicReference<UUID> cancelId = new AtomicReference<>();
    ScheduledExecutorService base = newScheduler();
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    // execution 已 attach，在 heartbeat 启动期间取消。
                    processorRef.get().cancel(cancelId.get());
                  }
                  return method.invoke(base, args);
                });
    Fixture fixture = fixture(NO_RETRY, requestSpec(), hooked);
    cancelId.set(fixture.invocationId);
    processorRef.set(fixture.processor);
    fixture.gateway.queueControlled(); // 不应被消费

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * 旧 claim 仍阻塞在准入时 lease 过期并被新 token 重新领取：新 claim supersede 旧本地 execution 并完成准入与运行；旧 claim
   * 准入返回后发现已被 abandon，释放名额、绝不运行，reschedule 也因 ownership 已转移而 LOST。
   */
  @Test
  void newTokenSupersedesExecutionStillInAdmission() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch inAdmit = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.beforeReturn =
        execution -> {
          if (inAdmit.getCount() > 0) {
            inAdmit.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
          }
        };
    ClaimedWork claimedA =
        claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "A"));
    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixture.processor.process(claimedA)));
    threadA.start();
    assertTrue(inAdmit.await(5, TimeUnit.SECONDS));

    // 旧 lease 被 dispatcher 重新 claim：删旧行、重建 wake 并以新 token 重新领取。
    replaceModelWork(fixture);
    ClaimedWork claimedB =
        claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "B"));
    fixture.gateway.queueControlled();
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimedB));
    ControlledRun fresh = fixture.gateway.controlled(fixture.invocationId);

    ControlledRun stale = new ControlledRun();
    fixture.gateway.queue(stale);
    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertTrue(stale.closedWithoutRun.get());
    assertEquals(2, fixture.gateway.admitCalls);
    assertEquals(1, fixture.gateway.runCalls.get());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(
        token(fixture.invocationId, "B"),
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId))
            .leaseToken());
    fresh.succeed(response("done", GenerationStopReason.COMPLETE));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
  }

  /** RUNNING 回写失败按既有 LOST 语义退出，名额和本地资源必须释放。 */
  @Test
  void markRunningFailureClosesAdmissionAndReleasesExecution() {
    Fixture fixture = fixture();
    AtomicBoolean fail = new AtomicBoolean();
    IllegalStateException failure = new IllegalStateException("running commit failed");
    HarnessStore failing =
        (HarnessStore)
            Proxy.newProxyInstance(
                HarnessStore.class.getClassLoader(),
                new Class<?>[] {HarnessStore.class, BuiltinStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("transaction") && fail.compareAndSet(true, false)) {
                    throw failure;
                  }
                  try {
                    return method.invoke(fixture.store, args);
                  } catch (InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    ModelProcessor processor =
        new ModelProcessor(
            failing,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock));
    fixture.gateway.queueControlled();
    fixture.gateway.beforeReturn = ignored -> fail.set(true);
    try {
      assertEquals(
          ProcessResult.LOST_OWNERSHIP,
          processor.process(
              claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "A"))));
      assertFalse(processor.hasActiveExecution());
      assertTrue(fixture.gateway.runs.get(fixture.invocationId).closedWithoutRun.get());
    } finally {
      processor.close();
    }
  }

  /** Executor 拒绝发生在 durable RUNNING 后：close 成功才确认 NOT_STARTED，而不是 Studio body 已退出。 */
  @Test
  void rejectedExecutionReleasesAdmissionAndConfirmsNotStarted() {
    Fixture fixture = fixture();
    AtomicReference<ExecutionExitFact> registered = new AtomicReference<>();
    ModelProcessor processor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            ignored -> {
              registered.set(
                  fixture.store.transaction(
                      tx -> tx.exits().loadUnconfirmed(fixture.baseline.threadId()).getFirst()));
              throw new RejectedExecutionException("rejected");
            },
            TestExecutionLifecycle.create(fixture.store, fixture.clock));
    fixture.gateway.queueControlled();
    try {
      assertEquals(
          ProcessResult.LOST_OWNERSHIP,
          processor.process(
              claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "A"))));
      assertFalse(processor.hasActiveExecution());
      assertTrue(fixture.gateway.runs.get(fixture.invocationId).closedWithoutRun.get());
      assertTrue(
          Boolean.TRUE.equals(
              fixture.store.transaction(
                  tx -> tx.exits().loadUnconfirmed(fixture.baseline.threadId()).isEmpty())));
      assertEquals(
          ExecutionExitState.NOT_STARTED,
          fixture.store.transaction(
              tx -> tx.exits().find(registered.get().executionId()).orElseThrow().state()),
          "the same registered fact must remain as NOT_STARTED, not disappear or claim body exit");
      assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
      assertNotNull(
          work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId())));
    } finally {
      processor.close();
    }
  }

  /** Executor 拒绝后 close 失败：只尝试一次且不确认，执行保持 UNCONFIRMED 等待后续恢复。 */
  @Test
  void rejectedExecutionCloseFailureLeavesGenerationUnconfirmed() {
    Fixture fixture = fixture();
    AtomicInteger closes = new AtomicInteger();
    AtomicInteger runs = new AtomicInteger();
    fixture.gateway.queue(
        new ModelGateway.Admitted() {
          @Override
          public ModelGateway.Outcome run(ModelGateway.Sink sink) {
            runs.incrementAndGet();
            return new ModelGateway.Succeeded(
                new ProviderCompletion(response("unexpected", GenerationStopReason.COMPLETE)));
          }

          @Override
          public void close() {
            closes.incrementAndGet();
            throw new IllegalStateException(ExecutionExitFailureStore.SYNTHETIC_SECRET);
          }
        });
    ModelProcessor processor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            ignored -> {
              throw new RejectedExecutionException("rejected");
            },
            TestExecutionLifecycle.create(fixture.store, fixture.clock));
    ExitLogCapture exitLogs = ExitLogCapture.attach(ModelProcessor.class);
    try {
      assertEquals(
          ProcessResult.LOST_OWNERSHIP,
          processor.process(
              claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "A"))));
      assertEquals(1, closes.get());
      assertEquals(0, runs.get());
      assertFalse(processor.hasActiveExecution());
      List<ExecutionExitFact> unconfirmed =
          fixture.store.transaction(tx -> tx.exits().loadUnconfirmed(fixture.baseline.threadId()));
      assertEquals(1, unconfirmed.size());
      assertEquals(ExecutionExitState.UNCONFIRMED, unconfirmed.getFirst().state());
      assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
      exitLogs.assertSingleSafeFailure(
          "cannot release model admission resources",
          "MODEL_ADMISSION_CLOSE",
          IllegalStateException.class,
          ExecutionExitFailureStore.SYNTHETIC_SECRET);
    } finally {
      try {
        processor.close();
      } finally {
        exitLogs.close();
      }
    }
    assertEquals(1, closes.get(), "close failure must not be retried");
  }

  /** 已接受的执行在 cancel 后才运行：body 不进入，finally 只 close 一次并在成功后确认 NOT_STARTED。 */
  @Test
  void cancelledBeforeBodyRunsClosesOnceAndConfirmsNotStarted() {
    Fixture fixture = fixture();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    AtomicInteger closes = new AtomicInteger();
    AtomicInteger runs = new AtomicInteger();
    fixture.gateway.queue(
        new ModelGateway.Admitted() {
          @Override
          public ModelGateway.Outcome run(ModelGateway.Sink sink) {
            runs.incrementAndGet();
            return new ModelGateway.Succeeded(
                new ProviderCompletion(response("unexpected", GenerationStopReason.COMPLETE)));
          }

          @Override
          public void close() {
            closes.incrementAndGet();
          }
        });
    ModelProcessor processor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            queued::set,
            TestExecutionLifecycle.create(fixture.store, fixture.clock));
    try {
      assertEquals(
          ProcessResult.STARTED,
          processor.process(
              claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "A"))));
      assertNotNull(queued.get());
      ExecutionExitFact registered =
          fixture.store.transaction(
              tx -> tx.exits().loadUnconfirmed(fixture.baseline.threadId()).getFirst());
      assertTrue(processor.cancel(fixture.invocationId));
      assertFalse(processor.hasActiveExecution());

      queued.get().run();

      assertEquals(0, runs.get(), "cancelled execution must not enter the gateway body");
      assertEquals(1, closes.get(), "processor finally owns the only close");
      List<ExecutionExitFact> unconfirmed =
          fixture.store.transaction(tx -> tx.exits().loadUnconfirmed(fixture.baseline.threadId()));
      assertTrue(unconfirmed.isEmpty(), "close success confirms the generation NOT_STARTED");
      assertEquals(
          ExecutionExitState.NOT_STARTED,
          fixture.store.transaction(
              tx -> tx.exits().find(registered.executionId()).orElseThrow().state()),
          "a queued execution that never entered body cannot claim STUDIO_EXITED");
      assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
      assertEquals(
          1,
          work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
              .wakeVersion(),
          "NOT_STARTED confirmation without a Join must not request a new THREAD wake");
    } finally {
      processor.close();
    }
    assertEquals(1, closes.get(), "processor close must not close an already released admission");
  }

  /** admission 取消只中断 body，不释放物理句柄；body 与资源结束后才确认 Studio 退出。 */
  @Test
  void cancelAfterBodyStartedKeepsPreparedUntilBodyAndResourcesClose() throws Exception {
    Fixture fixture = fixture();
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    ExecutionLifecycle lifecycle =
        TestExecutionLifecycle.create(fixture.store, fixture.clock, journal);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    fixture.gateway.queue(
        new ModelGateway.Admitted() {
          @Override
          public ModelGateway.Outcome run(ModelGateway.Sink sink) {
            entered.countDown();
            while (true) {
              try {
                if (release.await(10, TimeUnit.MILLISECONDS)) {
                  break;
                }
              } catch (InterruptedException cancelled) {
                interrupted.set(true);
              }
            }
            Thread.interrupted();
            return new ModelGateway.Unknown(
                new ModelInvocationError(ProviderErrorKind.TRANSIENT, "cancelled"));
          }

          @Override
          public void close() {}
        });
    ModelProcessor processor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            lifecycle);
    try {
      assertEquals(
          ProcessResult.STARTED,
          processor.process(
              claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "A"))));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      ExecutionExitFact registered =
          fixture.store.transaction(
              tx -> tx.exits().loadUnconfirmed(fixture.baseline.threadId()).getFirst());
      assertEquals(1, journal.loadAll().records().size(), "PREPARED must cover the live body");

      assertTrue(processor.cancel(fixture.invocationId));
      awaitTrue(interrupted::get);
      assertFalse(processor.hasActiveExecution(), "admission entry is removed on cancel");
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          fixture.store.transaction(
              tx -> tx.exits().find(registered.executionId()).orElseThrow().state()),
          "cancelling admission is not physical exit");
      assertFalse(
          journal.loadAll().records().isEmpty(), "PREPARED stays until body and resources end");

      release.countDown();
      var drain = lifecycle.drain(Duration.ofSeconds(5));
      assertTrue(drain.settled(), drain.toString());
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          fixture.store.transaction(
              tx -> tx.exits().find(registered.executionId()).orElseThrow().state()),
          "body exit after cancel must confirm STUDIO_EXITED");
      assertTrue(journal.loadAll().records().isEmpty());
    } finally {
      processor.close();
    }
  }

  /** 已过期 A 在前置检查后恢复，不得中断新 owner B 的合法执行。 */
  @Test
  void expiredClaimCannotCancelNewOwnerAfterOwnershipCheck() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    AtomicReference<Thread> worker = new AtomicReference<>();
    HarnessStore gated = ClaimOwnershipTestSupport.gate(fixture.store, worker, checked, resume);
    ModelProcessor processor =
        new ModelProcessor(
            gated,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock));
    ClaimedWork claimA =
        claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "A"));
    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread threadA = new Thread(() -> result.set(processor.process(claimA)));
    worker.set(threadA);
    try {
      threadA.start();
      assertTrue(checked.await(5, TimeUnit.SECONDS));
      fixture.clock.advance(Duration.ofSeconds(61));
      ClaimedWork claimB =
          claim(fixture.store, fixture.invocationId, token(fixture.invocationId, "B"));
      fixture.gateway.queueControlled();
      assertEquals(ProcessResult.STARTED, processor.process(claimB));
      ControlledRun fresh = fixture.gateway.controlled(fixture.invocationId);
      resume.countDown();
      threadA.join(5000);
      assertFalse(threadA.isAlive());
      assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
      assertTrue(processor.hasActiveExecution());
      fresh.succeed(response("done", GenerationStopReason.COMPLETE));
      awaitTrue(() -> !processor.hasActiveExecution());
      assertEquals(
          ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    } finally {
      resume.countDown();
      processor.close();
      threadA.join(5000);
    }
  }

  /**
   * stale-start fence：A（token-1）在 heartbeat 启动后暂停；lease 过期后由**另一实例**（模拟另一 JVM 的 processor，本地
   * registry / guard 独立，A 的本地 abandoned 检查不可见）以 token-2 接管并完成运行；A 恢复时物化事务的 claim 校验失败 → 立即 abandon
   * 并 LOST，绝不调用 Gateway。
   */
  @Test
  void staleStartAfterCrossInstanceRecoveryNeverCallsGateway() throws Exception {
    CountDownLatch inHeartbeat = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ScheduledExecutorService base = newScheduler();
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  Object result = method.invoke(base, args);
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    inHeartbeat.countDown();
                    release.await(5, TimeUnit.SECONDS);
                  }
                  return result;
                });
    Fixture fixtureA = fixture(NO_RETRY, requestSpec(), hooked);
    fixtureA.gateway.queueControlled(); // 不应被消费
    ClaimedWork claimedA =
        claim(fixtureA.store, fixtureA.invocationId, token(fixtureA.invocationId, "1"));

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixtureA.processor.process(claimedA)));
    threadA.start();
    assertTrue(inHeartbeat.await(5, TimeUnit.SECONDS)); // A 已 prepare 并 attach，暂停在 startHeartbeat。

    // lease 过期，dispatcher 以 token-2 重新 claim；另一实例接管并完成运行（跨实例，A 的 abandoned 检查不可见）。
    fixtureA.clock.advance(Duration.ofSeconds(61));
    replaceModelWork(fixtureA);
    ClaimedWork claimedB =
        claim(fixtureA.store, fixtureA.invocationId, token(fixtureA.invocationId, "2"));
    FakeGateway gatewayB = new FakeGateway();
    gatewayB.queueScript(
        new ModelGateway.Succeeded(
            new ProviderCompletion(response("done", GenerationStopReason.COMPLETE))));
    ModelProcessor processorB =
        new ModelProcessor(
            fixtureA.store,
            gatewayB,
            fixtureA.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixtureA.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixtureA.store, fixtureA.clock));
    assertEquals(ProcessResult.STARTED, processorB.process(claimedB));
    awaitTrue(() -> !processorB.hasActiveExecution());

    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(0, fixtureA.gateway.admitCalls, "stale start must never call the gateway");
    assertFalse(fixtureA.processor.hasActiveExecution());
    awaitExecutionExit(fixtureA, gatewayB);
    ModelInvocation model = model(fixtureA.store, fixtureA.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(3, thread(fixtureA.store, fixtureA.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixtureA.store, new WorkTarget(WorkTargetType.THREAD, fixtureA.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixtureA.store, new WorkTarget(BuiltinWorkType.MODEL, fixtureA.invocationId)));
  }

  /**
   * materialize 完成后 Stop / recovery 删除 MODEL work：准入只做本地检查可以发生，但 markRunning 围栏拒绝，释放名额且绝不运行，durable
   * 保持 READY。
   */
  @Test
  void staleStartAfterMaterializationNeverCallsGateway() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    HarnessStore hooked = afterEntryPathLoad(fixture.store, () -> deleteModelWork(fixture));
    ModelProcessor processor =
        new ModelProcessor(
            hooked,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(
        0, fixture.gateway.runCalls.get(), "post-materialization stale start must not run");
    assertTrue(fixture.gateway.runs.get(fixture.invocationId).closedWithoutRun.get());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertFalse(processor.hasActiveExecution());
  }

  /**
   * scheduler 拒绝 heartbeat 且期间 ownership 已丢（bounce 前 work 行被删）：按 bounce 实际结果返回 LOST_OWNERSHIP，
   * 不得无条件 RESCHEDULED；不调用 Gateway。
   */
  @Test
  void heartbeatSchedulerRejectionWithLostWorkReturnsLostOwnership() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    ScheduledExecutorService base = newScheduler();
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    // prepare 之后、bounce 之前删除 work 行：bounce 无法恢复，必须 LOST。
                    deleteModelWork(fixture);
                    throw new RejectedExecutionException("shutdown");
                  }
                  return method.invoke(base, args);
                });
    ModelProcessor processor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            hooked,
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock));
    fixture.gateway.queueControlled(); // 不应被消费

    assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(claimed));

    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertFalse(processor.hasActiveExecution());
  }

  /** lease 剩余 15s（< leaseDuration 30s）：prepare READY 仍 renew 出完整 margin。 */
  @Test
  void partialLeaseMarginIsRenewedToFullDuration() {
    Fixture fixture = fixture();
    ClaimedWork claimed =
        fixture
            .store
            .transaction(
                tx ->
                    tx.work()
                        .claimNext(
                            BuiltinWorkType.MODEL,
                            token(fixture.invocationId, "partial"),
                            Duration.ofSeconds(15)))
            .orElseThrow();
    fixture.gateway.queueControlled();

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(NOW.plusSeconds(30), modelWork.leaseUntil());
    assertEquals(token(fixture.invocationId, "partial"), modelWork.leaseToken());
  }

  /** lease 剩余恰好等于 leaseDuration：相等不 renew（renew 要求严格延展，等值会违反）。 */
  @Test
  void exactFullLeaseMarginIsNotRenewed() {
    Fixture fixture = fixture();
    ClaimedWork claimed =
        fixture
            .store
            .transaction(
                tx ->
                    tx.work()
                        .claimNext(
                            BuiltinWorkType.MODEL,
                            token(fixture.invocationId, "exact"),
                            Duration.ofSeconds(30)))
            .orElseThrow();
    fixture.gateway.queueControlled();

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(NOW.plusSeconds(30), modelWork.leaseUntil());
  }

  /** heartbeat 一旦 stop 就不能重启（scheduler 拒绝路径之外的防御）。 */
  @Test
  void heartbeatCannotRestartAfterStop() {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    Baseline baseline = seedBaseline(store, NOW);
    UUID invocationId = seedInvocation(store, baseline, requestSpec(), NOW);
    ClaimedWork claimed = claim(store, invocationId);
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(store, newScheduler(), Runnable::run, LEASE_CONFIG, () -> {});
    assertTrue(heartbeat.start(claimed));
    heartbeat.stop();
    assertFalse(heartbeat.start(claimed));
  }

  /** ModelProcessorConfig 要求提供非空的动态 fallback-delay provider。 */
  @Test
  void processorConfigRequiresDelayProvider() {
    Fixture fixture = fixture();
    assertThrows(
        NullPointerException.class,
        () -> new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, null));
    assertEquals(
        FALLBACK_DELAY,
        new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY)
            .admitFailureDelayProvider()
            .get());
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelProcessor(
                fixture.store,
                fixture.gateway,
                fixture.sink,
                null,
                fixture.clock,
                newScheduler(),
                Runnable::run,
                Runnable::run,
                TestExecutionLifecycle.create(fixture.store, fixture.clock)));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelProcessor(
                fixture.store,
                fixture.gateway,
                fixture.sink,
                new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
                fixture.clock,
                newScheduler(),
                null,
                Runnable::run,
                TestExecutionLifecycle.create(fixture.store, fixture.clock)));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelProcessor(
                fixture.store,
                fixture.gateway,
                fixture.sink,
                new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
                fixture.clock,
                newScheduler(),
                Runnable::run,
                null,
                TestExecutionLifecycle.create(fixture.store, fixture.clock)));
  }

  /** ProcessorLeaseConfig：interval 必须为正且严格小于 leaseDuration。 */
  @Test
  void processorLeaseConfigValidatesIntervals() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofNanos(500)));
    new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofMillis(500));
  }

  // ---------------------------------------------------------------------------------------------
  // registry / cancel / close / heartbeat（注册表 / 取消 / 关闭 / 心跳）
  // ---------------------------------------------------------------------------------------------

  /** cancel 只中断本地执行线程（连接关闭、heartbeat 停止），不反写任何 durable 状态，之后的增量全部丢弃。 */
  @Test
  void cancelStopsLocalExecutionWithoutDurableWrite() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun run = fixture.gateway.controlled(fixture.invocationId);

    assertTrue(fixture.processor.cancel(fixture.invocationId));
    awaitTrue(run.interrupted::get);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertNotNull(
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId))
            .leaseToken());
    assertNull(model(fixture.store, fixture.invocationId).streamCheckpoint());
    assertTrue(fixture.sink.events.isEmpty());

    assertFalse(fixture.processor.cancel(fixture.invocationId));
    assertThrows(NullPointerException.class, () -> fixture.processor.cancel(null));
  }

  /** close 中断全部本地执行线程；durable 行保持 RUNNING 等待 lease 恢复。 */
  @Test
  void closeCancelsAllLocalExecutions() {
    Fixture fixture = fixture();
    Baseline secondBaseline = seedBaseline(fixture.store, NOW);
    UUID secondInvocationId = seedInvocation(fixture.store, secondBaseline, requestSpec(), NOW);
    fixture.gateway.queueControlled();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun first = fixture.gateway.controlled(fixture.invocationId);
    assertEquals(
        ProcessResult.STARTED, fixture.processor.process(claim(fixture.store, secondInvocationId)));
    ControlledRun second = fixture.gateway.controlled(secondInvocationId);

    fixture.processor.close();

    awaitTrue(() -> first.interrupted.get() && second.interrupted.get());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(ModelInvocationStatus.RUNNING, model(fixture.store, secondInvocationId).status());
  }

  /** heartbeat 只 renew 当前 Work lease；lease 丢失后立即关 gate / cancel handle，不反写 durable。 */
  @Test
  void heartbeatRenewsLeaseAndStopsWhenOwnershipIsLost() throws Exception {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Baseline baseline = seedBaseline(store, now);
    UUID invocationId = seedInvocation(store, baseline, requestSpec(), now);
    FakeGateway gateway = new FakeGateway();
    RecordingSink sink = new RecordingSink();
    ModelProcessor processor =
        new ModelProcessor(
            store,
            gateway,
            sink,
            new ModelProcessorConfig(
                () -> new ProcessorLeaseConfig(Duration.ofMillis(400), Duration.ofMillis(100)),
                () -> NO_RETRY,
                () -> FALLBACK_DELAY),
            Clock.systemUTC(),
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(store, Clock.systemUTC()));
    ControlledRun run = new ControlledRun();
    gateway.queue(run);
    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.work()
                        .claimNext(
                            BuiltinWorkType.MODEL, token(invocationId), Duration.ofMillis(400)))
            .orElseThrow();

    assertEquals(ProcessResult.STARTED, processor.process(claimed));

    awaitTrue(
        () ->
            work(store, new WorkTarget(BuiltinWorkType.MODEL, invocationId))
                .leaseUntil()
                .isAfter(claimed.leaseUntil()),
        Duration.ofSeconds(2));
    store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.work().delete(new WorkTarget(BuiltinWorkType.MODEL, invocationId));
          return null;
        });
    awaitTrue(run.interrupted::get, Duration.ofSeconds(2));

    assertFalse(processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.RUNNING, model(store, invocationId).status());
  }

  /** scheduler 拒绝 heartbeat 提交：视为无法维持 lease，bounce 为 RESCHEDULED 且不调用 Gateway。 */
  @Test
  void heartbeatSchedulerRejectionBouncesWithoutGatewayCall() {
    Fixture fixture = fixture();
    ScheduledExecutorService dead = newScheduler();
    dead.shutdownNow();
    ModelProcessor deadProcessor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            dead,
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock));
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.RESCHEDULED,
        deadProcessor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
  }

  // ---------------------------------------------------------------------------------------------
  // prepare 期间丢失 ownership
  // ---------------------------------------------------------------------------------------------

  /** claim lease 已过期：typed LOST_OWNERSHIP，无任何 mutation。 */
  @Test
  void expiredClaimIsLostOwnershipWithoutMutation() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(0, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertEquals(token(fixture.invocationId), modelWork.leaseToken());
  }

  /** Stop deleteWork 后：claim 行缺失同样 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void deletedWorkClaimIsLostOwnershipWithoutMutation() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** admission 期间 Work 被删除：Busy / Rejected 都只返回 LOST，不写 invocation、Thread 或 THREAD wake。 */
  @Test
  void admissionResultAfterWorkDeletionIsLostWithoutMutation() {
    Fixture busy = fixture();
    busy.gateway.beforeReturn = execution -> deleteModelWork(busy);
    busy.gateway.queue(new ModelGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP, busy.processor.process(claim(busy.store, busy.invocationId)));

    assertEquals(ModelInvocationStatus.READY, model(busy.store, busy.invocationId).status());
    assertEquals(0, thread(busy.store, busy.baseline.threadId()).version());
    assertEquals(
        1,
        work(busy.store, new WorkTarget(WorkTargetType.THREAD, busy.baseline.threadId()))
            .wakeVersion());
    assertNull(work(busy.store, new WorkTarget(BuiltinWorkType.MODEL, busy.invocationId)));
    assertFalse(busy.processor.hasActiveExecution());

    Fixture rejected = fixture();
    rejected.gateway.beforeReturn = execution -> deleteModelWork(rejected);
    rejected.gateway.queue(
        new ModelGateway.Rejected(
            new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "model rejected")));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        rejected.processor.process(claim(rejected.store, rejected.invocationId)));

    assertEquals(
        ModelInvocationStatus.READY, model(rejected.store, rejected.invocationId).status());
    assertEquals(0, thread(rejected.store, rejected.baseline.threadId()).version());
    assertEquals(
        1,
        work(rejected.store, new WorkTarget(WorkTargetType.THREAD, rejected.baseline.threadId()))
            .wakeVersion());
    assertNull(work(rejected.store, new WorkTarget(BuiltinWorkType.MODEL, rejected.invocationId)));
    assertFalse(rejected.processor.hasActiveExecution());
  }

  /** 准入复核通过后、prepare 前 Work 被删除：READY 不进入 dispatch，零 gateway、零 mutation。 */
  @Test
  void readyClaimLostBeforePrepareIsLostWithoutGateway() {
    Fixture fixture = fixture();
    HarnessStore hooked = afterAdmissionCheck(fixture.store, () -> deleteModelWork(fixture));
    try (ModelProcessor processor =
        new ModelProcessor(
            hooked,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock))) {
      assertEquals(
          ProcessResult.LOST_OWNERSHIP,
          processor.process(claim(fixture.store, fixture.invocationId)));

      ModelInvocation model = model(fixture.store, fixture.invocationId);
      assertEquals(ModelInvocationStatus.READY, model.status());
      assertEquals(0, model.attempt());
      assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
      assertEquals(0, fixture.gateway.admitCalls);
      assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
      assertFalse(processor.hasActiveExecution());
    }
  }

  /** 准入复核通过后、终态清理 fence 前 Work 被删除：THREAD wake 的 tentative 写入随事务回滚，终态行与 Thread 保持不变。 */
  @Test
  void terminalCleanupClaimLostAtFinalFenceRollsBackThreadWake() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    HarnessStore hooked = afterAdmissionCheck(fixture.store, () -> deleteModelWork(fixture));
    try (ModelProcessor processor =
        new ModelProcessor(
            hooked,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock))) {
      assertEquals(
          ProcessResult.LOST_OWNERSHIP,
          processor.process(claim(fixture.store, fixture.invocationId)));

      ModelInvocation terminal = model(fixture.store, fixture.invocationId);
      assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
      assertEquals(1, terminal.attempt());
      assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
      assertEquals(
          1,
          work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
              .wakeVersion());
      assertEquals(0, fixture.gateway.admitCalls);
      assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
      assertFalse(processor.hasActiveExecution());
    }
  }

  /**
   * 真实 prepare + 拒绝终态路径：每次写事务只先锁 own Thread，再锁 Model/Work，不取 tree、Session 或祖先锁。
   * 拒绝回写与准入共用同一锁边界，覆盖失败终态路径。
   */
  @Test
  void prepareAndRejectedDispatchLockOwnThreadOnly() {
    Fixture fixture = fixture();
    fixture.gateway.queue(
        new ModelGateway.Rejected(
            new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "model rejected")));
    RecordingStore recording = new RecordingStore(fixture.store);
    ModelProcessor processor =
        new ModelProcessor(
            recording,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(() -> LEASE_CONFIG, () -> NO_RETRY, () -> FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor(),
            TestExecutionLifecycle.create(fixture.store, fixture.clock));

    assertEquals(
        ProcessResult.TERMINATED, processor.process(claim(fixture.store, fixture.invocationId)));

    List<List<String>> locked = recording.threadLockTransactions();
    assertEquals(2, locked.size(), "prepare and rejected dispatch each lock the own thread");
    for (List<String> ops : locked) {
      assertFalse(
          ops.stream()
              .anyMatch(
                  op ->
                      op.startsWith("threads.lockTree")
                          || op.startsWith("threads.findAncestorChain")
                          || op.startsWith("sessions.lock")),
          "processor must not take tree/session/ancestor locks: " + ops);
      assertEquals("threads.lock:" + fixture.baseline.threadId(), ops.get(0));
      assertTrue(
          ops.stream()
              .filter(op -> op.startsWith("threads.lock:"))
              .allMatch(op -> op.equals("threads.lock:" + fixture.baseline.threadId())));
      int model = indexOfPrefix(ops, "lockModelInvocation:");
      int work = indexOfPrefix(ops, "work.lockClaimed");
      assertTrue(model > 0, "own Thread must precede Model: " + ops);
      assertTrue(work > model, "Model must precede Work: " + ops);
    }
    assertTrue(
        locked.get(1).stream().anyMatch(op -> op.startsWith("work.request")),
        "rejected dispatch must request THREAD wake before terminal write");
  }

  private static int indexOfPrefix(List<String> ops, String prefix) {
    for (int i = 0; i < ops.size(); i++) {
      if (ops.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }
}

package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ToolProcessorTestSupport.FakeRun;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ToolProcessorTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.WorkStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * {@link fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor#drain()} 移交方法单元测试：
 * 验证对等待远端结果的工具执行停止本地执行体、TOOL 退出事实确认为 STUDIO_EXITED、Work 租约被释放可立即可被重新认领， 且 durable 状态保持为 RUNNING。
 */
class ToolProcessorDrainTest {

  private static final EnvironmentId TEST_ENV = EnvironmentId.of(UUID.randomUUID());

  private static Fixture approvedFixture() {
    Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY);
    ToolProcessorTestSupport.toApproved(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    return fixture;
  }

  private static ClaimedWork claim(Fixture fixture) {
    return ToolProcessorTestSupport.claim(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
  }

  private static ToolInvocation tool(Fixture fixture) {
    return ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
  }

  /**
   * 测试意图：等待远端结果的执行体在 drain 时被移交： 1. 本地执行体停止并退出； 2. durable 状态仍为 RUNNING（不反写终态）； 3. 本地 TOOL 退出事实确认为
   * STUDIO_EXITED； 4. Work 租约被 release，使其它 worker 可立即 claimNext。
   */
  @Test
  void drainHandsOverAwaitingRemoteExecutionAndReleasesClaim() throws Exception {
    Fixture fixture = approvedFixture();
    UUID remoteExecutionId = UUID.randomUUID();
    CountDownLatch remoteStartedLatch = new CountDownLatch(1);
    CountDownLatch waitInterruptedLatch = new CountDownLatch(1);

    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              ToolGateway.RemoteExecutionObserver observer =
                  fixture.gateway.executions.getFirst().remoteExecutions();
              boolean started = observer.tryStart(remoteExecutionId, TEST_ENV);
              if (!started) {
                return ToolProcessorTestSupport.succeeded("call-1");
              }
              remoteStartedLatch.countDown();
              try {
                // 模拟阻塞等待远端结果
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                waitInterruptedLatch.countDown();
              }
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    assertTrue(remoteStartedLatch.await(5, TimeUnit.SECONDS), "remote invocation must start");
    assertTrue(fixture.processor.hasActiveExecution(), "processor must have active execution");

    ExecutionExitFact localFactBeforeDrain =
        fixture.store.transaction(
            tx ->
                tx.exits().loadUnconfirmed(fixture.baseline.threadId()).stream()
                    .filter(fact -> fact.kind() == ExecutionExitKind.TOOL)
                    .findFirst()
                    .orElseThrow());

    // 触发排空移交
    fixture.processor.drain();

    // 1. 本地执行体已停止
    assertTrue(
        waitInterruptedLatch.await(5, TimeUnit.SECONDS),
        "execution body must be interrupted and exit");
    assertTrue(run.awaitExited(), "run must exit");
    assertFalse(fixture.processor.hasActiveExecution(), "processor active execution must be clear");

    // 2. durable 状态仍为 RUNNING，attempt 不变
    ToolInvocation tool = tool(fixture);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status(), "durable status must stay RUNNING");
    assertEquals(1, tool.attempt(), "attempt must remain unchanged at 1");

    // 3. TOOL 事实已确认为 STUDIO_EXITED
    ExecutionExitFact toolFact =
        fixture.store.transaction(
            tx -> tx.exits().find(localFactBeforeDrain.executionId()).orElseThrow());
    assertEquals(
        ExecutionExitState.STUDIO_EXITED,
        toolFact.state(),
        "tool fact must be confirmed as STUDIO_EXITED");

    // 4. Work 租约被 release，可立即被重新认领
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId);
    Work work = fixture.store.transaction(tx -> tx.work().find(toolTarget).orElseThrow());
    assertNull(work.leaseToken(), "work leaseToken must be cleared");

    UUID newClaimToken = UUID.randomUUID();
    Optional<ClaimedWork> reClaim =
        fixture.store.transaction(
            tx -> tx.work().claimNext(WorkTargetType.TOOL, newClaimToken, Duration.ofSeconds(30)));
    assertTrue(reClaim.isPresent(), "released work must be immediately claimable");
    assertEquals(toolTarget, reClaim.get().target());
    assertEquals(newClaimToken, reClaim.get().leaseToken());
  }

  /** 测试意图：普通本地工具（无远端调用）在 drain 时沿用现有 close 语义（本地 abandon，不 release claim，不提前确认退出）。 */
  @Test
  void drainAbandonsLocalExecutionWithoutReleasingClaim() throws Exception {
    Fixture fixture = approvedFixture();
    CountDownLatch bodyStartedLatch = new CountDownLatch(1);
    CountDownLatch waitInterruptedLatch = new CountDownLatch(1);

    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              bodyStartedLatch.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                waitInterruptedLatch.countDown();
              }
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    assertTrue(bodyStartedLatch.await(5, TimeUnit.SECONDS));

    fixture.processor.drain();

    assertTrue(waitInterruptedLatch.await(5, TimeUnit.SECONDS));
    assertTrue(run.awaitExited());
    assertFalse(fixture.processor.hasActiveExecution());

    // Work 租约没有被 release（仍保留未到期的旧 leaseToken）
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId);
    Work work = fixture.store.transaction(tx -> tx.work().find(toolTarget).orElseThrow());
    assertNotNull(work.leaseToken(), "local tool work lease must not be released on drain");

    // 另一个 worker 不能立即认领它
    Optional<ClaimedWork> reClaim =
        fixture.store.transaction(
            tx ->
                tx.work()
                    .claimNext(WorkTargetType.TOOL, UUID.randomUUID(), Duration.ofSeconds(30)));
    assertTrue(reClaim.isEmpty(), "unreleased work must not be immediately claimable");
  }

  /** 测试意图：等待远端结果的执行体在 join 超时时记录警告并仍然 release claim，此时 TOOL 退出事实保持 UNCONFIRMED。 */
  @Test
  void drainHandsOverEvenIfThreadJoinTimesOutAndLeavesFactUnconfirmed() throws Exception {
    Fixture fixture = approvedFixture();
    UUID remoteExecutionId = UUID.randomUUID();
    CountDownLatch remoteStartedLatch = new CountDownLatch(1);
    AtomicBoolean stopLoop = new AtomicBoolean();

    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              ToolGateway.RemoteExecutionObserver observer =
                  fixture.gateway.executions.getFirst().remoteExecutions();
              observer.tryStart(remoteExecutionId, TEST_ENV);
              remoteStartedLatch.countDown();
              // 忽略中断，模拟无法在 deadline 内退出的线程
              while (!stopLoop.get()) {
                try {
                  Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
              }
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    ProcessorLeaseConfig shortLease =
        new ProcessorLeaseConfig(Duration.ofMillis(50), Duration.ofMillis(20));
    ToolProcessor shortLeaseProcessor =
        new ToolProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                shortLease,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY,
                ToolProcessorTestSupport.REATTACH_WINDOW),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            TestExecutionExitConfirmer.create(fixture.store, fixture.clock),
            ToolProcessorTestSupport.STABLE_MATERIALIZER);

    assertEquals(ProcessResult.STARTED, shortLeaseProcessor.process(claim(fixture)));
    assertTrue(remoteStartedLatch.await(5, TimeUnit.SECONDS));

    ExecutionExitFact localFactBeforeDrain =
        fixture.store.transaction(
            tx ->
                tx.exits().loadUnconfirmed(fixture.baseline.threadId()).stream()
                    .filter(fact -> fact.kind() == ExecutionExitKind.TOOL)
                    .findFirst()
                    .orElseThrow());

    // 触发排空移交，50ms 租约 deadline 会超时
    shortLeaseProcessor.drain();

    // 超时下 Work 租约依然被 release
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId);
    Work work = fixture.store.transaction(tx -> tx.work().find(toolTarget).orElseThrow());
    assertNull(work.leaseToken(), "work leaseToken must still be cleared even if thread timed out");

    // TOOL 退出事实保持 UNCONFIRMED（因为执行线程尚未退出）
    ExecutionExitFact factAfterDrain =
        fixture.store.transaction(
            tx -> tx.exits().find(localFactBeforeDrain.executionId()).orElseThrow());
    assertEquals(
        ExecutionExitState.UNCONFIRMED,
        factAfterDrain.state(),
        "tool fact must remain UNCONFIRMED when body thread did not exit");

    // 释放循环以便线程退出
    stopLoop.set(true);
    assertTrue(run.awaitExited());
  }

  /** 测试意图：一个执行在移交时 release 失败，不中断其余执行的移交，且最终 close 仍然被调用。 */
  @Test
  void drainWithOneHandoverFailureContinuesOthersAndCloses() throws Exception {
    Fixture fixture = approvedFixture();

    ToolProcessorTestSupport.Baseline baseline2 =
        ToolProcessorTestSupport.seedToolBaseline(
            fixture.store,
            ToolProcessorTestSupport.NOW,
            false,
            ToolProcessorTestSupport.bashScenario(ToolSideEffect.READ_ONLY));
    ToolInvocationRequest request2 =
        new ToolInvocationRequest(
            new ToolCall("call-1", "bash", "{}"),
            ToolProcessorTestSupport.bashScenario(ToolSideEffect.READ_ONLY).binding());
    ToolProcessorTestSupport.Seeded seeded2 =
        ToolProcessorTestSupport.seedTool(
            fixture.store,
            baseline2,
            request2,
            ToolProcessorTestSupport.NOW,
            ToolProcessorTestSupport.bashScenario(ToolSideEffect.READ_ONLY));
    ToolProcessorTestSupport.toApproved(
        fixture.store, seeded2.toolInvocationId(), ToolProcessorTestSupport.NOW);

    UUID failingInvocationId = fixture.toolInvocationId;
    UUID successInvocationId = seeded2.toolInvocationId();

    AtomicBoolean shouldFailRelease = new AtomicBoolean(false);
    HarnessStore delegatingStore =
        new HarnessStore() {
          @Override
          public <T> T transaction(Function<Transaction, T> callback) {
            return fixture.store.transaction(
                tx -> {
                  Transaction wrappedTx =
                      (Transaction)
                          Proxy.newProxyInstance(
                              Transaction.class.getClassLoader(),
                              new Class<?>[] {Transaction.class},
                              (proxy, method, args) -> {
                                if ("work".equals(method.getName())) {
                                  WorkStore originalWork = tx.work();
                                  return (WorkStore)
                                      Proxy.newProxyInstance(
                                          WorkStore.class.getClassLoader(),
                                          new Class<?>[] {WorkStore.class},
                                          (workProxy, workMethod, workArgs) -> {
                                            if ("release".equals(workMethod.getName())) {
                                              ClaimedWork claimArg = (ClaimedWork) workArgs[0];
                                              if (shouldFailRelease.get()
                                                  && claimArg
                                                      .target()
                                                      .id()
                                                      .equals(failingInvocationId)) {
                                                throw new RuntimeException(
                                                    "simulated release failure");
                                              }
                                            }
                                            return workMethod.invoke(originalWork, workArgs);
                                          });
                                }
                                return method.invoke(tx, args);
                              });
                  return callback.apply(wrappedTx);
                });
          }

          @Override
          public void afterCommit(Runnable action) {
            fixture.store.afterCommit(action);
          }

          @Override
          public void assertNoAmbientTransaction() {
            fixture.store.assertNoAmbientTransaction();
          }
        };

    ToolProcessor processor =
        new ToolProcessor(
            delegatingStore,
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY,
                ToolProcessorTestSupport.REATTACH_WINDOW),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            TestExecutionExitConfirmer.create(fixture.store, fixture.clock),
            ToolProcessorTestSupport.STABLE_MATERIALIZER);

    CountDownLatch r1Started = new CountDownLatch(1);
    CountDownLatch r2Started = new CountDownLatch(1);
    CountDownLatch r1Interrupted = new CountDownLatch(1);
    CountDownLatch r2Interrupted = new CountDownLatch(1);

    UUID r1RemoteId = UUID.randomUUID();
    UUID r2RemoteId = UUID.randomUUID();

    FakeRun run1 =
        fixture.gateway.queueRun(
            progress -> {
              fixture.gateway.executions.get(0).remoteExecutions().tryStart(r1RemoteId, TEST_ENV);
              r1Started.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                r1Interrupted.countDown();
              }
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    FakeRun run2 =
        fixture.gateway.queueRun(
            progress -> {
              fixture.gateway.executions.get(1).remoteExecutions().tryStart(r2RemoteId, TEST_ENV);
              r2Started.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                r2Interrupted.countDown();
              }
              return ToolProcessorTestSupport.succeeded("call-2");
            });

    ClaimedWork claim1 =
        ToolProcessorTestSupport.claim(
            fixture.store, failingInvocationId, ToolProcessorTestSupport.NOW);
    ClaimedWork claim2 =
        ToolProcessorTestSupport.claim(
            fixture.store, successInvocationId, ToolProcessorTestSupport.NOW);

    assertEquals(ProcessResult.STARTED, processor.process(claim1));
    assertEquals(ProcessResult.STARTED, processor.process(claim2));
    assertTrue(r1Started.await(5, TimeUnit.SECONDS));
    assertTrue(r2Started.await(5, TimeUnit.SECONDS));

    shouldFailRelease.set(true);

    // 触发排空
    processor.drain();

    assertTrue(r1Interrupted.await(5, TimeUnit.SECONDS));
    assertTrue(r2Interrupted.await(5, TimeUnit.SECONDS));
    assertTrue(run1.awaitExited());
    assertTrue(run2.awaitExited());

    // 两个执行都已退出，活跃执行已被 close 清空
    assertFalse(processor.hasActiveExecution(), "close must be called clearing all executions");

    // 失败移交的 claim 保持租约到期（leaseToken 未被清空）
    WorkTarget target1 = new WorkTarget(WorkTargetType.TOOL, failingInvocationId);
    Work work1 = fixture.store.transaction(tx -> tx.work().find(target1).orElseThrow());
    assertNotNull(work1.leaseToken(), "failed release claim remains until lease expiry");

    // 成功移交的 claim 租约已被释放
    WorkTarget target2 = new WorkTarget(WorkTargetType.TOOL, successInvocationId);
    Work work2 = fixture.store.transaction(tx -> tx.work().find(target2).orElseThrow());
    assertNull(work2.leaseToken(), "successful handover work claim must be released");
  }

  /**
   * 测试意图：挂接执行体在等待远端结果时响应 drain 排空移交： 1. 处于 reattach 阻塞中的执行体被中断并退出； 2. durable ToolInvocation 保持为
   * RUNNING，attempt 保持为 1； 3. 挂接登记的本地 TOOL 退出事实确认为 STUDIO_EXITED； 4. 原 owner 的 TOOL 退出事实与 REMOTE
   * 退出事实仍为 UNCONFIRMED； 5. Work claim 租约被释放，允许下一个 worker 再次认领并重新挂接。
   */
  @Test
  void drainHandsOverReattachedExecutionReleasesClaimAndLeavesRunning() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ToolProcessorTestSupport.Fixture fixture =
          new ToolProcessorTestSupport.Fixture(
              ToolProcessorTestSupport.NO_RETRY,
              false,
              0,
              scheduler,
              ToolProcessorTestSupport.remoteScenario(ToolSideEffect.READ_ONLY),
              false);

      ToolProcessorTestSupport.toRunning(
          fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
      UUID oldLease = UUID.randomUUID();
      ExecutionExitFact oldToolFact =
          ToolProcessorTestSupport.registerOldToolExit(
              fixture.store, fixture.baseline.threadId(), fixture.toolInvocationId, 1, oldLease);
      ExecutionExitFact oldRemoteFact =
          ToolProcessorTestSupport.registerRemoteExit(
              fixture.store,
              fixture.baseline.threadId(),
              fixture.toolInvocationId,
              1,
              oldLease,
              oldToolFact.executionId(),
              ToolProcessorTestSupport.ENV_ID);

      ToolProcessorTestSupport.claim(
          fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
      fixture.clock.advance(Duration.ofSeconds(61));
      ClaimedWork recovered =
          ToolProcessorTestSupport.claim(
              fixture.store, fixture.toolInvocationId, fixture.clock.instant());

      CountDownLatch waitInterruptedLatch = new CountDownLatch(1);
      CountDownLatch reattachStartedLatch = new CountDownLatch(1);

      FakeRun run =
          fixture.gateway.queueReattachRun(
              progress -> {
                reattachStartedLatch.countDown();
                try {
                  Thread.sleep(60_000);
                } catch (InterruptedException e) {
                  waitInterruptedLatch.countDown();
                }
                return ToolProcessorTestSupport.succeeded("call-1");
              });

      assertEquals(ProcessResult.STARTED, fixture.processor.process(recovered));
      assertTrue(reattachStartedLatch.await(5, TimeUnit.SECONDS), "reattach run must start");

      ExecutionExitFact reattachedToolFactBeforeDrain =
          fixture.store.transaction(
              tx ->
                  tx.exits().loadUnconfirmed(fixture.baseline.threadId()).stream()
                      .filter(fact -> fact.kind() == ExecutionExitKind.TOOL)
                      .filter(fact -> !fact.executionId().equals(oldToolFact.executionId()))
                      .findFirst()
                      .orElseThrow());

      // 触发排空
      fixture.processor.drain();

      assertTrue(
          waitInterruptedLatch.await(5, TimeUnit.SECONDS),
          "reattach worker must be interrupted on drain");
      assertTrue(run.awaitExited(), "reattach run must exit");
      assertFalse(
          fixture.processor.hasActiveExecution(), "active execution must be cleared after drain");

      // durable 状态保持为 RUNNING，attempt 保持为 1
      ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
      assertEquals(ToolInvocationStatus.RUNNING, tool.status(), "tool status must stay RUNNING");
      assertEquals(1, tool.attempt(), "tool attempt must remain 1");

      // 挂接生成的本地 TOOL 退出事实确认为 STUDIO_EXITED
      ExecutionExitFact reattachedToolFact =
          fixture.store.transaction(
              tx -> tx.exits().find(reattachedToolFactBeforeDrain.executionId()).orElseThrow());
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          reattachedToolFact.state(),
          "reattached tool fact must be STUDIO_EXITED");

      // 原 owner 的 TOOL 退出事实与 REMOTE 退出事实仍为 UNCONFIRMED
      ExecutionExitFact oldToolFactAfterDrain =
          fixture.store.transaction(tx -> tx.exits().find(oldToolFact.executionId()).orElseThrow());
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          oldToolFactAfterDrain.state(),
          "old tool fact must remain UNCONFIRMED");
      ExecutionExitFact oldRemoteFactAfterDrain =
          fixture.store.transaction(
              tx -> tx.exits().find(oldRemoteFact.executionId()).orElseThrow());
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          oldRemoteFactAfterDrain.state(),
          "old remote fact must remain UNCONFIRMED");

      // Work claim 租约被释放，可被重新认领
      WorkTarget target = new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId);
      Work work = fixture.store.transaction(tx -> tx.work().find(target).orElseThrow());
      assertNull(work.leaseToken(), "handover work claim must be released");
    } finally {
      scheduler.shutdownNow();
    }
  }
}

package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ToolProcessorTestSupport.FakeRun;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ToolProcessorTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessResult;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    Optional<ClaimedWork> reClaim =
        fixture.store.transaction(
            tx ->
                tx.work()
                    .claimNext(WorkTargetType.TOOL, "new-claim-token", Duration.ofSeconds(30)));
    assertTrue(reClaim.isPresent(), "released work must be immediately claimable");
    assertEquals(toolTarget, reClaim.get().target());
    assertEquals("new-claim-token", reClaim.get().leaseToken());
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
                    .claimNext(WorkTargetType.TOOL, "other-claim-token", Duration.ofSeconds(30)));
    assertTrue(reClaim.isEmpty(), "unreleased work must not be immediately claimable");
  }

  /** 测试意图：远端调用未真正发出（例如 confirmNotStarted）时不满足等待远端结果判定，走普通 close 语义。 */
  @Test
  void drainAbandonsRemoteNotStartedWithoutRelease() throws Exception {
    Fixture fixture = approvedFixture();
    UUID remoteExecutionId = UUID.randomUUID();
    CountDownLatch enteredLatch = new CountDownLatch(1);
    CountDownLatch waitInterruptedLatch = new CountDownLatch(1);

    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              ToolGateway.RemoteExecutionObserver observer =
                  fixture.gateway.executions.getFirst().remoteExecutions();
              observer.tryStart(remoteExecutionId, TEST_ENV);
              // 模拟未真正发出
              observer.confirmNotStarted(remoteExecutionId);
              enteredLatch.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                waitInterruptedLatch.countDown();
              }
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    assertTrue(enteredLatch.await(5, TimeUnit.SECONDS));

    fixture.processor.drain();

    assertTrue(waitInterruptedLatch.await(5, TimeUnit.SECONDS));
    assertTrue(run.awaitExited());

    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId);
    Work work = fixture.store.transaction(tx -> tx.work().find(toolTarget).orElseThrow());
    assertNotNull(work.leaseToken(), "not-started remote must not release work lease on drain");
  }
}

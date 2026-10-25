package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.FakeRun;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ToolProcessor 的 admission 与阻塞执行：本地 admission 的三种确定性结果、副作用之前持久化 RUNNING、终态提交与重试、取消即 interrupt、
 * 资源只在终态决议与执行体返回之后释放。
 */
class ToolProcessorExecutionTest {

  private static final Duration WAIT = Duration.ofSeconds(5);

  private static Fixture approvedFixture() {
    return approvedFixture(ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY);
  }

  private static Fixture approvedFixture(InvocationRetryPolicy policy, ToolSideEffect sideEffect) {
    Fixture fixture = ToolProcessorTestSupport.fixture(policy, sideEffect);
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

  private static void awaitStatus(Fixture fixture, ToolInvocationStatus status)
      throws InterruptedException {
    ToolProcessorTestSupport.awaitTrue(() -> tool(fixture).status() == status, WAIT);
  }

  private static void awaitIdle(Fixture fixture) throws InterruptedException {
    ToolProcessorTestSupport.awaitTrue(() -> !fixture.processor.hasActiveExecution(), WAIT);
  }

  private static ToolGateway.Completion failed(boolean retryable) {
    return new ToolGateway.Failed(new ToolInvocationError("TRANSIENT", "unavailable"), retryable);
  }

  /** admission 通过：RUNNING 在副作用之前持久化，终态提交后才关闭 run，且关闭发生在执行体返回之后。 */
  @Test
  void admittedPersistsRunningBeforeExecuteThenSucceedsAndClosesRunAfterExit() throws Exception {
    Fixture fixture = approvedFixture();
    AtomicReference<ToolInvocationStatus> statusInBody = new AtomicReference<>();
    AtomicReference<Integer> closesInBody = new AtomicReference<>();
    FakeRun[] holder = new FakeRun[1];
    holder[0] =
        fixture.gateway.queueRun(
            progress -> {
              statusInBody.set(tool(fixture).status());
              closesInBody.set(holder[0].closes.get());
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    awaitStatus(fixture, ToolInvocationStatus.SUCCEEDED);
    awaitIdle(fixture);

    assertEquals(ToolInvocationStatus.RUNNING, statusInBody.get());
    assertEquals(0, closesInBody.get(), "resources stay pinned while the body runs");
    assertEquals(1, holder[0].closes.get());
    assertTrue(holder[0].exitedBeforeClose, "run closes only after the body has exited");
    ToolInvocation tool = tool(fixture);
    assertEquals(1, tool.attempt());
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(fixture.toolInvocationId, fixture.gateway.executions.get(0).invocationId());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** partial 经 progress sink 实时发布，且只在执行体运行期间。 */
  @Test
  void progressIsPublishedWhileBodyRuns() throws Exception {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueRun(
        progress -> {
          progress.accept(ToolProcessorTestSupport.partialResult("call-1"));
          progress.accept(ToolProcessorTestSupport.partialResult("call-1"));
          return ToolProcessorTestSupport.succeeded("call-1");
        });

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.SUCCEEDED);
    awaitIdle(fixture);

    assertEquals(2, fixture.sink.events.size());
    assertFalse(fixture.sink.events.get(0).equals(fixture.sink.events.get(1)));
  }

  /** Busy：肯定未执行，保持 READY，attempt 不变，按 retryAfter reschedule。 */
  @Test
  void busyReschedulesWithoutAdvancingAttempt() {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueAdmit(new ToolGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(claim(fixture)));

    ToolInvocation tool = tool(fixture);
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(0, tool.attempt());
    Work work = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolProcessorTestSupport.NOW.plusSeconds(5), work.availableAt());
    assertNull(work.leaseToken());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Rejected：确定性失败，attempt 不变，唤醒 THREAD。 */
  @Test
  void rejectedFailsInvocationAndWakesThread() {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueAdmit(
        new ToolGateway.Rejected(new ToolInvocationError("TOOL_NOT_FOUND", "gone")));

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(claim(fixture)));

    ToolInvocation tool = tool(fixture);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("TOOL_NOT_FOUND", tool.error().kind());
    assertEquals(0, tool.attempt());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** admit 抛异常：无副作用，保持 READY 并按 admitFailureDelay reschedule。 */
  @Test
  void admitExceptionReschedulesWithFallbackDelay() {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueAdmit(new IllegalStateException("boom"));

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(claim(fixture)));

    assertEquals(ToolInvocationStatus.READY, tool(fixture).status());
    assertEquals(
        ToolProcessorTestSupport.NOW.plus(ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
  }

  /** admit 返回 null 视为契约违反：同样无副作用地 reschedule。 */
  @Test
  void nullAdmissionReschedulesWithFallbackDelay() {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueNullAdmit();

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(claim(fixture)));
    assertEquals(ToolInvocationStatus.READY, tool(fixture).status());
  }

  /** admission 期间 Stop 已删除 Work：Busy / Rejected 都是 LOST 且没有 mutation。 */
  @Test
  void admissionResultWithLostWorkIsLostWithoutMutation() {
    Fixture busy = approvedFixture();
    busy.gateway.admitHook = () -> ToolProcessorTestSupport.deleteToolWork(busy);
    busy.gateway.queueAdmit(new ToolGateway.Busy(Duration.ofSeconds(5)));
    assertEquals(ProcessResult.LOST_OWNERSHIP, busy.processor.process(claim(busy)));
    assertEquals(ToolInvocationStatus.READY, tool(busy).status());

    Fixture rejected = approvedFixture();
    rejected.gateway.admitHook = () -> ToolProcessorTestSupport.deleteToolWork(rejected);
    rejected.gateway.queueAdmit(
        new ToolGateway.Rejected(new ToolInvocationError("TOOL_NOT_FOUND", "gone")));
    assertEquals(ProcessResult.LOST_OWNERSHIP, rejected.processor.process(claim(rejected)));
    assertEquals(ToolInvocationStatus.READY, tool(rejected).status());
    assertFalse(rejected.processor.hasActiveExecution());
  }

  /** RUNNING 持久化时 ownership 已丢失：LOST，run 被关闭且工具从未执行。 */
  @Test
  void lostWorkDuringMarkRunningClosesRunWithoutExecuting() {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.succeeded("call-1"));
    fixture.gateway.admitHook = () -> ToolProcessorTestSupport.deleteToolWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim(fixture)));

    assertEquals(0, run.executes.get());
    assertEquals(1, run.closes.get());
    assertEquals(ToolInvocationStatus.READY, tool(fixture).status());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** RUNNING 持久化抛异常：未登记也必须 close 恰一次，Tool 保持 READY，不留下执行事实。 */
  @Test
  void markRunningPersistenceFailureClosesRunOnceAndStaysReady() {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.succeeded("call-1"));
    try (ToolProcessor processor =
        new ToolProcessor(
            failRunningToolWrites(fixture.store),
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run)) {
      assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(claim(fixture)));

      assertEquals(0, run.executes.get());
      assertEquals(1, run.closes.get());
      assertEquals(ToolInvocationStatus.READY, tool(fixture).status());
      assertFalse(processor.hasActiveExecution());
      assertTrue(
          Boolean.TRUE.equals(
              fixture.store.transaction(
                  tx -> tx.loadUnconfirmedExecutions(fixture.baseline.threadId()).isEmpty())));
    }
  }

  /** RUNNING 回写失权且 close 失败：仍只尝试 close 一次，Tool 保持 READY，不登记退出事实。 */
  @Test
  void lostWorkDuringMarkRunningWithCloseFailureReleasesExactlyOnce() {
    Fixture fixture = approvedFixture();
    AtomicInteger closes = new AtomicInteger();
    fixture.gateway.queueAdmit(
        new ToolGateway.Admitted(
            new ToolGateway.Run() {
              @Override
              public ToolGateway.Completion execute(Consumer<ToolResult> progress) {
                throw new AssertionError("lost admission must not execute");
              }

              @Override
              public void close() {
                closes.incrementAndGet();
                throw new IllegalStateException("close failed");
              }
            }));
    fixture.gateway.admitHook = () -> ToolProcessorTestSupport.deleteToolWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim(fixture)));

    assertEquals(1, closes.get());
    assertEquals(ToolInvocationStatus.READY, tool(fixture).status());
    assertFalse(fixture.processor.hasActiveExecution());
    assertTrue(
        Boolean.TRUE.equals(
            fixture.store.transaction(
                tx -> tx.loadUnconfirmedExecutions(fixture.baseline.threadId()).isEmpty())));
  }

  /** claim lease 剩余不足：admission 之前 renew 出完整 margin。 */
  @Test
  void nearExpiryClaimGetsFullLeaseMarginBeforeAdmission() {
    Fixture fixture = approvedFixture();
    ClaimedWork claimed =
        ToolProcessorTestSupport.claim(
            fixture.store,
            fixture.toolInvocationId,
            ToolProcessorTestSupport.NOW,
            "near-expiry",
            Duration.ofSeconds(1));
    AtomicReference<Instant> leaseAtAdmission = new AtomicReference<>();
    fixture.gateway.admitHook =
        () ->
            leaseAtAdmission.set(
                ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId)
                    .leaseUntil());
    fixture.gateway.queueAdmit(new ToolGateway.Busy(Duration.ofSeconds(5)));

    fixture.processor.process(claimed);

    assertEquals(ToolProcessorTestSupport.NOW.plusSeconds(30), leaseAtAdmission.get());
  }

  /** retryable 失败 + READ_ONLY：RUNNING -> READY，按策略延迟重排，attempt 保留。 */
  @Test
  void retryableFailureRetriesReadOnlyWithPolicyDelay() throws Exception {
    Fixture fixture =
        approvedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    fixture.gateway.queueRun(progress -> failed(true));

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.READY);
    awaitIdle(fixture);

    assertEquals(1, tool(fixture).attempt());
    Work work = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolProcessorTestSupport.NOW.plusSeconds(5), work.availableAt());
    assertNull(work.leaseToken());
  }

  /** NON_IDEMPOTENT 的 retryable 失败绝不自动重试。 */
  @Test
  void retryableFailureOnNonIdempotentNeverRetries() throws Exception {
    Fixture fixture =
        approvedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.NON_IDEMPOTENT);
    fixture.gateway.queueRun(progress -> failed(true));

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.FAILED);
    awaitIdle(fixture);

    assertEquals("TRANSIENT", tool(fixture).error().kind());
  }

  /** 不可重试失败：FAILED 并唤醒 THREAD。 */
  @Test
  void nonRetryableFailureFailsAndWakesThread() throws Exception {
    Fixture fixture =
        approvedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    fixture.gateway.queueRun(progress -> failed(false));

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.FAILED);
    awaitIdle(fixture);

    assertNotNull(ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId()));
  }

  /** 重试预算耗尽后第二次失败进入 FAILED。 */
  @Test
  void retryExhaustionFailsAfterSecondFailure() throws Exception {
    Fixture fixture =
        approvedFixture(ToolProcessorTestSupport.RETRY_ONCE, ToolSideEffect.READ_ONLY);
    fixture.gateway.queueRun(progress -> failed(true));
    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.READY);
    awaitIdle(fixture);

    fixture.clock.advance(Duration.ofSeconds(10));
    fixture.gateway.queueRun(progress -> failed(true));
    fixture.processor.process(
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, fixture.clock.instant(), "token-2"));
    awaitStatus(fixture, ToolInvocationStatus.FAILED);
    awaitIdle(fixture);

    assertEquals(2, tool(fixture).attempt());
  }

  /** Unknown 完成：UNKNOWN 终态，不重试。 */
  @Test
  void unknownCompletionTerminatesUnknownWithoutRetry() throws Exception {
    Fixture fixture =
        approvedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    fixture.gateway.queueRun(
        progress -> new ToolGateway.Unknown(new ToolInvocationError("REMOTE_UNCERTAIN", "?")));

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.UNKNOWN);
    awaitIdle(fixture);

    assertEquals(1, tool(fixture).attempt());
  }

  /** Cancelled 完成：CANCELLED 终态。 */
  @Test
  void cancelledCompletionTerminatesCancelled() throws Exception {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueRun(
        progress -> new ToolGateway.Cancelled(new ToolInvocationError("CANCELLED", "c")));

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.CANCELLED);
    awaitIdle(fixture);
  }

  /** run 逃逸运行时异常：外部效果不可知，收敛为 UNKNOWN（绝不留在 RUNNING 等 lease）。 */
  @Test
  void escapedRunExceptionConvergesUnknown() throws Exception {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              throw new IllegalStateException("broken gateway");
            });

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.UNKNOWN);
    awaitIdle(fixture);

    assertEquals("GATEWAY_UNKNOWN", tool(fixture).error().kind());
    assertEquals(1, run.closes.get());
  }

  /** 终态结果校验：toolCallId 不匹配、内联 binary 与超 1 MiB 都是确定性 INVALID_RESULT，绝不落库。 */
  @Test
  void invalidTerminalResultsFailWithoutPersistingResult() throws Exception {
    List<ToolResult> invalid =
        List.of(
            ToolProcessorTestSupport.successResult("other", new TextResultContent("x")),
            ToolProcessorTestSupport.successResult(
                "call-1", new BinaryResultContent("image/png", new byte[] {1})),
            ToolProcessorTestSupport.successResult(
                "call-1", new TextResultContent("a".repeat(1_100_000))),
            // 合法 detailsJson 与内联文本聚合后超过 1 MiB canonical JSON。
            new ToolResult(
                "call-1",
                List.of(new TextResultContent("b".repeat(200_000))),
                false,
                "{\"x\":\"" + "a".repeat(900_000) + "\"}"),
            // 24 MiB 内联文本：bounded 编码器超限即中止，不物化完整 JSON。
            ToolProcessorTestSupport.successResult(
                "call-1", new TextResultContent("a".repeat(24 * 1024 * 1024))));
    for (ToolResult result : invalid) {
      Fixture fixture = approvedFixture();
      fixture.gateway.queueRun(
          progress -> new ToolGateway.Succeeded(ToolSuccess.withoutEffects(result)));

      fixture.processor.process(claim(fixture));
      awaitStatus(fixture, ToolInvocationStatus.FAILED);
      awaitIdle(fixture);

      assertEquals("INVALID_RESULT", tool(fixture).error().kind());
      assertNull(tool(fixture).result());
    }
  }

  /** cancel：interrupt 执行线程，不反写任何 durable 状态，run 在执行体返回之后才关闭。 */
  @Test
  void cancelInterruptsBodyWithoutDurableWrite() throws Exception {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    assertTrue(run.awaitExecuting());
    assertTrue(fixture.processor.cancel(fixture.toolInvocationId));
    assertTrue(run.awaitExited());
    awaitIdle(fixture);
    ToolProcessorTestSupport.awaitTrue(() -> run.closes.get() == 1, WAIT);

    assertTrue(run.exitedBeforeClose);
    assertEquals(ToolInvocationStatus.RUNNING, tool(fixture).status());
    assertNotNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** close：同样 interrupt 全部本地执行，durable 保持 RUNNING 待 lease 恢复为 UNKNOWN。 */
  @Test
  void closeInterruptsAllLocalExecutions() throws Exception {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.blockUntilInterrupted());

    fixture.processor.process(claim(fixture));
    assertTrue(run.awaitExecuting());
    fixture.processor.close();
    assertTrue(run.awaitExited());
    awaitIdle(fixture);

    assertEquals(ToolInvocationStatus.RUNNING, tool(fixture).status());
  }

  /** Stop 已删除 Work 后到达的终态：commit 被围栏拒绝，不写任何 durable 状态，run 仍被关闭。 */
  @Test
  void terminalAfterWorkDeletionIsLostWithoutDurableWrite() throws Exception {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              ToolProcessorTestSupport.deleteToolWork(fixture);
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    fixture.processor.process(claim(fixture));
    awaitIdle(fixture);
    ToolProcessorTestSupport.awaitTrue(() -> run.closes.get() == 1, WAIT);

    assertEquals(ToolInvocationStatus.RUNNING, tool(fixture).status());
    assertNull(tool(fixture).result());
  }

  /** realtime sink 失败只影响 best-effort 投影：partial 丢失，durable SUCCEEDED 与 THREAD 唤醒照常落地。 */
  @Test
  void sinkFailureNeverAffectsDurableTerminal() throws Exception {
    Fixture fixture = approvedFixture();
    fixture.sink.failure = new IllegalStateException("notification channel unavailable");
    fixture.gateway.queueRun(
        progress -> {
          progress.accept(ToolProcessorTestSupport.partialResult("call-1"));
          return ToolProcessorTestSupport.succeeded("call-1");
        });

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.SUCCEEDED);
    awaitIdle(fixture);

    assertTrue(fixture.sink.events.isEmpty());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** THREAD Work 在执行期间已被消费：成功终态仍重建 THREAD Work，Thread 不会卡在等待工具结果。 */
  @Test
  void successRecreatesConsumedThreadWork() throws Exception {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueRun(
        progress -> {
          fixture.store.transaction(
              tx -> {
                tx.lockThread(fixture.baseline.threadId()).orElseThrow();
                assertTrue(
                    tx.deleteWork(
                        new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId())));
                return null;
              });
          return ToolProcessorTestSupport.succeeded("call-1");
        });

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.SUCCEEDED);
    awaitIdle(fixture);

    assertNotNull(ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId()));
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** 执行期间到达新的 TOOL wake：终态 complete 只清 lease 并保留该行，新 wake 保持可见。 */
  @Test
  void newWakeSurvivesTerminalCompletion() throws Exception {
    Fixture fixture = approvedFixture();
    fixture.gateway.queueRun(
        progress -> {
          ToolProcessorTestSupport.requestToolWork(fixture, ToolProcessorTestSupport.NOW);
          return ToolProcessorTestSupport.succeeded("call-1");
        });

    fixture.processor.process(claim(fixture));
    awaitStatus(fixture, ToolInvocationStatus.SUCCEEDED);
    awaitIdle(fixture);

    Work toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(2, toolWork.wakeVersion());
    assertNull(toolWork.leaseToken());
    assertNull(toolWork.leaseUntil());
  }

  /** Stop 已把 RUNNING 终止为 UNKNOWN 之后到达的成功、重试或不确定终态：提交被拒，绝不覆盖，也不唤醒 Thread。 */
  @Test
  void terminalAfterStopNeverOverwritesTheStoppedInvocation() throws Exception {
    List<ToolGateway.Completion> lateCompletions =
        List.of(
            ToolProcessorTestSupport.succeeded("call-1"),
            failed(true),
            new ToolGateway.Unknown(new ToolInvocationError("UNCERTAIN", "outcome unknown")));
    for (ToolGateway.Completion late : lateCompletions) {
      Fixture fixture =
          approvedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
      FakeRun run =
          fixture.gateway.queueRun(
              progress -> {
                ToolProcessorTestSupport.transition(
                    fixture.store,
                    fixture.toolInvocationId,
                    tool ->
                        tool.unknown(
                            new ToolInvocationError("CANCELLED", "stopped"),
                            ToolProcessorTestSupport.NOW));
                return late;
              });

      fixture.processor.process(claim(fixture));
      awaitIdle(fixture);
      ToolProcessorTestSupport.awaitTrue(() -> run.closes.get() == 1, WAIT);

      ToolInvocation tool = tool(fixture);
      assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());
      assertEquals("CANCELLED", tool.error().kind());
      assertNull(tool.result());
      assertEquals(
          1,
          ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
              .wakeVersion());
    }
  }

  /** admission 已持有 run、RUNNING 回写前 Stop 先收敛为 FAILED：不执行、关闭 run、LOST 且不覆盖终态。 */
  @Test
  void stopDuringAdmissionClosesRunWithoutExecuting() {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.succeeded("call-1"));
    fixture.gateway.admitHook =
        () ->
            ToolProcessorTestSupport.transition(
                fixture.store,
                fixture.toolInvocationId,
                tool ->
                    tool.fail(
                        new ToolInvocationError("CANCELLED", "stopped"),
                        ToolProcessorTestSupport.NOW));

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim(fixture)));

    assertEquals(0, run.executes.get());
    assertEquals(1, run.closes.get());
    assertEquals(ToolInvocationStatus.FAILED, tool(fixture).status());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** retryable 失败提交前 Work 被删除：RUNNING -&gt; READY 的重排被最终 fence 拒绝，保留 RUNNING 供 lease 恢复。 */
  @Test
  void retryableFailureAfterWorkDeletionIsLostWithoutStateChange() throws Exception {
    Fixture fixture =
        approvedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              ToolProcessorTestSupport.deleteToolWork(fixture);
              return failed(true);
            });

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    awaitIdle(fixture);
    ToolProcessorTestSupport.awaitTrue(() -> run.closes.get() == 1, WAIT);

    assertEquals(ToolInvocationStatus.RUNNING, tool(fixture).status());
    assertEquals(1, tool(fixture).attempt());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** 非重试终态提交前 Work 被删除：request THREAD wake 随事务回滚，终态行保持 RUNNING。 */
  @Test
  void terminalAfterWorkDeletionRollsBackThreadWake() throws Exception {
    Fixture fixture =
        approvedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              ToolProcessorTestSupport.deleteToolWork(fixture);
              return new ToolGateway.Unknown(new ToolInvocationError("UNCERTAIN", "?"));
            });

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    awaitIdle(fixture);
    ToolProcessorTestSupport.awaitTrue(() -> run.closes.get() == 1, WAIT);

    ToolInvocation tool = tool(fixture);
    assertEquals(ToolInvocationStatus.RUNNING, tool.status());
    assertNull(tool.result());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** run.close 抛异常不阻断资源/registry 清理：终态已提交、执行释放且 close 只调用一次。 */
  @Test
  void closeFailureStillReleasesExecutionAfterTerminal() throws Exception {
    Fixture fixture = approvedFixture();
    AtomicInteger closes = new AtomicInteger();
    AtomicReference<Thread> runner = new AtomicReference<>();
    ToolGateway.Run run =
        new ToolGateway.Run() {
          @Override
          public ToolGateway.Completion execute(Consumer<ToolResult> progress) {
            runner.set(Thread.currentThread());
            return ToolProcessorTestSupport.succeeded("call-1");
          }

          @Override
          public void close() {
            closes.incrementAndGet();
            throw new IllegalStateException(ExecutionExitFailureStore.SYNTHETIC_SECRET);
          }
        };
    fixture.gateway.queueAdmit(new ToolGateway.Admitted(run));
    ExitLogCapture exitLogs = ExitLogCapture.attach(ToolExecution.class);
    try {
      assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
      awaitStatus(fixture, ToolInvocationStatus.SUCCEEDED);
      awaitIdle(fixture);
      ToolProcessorTestSupport.awaitTrue(() -> closes.get() == 1, WAIT);
      assertTrue(runner.get().join(Duration.ofSeconds(5)), "tool execution thread exits");

      assertEquals(1, closes.get());
      assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
      List<ExecutionExitFact> unconfirmed =
          fixture.store.transaction(
              tx -> tx.loadUnconfirmedExecutions(fixture.baseline.threadId()));
      assertEquals(1, unconfirmed.size());
      assertEquals(ExecutionExitState.UNCONFIRMED, unconfirmed.getFirst().state());
      exitLogs.assertSingleSafeFailure(
          "cannot release tool run resources",
          "TOOL_RUN_CLOSE",
          IllegalStateException.class,
          ExecutionExitFailureStore.SYNTHETIC_SECRET);
    } finally {
      try {
        fixture.processor.close();
        fixture.scheduler.shutdownNow();
        assertTrue(fixture.scheduler.awaitTermination(5, TimeUnit.SECONDS));
      } finally {
        exitLogs.close();
      }
    }
  }

  /** 终态写库失败：run 与 registry 仍释放，durable 保持 RUNNING 交给 lease 恢复，绝不伪造成 SUCCEEDED。 */
  @Test
  void terminalPersistenceFailureStillReleasesRunAndLeavesRunning() throws Exception {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.succeeded("call-1"));
    try (ToolProcessor processor =
        new ToolProcessor(
            failTerminalToolWrites(fixture.store),
            fixture.gateway,
            fixture.sink,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run)) {
      assertEquals(ProcessResult.STARTED, processor.process(claim(fixture)));
      ToolProcessorTestSupport.awaitTrue(() -> !processor.hasActiveExecution(), WAIT);
      ToolProcessorTestSupport.awaitTrue(() -> run.closes.get() == 1, WAIT);

      assertEquals(ToolInvocationStatus.RUNNING, tool(fixture).status());
      assertNull(tool(fixture).result());
    }
  }

  /** cancel 之后到达的 partial 直接丢弃：执行体已 abandon，实时投影不再发布。 */
  @Test
  void partialAfterCancelIsDropped() throws Exception {
    Fixture fixture = approvedFixture();
    FakeRun run =
        fixture.gateway.queueRun(
            progress -> {
              assertTrue(fixture.processor.cancel(fixture.toolInvocationId));
              progress.accept(ToolProcessorTestSupport.partialResult("call-1"));
              return ToolProcessorTestSupport.succeeded("call-1");
            });

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claim(fixture)));
    awaitIdle(fixture);
    ToolProcessorTestSupport.awaitTrue(() -> run.closes.get() == 1, WAIT);

    assertTrue(fixture.sink.events.isEmpty());
    assertEquals(ToolInvocationStatus.RUNNING, tool(fixture).status());
  }

  /** 只在写入 terminal ToolInvocation 的事务上注入失败，用于验证 safeTerminal 的失败清理。 */
  private static HarnessStore failTerminalToolWrites(HarnessStore delegate) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if ("transaction".equals(method.getName()) && args != null && args.length == 1) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, ?> callback =
                    (Function<HarnessStore.Transaction, ?>) args[0];
                return delegate.transaction(
                    tx ->
                        callback.apply(
                            (HarnessStore.Transaction)
                                Proxy.newProxyInstance(
                                    HarnessStore.Transaction.class.getClassLoader(),
                                    new Class<?>[] {HarnessStore.Transaction.class},
                                    (ignored, txMethod, txArgs) -> {
                                      if ("updateToolInvocations".equals(txMethod.getName())) {
                                        @SuppressWarnings("unchecked")
                                        List<ToolInvocation> invocations =
                                            (List<ToolInvocation>) txArgs[0];
                                        if (invocations.stream()
                                            .anyMatch(
                                                invocation -> invocation.status().isTerminal())) {
                                          throw new IllegalStateException(
                                              "injected terminal persistence failure");
                                        }
                                      }
                                      return invokeUnchecked(tx, txMethod, txArgs);
                                    })));
              }
              return invokeUnchecked(delegate, method, args);
            });
  }

  /** 只在写入 RUNNING ToolInvocation 的事务上注入失败，用于验证 markRunning 失败的 close 所有权。 */
  private static HarnessStore failRunningToolWrites(HarnessStore delegate) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if ("transaction".equals(method.getName()) && args != null && args.length == 1) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, ?> callback =
                    (Function<HarnessStore.Transaction, ?>) args[0];
                return delegate.transaction(
                    tx ->
                        callback.apply(
                            (HarnessStore.Transaction)
                                Proxy.newProxyInstance(
                                    HarnessStore.Transaction.class.getClassLoader(),
                                    new Class<?>[] {HarnessStore.Transaction.class},
                                    (ignored, txMethod, txArgs) -> {
                                      if ("updateToolInvocations".equals(txMethod.getName())) {
                                        @SuppressWarnings("unchecked")
                                        List<ToolInvocation> invocations =
                                            (List<ToolInvocation>) txArgs[0];
                                        if (invocations.stream()
                                            .anyMatch(
                                                invocation ->
                                                    invocation.status()
                                                        == ToolInvocationStatus.RUNNING)) {
                                          throw new IllegalStateException(
                                              "injected running persistence failure");
                                        }
                                      }
                                      return invokeUnchecked(tx, txMethod, txArgs);
                                    })));
              }
              return invokeUnchecked(delegate, method, args);
            });
  }

  private static Object invokeUnchecked(Object target, Method method, Object[] args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw new IllegalStateException(cause);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException(error);
    }
  }
}

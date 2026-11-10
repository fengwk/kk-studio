package fun.fengwk.kkstudio.harness.builtin.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.builtin.store.testing.SingleModelGateway;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionDrain;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionHandle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRecovery;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRegistration;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * ModelExecution 的故障收敛边界：生命周期登记失败、退出证明失败、Gateway 运行时异常、abandon 竞态与终态持久化失败都只能收敛为明确的返回值。
 *
 * <p>每个用例都通过真实内存 store 与真实执行生命周期驱动，只在 SPI 契约边界替换句柄行为，用于断言 durable 状态、退出事实与返回结果， 不依赖私有状态或日志文本。
 */
class ModelExecutionFailureConvergenceTest {

  private static final Instant NOW = ThreadProcessorTestSupport.NOW;
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(10));
  private static final Duration FALLBACK_DELAY = Duration.ofSeconds(1);
  private static final ModelStreamConfig FLUSH_CONFIG =
      new ModelStreamConfig(Duration.ofMinutes(1), 1024, 1024 * 1024, Duration.ofMinutes(1));
  private static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
  private static final ModelUsage USAGE = new ModelUsage(1, 1, 0, 0, Map.of(), 0L, 2L);

  private final List<ExecutorService> executorsToClose = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (ExecutorService executor : executorsToClose) {
      executor.shutdownNow();
    }
  }

  /** 冻结 Provider 身份与运行 store 资格在进入任何执行前校验：空白名称与非 Builtin store 都不得建立 execution。 */
  @Test
  void constructorRejectsMissingProviderIdentityAndNonBuiltinStore() {
    Fixture fixture = fixture();

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.execution(fixture.lifecycle, 1, " ", ignored -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.execution(
                nonBuiltinStore(), fixture.lifecycle, 1, "test-provider", ignored -> {}));
  }

  /** close 已赢得启动窗口时 lifecycle.begin 抛出：markRunning 返回 false，不登记 RUNNING、不进入 body、不谎报未启动。 */
  @Test
  void markRunningFailureLeavesInvocationReadyAndNeverStartsBody() {
    Fixture fixture = fixture();
    ModelExecution execution =
        fixture.execution(fixture.lifecycle, 1, "test-provider", ignored -> {});
    assertTrue(execution.startHeartbeat());
    fixture.lifecycle.close();

    assertFalse(execution.markRunning());
    assertFalse(execution.registered());
    assertFalse(execution.bodyStarted());
    assertEquals(ModelInvocationStatus.READY, fixture.model().status());
    assertTrue(fixture.exitFacts().isEmpty());

    execution.confirmNotStarted();
    execution.exitNotProven();
    assertEquals(ModelInvocationStatus.READY, fixture.model().status());
    assertTrue(fixture.exitFacts().isEmpty());

    AtomicInteger runs = new AtomicInteger();
    ModelGateway.Outcome outcome =
        execution.run(
            admitted(
                sink -> {
                  runs.incrementAndGet();
                  return new ModelGateway.Unknown(
                      new ModelInvocationError(ProviderErrorKind.TRANSIENT, "must not run"));
                }));
    assertNull(outcome);
    assertEquals(0, runs.get());
    assertEquals(ProcessResult.LOST_OWNERSHIP, execution.finish(outcome));
    assertEquals(ModelInvocationStatus.READY, fixture.model().status());
  }

  /** attempt 已陈旧时登记回调返回 null：句柄保持未登记，确认清理后不留下 RUNNING 或退出事实。 */
  @Test
  void staleAttemptCannotRegisterRunningOrClaimExit() {
    Fixture fixture = fixture();
    ModelExecution execution =
        fixture.execution(fixture.lifecycle, 2, "test-provider", ignored -> {});
    assertTrue(execution.startHeartbeat());

    assertFalse(execution.markRunning());
    assertFalse(execution.registered());
    assertEquals(ModelInvocationStatus.READY, fixture.model().status());
    assertTrue(fixture.exitFacts().isEmpty());

    execution.confirmNotStarted();
    assertEquals(ModelInvocationStatus.READY, fixture.model().status());
    assertTrue(fixture.exitFacts().isEmpty());
  }

  /** 退出证明第一次被句柄拒绝时只保留 UNCONFIRMED，不向调用方抛出；后续重试成功才推进到 NOT_STARTED。 */
  @Test
  void rejectedExitProofStaysUnconfirmedUntilRetrySucceeds() {
    Fixture fixture = fixture();
    AtomicBoolean failNextProof = new AtomicBoolean(true);
    LocalExecutionLifecycle lifecycle =
        wrapping(fixture.lifecycle, handle -> new WrappedHandle(handle, true, failNextProof));
    ModelExecution execution = fixture.execution(lifecycle, 1, "test-provider", ignored -> {});
    assertTrue(execution.startHeartbeat());
    assertTrue(execution.markRunning());
    ExecutionExitFact fact = fixture.singleExitFact();
    assertEquals(ExecutionExitState.UNCONFIRMED, fact.state());

    assertDoesNotThrow(execution::confirmNotStarted);
    assertEquals(ExecutionExitState.UNCONFIRMED, fixture.exitFact(fact.executionId()).state());
    assertEquals(ModelInvocationStatus.RUNNING, fixture.model().status());

    execution.confirmNotStarted();
    assertEquals(ExecutionExitState.NOT_STARTED, fixture.exitFact(fact.executionId()).state());
  }

  /** 已登记但 body 尚未 attach 就已取消：run 返回 null 且绝不进入 Gateway；确认未启动后保留精确退出事实。 */
  @Test
  void attachRejectionNeverRunsBodyAndConfirmsNotStarted() {
    Fixture fixture = fixture();
    LocalExecutionLifecycle lifecycle =
        wrapping(fixture.lifecycle, handle -> new WrappedHandle(handle, false, null));
    ModelExecution execution = fixture.execution(lifecycle, 1, "test-provider", ignored -> {});
    assertTrue(execution.startHeartbeat());
    assertTrue(execution.markRunning());
    ExecutionExitFact fact = fixture.singleExitFact();

    AtomicInteger runs = new AtomicInteger();
    ModelGateway.Outcome outcome =
        execution.run(
            admitted(
                sink -> {
                  runs.incrementAndGet();
                  return succeeded("must not run");
                }));
    assertNull(outcome);
    assertEquals(0, runs.get());
    assertFalse(execution.bodyStarted());

    execution.confirmNotStarted();
    assertEquals(ExecutionExitState.NOT_STARTED, fixture.exitFact(fact.executionId()).state());
  }

  /** Gateway 实现抛出运行时异常时映射为不可确认的 UNKNOWN 终态，不把宿主 bug 当成 Provider 业务失败重试。 */
  @Test
  void unexpectedGatewayFailureBecomesUnknownTerminal() {
    Fixture fixture = fixture();
    ModelExecution execution =
        fixture.execution(fixture.lifecycle, 1, "test-provider", ignored -> {});
    assertTrue(execution.startHeartbeat());
    assertTrue(execution.markRunning());
    ExecutionExitFact fact = fixture.singleExitFact();

    ModelGateway.Outcome outcome =
        execution.run(
            admitted(
                sink -> {
                  throw new IllegalStateException("gateway implementation bug");
                }));
    ModelGateway.Unknown unknown = assertInstanceOf(ModelGateway.Unknown.class, outcome);
    assertEquals(ProviderErrorKind.TRANSIENT, unknown.error().kind());
    assertTrue(execution.bodyStarted());
    assertFalse(execution.abandoned());

    assertEquals(ProcessResult.TERMINATED, execution.finish(outcome));
    ModelInvocation model = fixture.model();
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertNull(model.result());

    execution.confirmStudioExit();
    assertEquals(ExecutionExitState.STUDIO_EXITED, fixture.exitFact(fact.executionId()).state());
  }

  /** body 内 abandon 后 Gateway 再抛出：run 必须返回 null，finish 不得提交任何终态，durable 保持 RUNNING 等待恢复。 */
  @Test
  void abandonDuringGatewayFailureReturnsNullAndCommitsNothing() {
    Fixture fixture = fixture();
    AtomicInteger releases = new AtomicInteger();
    ModelExecution execution =
        fixture.execution(
            fixture.lifecycle, 1, "test-provider", ignored -> releases.incrementAndGet());
    assertTrue(execution.startHeartbeat());
    assertTrue(execution.markRunning());
    fixture.singleExitFact();

    ModelGateway.Outcome outcome =
        execution.run(
            admitted(
                sink -> {
                  execution.abandon();
                  throw new IllegalStateException("cancelled while failing");
                }));
    assertNull(outcome);
    assertTrue(execution.abandoned());
    assertTrue(execution.bodyStarted());
    assertEquals(1, releases.get());
    execution.abandon();
    assertEquals(1, releases.get(), "abandon release must be idempotent");

    assertEquals(ProcessResult.LOST_OWNERSHIP, execution.finish(outcome));
    ModelInvocation model = fixture.model();
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.result());
    assertEquals(ExecutionExitState.UNCONFIRMED, fixture.singleExitFact().state());
  }

  /** 成功终态提交失败：只返回 LOST_OWNERSHIP，业务状态保持 RUNNING，物理退出仍可独立确认。 */
  @Test
  void terminalPersistenceFailureKeepsRunningAndReturnsLostOwnership() {
    Fixture fixture = fixture();
    ModelExecution execution =
        fixture.execution(fixture.lifecycle, 1, "test-provider", ignored -> {});
    assertTrue(execution.startHeartbeat());
    assertTrue(execution.markRunning());
    ExecutionExitFact fact = fixture.singleExitFact();

    ModelGateway.Outcome outcome = execution.run(admitted(sink -> succeeded("done")));
    assertInstanceOf(ModelGateway.Succeeded.class, outcome);

    fixture.store.updateInvocationFailure = new IllegalStateException("terminal commit failed");
    assertEquals(ProcessResult.LOST_OWNERSHIP, execution.finish(outcome));
    ModelInvocation model = fixture.model();
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.result());
    assertTrue(execution.abandoned());

    execution.confirmStudioExit();
    assertEquals(ExecutionExitState.STUDIO_EXITED, fixture.exitFact(fact.executionId()).state());
  }

  /** 无法证明退出时保留已登记事实为 UNCONFIRMED，绝不落成 Studio 退出。 */
  @Test
  void exitNotProvenKeepsRegisteredFactUnconfirmed() {
    Fixture fixture = fixture();
    ModelExecution execution =
        fixture.execution(fixture.lifecycle, 1, "test-provider", ignored -> {});
    assertTrue(execution.startHeartbeat());
    assertTrue(execution.markRunning());
    ExecutionExitFact fact = fixture.singleExitFact();

    execution.exitNotProven();

    assertEquals(ExecutionExitState.UNCONFIRMED, fixture.exitFact(fact.executionId()).state());
    assertEquals(ModelInvocationStatus.RUNNING, fixture.model().status());
  }

  private Fixture fixture() {
    return new Fixture(executorsToClose);
  }

  private static ModelGateway.Admitted admitted(
      Function<ModelGateway.Sink, ModelGateway.Outcome> body) {
    return new ModelGateway.Admitted() {
      @Override
      public ModelGateway.Outcome run(ModelGateway.Sink sink) {
        return body.apply(sink);
      }

      @Override
      public void close() {}
    };
  }

  private static ModelGateway.Outcome succeeded(String text) {
    return new ModelGateway.Succeeded(new ProviderCompletion(response(text), null));
  }

  private static ProviderResponse response(String text) {
    return new ProviderResponse(
        text, "", List.of(), GenerationStopReason.COMPLETE, USAGE, "req-1", null, "{}");
  }

  private static HarnessStore nonBuiltinStore() {
    return new HarnessStore() {
      @Override
      public <T> T transaction(Function<HarnessStore.Transaction, T> callback) {
        throw new UnsupportedOperationException("not a BuiltinStore");
      }

      @Override
      public void afterCommit(Runnable action) {
        throw new UnsupportedOperationException("not a BuiltinStore");
      }

      @Override
      public void assertNoAmbientTransaction() {
        throw new UnsupportedOperationException("not a BuiltinStore");
      }
    };
  }

  private static LocalExecutionLifecycle wrapping(
      LocalExecutionLifecycle delegate, UnaryOperator<LocalExecutionHandle> operator) {
    return new LocalExecutionLifecycle() {
      @Override
      public LocalExecutionRecovery start() {
        return delegate.start();
      }

      @Override
      public boolean accepting() {
        return delegate.accepting();
      }

      @Override
      public LocalExecutionHandle begin(
          LocalExecutionIdentity identity, LocalExecutionRegistration registration) {
        return operator.apply(delegate.begin(identity, registration));
      }

      @Override
      public LocalExecutionDrain drain(Duration timeout) {
        return delegate.drain(timeout);
      }

      @Override
      public void close() {
        delegate.close();
      }
    };
  }

  /** 只替换句柄的 attach 与第一次退出证明结果；其余生命周期事实仍由真实句柄维护。 */
  private static final class WrappedHandle implements LocalExecutionHandle {

    private final LocalExecutionHandle delegate;
    private final boolean attachAllowed;
    private final AtomicBoolean failNextProvenExit;

    WrappedHandle(
        LocalExecutionHandle delegate, boolean attachAllowed, AtomicBoolean failNextProvenExit) {
      this.delegate = delegate;
      this.attachAllowed = attachAllowed;
      this.failNextProvenExit = failNextProvenExit;
    }

    @Override
    public ExecutionExitFact fact() {
      return delegate.fact();
    }

    @Override
    public boolean registered() {
      return delegate.registered();
    }

    @Override
    public boolean attachBody(Thread body) {
      return attachAllowed && delegate.attachBody(body);
    }

    @Override
    public void detachBody() {
      delegate.detachBody();
    }

    @Override
    public void requestCancel() {
      delegate.requestCancel();
    }

    @Override
    public void provenExit(ExecutionExitState state) {
      if (failNextProvenExit != null && failNextProvenExit.compareAndSet(true, false)) {
        throw new IllegalStateException("body is still alive; exit cannot be proven");
      }
      delegate.provenExit(state);
    }

    @Override
    public void exitNotProven() {
      delegate.exitNotProven();
    }
  }

  private static final class Fixture {

    final RecordingStore store;
    final InMemoryBuiltinHarnessStore delegate;
    final ThreadProcessorTestSupport.OpenTurnBaseline turn;
    final UUID invocationId;
    final ClaimedWork claim;
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    final ModelProcessorConfig config =
        new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, FLUSH_CONFIG);
    final ModelGateway gateway =
        SingleModelGateway.of(
            execution -> {
              throw new AssertionError("unused admission");
            });
    final LocalExecutionLifecycle lifecycle;

    Fixture(List<ExecutorService> executorsToClose) {
      executorsToClose.add(scheduler);
      this.delegate = new InMemoryBuiltinHarnessStore();
      this.store = new RecordingStore(delegate);
      this.turn = ThreadProcessorTestSupport.seedOpenInputTurn(delegate);
      this.invocationId =
          delegate.transaction(
              tx -> {
                tx.threads().lock(turn.threadId());
                UUID id = tx.nextId();
                builtin(store, tx)
                    .insertModelInvocation(
                        new ModelInvocation(
                            id,
                            turn.threadId(),
                            turn.turnStartEntryId(),
                            turn.userEntryId(),
                            ThreadProcessorTestSupport.plainRequest(),
                            ModelInvocationStatus.READY,
                            0,
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            NOW,
                            NOW));
                tx.work().request(new WorkTarget(BuiltinWorkType.MODEL, id));
                return id;
              });
      this.claim =
          delegate
              .transaction(
                  tx ->
                      tx.work()
                          .claimNext(
                              BuiltinWorkType.MODEL, UUID.randomUUID(), Duration.ofSeconds(60)))
              .orElseThrow();
      this.lifecycle = TestExecutionLifecycle.create(store, CLOCK);
    }

    ModelExecution execution(
        LocalExecutionLifecycle lifecycle,
        int attempt,
        String providerName,
        Consumer<ModelExecution> ownerRelease) {
      return execution(store, lifecycle, attempt, providerName, ownerRelease);
    }

    ModelExecution execution(
        HarnessStore runtimeStore,
        LocalExecutionLifecycle lifecycle,
        int attempt,
        String providerName,
        Consumer<ModelExecution> ownerRelease) {
      return new ModelExecution(
          runtimeStore,
          gateway,
          event -> {},
          claim,
          turn.threadId(),
          attempt,
          false,
          ThreadProcessorTestSupport.plainRequest().toolBindings(),
          config,
          providerName,
          CLOCK,
          scheduler,
          Runnable::run,
          Runnable::run,
          ownerRelease,
          lifecycle);
    }

    ModelInvocation model() {
      return ThreadProcessorTestSupport.model(delegate, invocationId);
    }

    List<ExecutionExitFact> exitFacts() {
      return delegate.transaction(tx -> tx.exits().loadUnconfirmed(turn.threadId()));
    }

    ExecutionExitFact singleExitFact() {
      List<ExecutionExitFact> facts = exitFacts();
      assertEquals(1, facts.size(), "exactly one registered generation");
      return facts.getFirst();
    }

    ExecutionExitFact exitFact(UUID executionId) {
      return delegate
          .transaction(tx -> tx.exits().find(executionId))
          .orElseThrow(() -> new AssertionError("missing exit fact " + executionId));
    }
  }
}

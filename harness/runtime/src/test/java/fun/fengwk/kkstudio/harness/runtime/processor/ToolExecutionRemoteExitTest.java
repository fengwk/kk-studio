package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回归：远端退出确认必须使用 {@code tryStart} 已持久化的远端事实及其数据库登记时间。
 *
 * <p>Studio 时钟可能落后于数据库时钟；若确认时改用本地 TOOL 的 registeredAt，journal 会固化一个早于远端登记时间的 proof，之后永远无法应用。
 */
class ToolExecutionRemoteExitTest {

  @Test
  void remoteAcknowledgementUsesPersistedRegistrationTimeWhenStudioClockIsBehind()
      throws Exception {
    ToolProcessorTestSupport.Fixture support = ToolProcessorTestSupport.fixture();
    ScheduledExecutorService scheduler = support.scheduler;
    try {
      ClaimedWork claim = approvedClaim(support);
      RecordingJournal journal = new RecordingJournal(TestExecutionExitConfirmer.memoryJournal());
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(
              support.store, journal, Clock.fixed(ToolProcessorTestSupport.NOW, ZoneOffset.UTC));
      ToolExecution execution = execution(support, claim, support.store, confirmer);
      UUID remoteId = UUID.randomUUID();
      AtomicReference<ExecutionExitFact> registered = new AtomicReference<>();
      AtomicReference<ExecutionExitFact> repeated = new AtomicReference<>();
      AtomicReference<ExecutionExitFact> confirmed = new AtomicReference<>();

      ToolProcessorTestSupport.FakeRun run =
          new ToolProcessorTestSupport.FakeRun(
              progress -> {
                // 数据库时钟推进到本地 TOOL 登记之后；Studio confirmer 时钟仍固定在早期 NOW。
                support.clock.advance(Duration.ofSeconds(10));
                ToolGateway.RemoteExecutionObserver observer = execution.remoteExecutions();
                assertTrue(observer.tryStart(remoteId), "first remote registration must succeed");
                registered.set(executionFact(support.store, remoteId));
                ExecutionExitFact local =
                    support.store.transaction(
                        tx ->
                            tx.loadUnconfirmedExecutions(support.baseline.threadId()).stream()
                                .filter(fact -> fact.kind() == ExecutionExitKind.TOOL)
                                .findFirst()
                                .orElseThrow());
                assertTrue(
                    registered.get().registeredAt().isAfter(local.registeredAt()),
                    "the remote registration must be later than the local TOOL registration");

                assertTrue(
                    observer.tryStart(remoteId), "duplicate registration must stay idempotent");
                repeated.set(executionFact(support.store, remoteId));

                observer.confirmExit(remoteId);
                confirmed.set(executionFact(support.store, remoteId));
                return ToolProcessorTestSupport.succeeded("call-1");
              });

      assertEquals(ProcessResult.STARTED, execution.start(run));
      assertTrue(run.awaitExited(), "the remote tool body must exit");
      assertTrue(run.executionThread.join(Duration.ofSeconds(5)), "the real finally must finish");

      ExecutionExitFact persisted = executionFact(support.store, remoteId);
      assertEquals(ExecutionExitState.REMOTE_ACKNOWLEDGED, persisted.state());
      assertEquals(
          registered.get().registeredAt(),
          repeated.get().registeredAt(),
          "duplicate tryStart must reuse the canonical persisted registeredAt");
      assertEquals(confirmed.get().confirmedAt(), persisted.confirmedAt());
      assertFalse(
          persisted.confirmedAt().isBefore(persisted.registeredAt()),
          "remote confirmation must not precede its persisted registration");
      ExecutionExitJournalRecord proof = journal.proof(remoteId);
      assertNotNull(proof, "the remote proof must have been journaled");
      assertEquals(persisted.confirmedAt(), proof.confirmedAt());
      assertFalse(
          proof.confirmedAt().isBefore(persisted.registeredAt()),
          "canonical proof time must be at or after the remote persisted registration");
    } finally {
      close(scheduler);
    }
  }

  @Test
  void remoteProofSurvivesTransientDatabaseFailureAndReplaysWithOriginalRegistrationTime()
      throws Exception {
    ToolProcessorTestSupport.Fixture support = ToolProcessorTestSupport.fixture();
    ScheduledExecutorService scheduler = support.scheduler;
    try {
      ClaimedWork claim = approvedClaim(support);
      AtomicBoolean failNextTransaction = new AtomicBoolean();
      HarnessStore failingStore = failNextTransaction(support.store, failNextTransaction);
      RecordingJournal journal = new RecordingJournal(TestExecutionExitConfirmer.memoryJournal());
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(
              failingStore, journal, Clock.fixed(ToolProcessorTestSupport.NOW, ZoneOffset.UTC));
      ToolExecution execution = execution(support, claim, failingStore, confirmer);
      UUID remoteId = UUID.randomUUID();
      AtomicReference<ExecutionExitFact> registered = new AtomicReference<>();

      ToolProcessorTestSupport.FakeRun run =
          new ToolProcessorTestSupport.FakeRun(
              progress -> {
                support.clock.advance(Duration.ofSeconds(10));
                ToolGateway.RemoteExecutionObserver observer = execution.remoteExecutions();
                assertTrue(observer.tryStart(remoteId));
                registered.set(executionFact(support.store, remoteId));

                // proof 已在 journal 落盘后数据库才短暂不可用：异常向调用方传播，但证据必须保留供重放。
                failNextTransaction.set(true);
                assertThrows(IllegalStateException.class, () -> observer.confirmExit(remoteId));
                assertEquals(
                    List.of(remoteId),
                    journal.loadAll().stream()
                        .map(ExecutionExitJournalRecord::executionId)
                        .toList(),
                    "the remote proof must survive the failed database transaction");
                return ToolProcessorTestSupport.succeeded("call-1");
              });

      assertEquals(ProcessResult.STARTED, execution.start(run));
      assertTrue(run.awaitExited(), "the remote tool body must exit");
      assertTrue(run.executionThread.join(Duration.ofSeconds(5)), "the real finally must finish");
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          executionFact(support.store, remoteId).state(),
          "a failed database confirmation must not claim the remote exited");

      confirmer.replayPending();

      ExecutionExitFact replayed = executionFact(support.store, remoteId);
      assertEquals(ExecutionExitState.REMOTE_ACKNOWLEDGED, replayed.state());
      assertTrue(journal.loadAll().isEmpty(), "a successful replay must delete the retained proof");
      assertFalse(replayed.confirmedAt().isBefore(replayed.registeredAt()));
      assertEquals(registered.get().registeredAt(), replayed.registeredAt());
      assertEquals(
          registered.get().registeredAt(),
          journal.proof(remoteId).confirmedAt(),
          "the replayed canonical time must keep the original persisted registration boundary");
    } finally {
      close(scheduler);
    }
  }

  private static ClaimedWork approvedClaim(ToolProcessorTestSupport.Fixture support) {
    ClaimedWork claim =
        ToolProcessorTestSupport.claim(
            support.store, support.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.toApproved(
        support.store, support.toolInvocationId, ToolProcessorTestSupport.NOW);
    return claim;
  }

  private static ToolExecution execution(
      ToolProcessorTestSupport.Fixture support,
      ClaimedWork claim,
      HarnessStore store,
      ExecutionExitConfirmer confirmer) {
    return new ToolExecution(
        store,
        support.sink,
        claim,
        support.baseline.threadId(),
        1,
        support.request,
        new ToolProcessorConfig(
            ToolProcessorTestSupport.LEASE_CONFIG,
            () -> ToolProcessorTestSupport.NO_RETRY,
            ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
            ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        support.clock,
        support.scheduler,
        Runnable::run,
        ignored -> {},
        confirmer);
  }

  private static ExecutionExitFact executionFact(HarnessStore store, UUID executionId) {
    return store.transaction(tx -> tx.findExecution(executionId).orElseThrow());
  }

  private static HarnessStore failNextTransaction(
      HarnessStore delegate, AtomicBoolean failNextTransaction) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("transaction")
                  && failNextTransaction.compareAndSet(true, false)) {
                throw new IllegalStateException("database unavailable");
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException error) {
                throw error.getCause();
              }
            });
  }

  private static void close(ScheduledExecutorService scheduler) throws InterruptedException {
    scheduler.shutdownNow();
    assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS), "scheduler must terminate");
  }

  /** 记录第一次 canonical 写入；确认成功后 delegate 会删除记录，但回归断言仍需要观察 proof 时间。 */
  private static final class RecordingJournal implements ExecutionExitJournal {

    private final ExecutionExitJournal delegate;
    private final Map<UUID, ExecutionExitJournalRecord> proofs = new ConcurrentHashMap<>();

    private RecordingJournal(ExecutionExitJournal delegate) {
      this.delegate = delegate;
    }

    @Override
    public ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
      ExecutionExitJournalRecord canonical = delegate.write(record);
      proofs.putIfAbsent(canonical.executionId(), canonical);
      return canonical;
    }

    @Override
    public void delete(UUID executionId) {
      delegate.delete(executionId);
    }

    @Override
    public List<ExecutionExitJournalRecord> loadAll() {
      return delegate.loadAll();
    }

    @Override
    public void close() {
      delegate.close();
    }

    private ExecutionExitJournalRecord proof(UUID executionId) {
      return proofs.get(executionId);
    }
  }
}

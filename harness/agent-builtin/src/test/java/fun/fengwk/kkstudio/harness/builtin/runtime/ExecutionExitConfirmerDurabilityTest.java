package fun.fengwk.kkstudio.harness.builtin.runtime;

import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalSnapshot;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 退出确认的 durable 边界：先落盘 proof、数据库故障保留、重启重放与并发 exactly-once。 */
class ExecutionExitConfirmerDurabilityTest {

  @Test
  void databaseFailureAfterProofWriteKeepsJournalForRestartReplay(@TempDir Path directory)
      throws Exception {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(1), T0);
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer unavailable =
          new ExecutionExitConfirmer(unavailableStore(), journal, clock);
      assertThrows(
          IllegalStateException.class,
          () -> unavailable.confirm(fact, ExecutionExitState.STUDIO_EXITED));
      assertEquals(1, journal.loadAll().records().size());
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          store.transaction(tx -> tx.findExecution(fact.executionId()).orElseThrow().state()));
    }

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer restarted =
          new ExecutionExitConfirmer(
              store, journal, Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC));
      restarted.replayPending();
      assertEquals(0, journal.loadAll().records().size());
    }
    ExecutionExitFact confirmed =
        store.transaction(tx -> tx.findExecution(fact.executionId()).orElseThrow());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt());
  }

  @Test
  void proofWrittenBeforeAbandonmentReplaysWithoutReplacingEitherTime(@TempDir Path directory)
      throws Exception {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(1), T0);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer unavailable =
          new ExecutionExitConfirmer(unavailableStore(), journal, Clock.fixed(T0, ZoneOffset.UTC));
      assertThrows(
          IllegalStateException.class,
          () -> unavailable.confirm(fact, ExecutionExitState.STUDIO_EXITED));
      assertEquals(1, journal.loadAll().records().size());
    }
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          return tx.abandonExecution(fact, T0.plusMillis(1)).orElseThrow();
        });

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer restarted =
          new ExecutionExitConfirmer(
              store, journal, Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC));
      restarted.replayPending();
      assertTrue(journal.loadAll().records().isEmpty());
    }
    ExecutionExitFact confirmed =
        store.transaction(tx -> tx.findExecution(fact.executionId()).orElseThrow());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt());
    assertEquals(T0.plusMillis(1), confirmed.abandonedAt());
  }

  @Test
  void journalFailurePreventsAnyDatabaseReadOrSubmission() {
    AtomicInteger databaseAccesses = new AtomicInteger();
    HarnessStore store =
        (HarnessStore)
            Proxy.newProxyInstance(
                HarnessStore.class.getClassLoader(),
                new Class<?>[] {HarnessStore.class},
                (proxy, method, args) -> {
                  databaseAccesses.incrementAndGet();
                  throw new IllegalStateException("database must not be touched");
                });
    ExecutionExitJournal failingJournal =
        new ExecutionExitJournal() {
          @Override
          public ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
            throw new ExecutionExitJournalException("cannot write");
          }

          @Override
          public void delete(UUID executionId) {
            throw new AssertionError("delete must not be reached");
          }

          @Override
          public ExecutionExitJournalSnapshot loadAll() {
            return new ExecutionExitJournalSnapshot(List.of(), List.of());
          }

          @Override
          public void close() {}
        };
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(store, failingJournal, Clock.fixed(T0, ZoneOffset.UTC));

    assertThrows(
        ExecutionExitJournalException.class,
        () ->
            confirmer.confirm(
                expected(TestIds.id(5), TestIds.id(6), T0), ExecutionExitState.STUDIO_EXITED));
    assertEquals(0, databaseAccesses.get());
  }

  @Test
  void missingFactKeepsJournalButMissingThreadDeletesIt(@TempDir Path directory) throws Exception {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    ExecutionExitConfirmer confirmer;
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      confirmer = new ExecutionExitConfirmer(store, journal, clock);
      ExecutionExitFact unknown = expected(baseline.threadId(), TestIds.id(2), T0);
      assertThrows(
          IllegalStateException.class,
          () -> confirmer.confirm(unknown, ExecutionExitState.STUDIO_EXITED));
      assertEquals(1, journal.loadAll().records().size());
      journal.delete(unknown.executionId());
    }

    ExecutionExitFact deleted = register(store, baseline.threadId(), TestIds.id(3), T0);
    store.transaction(
        tx -> {
          tx.lockTree(baseline.threadId());
          tx.lockThread(baseline.threadId());
          tx.deleteThreads(List.of(baseline.threadId()));
          return null;
        });
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer restarted = new ExecutionExitConfirmer(store, journal, clock);
      assertEquals(
          ExecutionExitConfirmer.Outcome.THREAD_MISSING,
          restarted.confirm(deleted, ExecutionExitState.STUDIO_EXITED).outcome());
      assertTrue(journal.loadAll().records().isEmpty());
    }
  }

  @Test
  void replayContinuesAfterOneRecordFails(@TempDir Path directory) throws Exception {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact missing = expected(baseline.threadId(), TestIds.id(7), T0);
    ExecutionExitFact present = register(store, baseline.threadId(), TestIds.id(8), T0);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(recordFor(missing));
      journal.write(recordFor(present));
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(store, journal, Clock.fixed(T0, ZoneOffset.UTC));

      confirmer.replayPending();

      assertEquals(1, journal.loadAll().records().size());
      assertEquals(missing.executionId(), journal.loadAll().records().getFirst().executionId());
    }
    ExecutionExitFact confirmed =
        store.transaction(tx -> tx.findExecution(present.executionId()).orElseThrow());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
  }

  @Test
  void deleteFailureKeepsProofAndReplayDoesNotAdvanceVersionAgain(@TempDir Path directory) {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(9), T0);

    try (FileExecutionExitJournal delegate = new FileExecutionExitJournal(directory)) {
      ExecutionExitJournal journal = new FailingDeleteJournal(delegate);
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(store, journal, Clock.fixed(T0, ZoneOffset.UTC));

      assertEquals(
          ExecutionExitConfirmer.Outcome.CONFIRMED,
          confirmer.confirm(fact, ExecutionExitState.STUDIO_EXITED).outcome());
      assertEquals(1, delegate.loadAll().records().size());
      assertEquals(1L, threadVersion(store, baseline.threadId()));

      assertEquals(
          ExecutionExitConfirmer.Outcome.CONFIRMED,
          confirmer.confirm(fact, ExecutionExitState.STUDIO_EXITED).outcome());
      assertTrue(delegate.loadAll().records().isEmpty());
      assertEquals(1L, threadVersion(store, baseline.threadId()));
    }
  }

  @Test
  void confirmedStateCannotBeReplacedByDifferentRealEvidence(@TempDir Path directory) {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(10), T0);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(store, journal, Clock.fixed(T0, ZoneOffset.UTC));
      confirmer.confirm(fact, ExecutionExitState.STUDIO_EXITED);

      assertThrows(
          IllegalArgumentException.class,
          () -> confirmer.confirm(fact, ExecutionExitState.NOT_STARTED));

      assertEquals(1, journal.loadAll().records().size());
      assertEquals(
          ExecutionExitState.NOT_STARTED, journal.loadAll().records().getFirst().targetState());
    }
    ExecutionExitFact confirmed =
        store.transaction(tx -> tx.findExecution(fact.executionId()).orElseThrow());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(1L, threadVersion(store, baseline.threadId()));
  }

  @Test
  void journalIdentityConflictIsRetainedWithoutTouchingTheDatabase(@TempDir Path directory) {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact recorded = expected(baseline.threadId(), TestIds.id(11), T0);
    ExecutionExitFact conflicting =
        new ExecutionExitFact(
            recorded.executionId(),
            recorded.threadId(),
            TestIds.id(12),
            recorded.attempt(),
            recorded.ownerLeaseToken(),
            recorded.kind(),
            recorded.parentExecutionId(),
            ExecutionExitState.UNCONFIRMED,
            recorded.registeredAt(),
            null,
            null);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(recordFor(recorded));
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(store, journal, Clock.fixed(T0, ZoneOffset.UTC));

      assertThrows(
          ExecutionExitJournalException.class,
          () -> confirmer.confirm(conflicting, ExecutionExitState.STUDIO_EXITED));

      assertEquals(1, journal.loadAll().records().size());
      assertEquals(recorded.invocationId(), journal.loadAll().records().getFirst().invocationId());
    }
  }

  @Test
  void unconfirmedTargetIsRejectedBeforeWritingProof(@TempDir Path directory) {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(13), T0);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(store, journal, Clock.fixed(T0, ZoneOffset.UTC));

      assertThrows(
          IllegalArgumentException.class,
          () -> confirmer.confirm(fact, ExecutionExitState.UNCONFIRMED));
      assertTrue(journal.loadAll().records().isEmpty());
    }
  }

  @Test
  void concurrentCallbacksAdvanceVersionExactlyOnce() throws Exception {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(4), T0);
    ExecutionExitConfirmer confirmer =
        TestExecutionExitConfirmer.create(store, Clock.fixed(T0, ZoneOffset.UTC));
    CountDownLatch start = new CountDownLatch(1);
    // TWR waits for both callbacks even if one assertion fails, so no worker outlives this test.
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> confirmAfter(start, confirmer, fact));
      var second = workers.submit(() -> confirmAfter(start, confirmer, fact));
      start.countDown();
      assertEquals(
          ExecutionExitConfirmer.Outcome.CONFIRMED, first.get(5, TimeUnit.SECONDS).outcome());
      assertEquals(
          ExecutionExitConfirmer.Outcome.CONFIRMED, second.get(5, TimeUnit.SECONDS).outcome());
    }
    ExecutionExitFact confirmed =
        store.transaction(tx -> tx.findExecution(fact.executionId()).orElseThrow());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    long version =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow().version());
    assertEquals(1L, version);
  }

  private static ExecutionExitConfirmer.Result confirmAfter(
      CountDownLatch start, ExecutionExitConfirmer confirmer, ExecutionExitFact fact)
      throws InterruptedException {
    start.await();
    return confirmer.confirm(fact, ExecutionExitState.STUDIO_EXITED);
  }

  private static HarnessStore unavailableStore() {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              throw new IllegalStateException("database unavailable");
            });
  }

  private static ExecutionExitFact register(
      InMemoryBuiltinHarnessStore store, UUID threadId, UUID invocationId, Instant registeredAt) {
    ExecutionExitFact fact = expected(threadId, invocationId, registeredAt);
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          return tx.registerExecution(fact);
        });
    return fact;
  }

  private static long threadVersion(InMemoryBuiltinHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow().version());
  }

  private static ExecutionExitJournalRecord recordFor(ExecutionExitFact fact) {
    return new ExecutionExitJournalRecord(
        fact.executionId(),
        fact.threadId(),
        fact.invocationId(),
        fact.attempt(),
        fact.ownerLeaseToken(),
        fact.kind(),
        fact.parentExecutionId(),
        ExecutionExitState.STUDIO_EXITED,
        T0);
  }

  private static final class FailingDeleteJournal implements ExecutionExitJournal {

    private final ExecutionExitJournal delegate;
    private final AtomicBoolean failNextDelete = new AtomicBoolean(true);

    private FailingDeleteJournal(ExecutionExitJournal delegate) {
      this.delegate = delegate;
    }

    @Override
    public ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
      return delegate.write(record);
    }

    @Override
    public void delete(UUID executionId) {
      if (failNextDelete.compareAndSet(true, false)) {
        throw new ExecutionExitJournalException("delete failed");
      }
      delegate.delete(executionId);
    }

    @Override
    public ExecutionExitJournalSnapshot loadAll() {
      return delegate.loadAll();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static ExecutionExitFact expected(
      UUID threadId, UUID invocationId, Instant registeredAt) {
    return new ExecutionExitFact(
        UUID.randomUUID(),
        threadId,
        invocationId,
        1,
        "lease-1",
        ExecutionExitKind.TOOL,
        null,
        ExecutionExitState.UNCONFIRMED,
        registeredAt,
        null,
        null);
  }
}

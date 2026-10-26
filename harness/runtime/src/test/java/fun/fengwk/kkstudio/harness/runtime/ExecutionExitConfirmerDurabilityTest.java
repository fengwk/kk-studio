package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
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
import java.util.concurrent.atomic.AtomicInteger;

/** 退出确认的 durable 边界：先落盘 proof、数据库故障保留、重启重放与并发 exactly-once。 */
class ExecutionExitConfirmerDurabilityTest {

  @Test
  void databaseFailureAfterProofWriteKeepsJournalForRestartReplay(@TempDir Path directory)
      throws Exception {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(1), T0);
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer unavailable =
          new ExecutionExitConfirmer(unavailableStore(), journal, clock);
      assertThrows(
          IllegalStateException.class,
          () -> unavailable.confirm(fact, ExecutionExitState.STUDIO_EXITED));
      assertEquals(1, journal.loadAll().size());
      assertEquals(
          ExecutionExitState.UNCONFIRMED,
          store.transaction(tx -> tx.findExecution(fact.executionId()).orElseThrow().state()));
    }

    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer restarted =
          new ExecutionExitConfirmer(
              store, journal, Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC));
      restarted.replayPending();
      assertEquals(0, journal.loadAll().size());
    }
    ExecutionExitFact confirmed =
        store.transaction(tx -> tx.findExecution(fact.executionId()).orElseThrow());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt());
  }

  @Test
  void proofWrittenBeforeAbandonmentReplaysWithoutReplacingEitherTime(@TempDir Path directory)
      throws Exception {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(1), T0);
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer unavailable =
          new ExecutionExitConfirmer(unavailableStore(), journal, Clock.fixed(T0, ZoneOffset.UTC));
      assertThrows(
          IllegalStateException.class,
          () -> unavailable.confirm(fact, ExecutionExitState.STUDIO_EXITED));
      assertEquals(1, journal.loadAll().size());
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
      assertTrue(journal.loadAll().isEmpty());
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
          public List<ExecutionExitJournalRecord> loadAll() {
            return List.of();
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
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    ExecutionExitConfirmer confirmer;
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      confirmer = new ExecutionExitConfirmer(store, journal, clock);
      ExecutionExitFact unknown = expected(baseline.threadId(), TestIds.id(2), T0);
      assertThrows(
          IllegalStateException.class,
          () -> confirmer.confirm(unknown, ExecutionExitState.STUDIO_EXITED));
      assertEquals(1, journal.loadAll().size());
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
      assertTrue(journal.loadAll().isEmpty());
    }
  }

  @Test
  void concurrentCallbacksAdvanceVersionExactlyOnce() throws Exception {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(4), T0);
    ExecutionExitConfirmer confirmer =
        TestExecutionExitConfirmer.create(store, Clock.fixed(T0, ZoneOffset.UTC));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      var first = workers.submit(() -> confirmAfter(start, confirmer, fact));
      var second = workers.submit(() -> confirmAfter(start, confirmer, fact));
      start.countDown();
      assertEquals(
          ExecutionExitConfirmer.Outcome.CONFIRMED, first.get(5, TimeUnit.SECONDS).outcome());
      assertEquals(
          ExecutionExitConfirmer.Outcome.CONFIRMED, second.get(5, TimeUnit.SECONDS).outcome());
    } finally {
      workers.shutdownNow();
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
      InMemoryHarnessStore store, UUID threadId, UUID invocationId, Instant registeredAt) {
    ExecutionExitFact fact = expected(threadId, invocationId, registeredAt);
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          return tx.registerExecution(fact);
        });
    return fact;
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

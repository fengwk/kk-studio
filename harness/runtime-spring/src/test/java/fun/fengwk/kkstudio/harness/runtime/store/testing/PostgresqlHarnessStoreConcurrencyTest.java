package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class PostgresqlHarnessStoreConcurrencyTest {

  private HarnessStore store;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
  }

  @Test
  void simultaneousClaimsProduceExactlyOneLeaseOwner() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.requestWork(target, T0);
        });

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Optional<ClaimedWork>> first =
          executor.submit(() -> claimAfterBarrier("lease-a", ready, start));
      Future<Optional<ClaimedWork>> second =
          executor.submit(() -> claimAfterBarrier("lease-b", ready, start));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();

      List<Optional<ClaimedWork>> outcomes =
          List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(1L, outcomes.stream().filter(Optional::isPresent).count());
      assertEquals(
          target, outcomes.stream().flatMap(Optional::stream).findFirst().orElseThrow().target());
    }
  }

  @Test
  void skipLockedClaimsTheNextDueTargetWithoutWaiting() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    long secondThreadId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, baseline.rootEntryId()));
              return id;
            });
    WorkTarget firstTarget = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget secondTarget = new WorkTarget(WorkTargetType.THREAD, secondThreadId);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.requestWork(firstTarget, T0);
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(secondThreadId);
          tx.requestWork(secondTarget, T0);
        });

    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> holder =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.lockWork(firstTarget).orElseThrow();
                        firstLocked.countDown();
                        await(releaseFirst);
                        return null;
                      }));
      assertTrue(firstLocked.await(10, TimeUnit.SECONDS));

      Future<Optional<ClaimedWork>> claimer =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-next", T5)));
      try {
        ClaimedWork claimed = claimer.get(3, TimeUnit.SECONDS).orElseThrow();
        assertEquals(secondTarget, claimed.target());
      } finally {
        releaseFirst.countDown();
      }
      holder.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void reversedThreadLocksAreRejectedBeforePostgresqlCanDeadlock() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    long higherThreadId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, baseline.rootEntryId()));
              return id;
            });
    CountDownLatch firstLocksAcquired = new CountDownLatch(2);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> ascending =
          executor.submit(
              () -> lockThreadPair(baseline.threadId(), higherThreadId, firstLocksAcquired));
      Future<Boolean> descending =
          executor.submit(
              () -> lockThreadPair(higherThreadId, baseline.threadId(), firstLocksAcquired));

      assertTrue(ascending.get(10, TimeUnit.SECONDS));
      assertFalse(descending.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void concurrentNewWakeSurvivesCompletionOfTheOlderClaim() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.requestWork(target, T0);
        });
    ClaimedWork claim =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-old", T5))
            .orElseThrow();

    CountDownLatch wakeWritten = new CountDownLatch(1);
    CountDownLatch releaseWake = new CountDownLatch(1);
    CountDownLatch completionStarted = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> requester =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.lockThread(baseline.threadId()).orElseThrow();
                        tx.requestWork(target, T1);
                        wakeWritten.countDown();
                        await(releaseWake);
                        return null;
                      }));
      assertTrue(wakeWritten.await(10, TimeUnit.SECONDS));

      Future<Optional<Work>> completion =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        completionStarted.countDown();
                        return tx.completeWork(claim, T2);
                      }));
      assertTrue(completionStarted.await(10, TimeUnit.SECONDS));
      try {
        assertThrows(TimeoutException.class, () -> completion.get(200, TimeUnit.MILLISECONDS));
      } finally {
        releaseWake.countDown();
      }
      requester.get(10, TimeUnit.SECONDS);
      Work remaining = completion.get(10, TimeUnit.SECONDS).orElseThrow();
      assertEquals(2L, remaining.wakeVersion());
      assertNull(remaining.leaseToken());
      assertNull(remaining.leaseUntil());
    }

    Work stored = store.transaction(tx -> tx.findWork(target).orElseThrow());
    assertEquals(2L, stored.wakeVersion());
    assertEquals(T0, stored.availableAt());
    assertNull(stored.leaseToken());
  }

  @Test
  void requestWithoutOwnerLockCannotResurrectControlDeletedWork() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, T0);
        });

    CountDownLatch deleted = new CountDownLatch(1);
    CountDownLatch releaseDelete = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> control =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.lockThread(baseline.threadId()).orElseThrow();
                        assertTrue(tx.deleteWork(target));
                        deleted.countDown();
                        await(releaseDelete);
                        return null;
                      }));
      assertTrue(deleted.await(10, TimeUnit.SECONDS));

      Future<Void> staleRequester =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.requestWork(target, T1);
                        return null;
                      }));
      try {
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> staleRequester.get(3, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
      } finally {
        releaseDelete.countDown();
      }
      control.get(10, TimeUnit.SECONDS);
    }
    assertTrue(store.transaction(tx -> tx.findWork(target)).isEmpty());
  }

  @Test
  void reversedConcurrentToolBatchesUseOneCanonicalDatabaseLockOrder() throws Exception {
    TurnBaseline baseline = seedTurnBaseline(store);
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(
            store, baseline.sessionId(), userEntryId, assistantPayload("call-1", "call-2"));
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
          ModelInvocation model = tx.lockModelInvocation(1).orElseThrow();
          tx.updateModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.CANCELLED,
                  assistantEntryId,
                  model.createdAt()));
        });
    ToolInvocation ordinal0 =
        toolInvocation(10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2);
    ToolInvocation ordinal1 =
        toolInvocation(11, 1, assistantEntryId, 1, "call-2", ToolInvocationStatus.READY, null, T2);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> ascending =
          executor.submit(() -> insertToolBatch(List.of(ordinal0, ordinal1), ready, start));
      Future<Boolean> descending =
          executor.submit(() -> insertToolBatch(List.of(ordinal1, ordinal0), ready, start));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();

      List<Boolean> committed =
          List.of(ascending.get(10, TimeUnit.SECONDS), descending.get(10, TimeUnit.SECONDS));
      assertEquals(1L, committed.stream().filter(Boolean::booleanValue).count());
    }
    assertEquals(
        List.of(0, 1),
        store.transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantEntryId)).stream()
            .map(ToolInvocation::ordinal)
            .toList());
  }

  private boolean insertToolBatch(
      List<ToolInvocation> invocations, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    try {
      inTransaction(store, tx -> tx.insertToolInvocations(invocations));
      return true;
    } catch (IllegalArgumentException expected) {
      return false;
    }
  }

  private boolean lockThreadPair(
      long firstThreadId, long secondThreadId, CountDownLatch firstLocks) {
    try {
      store.transaction(
          tx -> {
            tx.lockThread(firstThreadId).orElseThrow();
            firstLocks.countDown();
            await(firstLocks);
            tx.lockThread(secondThreadId).orElseThrow();
            return null;
          });
      return true;
    } catch (IllegalStateException expected) {
      return false;
    }
  }

  private Optional<ClaimedWork> claimAfterBarrier(
      String token, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return store.transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T0, token, T5));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for concurrent test barrier");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}

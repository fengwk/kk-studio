package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
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
import java.util.UUID;
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
    UUID secondThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
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
    UUID higherThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
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

  /**
   * 同一 Session 内的两个 sibling Thread：acceptCommands 的锁序是 Session KEY SHARE -&gt; Thread FOR UPDATE。
   * KEY SHARE 锁彼此兼容，因此两个事务能同时持有同一 Session 的 KEY SHARE 与各自 Thread 的 FOR UPDATE 锁 —— 证明 sibling
   * acceptance 不会被 Session 级锁串行化（若误用 FOR UPDATE 锁 Session，第二个事务会在此处死等而非双方都到达 barrier）。
   */
  @Test
  void siblingThreadsKeyShareTheSameSessionAndLockTheirOwnThreadsConcurrently() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    UUID siblingId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
              return id;
            });

    CountDownLatch bothAcquired = new CountDownLatch(2);
    CountDownLatch releaseBoth = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.lockSessionForKeyShare(baseline.sessionId()).orElseThrow();
                        tx.lockThread(baseline.threadId()).orElseThrow();
                        bothAcquired.countDown();
                        await(releaseBoth);
                        // 按 accept 阻塞式取回自己的 Thread 行锁完成收尾。
                        tx.lockThread(baseline.threadId()).orElseThrow();
                        return true;
                      }));
      Future<Boolean> second =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.lockSessionForKeyShare(baseline.sessionId()).orElseThrow();
                        tx.lockThread(siblingId).orElseThrow();
                        bothAcquired.countDown();
                        await(releaseBoth);
                        tx.lockThread(siblingId).orElseThrow();
                        return true;
                      }));
      // 双方都拿到同一 Session 的 KEY SHARE + 各自 Thread 的 FOR UPDATE：sibling 不互斥、不 deadlock。
      assertTrue(bothAcquired.await(10, TimeUnit.SECONDS));
      releaseBoth.countDown();
      assertTrue(first.get(10, TimeUnit.SECONDS));
      assertTrue(second.get(10, TimeUnit.SECONDS));
    }
  }

  /** 同 Session 两个 ENTRY materialization：KEY SHARE 锁彼此兼容，新 Thread 各自插入、互不串行化。 */
  @Test
  void concurrentEntryMaterializationsKeyShareTheSameSessionWithoutSerialization()
      throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    CountDownLatch bothAcquired = new CountDownLatch(2);
    CountDownLatch releaseBoth = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<UUID> first =
          executor.submit(
              () ->
                  materializeEntry(
                      baseline.sessionId(), baseline.rootEntryId(), bothAcquired, releaseBoth));
      Future<UUID> second =
          executor.submit(
              () ->
                  materializeEntry(
                      baseline.sessionId(), baseline.rootEntryId(), bothAcquired, releaseBoth));
      // 双方都拿到同一 Session 的 KEY SHARE 并各自插入新 Thread：若误用 FOR UPDATE 锁 Session，第二个事务会在此处死等。
      assertTrue(bothAcquired.await(10, TimeUnit.SECONDS));
      releaseBoth.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    // 两个新 Thread 都落库，且属于同一 Session。
    assertEquals(3, store.transaction(tx -> tx.listThreadsBySession(baseline.sessionId())).size());
  }

  private UUID materializeEntry(
      UUID sessionId, UUID rootEntryId, CountDownLatch bothAcquired, CountDownLatch releaseBoth) {
    return store.transaction(
        tx -> {
          // ENTRY 新建路径：KEY SHARE Session（不 FOR UPDATE），新 Thread 直接指向既有 Entry（不复制 Entry）。
          tx.lockSessionForKeyShare(sessionId).orElseThrow();
          UUID threadId = tx.nextId();
          tx.insertThread(thread(threadId, sessionId, rootEntryId));
          bothAcquired.countDown();
          await(releaseBoth);
          // 收尾：阻塞式取回自己的 Thread 行锁（与接受路径的锁持有语义一致）。
          tx.lockThread(threadId).orElseThrow();
          return threadId;
        });
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
  void deepDeleteWaitsForModelCallbackBeforeLockingItsWork() throws Exception {
    // Model callback 的规范锁序是 Model -> Work；深删必须先等 Model，再锁 Work，不能反向形成数据库死锁。
    TurnBaseline baseline = seedTurnBaseline(store);
    UUID modelId = id(100L);
    WorkTarget target = new WorkTarget(WorkTargetType.MODEL, modelId);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.insertModelInvocation(
              modelInvocation(
                  modelId,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
          tx.requestWork(target, T1);
        });
    ClaimedWork claim =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.MODEL, T1, "lease-delete", T5))
            .orElseThrow();

    CountDownLatch modelLocked = new CountDownLatch(1);
    CountDownLatch continueToWork = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> callback =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.lockModelInvocation(modelId).orElseThrow();
                        modelLocked.countDown();
                        await(continueToWork);
                        return tx.lockClaimedWork(claim, T2).isPresent();
                      }));
      assertTrue(modelLocked.await(10, TimeUnit.SECONDS));

      Future<Boolean> deletion =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.lockThread(baseline.threadId()).orElseThrow();
                        return tx.deleteThreads(List.of(baseline.threadId())) == 1;
                      }));
      try {
        assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS));
      } finally {
        continueToWork.countDown();
      }

      assertTrue(callback.get(10, TimeUnit.SECONDS));
      assertTrue(deletion.get(10, TimeUnit.SECONDS));
    }

    assertTrue(store.transaction(tx -> tx.findThread(baseline.threadId())).isEmpty());
    assertTrue(store.transaction(tx -> tx.findModelInvocation(modelId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findWork(target)).isEmpty());
  }

  @Test
  void reversedConcurrentToolBatchesUseOneCanonicalDatabaseLockOrder() throws Exception {
    TurnBaseline baseline = seedTurnBaseline(store);
    // SUCCEEDED assistant 由同一 request/response 经 mapper 派生（strict attach 校验要求全等）。
    // 请求带 bash binding，使 assistant ToolCall renderer 与 ToolInvocation binding 全等。
    var request = succeededRequest();
    var response = assistantResponse("call-1", "call-2");
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantEntryId =
        insertChildEntry(
            store, baseline.sessionId(), userEntryId, mappedAssistant(request, response));
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.insertModelInvocation(
              new ModelInvocation(
                  id(1L),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  request,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  T1,
                  T1));
          ModelInvocation model = tx.lockModelInvocation(id(1L)).orElseThrow();
          tx.updateModelInvocation(model.beginDispatch(T1));
          model = tx.lockModelInvocation(id(1L)).orElseThrow();
          tx.updateModelInvocation(model.markRunning(T1));
          model = tx.lockModelInvocation(id(1L)).orElseThrow();
          tx.updateModelInvocation(model.succeed(response, T1));
          model = tx.lockModelInvocation(id(1L)).orElseThrow();
          tx.updateModelInvocation(model.attachResultEntry(assistantEntryId, T1));
        });
    ToolInvocation ordinal0 =
        toolInvocation(
            id(10L), id(1L), assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, T2);
    ToolInvocation ordinal1 =
        toolInvocation(
            id(11L), id(1L), assistantEntryId, 1, "call-2", ToolInvocationStatus.READY, T2);

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
      UUID firstThreadId, UUID secondThreadId, CountDownLatch firstLocks) {
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

package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.Optional;

/** Work mailbox protocol: target existence, lost wake, due ordering, lease fencing and rollback. */
class InMemoryWorkTest {

  private InMemoryHarnessStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
  }

  private ClaimedWork claimNext(WorkTargetType type, Instant now) {
    return store
        .transaction(tx -> tx.claimNextWork(type, now, "lease-token", now.plusSeconds(60)))
        .orElseThrow();
  }

  @Test
  void requestWorkRequiresAnExistingTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.requestWork(new WorkTarget(WorkTargetType.THREAD, 1), T0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.requestWork(new WorkTarget(WorkTargetType.MODEL, 1), T0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(store, tx -> tx.requestWork(new WorkTarget(WorkTargetType.TOOL, 1), T0)));
  }

  @Test
  void requestWorkInsertsTheInitialWake() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T1));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(1L, work.wakeVersion());
    assertEquals(T1, work.availableAt());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
  }

  @Test
  void requestWorkIncrementsWakeVersionAndPullsAvailableAtForward() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T2));
    inTransaction(store, tx -> tx.requestWork(target, T0));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertEquals(T0, work.availableAt());
  }

  @Test
  void claimNextWorkConsidersOnlyTheRequestedTargetType() {
    TurnBaseline baseline = seedTurnBaseline(store);
    inTransaction(
        store,
        tx ->
            tx.insertModelInvocation(
                modelInvocation(
                    1,
                    baseline.threadId(),
                    baseline.turnStartEntryId(),
                    baseline.turnStartEntryId(),
                    ModelInvocationStatus.READY,
                    null,
                    T1)));
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, 1);
    inTransaction(
        store,
        tx -> {
          tx.requestWork(threadTarget, T0);
          tx.requestWork(modelTarget, T0);
        });
    ClaimedWork claimed = claimNext(WorkTargetType.THREAD, T1);
    assertEquals(threadTarget, claimed.target());
    Work modelWork = store.transaction(tx -> tx.findWork(modelTarget)).orElseThrow();
    assertNull(modelWork.leaseToken());
  }

  @Test
  void claimNextWorkIsOrderedByAvailableAtThenTargetId() {
    Baseline baseline = seedThreadBaseline(store);
    long thread2 =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, baseline.rootEntryId()));
              return id;
            });
    long thread3 =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, baseline.rootEntryId()));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread2), T2);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread3), T1);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), T1);
        });
    assertEquals(baseline.threadId(), claimNext(WorkTargetType.THREAD, T3).target().id());
    assertEquals(thread3, claimNext(WorkTargetType.THREAD, T3).target().id());
    assertEquals(thread2, claimNext(WorkTargetType.THREAD, T3).target().id());
    assertTrue(
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T3, "token", T4))
            .isEmpty());
  }

  @Test
  void claimNextWorkRequiresAvailableTimeAndFreeOrExpiredLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T2));
    // not available yet
    assertTrue(
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "token-1", T4))
            .isEmpty());
    // claim at availability time
    ClaimedWork claimed =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T2, "token-1", T4))
            .orElseThrow();
    // active lease blocks another claim
    assertTrue(
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T3, "token-2", T4))
            .isEmpty());
    // lease expired at now -> reclaimable with a fresh token
    ClaimedWork reclaimed =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T4, "token-3", T5))
            .orElseThrow();
    assertNotEquals(claimed.leaseToken(), reclaimed.leaseToken());
    assertEquals(1L, reclaimed.claimedWakeVersion());
  }

  @Test
  void lostWakeSurvivesOldCompletion() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    // new wake arrives while the lease is held
    inTransaction(store, tx -> tx.requestWork(target, T2));
    // stale completion keeps the row and only clears the lease
    Optional<Work> kept = store.transaction(tx -> tx.completeWork(claim, T2));
    assertTrue(kept.isPresent());
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
    // the newer wake remains claimable
    assertEquals(2L, claimNext(WorkTargetType.THREAD, T2).claimedWakeVersion());
  }

  @Test
  void completeWorkDeletesTheRowWhenNoNewerWakeExists() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    assertTrue(store.transaction(tx -> tx.completeWork(claim, T2)).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isEmpty()));
  }

  @Test
  void completeWorkOnMissingRowThrowsLostOwnership() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    store.transaction(tx -> tx.completeWork(claim, T2));
    // the row is gone; ownership cannot be verified -> lost ownership, never silently accepted
    assertThrows(
        IllegalStateException.class, () -> store.transaction(tx -> tx.completeWork(claim, T2)));
    // a claim for a target that never had work is lost ownership too
    WorkTarget never = new WorkTarget(WorkTargetType.THREAD, 42);
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                tx ->
                    tx.completeWork(
                        new ClaimedWork(never, 1L, "lease-token", T2.plusSeconds(60)), T2)));
  }

  @Test
  void completeWorkRejectsStaleTokenAndFutureClaimedVersion() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx ->
                    tx.completeWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong-token", claim.leaseUntil()),
                        T2)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx ->
                    tx.completeWork(
                        new ClaimedWork(
                            target,
                            claim.claimedWakeVersion() + 1,
                            claim.leaseToken(),
                            claim.leaseUntil()),
                        T2)));
  }

  @Test
  void renewWorkExtendsTheActiveLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    Instant newUntil = T2.plusSeconds(120);
    inTransaction(store, tx -> tx.renewWork(claim, T2, newUntil));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(newUntil, work.leaseUntil());
    assertEquals(claim.leaseToken(), work.leaseToken());
  }

  @Test
  void renewWorkRejectsStaleTokenStaleLeaseAndNonExtendingUntil() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    // wrong token
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.renewWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong", claim.leaseUntil()),
                        T2,
                        T3)));
    // must strictly extend the current lease
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.renewWork(claim, T2, claim.leaseUntil())));
    // renew after the lease has expired
    Instant afterExpiry = claim.leaseUntil().plusSeconds(5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.renewWork(claim, afterExpiry, afterExpiry.plusSeconds(10))));
  }

  @Test
  void rescheduleWorkMovesAvailableAtAndClearsTheLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    inTransaction(store, tx -> tx.rescheduleWork(claim, T2, T4));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(T4, work.availableAt());
    assertEquals(1L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
  }

  @Test
  void rescheduleWithStaleClaimPullsAvailableAtForwardAndKeepsTheNewWake() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    inTransaction(store, tx -> tx.requestWork(target, T4));
    // stale claim reschedule: lease cleared, wakeVersion 2 kept, availableAt = min(current,
    // requested)
    inTransaction(store, tx -> tx.rescheduleWork(claim, T2, T3));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertEquals(T0, work.availableAt());
    assertNull(work.leaseToken());
  }

  @Test
  void rescheduleWorkRejectsStaleToken() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.rescheduleWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong", claim.leaseUntil()),
                        T2,
                        T3)));
  }

  @Test
  void workMutationsRollBackWithTheTransaction() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(store, tx -> tx.requestWork(target, T0));
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.claimNextWork(WorkTargetType.THREAD, T1, "token-1", T2.plusSeconds(60));
                  throw new IllegalStateException("boom");
                }));
    // the lease from the failed transaction is not visible
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
    // the same row can be claimed afterwards
    assertTrue(
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "token-2", T2.plusSeconds(60)))
            .isPresent());
  }

  @Test
  void findAndLockWorkReturnEmptyForMissingTargets() {
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, 1);
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.lockWork(target).isEmpty()));
  }
}

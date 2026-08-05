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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.Optional;

/** Work mailbox protocol: target existence, lost wake, due ordering, lease fencing and rollback. */
public abstract class HarnessStoreWorkContract {

  private HarnessStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  abstract HarnessStore createStore();

  private ClaimedWork claimNext(WorkTargetType type, Instant now) {
    return store
        .transaction(tx -> tx.claimNextWork(type, now, "lease-token", now.plusSeconds(60)))
        .orElseThrow();
  }

  private void requestWork(WorkTarget target, Instant requestedAt) {
    inTransaction(
        store,
        tx -> {
          lockWorkOwner(tx, target);
          tx.requestWork(target, requestedAt);
        });
  }

  private boolean deleteWork(WorkTarget target) {
    return store.transaction(
        tx -> {
          lockWorkOwner(tx, target);
          return tx.deleteWork(target);
        });
  }

  private static void lockWorkOwner(HarnessStore.Transaction tx, WorkTarget target) {
    long threadId =
        switch (target.type()) {
          case THREAD -> target.id();
          case MODEL -> tx.findModelInvocation(target.id()).orElseThrow().threadId();
          case TOOL -> {
            long modelInvocationId =
                tx.findToolInvocation(target.id()).orElseThrow().modelInvocationId();
            yield tx.findModelInvocation(modelInvocationId).orElseThrow().threadId();
          }
        };
    tx.lockThread(threadId).orElseThrow();
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
  void requestAndDeleteWorkRequireTheOwningThreadLock() {
    TurnBaseline baseline = seedTurnBaseline(store);
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
        });
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, 1);

    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.requestWork(threadTarget, T0)));
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.requestWork(modelTarget, T0)));

    requestWork(threadTarget, T0);
    requestWork(modelTarget, T0);
    assertThrows(
        IllegalStateException.class, () -> store.transaction(tx -> tx.deleteWork(threadTarget)));
    assertThrows(
        IllegalStateException.class, () -> store.transaction(tx -> tx.deleteWork(modelTarget)));
    assertTrue(deleteWork(threadTarget));
    assertTrue(deleteWork(modelTarget));
  }

  @Test
  void lockOrderRejectsThreadAfterModelAndDescendingWorkTargets() {
    TurnBaseline baseline = seedTurnBaseline(store);
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
        });
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.lockModelInvocation(1).orElseThrow();
                  tx.lockThread(baseline.threadId());
                  return null;
                }));

    long higherThreadId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, baseline.rootEntryId()));
              return id;
            });
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.lockThread(higherThreadId).orElseThrow();
          assertEquals(baseline.threadId(), tx.lockThread(baseline.threadId()).orElseThrow().id());
          return null;
        });
    store.transaction(
        tx -> {
          tx.lockThread(higherThreadId).orElseThrow();
          assertThrows(IllegalStateException.class, () -> tx.lockThread(baseline.threadId()));
          return null;
        });
    WorkTarget lower = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget higher = new WorkTarget(WorkTargetType.THREAD, higherThreadId);
    requestWork(lower, T0);
    requestWork(higher, T0);
    store.transaction(
        tx -> {
          tx.lockWork(higher).orElseThrow();
          assertThrows(IllegalStateException.class, () -> tx.lockWork(lower));
          return null;
        });
  }

  @Test
  void workTemporalArgumentsRejectSubMillisecondPrecision() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    assertThrows(IllegalArgumentException.class, () -> requestWork(target, T0.plusNanos(1)));

    requestWork(target, T0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, T1.plusNanos(1), "token-a", T5)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "token-b", T5.plusNanos(1))));

    ClaimedWork claim =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "token-c", T5))
            .orElseThrow();
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.completeWork(claim, T2.plusNanos(1))));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.lockClaimedWork(claim, T2.plusNanos(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.renewWork(claim, T2, T5.plusSeconds(1).plusNanos(1));
                  return null;
                }));
    requestWork(target, T0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.rescheduleWork(claim, T2, T3.plusNanos(1));
                  return null;
                }));
    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claim, T2)).isPresent());
  }

  @Test
  void requestWorkInsertsTheInitialWake() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target, T1);
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
    requestWork(target, T2);
    requestWork(target, T0);
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertEquals(T0, work.availableAt());
  }

  @Test
  void claimNextWorkConsidersOnlyTheRequestedTargetType() {
    TurnBaseline baseline = seedTurnBaseline(store);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, 1);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
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
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.lockThread(thread2).orElseThrow();
          tx.lockThread(thread3).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), T1);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread2), T2);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread3), T1);
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
    requestWork(target, T2);
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
    requestWork(target, T0);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    // new wake arrives while the lease is held
    requestWork(target, T2);
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
    requestWork(target, T0);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    assertTrue(store.transaction(tx -> tx.completeWork(claim, T2)).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isEmpty()));
  }

  @Test
  void completeWorkOnMissingRowThrowsLostOwnership() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target, T0);
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
    requestWork(target, T0);
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
    requestWork(target, T0);
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
    requestWork(target, T0);
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
    requestWork(target, T0);
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
    requestWork(target, T0);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    requestWork(target, T4);
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
    requestWork(target, T0);
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
    requestWork(target, T0);
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

  @Test
  void lockClaimedWorkReturnsTheCurrentWorkWhileOwnershipIsValid() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target, T0);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    Work work = store.transaction(tx -> tx.lockClaimedWork(claim, T2)).orElseThrow();
    assertEquals(target, work.target());
    assertEquals(1L, work.wakeVersion());
    assertEquals(claim.leaseToken(), work.leaseToken());
    assertEquals(claim.leaseUntil(), work.leaseUntil());
  }

  @Test
  void lockClaimedWorkReturnsEmptyOnMissingRowStaleTokenAndExpiredLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target, T0);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    // a claim for a target that never had work is lost ownership
    WorkTarget never = new WorkTarget(WorkTargetType.THREAD, 42);
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.lockClaimedWork(
                        new ClaimedWork(never, 1L, "lease-token", T2.plusSeconds(60)), T2))
            .isEmpty());
    // wrong token is stale ownership
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.lockClaimedWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong-token", claim.leaseUntil()),
                        T2))
            .isEmpty());
    // lease already expired at now is stale ownership
    assertTrue(
        store
            .transaction(tx -> tx.lockClaimedWork(claim, claim.leaseUntil().plusSeconds(1)))
            .isEmpty());
    // the row is untouched by failed ownership checks
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(claim.leaseToken(), work.leaseToken());
    assertEquals(claim.leaseUntil(), work.leaseUntil());
  }

  @Test
  void lockClaimedWorkSurvivesWakeVersionGrowth() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target, T0);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    // a newer wake arrives while the lease is held
    requestWork(target, T4);
    Work work = store.transaction(tx -> tx.lockClaimedWork(claim, T2)).orElseThrow();
    // ownership is not lost: the returned row carries the newer wakeVersion while availableAt
    // stays the earliest requested time (requestWork pulls forward to the minimum)
    assertEquals(2L, work.wakeVersion());
    assertEquals(claim.leaseToken(), work.leaseToken());
    assertEquals(T0, work.availableAt());
  }

  @Test
  void deleteWorkRemovesTheRowWithoutNeedingAClaimToken() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target, T0);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, T1);
    assertTrue(deleteWork(target));
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isEmpty()));
    // the deleted row fences the old callback: every claim-based mutation is lost ownership
    assertThrows(
        IllegalStateException.class, () -> store.transaction(tx -> tx.completeWork(claim, T2)));
    assertThrows(
        IllegalStateException.class, () -> inTransaction(store, tx -> tx.renewWork(claim, T2, T3)));
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.rescheduleWork(claim, T2, T3)));
  }

  @Test
  void deleteWorkReturnsFalseForMissingTargetsAndRollsBackWithTheTransaction() {
    WorkTarget missing = new WorkTarget(WorkTargetType.THREAD, 42);
    assertFalse(store.<Boolean>transaction(tx -> tx.deleteWork(missing)));
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target, T0);
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.deleteWork(target);
                  throw new IllegalStateException("boom");
                }));
    // the deletion from the failed transaction is not visible
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isPresent()));
  }
}

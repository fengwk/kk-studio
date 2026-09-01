package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Work mailbox 协议：target 存在性、lost wake、due 排序、lease fence 与 rollback。 */
public abstract class HarnessStoreWorkContract {

  protected HarnessStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  abstract HarnessStore createStore();

  protected ClaimedWork claimNext(WorkTargetType type, Instant now) {
    return store
        .transaction(tx -> tx.claimNextWork(type, now, "lease-token", now.plusSeconds(60)))
        .orElseThrow();
  }

  private void requestWork(WorkTarget target, Instant requestedAt) {
    requestWork(target, requestedAt, null);
  }

  private void requestWork(
      WorkTarget target, Instant requestedAt, EnvironmentName requiredEnvironmentName) {
    inTransaction(
        store,
        tx -> {
          lockWorkOwner(tx, target);
          tx.requestWork(target, requestedAt, requiredEnvironmentName);
        });
  }

  protected record SeededTool(UUID threadId, UUID modelId, UUID toolId) {}

  protected SeededTool seedTool(HarnessStore store) {
    TurnBaseline baseline = seedTurnBaseline(store);
    ModelRequestSpec requestSpec = succeededRequest();
    ProviderResponse response = StoreTestSupport.assistantResponse("call-1");
    return store.transaction(
        tx -> {
          UUID modelId = tx.nextId();
          UUID toolId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  baseline.sessionId(),
                  baseline.turnStartEntryId(),
                  StoreTestSupport.userMessagePayload(),
                  T1));
          tx.insertEntry(
              new Entry(
                  assistantEntryId,
                  baseline.sessionId(),
                  userEntryId,
                  mappedAssistant(requestSpec, response),
                  T1));
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.insertModelInvocation(
              new ModelInvocation(
                  modelId,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  requestSpec,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  T1,
                  T1));
          ModelInvocation model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.beginDispatch(T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.markRunning(T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.succeed(response, T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.attachResultEntry(assistantEntryId, T1));
          ToolInvocation tool =
              toolInvocation(
                  toolId, modelId, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, T1);
          tx.insertToolInvocations(List.of(tool));
          return new SeededTool(baseline.threadId(), modelId, toolId);
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
    UUID threadId =
        switch (target.type()) {
          case THREAD -> target.id();
          case MODEL -> tx.findModelInvocation(target.id()).orElseThrow().threadId();
          case TOOL -> {
            UUID modelInvocationId =
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
                store,
                tx -> tx.requestWork(new WorkTarget(WorkTargetType.THREAD, TestIds.id(1)), T0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> tx.requestWork(new WorkTarget(WorkTargetType.MODEL, TestIds.id(1)), T0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> tx.requestWork(new WorkTarget(WorkTargetType.TOOL, TestIds.id(1)), T0)));
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
                  TestIds.id(1),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, TestIds.id(1));

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
                  TestIds.id(1),
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
                  tx.lockModelInvocation(TestIds.id(1)).orElseThrow();
                  tx.lockThread(baseline.threadId());
                  return null;
                }));

    UUID higherThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
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
                  TestIds.id(1),
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, TestIds.id(1));
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
    UUID thread2 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
              return id;
            });
    UUID thread3 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
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
    // 尚未可用
    assertTrue(
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "token-1", T4))
            .isEmpty());
    // 在可用时刻 claim
    ClaimedWork claimed =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T2, "token-1", T4))
            .orElseThrow();
    // 活跃 lease 阻塞其他 claim
    assertTrue(
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T3, "token-2", T4))
            .isEmpty());
    // lease 在 now 已过期，可用新 token reclaim
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
    // 在 lease 持有期间发生新的 wake
    requestWork(target, T2);
    // 过期的 completion 仅清掉 lease，保留 row
    Optional<Work> kept = store.transaction(tx -> tx.completeWork(claim, T2));
    assertTrue(kept.isPresent());
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
    // 较新的 wake 仍可被 claim
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
    // row 已消失，ownership 无法验证，属于 lost ownership，绝不静默接受
    assertThrows(
        IllegalStateException.class, () -> store.transaction(tx -> tx.completeWork(claim, T2)));
    // 对从未存在 work 的 target 的 claim 也是 lost ownership
    WorkTarget never = new WorkTarget(WorkTargetType.THREAD, TestIds.id(42));
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
    // 错误的 token
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
    // 必须严格延长当前 lease
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.renewWork(claim, T2, claim.leaseUntil())));
    // lease 过期后再 renew
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
    // 过期 claim 的 reschedule：lease 清掉，保留 wakeVersion 2，availableAt = min(当前,
    // 请求时间)
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
    // 失败事务中的 lease 不可见
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
    // 同一 row 之后仍可被 claim
    assertTrue(
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "token-2", T2.plusSeconds(60)))
            .isPresent());
  }

  @Test
  void findAndLockWorkReturnEmptyForMissingTargets() {
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, TestIds.id(1));
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
    // 对从未存在 work 的 target 的 claim 即 lost ownership
    WorkTarget never = new WorkTarget(WorkTargetType.THREAD, TestIds.id(42));
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.lockClaimedWork(
                        new ClaimedWork(never, 1L, "lease-token", T2.plusSeconds(60)), T2))
            .isEmpty());
    // 错误 token 是 stale ownership
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.lockClaimedWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong-token", claim.leaseUntil()),
                        T2))
            .isEmpty());
    // lease 在 now 已过期属 stale ownership
    assertTrue(
        store
            .transaction(tx -> tx.lockClaimedWork(claim, claim.leaseUntil().plusSeconds(1)))
            .isEmpty());
    // 失败的 ownership 检查不会改动 row
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
    // lease 持有期间发生新的 wake
    requestWork(target, T4);
    Work work = store.transaction(tx -> tx.lockClaimedWork(claim, T2)).orElseThrow();
    // ownership 未丢失：返回的 row 带有新的 wakeVersion，availableAt 仍是请求时间中的最早者
    // （requestWork 会向前拉到最小值）
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
    // 被删除的 row 对旧 callback 形成 fence：所有基于 claim 的 mutation 都成为 lost ownership
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
    WorkTarget missing = new WorkTarget(WorkTargetType.THREAD, TestIds.id(42));
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
    // 失败事务中的删除不可见
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isPresent()));
  }

  @Test
  void requestWorkFreezesAndPreservesEnvironmentAffinity() {
    SeededTool seeded = seedTool(store);
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, seeded.toolId());
    EnvironmentName env1 = new EnvironmentName("env-1");
    EnvironmentName env2 = new EnvironmentName("env-2");

    // 1. TOOL target 初始化冻结亲和性
    requestWork(toolTarget, T1, env1);
    Work work = store.transaction(tx -> tx.findWork(toolTarget)).orElseThrow();
    assertEquals(env1, work.requiredEnvironmentName());
    assertEquals(1L, work.wakeVersion());

    // 2. 无参 requestWork 保留已冻结亲和性
    requestWork(toolTarget, T2);
    work = store.transaction(tx -> tx.findWork(toolTarget)).orElseThrow();
    assertEquals(env1, work.requiredEnvironmentName());
    assertEquals(2L, work.wakeVersion());

    // 3. 带相同环境名 requestWork 保留亲和性
    requestWork(toolTarget, T3, env1);
    work = store.transaction(tx -> tx.findWork(toolTarget)).orElseThrow();
    assertEquals(env1, work.requiredEnvironmentName());
    assertEquals(3L, work.wakeVersion());

    // 4. 冲突的环境名被拒绝
    assertThrows(IllegalArgumentException.class, () -> requestWork(toolTarget, T4, env2));

    // 5. 非 TOOL target 拒绝非空环境亲和性
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, seeded.threadId());
    assertThrows(IllegalArgumentException.class, () -> requestWork(threadTarget, T1, env1));
  }

  @Test
  void claimNextWorkReturnsClaimedWorkWithEnvironmentAffinity() {
    SeededTool seeded = seedTool(store);
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, seeded.toolId());
    EnvironmentName env = new EnvironmentName("env-1");
    requestWork(toolTarget, T1, env);

    ClaimedWork claimed = claimNext(WorkTargetType.TOOL, T2);
    assertEquals(toolTarget, claimed.target());
    assertEquals(env, claimed.requiredEnvironmentName());
  }
}

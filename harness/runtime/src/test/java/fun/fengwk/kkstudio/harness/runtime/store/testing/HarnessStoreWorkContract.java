package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.store.EnvironmentToolWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.PendingEnvironmentWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Work mailbox 协议：target 存在性、立即唤醒、due 排序、lease fence 与 rollback。
 *
 * <p>所有时间断言都表达为「权威时间域」不变量：内存实现的权威时间就是调用方传入的 {@code now}（即 {@link #authorityNow()}
 * 返回的测试时钟），生产实现的权威时间是数据库时钟。契约不假设任何 JVM 绝对时刻，因此 accept（{@link
 * HarnessStore.Transaction#requestWork}）总是「立即」，而未来重试只能通过 {@link
 * HarnessStore.Transaction#rescheduleWork} 的相对 {@link Duration} 表达。
 */
public abstract class HarnessStoreWorkContract {

  /**
   * claim / renew 使用的标准租约时长。
   *
   * <p>契约只约束「lease 覆盖权威时间 + duration」，不约束绝对 deadline：生产权威时间是数据库时钟，测试不得用 JVM 绝对时间反推。
   */
  protected static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

  protected HarnessStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  abstract HarnessStore createStore();

  /**
   * 实现用于 Work 排期的权威时间域「此刻」快照：内存实现返回测试注入的时钟（它同时就是调用方传入的 {@code now}），生产实现返回数据库时钟。
   *
   * <p>契约只把它当作该时间域的参照，既不假设它等于任何 JVM 绝对时刻，也不假设它能被 JVM 侧推进。
   */
  protected abstract Instant authorityNow();

  /**
   * 让 target 当前 lease 在各自权威时间域内确定过期。
   *
   * <p>PostgreSQL 直接改写持久化行的 lease deadline，内存实现改写内存 lease deadline；两者都不依赖 JVM 与数据库的时钟关系。
   */
  protected abstract void expireLease(WorkTarget target);

  /**
   * 让 target 当前 Work 在各自权威时间域内确定 due，且不改动 wakeVersion 与 lease。
   *
   * <p>PostgreSQL 直接改写持久化行的 available_at，内存实现改写内存 available_at；两者都不依赖 JVM 与数据库的时钟关系。
   */
  protected abstract void forceWorkAvailable(WorkTarget target);

  /**
   * 让 target 的 available_at 落在权威时间域内的未来 {@code delay} 之后（验证未到期 Work 不进入等待环境投影）。
   *
   * <p>PostgreSQL 直接改写持久化行的 available_at，内存实现改写内存 available_at；两者都不依赖 JVM 与数据库的时钟关系。
   */
  protected abstract void forceWorkAvailableAfter(WorkTarget target, Duration delay);

  /**
   * 为环境播种「任意节点持有有效 READY 连接租约」事实：环境已上线，其待领取工具有可路由的承接节点。
   *
   * <p>实现必须在各自权威时间域内给出租约（远长于测试跨度）。内存实现用 READY 租约谓词表达，生产实现改写 {@code environment_connection} 行。
   */
  protected abstract void seedReadyEnvironmentLease(EnvironmentId environmentId);

  /** 让环境的 READY 连接租约在权威时间域内失效（自然到期或离开 READY），表征环境离线。 */
  protected abstract void expireReadyEnvironmentLease(EnvironmentId environmentId);

  protected ClaimedWork claimNext(WorkTargetType type, Instant now) {
    return store
        .transaction(tx -> tx.claimNextWork(type, now, "lease-token", CLAIM_LEASE))
        .orElseThrow();
  }

  /** 在权威时间域「此刻」为 target 请求一次立即 wake（accept 路径）。 */
  private void requestWork(WorkTarget target) {
    requestWork(target, null);
  }

  private void requestWork(WorkTarget target, EnvironmentId requiredEnvironmentId) {
    requestWorkAt(target, authorityNow(), requiredEnvironmentId);
  }

  /** 用显式 now 调用 requestWork，用于毫秒精度等边界断言。 */
  private void requestWorkAt(WorkTarget target, Instant now) {
    requestWorkAt(target, now, null);
  }

  private void requestWorkAt(WorkTarget target, Instant now, EnvironmentId requiredEnvironmentId) {
    inTransaction(
        store,
        tx -> {
          lockWorkOwner(tx, target);
          tx.requestWork(target, now, requiredEnvironmentId);
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
                tx ->
                    tx.requestWork(
                        new WorkTarget(WorkTargetType.THREAD, TestIds.id(1)), authorityNow())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.requestWork(
                        new WorkTarget(WorkTargetType.MODEL, TestIds.id(1)), authorityNow())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.requestWork(
                        new WorkTarget(WorkTargetType.TOOL, TestIds.id(1)), authorityNow())));
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
        () -> inTransaction(store, tx -> tx.requestWork(threadTarget, authorityNow())));
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.requestWork(modelTarget, authorityNow())));

    requestWork(threadTarget);
    requestWork(modelTarget);
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
              tx.insertThread(
                  StoreTestSupport.siblingRoot(
                      id, baseline.sessionId(), baseline.rootEntryId(), "sibling"));
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
    requestWork(lower);
    requestWork(higher);
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
    assertThrows(
        IllegalArgumentException.class, () -> requestWorkAt(target, authorityNow().plusNanos(1)));

    requestWork(target);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD,
                        authorityNow().plusNanos(1),
                        "token-a",
                        CLAIM_LEASE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD,
                        authorityNow(),
                        "token-b",
                        CLAIM_LEASE.plusNanos(1))));

    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-c", CLAIM_LEASE))
            .orElseThrow();
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.completeWork(claim, authorityNow().plusNanos(1))));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.lockClaimedWork(claim, authorityNow().plusNanos(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.renewWork(claim, authorityNow(), CLAIM_LEASE.plusNanos(1));
                  return null;
                }));
    requestWork(target);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.rescheduleWork(claim, authorityNow(), Duration.ofMillis(1).plusNanos(1));
                  return null;
                }));
    // 负 delay 必须被拒绝，且失败不改变 lease（随后仍可锁定）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.rescheduleWork(claim, authorityNow(), Duration.ofMillis(-1));
                  return null;
                }));
    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claim, authorityNow())).isPresent());
  }

  /** 零 delay 是合法的「立即重排」：清除 lease 并把 availableAt 设为权威此刻，随即可以 claim。 */
  @Test
  void rescheduleWorkAcceptsZeroDelayAsImmediate() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    inTransaction(store, tx -> tx.rescheduleWork(claim, authorityNow(), Duration.ZERO));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(1L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertEquals(1L, claimNext(WorkTargetType.THREAD, authorityNow()).claimedWakeVersion());
  }

  @Test
  void requestWorkInsertsAnImmediatelyAvailableInitialWake() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(1L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
    // accept 表示「立即」：无需 JVM 侧推进时钟，权威时间域此刻即可 claim。
    assertEquals(1L, claimNext(WorkTargetType.THREAD, authorityNow()).claimedWakeVersion());
  }

  @Test
  void requestWorkIncrementsWakeVersionAndPullsAvailableAtForward() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    // 一次相对延迟的重试把 Work 推到权威时间之后：此刻不可 claim。
    inTransaction(store, tx -> tx.rescheduleWork(claim, authorityNow(), Duration.ofHours(1)));
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-x", CLAIM_LEASE))
            .isEmpty());
    // 新的 wake 仍表示「立即」：把 availableAt 拉回权威时间并递增 wakeVersion。
    requestWork(target);
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertEquals(2L, claimNext(WorkTargetType.THREAD, authorityNow()).claimedWakeVersion());
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
          tx.requestWork(threadTarget, authorityNow());
          tx.requestWork(modelTarget, authorityNow());
        });
    ClaimedWork claimed = claimNext(WorkTargetType.THREAD, authorityNow());
    assertEquals(threadTarget, claimed.target());
    Work modelWork = store.transaction(tx -> tx.findWork(modelTarget)).orElseThrow();
    assertNull(modelWork.leaseToken());
  }

  /**
   * claim 候选按 (availableAt, targetId) 升序确定性选取。
   *
   * <p>三个 Work 都先立即 wake，再各自用不同相对 delay 重排，从而在权威时间域内构造确定的不同 availableAt；覆盖「未来重试」 不被跳过。
   */
  @Test
  void claimNextWorkIsOrderedByAvailableAtThenTargetId() {
    Baseline baseline = seedThreadBaseline(store);
    UUID thread2 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.siblingRoot(
                      id, baseline.sessionId(), baseline.rootEntryId(), "sibling-2"));
              return id;
            });
    UUID thread3 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.siblingRoot(
                      id, baseline.sessionId(), baseline.rootEntryId(), "sibling-3"));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.lockThread(thread2).orElseThrow();
          tx.lockThread(thread3).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), authorityNow());
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread2), authorityNow());
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread3), authorityNow());
        });
    ClaimedWork a = claimNext(WorkTargetType.THREAD, authorityNow());
    ClaimedWork b = claimNext(WorkTargetType.THREAD, authorityNow());
    ClaimedWork c = claimNext(WorkTargetType.THREAD, authorityNow());
    inTransaction(store, tx -> tx.rescheduleWork(a, authorityNow(), Duration.ofSeconds(30)));
    inTransaction(store, tx -> tx.rescheduleWork(b, authorityNow(), Duration.ofSeconds(10)));
    inTransaction(store, tx -> tx.rescheduleWork(c, authorityNow(), Duration.ofSeconds(20)));

    Instant due = authorityNow().plus(Duration.ofSeconds(30));
    assertEquals(b.target(), claimNext(WorkTargetType.THREAD, due).target());
    assertEquals(c.target(), claimNext(WorkTargetType.THREAD, due).target());
    assertEquals(a.target(), claimNext(WorkTargetType.THREAD, due).target());
    assertTrue(
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, due, "token", CLAIM_LEASE))
            .isEmpty());
  }

  @Test
  void claimNextWorkRequiresAvailableTimeAndFreeOrExpiredLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    // 一次 claim + 相对延迟 reschedule 把 Work 推到权威时间之后。
    ClaimedWork scheduled = claimNext(WorkTargetType.THREAD, authorityNow());
    inTransaction(store, tx -> tx.rescheduleWork(scheduled, authorityNow(), Duration.ofHours(1)));
    // 尚未可用
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-1", CLAIM_LEASE))
            .isEmpty());
    // 权威时间域内到期后可 claim
    forceWorkAvailable(target);
    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-1", CLAIM_LEASE))
            .orElseThrow();
    // 活跃 lease 阻塞其他 claim
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-2", CLAIM_LEASE))
            .isEmpty());
    // lease 在权威时间域内过期后，可用新 token reclaim
    expireLease(target);
    ClaimedWork reclaimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-3", CLAIM_LEASE))
            .orElseThrow();
    assertNotEquals(claimed.leaseToken(), reclaimed.leaseToken());
    assertEquals(1L, reclaimed.claimedWakeVersion());
  }

  @Test
  void lostWakeSurvivesOldCompletion() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    // 在 lease 持有期间发生新的 wake
    requestWork(target);
    // 过期的 completion 仅清掉 lease，保留 row
    Optional<Work> kept = store.transaction(tx -> tx.completeWork(claim, authorityNow()));
    assertTrue(kept.isPresent());
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
    // 较新的 wake 仍可被 claim
    assertEquals(2L, claimNext(WorkTargetType.THREAD, authorityNow()).claimedWakeVersion());
  }

  @Test
  void completeWorkDeletesTheRowWhenNoNewerWakeExists() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    assertTrue(store.transaction(tx -> tx.completeWork(claim, authorityNow())).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isEmpty()));
  }

  @Test
  void completeWorkOnMissingRowThrowsLostOwnership() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    store.transaction(tx -> tx.completeWork(claim, authorityNow()));
    // row 已消失，ownership 无法验证，属于 lost ownership，绝不静默接受
    assertThrows(
        IllegalStateException.class,
        () -> store.transaction(tx -> tx.completeWork(claim, authorityNow())));
    // 对从未存在 work 的 target 的 claim 也是 lost ownership
    WorkTarget never = new WorkTarget(WorkTargetType.THREAD, TestIds.id(42));
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                tx ->
                    tx.completeWork(
                        new ClaimedWork(never, 1L, "lease-token", authorityNow().plusSeconds(60)),
                        authorityNow())));
  }

  @Test
  void completeWorkRejectsStaleTokenAndFutureClaimedVersion() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx ->
                    tx.completeWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong-token", claim.leaseUntil()),
                        authorityNow())));
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
                        authorityNow())));
  }

  /** 测试意图：renew 只在目标时点严格晚于现有 lease 时延展 deadline 并保留 token；不缩短（返回 false）且不改动 row。 */
  @Test
  void renewWorkExtendsTheActiveLeaseWithoutShorteningIt() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    Instant before = claim.leaseUntil();

    // 目标时点未超过现有 lease：保持不变并返回 false，避免把更长的 lease 缩短。
    boolean shortened =
        store.transaction(tx -> tx.renewWork(claim, authorityNow(), Duration.ofSeconds(1)));
    assertFalse(shortened);
    assertEquals(before, store.transaction(tx -> tx.findWork(target)).orElseThrow().leaseUntil());

    // 目标时点严格晚于现有 lease：延展并保留 token。
    boolean extended =
        store.transaction(tx -> tx.renewWork(claim, authorityNow(), CLAIM_LEASE.plusSeconds(120)));
    assertTrue(extended);
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertTrue(work.leaseUntil().isAfter(before));
    assertEquals(claim.leaseToken(), work.leaseToken());
  }

  @Test
  void renewWorkRejectsStaleTokenAndExpiredLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
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
                        authorityNow(),
                        CLAIM_LEASE.plusSeconds(60))));
    // lease 在权威时间域内过期后再 renew 是 lost lease
    expireLease(target);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.renewWork(claim, authorityNow(), CLAIM_LEASE.plusSeconds(60))));
  }

  @Test
  void rescheduleWorkSetsAvailableAtToAuthorityTimePlusDelayAndClearsTheLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    Instant before = authorityNow();
    inTransaction(store, tx -> tx.rescheduleWork(claim, before, Duration.ofMinutes(30)));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(1L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
    // availableAt = 权威时间 + delay：严格晚于重排时刻，且权威时间域此刻仍不可 claim。
    assertTrue(work.availableAt().isAfter(before));
    assertTrue(
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token", CLAIM_LEASE))
            .isEmpty());
    // 权威时间域内到期后即可 claim。
    forceWorkAvailable(target);
    assertTrue(
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token", CLAIM_LEASE))
            .isPresent());
  }

  @Test
  void rescheduleWithStaleClaimPullsAvailableAtForwardAndKeepsTheNewWake() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    requestWork(target);
    // 过期 claim 的 reschedule：lease 清掉，保留 wakeVersion 2；availableAt 取 min，不会把更新的 wake 推后。
    inTransaction(store, tx -> tx.rescheduleWork(claim, authorityNow(), Duration.ofMinutes(30)));
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(2L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertEquals(2L, claimNext(WorkTargetType.THREAD, authorityNow()).claimedWakeVersion());
  }

  @Test
  void rescheduleWorkRejectsStaleToken() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.rescheduleWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong", claim.leaseUntil()),
                        authorityNow(),
                        Duration.ofSeconds(1))));
  }

  @Test
  void workMutationsRollBackWithTheTransaction() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-1", CLAIM_LEASE);
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
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, authorityNow(), "token-2", CLAIM_LEASE))
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
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    Work work = store.transaction(tx -> tx.lockClaimedWork(claim, authorityNow())).orElseThrow();
    assertEquals(target, work.target());
    assertEquals(1L, work.wakeVersion());
    assertEquals(claim.leaseToken(), work.leaseToken());
    assertEquals(claim.leaseUntil(), work.leaseUntil());
  }

  @Test
  void lockClaimedWorkReturnsEmptyOnMissingRowStaleTokenAndExpiredLease() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    // 对从未存在 work 的 target 的 claim 即 lost ownership
    WorkTarget never = new WorkTarget(WorkTargetType.THREAD, TestIds.id(42));
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.lockClaimedWork(
                        new ClaimedWork(never, 1L, "lease-token", authorityNow().plusSeconds(60)),
                        authorityNow()))
            .isEmpty());
    // 错误 token 是 stale ownership
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.lockClaimedWork(
                        new ClaimedWork(
                            target, claim.claimedWakeVersion(), "wrong-token", claim.leaseUntil()),
                        authorityNow()))
            .isEmpty());
    // 失败的 ownership 检查不会改动 row
    Work work = store.transaction(tx -> tx.findWork(target)).orElseThrow();
    assertEquals(claim.leaseToken(), work.leaseToken());
    assertEquals(claim.leaseUntil(), work.leaseUntil());
    // lease 在权威时间域内过期属 stale ownership（用实现自己的过期方式，不依赖 JVM 与数据库的时钟关系）
    expireLease(target);
    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claim, authorityNow())).isEmpty());
  }

  @Test
  void lockClaimedWorkSurvivesWakeVersionGrowth() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    // lease 持有期间发生新的 wake
    requestWork(target);
    Work work = store.transaction(tx -> tx.lockClaimedWork(claim, authorityNow())).orElseThrow();
    // ownership 未丢失：返回的 row 带有新的 wakeVersion，availableAt 仍不晚于权威时间（requestWork 是立即唤醒）
    assertEquals(2L, work.wakeVersion());
    assertEquals(claim.leaseToken(), work.leaseToken());
    assertFalse(work.availableAt().isAfter(authorityNow()));
  }

  @Test
  void deleteWorkRemovesTheRowWithoutNeedingAClaimToken() {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
    ClaimedWork claim = claimNext(WorkTargetType.THREAD, authorityNow());
    assertTrue(deleteWork(target));
    assertTrue(store.<Boolean>transaction(tx -> tx.findWork(target).isEmpty()));
    // 被删除的 row 对旧 callback 形成 fence：所有基于 claim 的 mutation 都成为 lost ownership
    assertThrows(
        IllegalStateException.class,
        () -> store.transaction(tx -> tx.completeWork(claim, authorityNow())));
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.renewWork(claim, authorityNow(), CLAIM_LEASE)));
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store, tx -> tx.rescheduleWork(claim, authorityNow(), Duration.ofSeconds(1))));
  }

  @Test
  void deleteWorkReturnsFalseForMissingTargetsAndRollsBackWithTheTransaction() {
    WorkTarget missing = new WorkTarget(WorkTargetType.THREAD, TestIds.id(42));
    assertFalse(store.<Boolean>transaction(tx -> tx.deleteWork(missing)));
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    requestWork(target);
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
    EnvironmentId env1 = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    EnvironmentId env2 = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

    // 1. TOOL target 初始化冻结亲和性
    requestWork(toolTarget, env1);
    Work work = store.transaction(tx -> tx.findWork(toolTarget)).orElseThrow();
    assertEquals(env1, work.requiredEnvironmentId());
    assertEquals(1L, work.wakeVersion());

    // 2. 无参 requestWork 保留已冻结亲和性
    requestWork(toolTarget);
    work = store.transaction(tx -> tx.findWork(toolTarget)).orElseThrow();
    assertEquals(env1, work.requiredEnvironmentId());
    assertEquals(2L, work.wakeVersion());

    // 3. 带相同环境名 requestWork 保留亲和性
    requestWork(toolTarget, env1);
    work = store.transaction(tx -> tx.findWork(toolTarget)).orElseThrow();
    assertEquals(env1, work.requiredEnvironmentId());
    assertEquals(3L, work.wakeVersion());

    // 4. 冲突的环境名被拒绝
    assertThrows(IllegalArgumentException.class, () -> requestWork(toolTarget, env2));

    // 5. 非 TOOL target 拒绝非空环境亲和性
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, seeded.threadId());
    assertThrows(IllegalArgumentException.class, () -> requestWork(threadTarget, env1));
  }

  @Test
  void claimNextWorkReturnsClaimedWorkWithEnvironmentAffinity() {
    SeededTool seeded = seedTool(store);
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, seeded.toolId());
    EnvironmentId env = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    requestWork(toolTarget, env);

    ClaimedWork claimed = claimNext(WorkTargetType.TOOL, authorityNow());
    assertEquals(toolTarget, claimed.target());
    assertEquals(env, claimed.requiredEnvironmentId());
  }

  /** 等待环境投影使用的环境身份；实现必须保证该 environment 注册行存在（生产实现由 createStore 播种）。 */
  protected static final EnvironmentId WAITING_ENVIRONMENT =
      EnvironmentId.parse("33333333-3333-3333-3333-333333333333");

  /** 另一环境身份，用于验证按 (根, 环境) 分组。 */
  protected static final EnvironmentId SECOND_ENVIRONMENT =
      EnvironmentId.parse("44444444-4444-4444-4444-444444444444");

  private static final UUID CURSOR_ZERO = new UUID(0L, 0L);

  protected record SeededTools(UUID threadId, UUID modelId, List<UUID> toolIds) {}

  private List<PendingEnvironmentWaitRow> environmentWaits(Instant afterCreatedAt, UUID afterId) {
    return store.transaction(
        tx -> tx.listPendingEnvironmentWaits(authorityNow(), afterCreatedAt, afterId, 10));
  }

  /** 在同一 root thread 播种 {@code count} 个 READY TOOL 调用，用于验证按根聚合计数。 */
  protected SeededTools seedTools(HarnessStore store, int count) {
    TurnBaseline baseline = seedTurnBaseline(store);
    ModelRequestSpec requestSpec = succeededRequest();
    String[] callIds = new String[count];
    for (int index = 0; index < count; index++) {
      callIds[index] = "call-" + (index + 1);
    }
    ProviderResponse response = StoreTestSupport.assistantResponse(callIds);
    return store.transaction(
        tx -> {
          UUID modelId = tx.nextId();
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
          List<UUID> toolIds = new ArrayList<>();
          List<ToolInvocation> tools = new ArrayList<>();
          for (int index = 0; index < count; index++) {
            UUID toolId = tx.nextId();
            toolIds.add(toolId);
            tools.add(
                toolInvocation(
                    toolId,
                    modelId,
                    assistantEntryId,
                    index,
                    callIds[index],
                    ToolInvocationStatus.READY,
                    T1));
          }
          tx.insertToolInvocations(tools);
          return new SeededTools(baseline.threadId(), modelId, List.copyOf(toolIds));
        });
  }

  /**
   * 测试意图：只有「READY 调用 + 到期且无有效执行 lease 的 TOOL Work + 环境无有效 READY 连接租约」同时成立才进入等待环境投影；环境上线后消失，
   * 自然到期后重新出现（不依赖任何写入事件）。
   */
  @Test
  void environmentWaitTracksOfflineEnvironmentAndLeaseExpiry() {
    SeededTools seeded = seedTools(store, 1);
    UUID toolId = seeded.toolIds().get(0);
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, toolId);
    requestWork(target, WAITING_ENVIRONMENT);

    PendingEnvironmentWaitRow expected =
        new PendingEnvironmentWaitRow(seeded.threadId(), WAITING_ENVIRONMENT, T1, toolId, 1);
    assertEquals(List.of(expected), environmentWaits(Instant.EPOCH, CURSOR_ZERO));

    // 环境持有有效 READY 租约：待领取事实消失。
    seedReadyEnvironmentLease(WAITING_ENVIRONMENT);
    assertEquals(List.of(), environmentWaits(Instant.EPOCH, CURSOR_ZERO));

    // 租约自然到期（无数据库写事件）：重新成为等待环境。
    expireReadyEnvironmentLease(WAITING_ENVIRONMENT);
    assertEquals(List.of(expected), environmentWaits(Instant.EPOCH, CURSOR_ZERO));
  }

  /** 测试意图：未到期的 Work 不是「待领取环境等待」；server-side TOOL（无冻结环境）不计入。 */
  @Test
  void environmentWaitRequiresDueWorkAndFrozenEnvironment() {
    SeededTools dueSeeded = seedTools(store, 1);
    WorkTarget dueTarget = new WorkTarget(WorkTargetType.TOOL, dueSeeded.toolIds().get(0));
    requestWork(dueTarget, WAITING_ENVIRONMENT);
    forceWorkAvailableAfter(dueTarget, Duration.ofMinutes(5));
    assertEquals(List.of(), environmentWaits(Instant.EPOCH, CURSOR_ZERO));

    forceWorkAvailable(dueTarget);
    assertEquals(1, environmentWaits(Instant.EPOCH, CURSOR_ZERO).size());

    // server-side TOOL：没有冻结 required_environment_id，不进入环境等待。
    SeededTools serverSide = seedTools(store, 1);
    requestWork(new WorkTarget(WorkTargetType.TOOL, serverSide.toolIds().get(0)));
    List<PendingEnvironmentWaitRow> waits = environmentWaits(Instant.EPOCH, CURSOR_ZERO);
    assertEquals(1, waits.size());
    assertEquals(dueSeeded.threadId(), waits.get(0).rootThreadId());
  }

  /** 测试意图：按 (真实执行根, 所需环境) 聚合，代表取组内最早 {@code (createdAt, id)}；跨根/跨环境分组不重复，并可按代表游标稳定翻页。 */
  @Test
  void environmentWaitAggregatesByRootAndEnvironmentWithStableCursor() {
    SeededTools first = seedTools(store, 2);
    requestWork(new WorkTarget(WorkTargetType.TOOL, first.toolIds().get(0)), WAITING_ENVIRONMENT);
    requestWork(new WorkTarget(WorkTargetType.TOOL, first.toolIds().get(1)), WAITING_ENVIRONMENT);
    SeededTools second = seedTools(store, 2);
    requestWork(new WorkTarget(WorkTargetType.TOOL, second.toolIds().get(0)), WAITING_ENVIRONMENT);
    requestWork(new WorkTarget(WorkTargetType.TOOL, second.toolIds().get(1)), SECOND_ENVIRONMENT);

    List<PendingEnvironmentWaitRow> all = environmentWaits(Instant.EPOCH, CURSOR_ZERO);
    assertEquals(3, all.size());
    assertEquals(
        new PendingEnvironmentWaitRow(
            first.threadId(), WAITING_ENVIRONMENT, T1, first.toolIds().get(0), 2),
        all.get(0));
    assertEquals(
        new PendingEnvironmentWaitRow(
            second.threadId(), WAITING_ENVIRONMENT, T1, second.toolIds().get(0), 1),
        all.get(1));
    assertEquals(
        new PendingEnvironmentWaitRow(
            second.threadId(), SECOND_ENVIRONMENT, T1, second.toolIds().get(1), 1),
        all.get(2));

    // 同一根不同环境是两个独立分组，不会合并；按代表游标翻页不重不漏。
    List<PendingEnvironmentWaitRow> firstPage =
        store.transaction(
            tx -> tx.listPendingEnvironmentWaits(authorityNow(), Instant.EPOCH, CURSOR_ZERO, 2));
    assertEquals(all.subList(0, 2), firstPage);
    PendingEnvironmentWaitRow cursor = firstPage.get(firstPage.size() - 1);
    List<PendingEnvironmentWaitRow> secondPage =
        store.transaction(
            tx ->
                tx.listPendingEnvironmentWaits(
                    authorityNow(),
                    cursor.representativeCreatedAt(),
                    cursor.representativeInvocationId(),
                    2));
    assertEquals(List.of(all.get(2)), secondPage);
  }

  /** 单调用投影保留冻结环境，不能从根聚合反推；未 due、上线和 server-side 均不标等待。 */
  @Test
  void toolEnvironmentSnapshotAndTimeHorizonUseWorkFacts() {
    SeededTools seeded = seedTools(store, 3);
    UUID waiting = seeded.toolIds().get(0);
    UUID future = seeded.toolIds().get(1);
    UUID server = seeded.toolIds().get(2);
    WorkTarget futureTarget = new WorkTarget(WorkTargetType.TOOL, future);
    requestWork(new WorkTarget(WorkTargetType.TOOL, waiting), WAITING_ENVIRONMENT);
    requestWork(futureTarget, WAITING_ENVIRONMENT);
    requestWork(new WorkTarget(WorkTargetType.TOOL, server));
    forceWorkAvailableAfter(futureTarget, Duration.ofMinutes(5));
    List<EnvironmentToolWaitRow> rows =
        store.transaction(tx -> tx.listEnvironmentToolWaits(authorityNow(), seeded.toolIds()));
    EnvironmentToolWaitRow waitingRow =
        rows.stream().filter(row -> row.invocationId().equals(waiting)).findFirst().orElseThrow();
    assertEquals(WAITING_ENVIRONMENT, waitingRow.environmentId());
    assertTrue(waitingRow.waitingForEnvironment());
    assertNull(waitingRow.freshnessAt());
    EnvironmentToolWaitRow futureRow =
        rows.stream().filter(row -> row.invocationId().equals(future)).findFirst().orElseThrow();
    assertEquals(WAITING_ENVIRONMENT, futureRow.environmentId());
    assertFalse(futureRow.waitingForEnvironment());
    assertEquals(
        store.transaction(tx -> tx.findWork(futureTarget)).orElseThrow().availableAt(),
        futureRow.freshnessAt());
    assertTrue(rows.contains(new EnvironmentToolWaitRow(server, null, false, null, null)));
    Instant horizon =
        store.transaction(tx -> tx.findNextEnvironmentWaitChange(authorityNow())).orElseThrow();
    assertTrue(horizon.isAfter(authorityNow()));
    assertTrue(horizon.isBefore(authorityNow().plus(Duration.ofMinutes(6))));
    seedReadyEnvironmentLease(WAITING_ENVIRONMENT);
    assertFalse(
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(authorityNow(), List.of(waiting)))
            .get(0)
            .waitingForEnvironment());
    assertEquals(
        List.of(), store.transaction(tx -> tx.listEnvironmentToolWaits(authorityNow(), List.of())));
  }

  /** RUNNING/terminal 即使保留有未来 Work 和在线环境租约也不等待、不安排到期回读。 */
  @Test
  void toolProjectionDoesNotScheduleNonReadyInvocations() {
    SeededTool seeded = seedTool(store);
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, seeded.toolId());
    requestWork(target, WAITING_ENVIRONMENT);
    forceWorkAvailableAfter(target, Duration.ofMinutes(5));
    seedReadyEnvironmentLease(WAITING_ENVIRONMENT);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(seeded.threadId()).orElseThrow();
          ToolInvocation ready = tx.lockToolInvocation(seeded.toolId()).orElseThrow();
          ToolInvocation approved = ready.markApprovalNotRequired(T1);
          tx.updateToolInvocations(List.of(approved));
          ToolInvocation dispatching = approved.beginDispatch(T1);
          tx.updateToolInvocations(List.of(dispatching));
          tx.updateToolInvocations(List.of(dispatching.markRunning(T1)));
        });
    EnvironmentToolWaitRow running =
        store
            .transaction(
                tx -> tx.listEnvironmentToolWaits(authorityNow(), List.of(seeded.toolId())))
            .getFirst();
    assertFalse(running.waitingForEnvironment());
    assertNull(running.freshnessAt());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(seeded.threadId()).orElseThrow();
          ToolInvocation runningTool = tx.lockToolInvocation(seeded.toolId()).orElseThrow();
          tx.updateToolInvocations(
              List.of(runningTool.fail(new ToolInvocationError("FAILED", "test failure"), T1)));
        });
    EnvironmentToolWaitRow terminal =
        store
            .transaction(
                tx -> tx.listEnvironmentToolWaits(authorityNow(), List.of(seeded.toolId())))
            .getFirst();
    assertFalse(terminal.waitingForEnvironment());
    assertNull(terminal.freshnessAt());
  }
}

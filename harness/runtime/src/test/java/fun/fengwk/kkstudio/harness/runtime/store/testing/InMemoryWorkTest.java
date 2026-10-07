package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class InMemoryWorkTest extends HarnessStoreWorkContract {

  /** 内存实现的权威时间域：调用方传入的 now 同时就是它的时钟；契约测试统一用这个固定「此刻」。 */
  private static final Instant CLOCK = Instant.ofEpochMilli(1_700_000_000_000L);

  /** 当前持有有效 READY 连接租约的环境；由契约 hook 改写，供读取投影判定环境是否上线。 */
  private final Set<UUID> readyEnvironments = new HashSet<>();

  @Override
  HarnessStore createStore() {
    readyEnvironments.clear();
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    store.setEnvironmentReadyLeasePredicate(
        (environmentId, now) -> readyEnvironments.contains(environmentId.value()));
    return store;
  }

  @Override
  protected Instant authorityNow() {
    return CLOCK;
  }

  /** 内存实现没有独立时钟：lease deadline 只能以传入的 {@code now} 为权威时间域，过期也直接改写内存 deadline。 */
  @Override
  protected void expireLease(WorkTarget target) {
    ((InMemoryHarnessStore) store).forceLeaseUntil(target, Instant.EPOCH);
  }

  @Override
  protected void forceWorkAvailable(WorkTarget target) {
    ((InMemoryHarnessStore) store).forceAvailableAt(target, Instant.EPOCH);
  }

  @Override
  protected void forceWorkAvailableAfter(WorkTarget target, Duration delay) {
    ((InMemoryHarnessStore) store).forceAvailableAt(target, CLOCK.plus(delay));
  }

  @Override
  protected void seedReadyEnvironmentLease(EnvironmentId environmentId) {
    readyEnvironments.add(environmentId.value());
  }

  @Override
  protected void expireReadyEnvironmentLease(EnvironmentId environmentId) {
    readyEnvironments.remove(environmentId.value());
  }

  /** boolean 环境谓词没有到期时刻；每调用仍精确输出 Work 最早未来边界，等于 now 即不再定时。 */
  @Test
  void toolProjectionUsesFrozenNameAndWorkDeadlineWithBooleanEnvironment() {
    SeededTool seeded = seedTool(store);
    EnvironmentId environment = EnvironmentId.of(UUID.randomUUID());
    InMemoryHarnessStore memory = (InMemoryHarnessStore) store;
    memory.setEnvironmentName(environment, "frozen-environment");
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, seeded.toolId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(seeded.threadId()).orElseThrow();
          tx.requestWork(target, CLOCK, environment);
        });
    seedReadyEnvironmentLease(environment);
    var online =
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(CLOCK, List.of(seeded.toolId())))
            .getFirst();
    assertEquals("frozen-environment", online.environmentName());
    assertFalse(online.waitingForEnvironment());
    assertNull(online.freshnessAt());
    Instant due = CLOCK.plusSeconds(60);
    memory.forceAvailableAt(target, due);
    var future =
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(CLOCK, List.of(seeded.toolId())))
            .getFirst();
    assertEquals(due, future.freshnessAt());
    expireReadyEnvironmentLease(environment);
    var expired =
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(due, List.of(seeded.toolId())))
            .getFirst();
    assertTrue(expired.waitingForEnvironment());
    assertNull(expired.freshnessAt());
    memory.setRouteReadyPredicate((node, env, now) -> true);
    seedReadyEnvironmentLease(environment);
    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.TOOL,
                        due,
                        "projection-lease",
                        CLAIM_LEASE,
                        UUID.randomUUID()))
            .orElseThrow();
    expireReadyEnvironmentLease(environment);
    Instant nextDue = due.plusSeconds(120);
    memory.forceAvailableAt(target, nextDue);
    assertEquals(
        nextDue,
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(due, List.of(seeded.toolId())))
            .getFirst()
            .freshnessAt());
    memory.forceAvailableAt(target, Instant.EPOCH);
    var leased =
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(due, List.of(seeded.toolId())))
            .getFirst();
    assertFalse(leased.waitingForEnvironment());
    assertEquals(claim.leaseUntil(), leased.freshnessAt());
    var leaseExpired =
        store
            .transaction(
                tx -> tx.listEnvironmentToolWaits(claim.leaseUntil(), List.of(seeded.toolId())))
            .getFirst();
    assertTrue(leaseExpired.waitingForEnvironment());
    assertNull(leaseExpired.freshnessAt());
  }

  /**
   * 测试意图：内存实现的权威时间域就是传入的 {@code now}——claim / renew 的 deadline 必须精确等于 {@code now + 传入 duration}，
   * 且现有 lease 已覆盖目标时点时不得缩短。
   */
  @Test
  void claimAndRenewDeadlinesAreAnchoredToTheInjectedNow() {
    Duration claimLease = Duration.ofMinutes(5);
    Duration renewLease = Duration.ofMinutes(7);
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, T0);
        });

    ClaimedWork claim =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "token", claimLease))
            .orElseThrow();
    assertEquals(T1.plus(claimLease), claim.leaseUntil());

    // 现有 lease（T1+5min）已覆盖 T2 之后 1 秒的目标时点：不缩短、返回 false
    boolean shortened = store.transaction(tx -> tx.renewWork(claim, T2, Duration.ofSeconds(1)));
    assertFalse(shortened);
    assertEquals(
        T1.plus(claimLease),
        store.transaction(tx -> tx.findWork(target)).orElseThrow().leaseUntil());

    // 目标时点更晚：延展到 now + duration
    boolean extended = store.transaction(tx -> tx.renewWork(claim, T2, renewLease));
    assertTrue(extended);
    assertEquals(
        T2.plus(renewLease),
        store.transaction(tx -> tx.findWork(target)).orElseThrow().leaseUntil());
  }
}

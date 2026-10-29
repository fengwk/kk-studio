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
import fun.fengwk.kkstudio.harness.runtime.store.EnvironmentToolWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class InMemoryWorkTest extends HarnessStoreWorkContract {

  /** 内存实现的权威时间域：store 的固定测试时钟；契约测试统一用这个「此刻」。 */
  private static final Instant CLOCK = Instant.ofEpochMilli(1_700_000_000_000L);

  /** 当前持有有效 READY 连接租约的环境；由契约 hook 改写，供读取投影判定环境是否上线。 */
  private final Set<UUID> readyEnvironments = new HashSet<>();

  @Override
  HarnessStore createStore() {
    readyEnvironments.clear();
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    store.setClock(Clock.fixed(CLOCK, ZoneOffset.UTC));
    store.setEnvironmentReadyLeasePredicate(
        (environmentId, now) -> readyEnvironments.contains(environmentId.value()));
    return store;
  }

  @Override
  protected Instant authorityNow() {
    return CLOCK;
  }

  /** 内存时钟固定不前进：过期直接改写内存 lease deadline。 */
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
          tx.requestWork(target, environment);
        });
    seedReadyEnvironmentLease(environment);
    var online = toolWait(seeded);
    assertEquals("frozen-environment", online.environmentName());
    assertFalse(online.waitingForEnvironment());
    assertNull(online.freshnessAt());
    Instant due = CLOCK.plusSeconds(60);
    memory.forceAvailableAt(target, due);
    assertEquals(due, toolWait(seeded).freshnessAt());
    expireReadyEnvironmentLease(environment);
    memory.setNow(due);
    var expired = toolWait(seeded);
    assertTrue(expired.waitingForEnvironment());
    assertNull(expired.freshnessAt());
    memory.setRouteReadyPredicate((node, env, now) -> true);
    seedReadyEnvironmentLease(environment);
    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.TOOL, "projection-lease", CLAIM_LEASE, UUID.randomUUID()))
            .orElseThrow();
    expireReadyEnvironmentLease(environment);
    Instant nextDue = due.plusSeconds(120);
    memory.forceAvailableAt(target, nextDue);
    assertEquals(nextDue, toolWait(seeded).freshnessAt());
    memory.forceAvailableAt(target, Instant.EPOCH);
    var leased = toolWait(seeded);
    assertFalse(leased.waitingForEnvironment());
    assertEquals(claim.leaseUntil(), leased.freshnessAt());
    memory.setNow(claim.leaseUntil());
    var leaseExpired = toolWait(seeded);
    assertTrue(leaseExpired.waitingForEnvironment());
    assertNull(leaseExpired.freshnessAt());
  }

  private EnvironmentToolWaitRow toolWait(SeededTool seeded) {
    return store
        .transaction(tx -> tx.listEnvironmentToolWaits(List.of(seeded.toolId())))
        .getFirst();
  }

  /**
   * 测试意图：内存实现的权威时间域是事务时间——claim / renew 的 deadline 必须精确等于 {@code now + 传入 duration}， 且现有 lease
   * 已覆盖目标时点时不得缩短。
   */
  @Test
  void claimAndRenewDeadlinesAreAnchoredToTheTransactionTime() {
    InMemoryHarnessStore memory = (InMemoryHarnessStore) store;
    Duration claimLease = Duration.ofMinutes(5);
    Duration renewLease = Duration.ofMinutes(7);
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    memory.setNow(T0);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target);
        });

    memory.setNow(T1);
    ClaimedWork claim =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, "token", claimLease))
            .orElseThrow();
    assertEquals(T1.plus(claimLease), claim.leaseUntil());

    // 现有 lease（T1+5min）已覆盖 T2 之后 1 秒的目标时点：不缩短、返回 false
    memory.setNow(T2);
    boolean shortened = store.transaction(tx -> tx.renewWork(claim, Duration.ofSeconds(1)));
    assertFalse(shortened);
    assertEquals(
        T1.plus(claimLease),
        store.transaction(tx -> tx.findWork(target)).orElseThrow().leaseUntil());

    // 目标时点更晚：延展到 now + duration
    boolean extended = store.transaction(tx -> tx.renewWork(claim, renewLease));
    assertTrue(extended);
    assertEquals(
        T2.plus(renewLease),
        store.transaction(tx -> tx.findWork(target)).orElseThrow().leaseUntil());
  }
}

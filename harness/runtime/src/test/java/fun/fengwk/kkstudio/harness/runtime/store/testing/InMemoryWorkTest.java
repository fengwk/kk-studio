package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;

class InMemoryWorkTest extends HarnessStoreWorkContract {

  /** 内存实现的权威时间域：调用方传入的 now 同时就是它的时钟；契约测试统一用这个固定「此刻」。 */
  private static final Instant CLOCK = Instant.ofEpochMilli(1_700_000_000_000L);

  @Override
  HarnessStore createStore() {
    return new InMemoryHarnessStore();
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

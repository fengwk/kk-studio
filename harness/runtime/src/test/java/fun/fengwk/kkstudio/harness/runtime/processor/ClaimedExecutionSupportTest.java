package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** 验证处理器共用骨架的所有权入口与生命周期；guard 的并发算法由其已有测试验证。 */
class ClaimedExecutionSupportTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final ProcessorLeaseConfig LEASE =
      new ProcessorLeaseConfig(Duration.ofSeconds(60), Duration.ofSeconds(10));

  private final HarnessStore store = mock(HarnessStore.class);
  private final HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
  private final ClaimedWork claim =
      new ClaimedWork(
          new WorkTarget(WorkTargetType.TOOL, UUID.randomUUID()), 1, "token", NOW.plusSeconds(60));
  private final ClaimedExecutionSupport<ClaimAdmissionGuard.GuardedExecution> support =
      new ClaimedExecutionSupport<>(store, WorkTargetType.TOOL, LEASE);

  private void transactions() {
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(tx);
            });
  }

  @Test
  void rechecksOwnershipBeforeRegisteringAnExecution() {
    // 第一次校验通过但原子准入复核失权时，不得登记 execution。
    transactions();
    when(tx.lockClaimedWork(claim)).thenReturn(Optional.of(ownedWork()), Optional.empty());
    assertNull(support.admit(claim));
    assertFalse(support.hasActiveExecution());
    verify(tx, times(2)).lockClaimedWork(claim);
  }

  @Test
  void registeredExecutionCanBeReleasedOrCancelledAndCloseRejectsNewClaims() {
    // 只由注册的 execution 决定 active，取消与关闭委托 abandon，绝不写 invocation 状态。
    transactions();
    when(tx.lockClaimedWork(claim)).thenReturn(Optional.of(ownedWork()));
    ClaimAdmissionGuard.GuardedExecution execution =
        mock(ClaimAdmissionGuard.GuardedExecution.class);
    when(execution.claim()).thenReturn(claim);
    try (var ticket = support.admit(claim)) {
      assertTrue(ticket.attach(execution));
      assertSame(execution, support.execution(claim.target().id()));
      assertTrue(support.hasActiveExecution());
      support.release(execution);
      assertFalse(support.hasActiveExecution());
    }
    try (var ticket = support.admit(claim)) {
      assertTrue(ticket.attach(execution));
      assertTrue(support.cancel(claim.target().id()));
      assertFalse(support.hasActiveExecution());
      verify(execution).abandon();
    }
    support.close();
    assertTrue(support.isClosed());
    assertThrows(IllegalStateException.class, () -> support.admit(claim));
  }

  @Test
  void leaseMarginUsesTheCallerTransaction() {
    // 续租直接委托传入事务，不读取 JVM 时钟，也不另开事务。
    support.ensureLeaseMargin(tx, claim);
    verify(tx).renewWork(claim, LEASE.leaseDuration());
  }

  private Work ownedWork() {
    return Work.initial(claim.target(), NOW).claim(NOW, claim.leaseToken(), claim.leaseUntil());
  }
}

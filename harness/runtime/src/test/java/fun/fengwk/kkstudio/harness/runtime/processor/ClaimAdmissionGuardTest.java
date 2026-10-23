package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

class ClaimAdmissionGuardTest {

  @Test
  void ticketDeduplicatesSameTokenWithoutAbandoning() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim = claimedWork(id, "token-1");
    FakeExecution exec1 = new FakeExecution(claim);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket1 = guard.tryAdmit(claim, () -> true);
    assertNotNull(ticket1);
    assertTrue(ticket1.attach(exec1));
    assertTrue(guard.hasActiveExecution());

    // 同 token 再次准入：直接拒绝，不取消旧执行
    ClaimAdmissionGuard.Ticket<FakeExecution> duplicateTicket = guard.tryAdmit(claim, () -> true);
    assertNull(duplicateTicket);
    assertFalse(exec1.abandoned.get());
    assertTrue(guard.hasActiveExecution());

    guard.releaseExecution(exec1);
    assertFalse(guard.hasActiveExecution());
  }

  @Test
  void ticketVerifierRejectionDoesNotAbandonCurrentExecution() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim1 = claimedWork(id, "token-1");
    FakeExecution exec1 = new FakeExecution(claim1);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket1 = guard.tryAdmit(claim1, () -> true);
    assertNotNull(ticket1);
    assertTrue(ticket1.attach(exec1));

    // 旧 claim2 或失权 claim 校验返回 false：拒绝准入，不影响当前合法 exec1
    ClaimedWork claim2 = claimedWork(id, "token-2");
    ClaimAdmissionGuard.Ticket<FakeExecution> ticket2 = guard.tryAdmit(claim2, () -> false);
    assertNull(ticket2);
    assertFalse(exec1.abandoned.get());
    assertEquals(exec1, guard.getExecution(id));

    guard.releaseExecution(exec1);
    assertFalse(guard.hasActiveExecution());
  }

  @Test
  void ticketSupersedesOlderExecutionWhenVerifierPasses() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim1 = claimedWork(id, "token-1");
    FakeExecution exec1 = new FakeExecution(claim1);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket1 = guard.tryAdmit(claim1, () -> true);
    assertNotNull(ticket1);
    assertTrue(ticket1.attach(exec1));

    ClaimedWork claim2 = claimedWork(id, "token-2");
    FakeExecution exec2 = new FakeExecution(claim2);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket2 = guard.tryAdmit(claim2, () -> true);
    assertNotNull(ticket2);
    assertTrue(exec1.abandoned.get(), "superseded old execution must be abandoned");
    assertTrue(ticket1.isSuperseded());

    assertTrue(ticket2.attach(exec2));
    assertEquals(exec2, guard.getExecution(id));

    // 旧 ticket 无法再挂载
    FakeExecution execLate = new FakeExecution(claim1);
    assertFalse(ticket1.attach(execLate));
  }

  @Test
  void unattachedTicketReleasesOnClose() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim = claimedWork(id, "token-1");

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket = guard.tryAdmit(claim, () -> true);
    assertNotNull(ticket);
    assertFalse(guard.hasActiveExecution());

    ticket.close();

    // 释放后相同 token 可再次准入
    ClaimAdmissionGuard.Ticket<FakeExecution> ticket2 = guard.tryAdmit(claim, () -> true);
    assertNotNull(ticket2);
  }

  @Test
  void cancelAbandonsExecutionAndRemovesEntry() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim = claimedWork(id, "token-1");
    FakeExecution exec = new FakeExecution(claim);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket = guard.tryAdmit(claim, () -> true);
    assertNotNull(ticket);
    assertTrue(ticket.attach(exec));

    assertTrue(guard.cancel(id));
    assertTrue(exec.abandoned.get());
    assertFalse(guard.hasActiveExecution());
    assertNull(guard.getExecution(id));
  }

  @Test
  void cancelBeforeAttachRefusesSubsequentAttach() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim = claimedWork(id, "token-1");
    FakeExecution exec = new FakeExecution(claim);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket = guard.tryAdmit(claim, () -> true);
    assertNotNull(ticket);
    assertFalse(ticket.isSuperseded());

    // 在 attach 前调用 cancel
    assertTrue(guard.cancel(id));
    assertTrue(ticket.isSuperseded());

    // 随后的 attach 必须被拒绝
    assertFalse(ticket.attach(exec));
    assertFalse(guard.hasActiveExecution());
    assertNull(guard.getExecution(id));
  }

  @Test
  void closeAbandonsAllActiveExecutions() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id1 = TestIds.id(1);
    UUID id2 = TestIds.id(2);
    ClaimedWork claim1 = claimedWork(id1, "token-1");
    ClaimedWork claim2 = claimedWork(id2, "token-2");
    FakeExecution exec1 = new FakeExecution(claim1);
    FakeExecution exec2 = new FakeExecution(claim2);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket1 = guard.tryAdmit(claim1, () -> true);
    ClaimAdmissionGuard.Ticket<FakeExecution> ticket2 = guard.tryAdmit(claim2, () -> true);
    ticket1.attach(exec1);
    ticket2.attach(exec2);

    guard.close();

    assertTrue(exec1.abandoned.get());
    assertTrue(exec2.abandoned.get());
    assertFalse(guard.hasActiveExecution());
  }

  @Test
  void concurrentCloseDuringVerifierPreventsSlotCreation() throws Exception {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim = claimedWork(id, "token-1");

    CountDownLatch verifierEntered = new CountDownLatch(1);
    CountDownLatch resumeVerifier = new CountDownLatch(1);
    CountDownLatch closeFinished = new CountDownLatch(1);
    AtomicReference<ClaimAdmissionGuard.Ticket<FakeExecution>> ticketRef = new AtomicReference<>();

    Thread admitThread =
        new Thread(
            () ->
                ticketRef.set(
                    guard.tryAdmit(
                        claim,
                        () -> {
                          verifierEntered.countDown();
                          try {
                            resumeVerifier.await(5, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          return true;
                        })));

    admitThread.start();
    assertTrue(verifierEntered.await(5, TimeUnit.SECONDS));

    Thread closeThread =
        new Thread(
            () -> {
              guard.close();
              closeFinished.countDown();
            });
    closeThread.start();

    // 等待 close 真正开始并将 guard 设为 closed
    awaitTrue(guard::isClosed);

    // 恢复 verifier 继续执行
    resumeVerifier.countDown();

    admitThread.join(5000);
    closeThread.join(5000);
    assertFalse(admitThread.isAlive());
    assertFalse(closeThread.isAlive());

    // close 期间迟到准入绝不创建 slot：返回不可 attach 的 ticket，绝不登记 execution。
    ClaimAdmissionGuard.Ticket<FakeExecution> ticket = ticketRef.get();
    assertNotNull(ticket);
    assertTrue(ticket.isSuperseded());
    assertFalse(ticket.attach(new FakeExecution(claim)));
    assertFalse(guard.hasActiveExecution());
    assertNull(guard.getExecution(id));
  }

  @Test
  void ticketAttachRejectedAfterClose() {
    ClaimAdmissionGuard<FakeExecution> guard = new ClaimAdmissionGuard<>();
    UUID id = TestIds.id(1);
    ClaimedWork claim = claimedWork(id, "token-1");
    FakeExecution exec = new FakeExecution(claim);

    ClaimAdmissionGuard.Ticket<FakeExecution> ticket = guard.tryAdmit(claim, () -> true);
    assertNotNull(ticket);

    guard.close();

    // close 后 attach 必须失败
    assertFalse(ticket.attach(exec));
    assertTrue(ticket.isSuperseded());
    assertFalse(guard.hasActiveExecution());
  }

  private static void awaitTrue(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(condition.getAsBoolean(), "condition was not satisfied within timeout");
  }

  private static ClaimedWork claimedWork(UUID id, String token) {
    return new ClaimedWork(new WorkTarget(WorkTargetType.TOOL, id), 1L, token, Instant.EPOCH);
  }

  private static final class FakeExecution implements ClaimAdmissionGuard.GuardedExecution {
    private final ClaimedWork claim;
    final AtomicBoolean abandoned = new AtomicBoolean();

    FakeExecution(ClaimedWork claim) {
      this.claim = claim;
    }

    @Override
    public ClaimedWork claim() {
      return claim;
    }

    @Override
    public void abandon() {
      abandoned.set(true);
    }
  }
}

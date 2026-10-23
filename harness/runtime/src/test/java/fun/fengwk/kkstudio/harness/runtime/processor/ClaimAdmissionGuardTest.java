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
import java.util.concurrent.atomic.AtomicBoolean;

class ClaimAdmissionGuardTest {

  /** 同一 token 在 active admission 期间只能进入一次；对称 release 后可再次进入。 */
  @Test
  void rejectsDuplicateTokenUntilReleased() {
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), "token-1"));
    assertFalse(guard.tryAdmit(TestIds.id(1), "token-1"));

    guard.release(TestIds.id(1), "token-1");
    assertTrue(guard.tryAdmit(TestIds.id(1), "token-1"));
  }

  /** 新 lease token 可 supersede 旧 token；旧调用方的迟到 release 不能删除当前 admission。 */
  @Test
  void staleReleaseDoesNotRemoveSupersedingToken() {
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();

    assertTrue(guard.tryAdmit(TestIds.id(1), "token-1"));
    assertTrue(guard.tryAdmit(TestIds.id(1), "token-2"));

    guard.release(TestIds.id(1), "token-1");
    assertFalse(guard.tryAdmit(TestIds.id(1), "token-2"));

    guard.release(TestIds.id(1), "token-2");
    assertTrue(guard.tryAdmit(TestIds.id(1), "token-2"));
  }

  @Test
  void ticketDeduplicatesSameTokenWithoutAbandoning() {
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();
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
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();
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
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();
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
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();
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
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();
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
  void closeAbandonsAllActiveExecutions() {
    ClaimAdmissionGuard guard = new ClaimAdmissionGuard();
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

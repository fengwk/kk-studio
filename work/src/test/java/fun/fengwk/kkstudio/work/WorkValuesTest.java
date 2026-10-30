package fun.fengwk.kkstudio.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.work.WorkState;

import java.time.Instant;
import java.util.UUID;

/** Work 的只读投影与 claim 凭据必须保留冻结的调度事实。 */
class WorkValuesTest {

  @Test
  void snapshotExposesTheSharedStateWithoutLosingEnvironmentAffinity() {
    UUID target = UUID.randomUUID();
    UUID environment = UUID.randomUUID();
    Instant now = Instant.parse("2026-10-31T00:00:00Z");
    WorkState state = WorkState.initial(now, environment).claim(now, "owner", now.plusSeconds(30));
    DurableWork work = new DurableWork(target, state);

    assertEquals(target, work.targetId());
    assertEquals(state.availableAt(), work.availableAt());
    assertEquals(state.wakeVersion(), work.wakeVersion());
    assertEquals(state.leaseToken(), work.leaseToken());
    assertEquals(state.leaseUntil(), work.leaseUntil());
    assertEquals(environment, work.requiredEnvironmentId());
  }

  @Test
  void claimRejectsANonPositiveWakeVersionAndKeepsOptionalAffinity() {
    UUID target = UUID.randomUUID();
    Instant until = Instant.parse("2026-10-31T00:00:30Z");
    assertThrows(IllegalArgumentException.class, () -> new WorkClaim(target, 0, "owner", until));
    WorkClaim claim = new WorkClaim(target, 1, "owner", until);
    assertNull(claim.requiredEnvironmentId());
    assertEquals(target, claim.targetId());
    assertEquals(1, claim.claimedWakeVersion());
    assertEquals("owner", claim.leaseToken());
    assertEquals(until, claim.leaseUntil());
  }
}

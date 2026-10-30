package fun.fengwk.kkstudio.common.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@link WorkState} 的纯跃迁与不变量；时间全部由调用方注入，不读取 JVM 时钟。 */
class WorkStateTest {

  private static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");
  private static final UUID ENV = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_ENV = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void initialIsImmediatelyAvailableWithoutLease() {
    WorkState state = WorkState.initial(T0, null);

    assertEquals(T0, state.availableAt());
    assertEquals(1L, state.wakeVersion());
    assertNull(state.leaseToken());
    assertNull(state.leaseUntil());
    assertNull(state.requiredEnvironmentId());
  }

  @Test
  void requestIncrementsWakePullsDueForwardAndPreservesLease() {
    WorkState claimed = WorkState.initial(T0, null).claim(T0, "token", T0.plusSeconds(30));

    WorkState later = claimed.request(T0.plusSeconds(10), null);
    assertEquals(2L, later.wakeVersion());
    assertEquals(T0, later.availableAt());
    assertEquals("token", later.leaseToken());
    assertEquals(T0.plusSeconds(30), later.leaseUntil());

    WorkState earlier = WorkState.initial(T0.plusSeconds(60), null).request(T0, null);
    assertEquals(T0, earlier.availableAt());
    assertEquals(2L, earlier.wakeVersion());
  }

  @Test
  void requestFreezesEnvironmentAffinityAndRejectsConflict() {
    WorkState initial = WorkState.initial(T0, ENV);

    assertEquals(ENV, initial.request(T0, null).requiredEnvironmentId());
    assertEquals(ENV, initial.request(T0, ENV).requiredEnvironmentId());
    assertThrows(IllegalArgumentException.class, () -> initial.request(T0, OTHER_ENV));
    assertThrows(
        IllegalArgumentException.class, () -> WorkState.initial(T0, null).request(T0, ENV));
  }

  @Test
  void claimRequiresDueFreeLeaseAndFutureDeadline() {
    WorkState future = WorkState.initial(T0.plusSeconds(1), null);
    assertThrows(
        IllegalArgumentException.class, () -> future.claim(T0, "token", T0.plusSeconds(10)));

    WorkState claimed = WorkState.initial(T0, null).claim(T0, "token", T0.plusSeconds(10));
    assertEquals("token", claimed.leaseToken());
    assertEquals(T0.plusSeconds(10), claimed.leaseUntil());
    assertThrows(
        IllegalArgumentException.class, () -> claimed.claim(T0, "other", T0.plusSeconds(20)));

    WorkState expired = claimed.claim(T0.plusSeconds(10), "other", T0.plusSeconds(20));
    assertEquals("other", expired.leaseToken());
    assertThrows(
        IllegalArgumentException.class, () -> WorkState.initial(T0, null).claim(T0, "token", T0));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkState.initial(T0, null).claim(T0, " ", T0.plusSeconds(1)));
  }

  @Test
  void renewValidatesOwnershipAndRequiresStrictExtension() {
    WorkState claimed = WorkState.initial(T0, null).claim(T0, "token", T0.plusSeconds(10));

    assertThrows(
        IllegalArgumentException.class, () -> claimed.renew("other", T0, T0.plusSeconds(20)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.renew("token", T0.plusSeconds(10), T0.plusSeconds(20)));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.renew("token", T0, T0.plusSeconds(10)));
    WorkState renewed = claimed.renew("token", T0, T0.plusSeconds(20));
    assertEquals("token", renewed.leaseToken());
    assertEquals(T0.plusSeconds(20), renewed.leaseUntil());
    assertEquals(claimed.availableAt(), renewed.availableAt());
    assertEquals(claimed.wakeVersion(), renewed.wakeVersion());
  }

  @Test
  void completeDeletesCurrentWakeAndKeepsNewerWakeWithoutLease() {
    WorkState claimed = WorkState.initial(T0, null).claim(T0, "token", T0.plusSeconds(30));

    assertEquals(Optional.empty(), claimed.complete("token", 1L, T0));
    Optional<WorkState> newer = claimed.request(T0.plusSeconds(5), null).complete("token", 1L, T0);
    assertEquals(2L, newer.orElseThrow().wakeVersion());
    assertNull(newer.orElseThrow().leaseToken());
    assertNull(newer.orElseThrow().leaseUntil());
    assertEquals(T0, newer.orElseThrow().availableAt());
  }

  @Test
  void completeRejectsStaleTokenExpiredLeaseAndFutureClaimedVersion() {
    WorkState claimed = WorkState.initial(T0, null).claim(T0, "token", T0.plusSeconds(30));

    assertThrows(IllegalArgumentException.class, () -> claimed.complete("other", 1L, T0));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.complete("token", 1L, T0.plusSeconds(30)));
    assertThrows(IllegalArgumentException.class, () -> claimed.complete("token", 0L, T0));
    assertThrows(IllegalArgumentException.class, () -> claimed.complete("token", 2L, T0));
  }

  @Test
  void rescheduleSetsNewDueOrKeepsEarlierNewerWake() {
    WorkState claimed = WorkState.initial(T0, null).claim(T0, "token", T0.plusSeconds(30));

    WorkState rescheduled = claimed.reschedule("token", 1L, T0, T0.plusSeconds(60));
    assertEquals(T0.plusSeconds(60), rescheduled.availableAt());
    assertEquals(1L, rescheduled.wakeVersion());
    assertNull(rescheduled.leaseToken());

    WorkState woken = claimed.request(T0.plusSeconds(5), null);
    WorkState stale = woken.reschedule("token", 1L, T0, T0.plusSeconds(60));
    assertEquals(2L, stale.wakeVersion());
    assertEquals(T0, stale.availableAt());
    assertNull(stale.leaseToken());
  }

  @Test
  void rescheduleRejectsLostOwnershipAndInvalidVersions() {
    WorkState claimed = WorkState.initial(T0, null).claim(T0, "token", T0.plusSeconds(30));

    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("other", 1L, T0, T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token", 1L, T0.plusSeconds(30), T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token", 0L, T0, T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token", 2L, T0, T0.plusSeconds(10)));
  }

  @Test
  void constructorRejectsInvalidLeasePairsAndVersions() {
    assertThrows(IllegalArgumentException.class, () -> new WorkState(T0, 0L, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> new WorkState(T0, 1L, "token", null, null));
    assertThrows(IllegalArgumentException.class, () -> new WorkState(T0, 1L, null, T0, null));
    assertThrows(IllegalArgumentException.class, () -> new WorkState(T0, 1L, " ", T0, null));
    assertThrows(IllegalArgumentException.class, () -> new WorkState(T0, 1L, " token", T0, null));
    assertThrows(
        IllegalArgumentException.class, () -> new WorkState(T0, 1L, "x".repeat(129), T0, null));
    assertFalse(new WorkState(T0, 1L, null, null, null).hasActiveLeaseAt(T0));
    assertTrue(new WorkState(T0, 1L, "token", T0.plusSeconds(1), null).hasActiveLeaseAt(T0));
  }
}

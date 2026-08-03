package fun.fengwk.kkstudio.harness.runtime.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

/** Work initial/request/claim/renew/complete/reschedule protocol and lost-wake fencing. */
class WorkTest {

  private static final WorkTarget TARGET = new WorkTarget(WorkTargetType.THREAD, 1L);
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void createsInitialWakeWithoutLease() {
    Work work = Work.initial(TARGET, T0);

    assertEquals(TARGET, work.target());
    assertEquals(T0, work.availableAt());
    assertEquals(1L, work.wakeVersion());
    assertNull(work.leaseToken());
    assertNull(work.leaseUntil());
  }

  @Test
  void requestIncrementsWakeKeepsEarliestAvailableAtAndPreservesLease() {
    Work work = Work.initial(TARGET, T0);
    Work laterRequest = work.request(T0.plusSeconds(60));
    assertEquals(2L, laterRequest.wakeVersion());
    assertEquals(T0, laterRequest.availableAt());

    Work earlierRequest = work.request(T0.minusSeconds(60));
    assertEquals(T0.minusSeconds(60), earlierRequest.availableAt());

    Work claimed = work.claim(T0, "token-1", T0.plusSeconds(30));
    Work requestedWhileLeased = claimed.request(T0.plusSeconds(60));
    assertEquals(2L, requestedWhileLeased.wakeVersion());
    assertEquals("token-1", requestedWhileLeased.leaseToken());
    assertEquals(T0.plusSeconds(30), requestedWhileLeased.leaseUntil());
  }

  @Test
  void requestOverflowThrows() {
    Work maxed = new Work(TARGET, T0, Long.MAX_VALUE, null, null);
    assertThrows(ArithmeticException.class, () -> maxed.request(T0));
  }

  @Test
  void claimRequiresAvailabilityNoActiveLeaseAndFutureUntil() {
    Work available = Work.initial(TARGET, T0);
    Work claimed = available.claim(T0, "token-1", T0.plusSeconds(30));

    assertEquals("token-1", claimed.leaseToken());
    assertEquals(T0.plusSeconds(30), claimed.leaseUntil());
    assertEquals(1L, claimed.wakeVersion());
    assertEquals(T0, claimed.availableAt());

    assertThrows(
        IllegalArgumentException.class,
        () -> Work.initial(TARGET, T0.plusSeconds(1)).claim(T0, "token-2", T0.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.claim(T0, "token-2", T0.plusSeconds(60)));
    assertThrows(IllegalArgumentException.class, () -> available.claim(T0, "token-2", T0));
    assertThrows(
        IllegalArgumentException.class, () -> available.claim(T0, "token-2", T0.minusSeconds(1)));
  }

  @Test
  void claimAllowedAfterLeaseExpiry() {
    Work claimed = Work.initial(TARGET, T0).claim(T0, "token-1", T0.plusSeconds(30));

    Work reclaimed = claimed.claim(T0.plusSeconds(30), "token-2", T0.plusSeconds(60));

    assertEquals("token-2", reclaimed.leaseToken());
    assertEquals(T0.plusSeconds(60), reclaimed.leaseUntil());
  }

  @Test
  void claimRejectsNonCanonicalTokens() {
    Work available = Work.initial(TARGET, T0);
    assertThrows(
        IllegalArgumentException.class, () -> available.claim(T0, null, T0.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class, () -> available.claim(T0, " ", T0.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class, () -> available.claim(T0, " token", T0.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class, () -> available.claim(T0, "token ", T0.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class,
        () -> available.claim(T0, "t".repeat(129), T0.plusSeconds(30)));
  }

  @Test
  void renewStrictlyExtendsActiveMatchingLease() {
    Work claimed = Work.initial(TARGET, T0).claim(T0, "token-1", T0.plusSeconds(30));

    Work renewed = claimed.renew("token-1", T0.plusSeconds(10), T0.plusSeconds(120));
    assertEquals(T0.plusSeconds(120), renewed.leaseUntil());
    assertEquals("token-1", renewed.leaseToken());

    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.renew("wrong", T0.plusSeconds(10), T0.plusSeconds(120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.renew("token-1", T0.plusSeconds(10), T0.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.renew("token-1", T0.plusSeconds(10), T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Work.initial(TARGET, T0).renew("token-1", T0, T0.plusSeconds(30)));
  }

  @Test
  void renewRejectsStaleLease() {
    Work claimed = Work.initial(TARGET, T0).claim(T0, "token-1", T0.plusSeconds(30));

    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.renew("token-1", T0.plusSeconds(30), T0.plusSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.renew("token-1", T0.plusSeconds(31), T0.plusSeconds(60)));
  }

  @Test
  void completeDeletesOnMatchingWakeAndClearsLeaseOnStaleWake() {
    Work claimed = Work.initial(TARGET, T0).claim(T0, "token-1", T0.plusSeconds(30));

    Optional<Work> deleted = claimed.complete("token-1", 1L, T0.plusSeconds(10));
    assertTrue(deleted.isEmpty());

    Work lostWake = claimed.request(T0.plusSeconds(60));
    Optional<Work> staleComplete = lostWake.complete("token-1", 1L, T0.plusSeconds(10));
    assertTrue(staleComplete.isPresent());
    Work preserved = staleComplete.orElseThrow();
    assertEquals(TARGET, preserved.target());
    assertEquals(T0, preserved.availableAt());
    assertEquals(2L, preserved.wakeVersion());
    assertNull(preserved.leaseToken());
    assertNull(preserved.leaseUntil());
  }

  @Test
  void completeRejectsTokenMismatchInvalidClaimAndStaleLease() {
    Work claimed = Work.initial(TARGET, T0).claim(T0, "token-1", T0.plusSeconds(30));

    assertThrows(
        IllegalArgumentException.class, () -> claimed.complete("wrong", 1L, T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.complete("token-1", 2L, T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.complete("token-1", 0L, T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.complete("token-1", -1L, T0.plusSeconds(10)));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.complete("token-1", 1L, T0.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class, () -> claimed.complete("token-1", 1L, T0.plusSeconds(31)));
    assertThrows(
        IllegalArgumentException.class, () -> Work.initial(TARGET, T0).complete("token-1", 1L, T0));
  }

  @Test
  void rescheduleSetsRequestedAtOnMatchingWakeAndEarliestOnStaleWake() {
    Work claimed = Work.initial(TARGET, T0).claim(T0, "token-1", T0.plusSeconds(30));

    Work rescheduled = claimed.reschedule("token-1", 1L, T0.plusSeconds(10), T0.plusSeconds(120));
    assertEquals(T0.plusSeconds(120), rescheduled.availableAt());
    assertEquals(1L, rescheduled.wakeVersion());
    assertNull(rescheduled.leaseToken());
    assertNull(rescheduled.leaseUntil());

    Work lostWake = claimed.request(T0.plusSeconds(60));
    Work staleReschedule =
        lostWake.reschedule("token-1", 1L, T0.plusSeconds(10), T0.plusSeconds(120));
    assertEquals(T0, staleReschedule.availableAt());
    assertEquals(2L, staleReschedule.wakeVersion());
    assertNull(staleReschedule.leaseToken());

    Work staleEarlier = lostWake.reschedule("token-1", 1L, T0.plusSeconds(10), T0.minusSeconds(60));
    assertEquals(T0.minusSeconds(60), staleEarlier.availableAt());
  }

  @Test
  void rescheduleRejectsTokenMismatchInvalidClaimAndStaleLease() {
    Work claimed = Work.initial(TARGET, T0).claim(T0, "token-1", T0.plusSeconds(30));

    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("wrong", 1L, T0.plusSeconds(10), T0.plusSeconds(120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token-1", 2L, T0.plusSeconds(10), T0.plusSeconds(120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token-1", 0L, T0.plusSeconds(10), T0.plusSeconds(120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token-1", -1L, T0.plusSeconds(10), T0.plusSeconds(120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token-1", 1L, T0.plusSeconds(30), T0.plusSeconds(120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> claimed.reschedule("token-1", 1L, T0.plusSeconds(31), T0.plusSeconds(120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Work.initial(TARGET, T0).reschedule("token-1", 1L, T0, T0.plusSeconds(120)));
  }

  @Test
  void rejectsInvalidRowFacts() {
    assertThrows(NullPointerException.class, () -> new Work(null, T0, 1L, null, null));
    assertThrows(NullPointerException.class, () -> new Work(TARGET, null, 1L, null, null));
    assertThrows(IllegalArgumentException.class, () -> new Work(TARGET, T0, 0L, null, null));
    assertThrows(IllegalArgumentException.class, () -> new Work(TARGET, T0, -1L, null, null));
    assertThrows(IllegalArgumentException.class, () -> new Work(TARGET, T0, 1L, "token", null));
    assertThrows(IllegalArgumentException.class, () -> new Work(TARGET, T0, 1L, null, T0));
    assertThrows(IllegalArgumentException.class, () -> new Work(TARGET, T0, 1L, " ", T0));
    assertThrows(IllegalArgumentException.class, () -> new Work(TARGET, T0, 1L, " t", T0));
    assertThrows(
        IllegalArgumentException.class, () -> new Work(TARGET, T0, 1L, "t".repeat(129), T0));
  }
}

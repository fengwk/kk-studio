package fun.fengwk.kkstudio.platform.daemon.server;

import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.INSTANCE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.SerialListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exit confirmation claims must not be replaced by attach or pin tombstones after release. */
class DaemonInvocationTrackerExitConfirmationTest {

  @Test
  void attachCannotReplaceClaimedTombstoneAndCanAttachAfterClaimRelease() {
    DaemonInvocationTracker tracker = new DaemonInvocationTracker();
    UUID invocationId = UUID.randomUUID();
    ActiveInvocation original = active(invocationId, INSTANCE_ID);
    tracker.register(original);
    assertTrue(tracker.retire(original));
    ExitConfirmation claimed =
        tracker.claimExitConfirmation(ENVIRONMENT_ID, invocationId, INSTANCE_ID).orElseThrow();

    ActiveInvocation attached = active(invocationId, INSTANCE_ID);
    assertThrows(IllegalArgumentException.class, () -> tracker.registerAttach(attached));
    assertTrue(tracker.completeExitConfirmation(ENVIRONMENT_ID, invocationId, claimed, false));

    tracker.registerAttach(attached);
    assertSame(attached, tracker.registeredInvocation(ENVIRONMENT_ID, invocationId));
  }

  @Test
  void claimedTombstonesCannotPreventTheTrackerCapacityBound() {
    DaemonInvocationTracker tracker = new DaemonInvocationTracker();
    List<UUID> invocationIds = new ArrayList<>();
    List<ExitConfirmation> claims = new ArrayList<>();
    for (int index = 0; index <= DaemonInvocationTracker.MAX_INVOCATION_TOMBSTONES; index++) {
      UUID invocationId = new UUID(0L, index + 1L);
      ActiveInvocation active = active(invocationId, INSTANCE_ID);
      tracker.register(active);
      ExitConfirmation claim =
          tracker.claimExitConfirmation(ENVIRONMENT_ID, invocationId, INSTANCE_ID).orElseThrow();
      assertTrue(tracker.retire(active));
      invocationIds.add(invocationId);
      claims.add(claim);
    }

    long retained =
        invocationIds.stream().filter(id -> tracker.hasTombstone(ENVIRONMENT_ID, id)).count();
    assertEquals(DaemonInvocationTracker.MAX_INVOCATION_TOMBSTONES, retained);
    assertFalse(
        tracker.completeExitConfirmation(
            ENVIRONMENT_ID, invocationIds.getFirst(), claims.getFirst(), false),
        "an evicted claim cannot later deliver or acknowledge an exit fact");
    assertTrue(
        tracker.completeExitConfirmation(
            ENVIRONMENT_ID, invocationIds.getLast(), claims.getLast(), false));
  }

  private static ActiveInvocation active(UUID invocationId, String daemonInstanceId) {
    return new ActiveInvocation(
        ENVIRONMENT_ID,
        invocationId,
        new SerialListener(),
        Duration.ZERO,
        daemonInstanceId,
        ignored -> {},
        () -> null);
  }
}

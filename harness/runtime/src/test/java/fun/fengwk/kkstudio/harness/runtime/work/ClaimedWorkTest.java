package fun.fengwk.kkstudio.harness.runtime.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** ClaimedWork record snapshot and constructor invariants. */
class ClaimedWorkTest {

  private static final WorkTarget TARGET = new WorkTarget(WorkTargetType.TOOL, 7L);
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void acceptsValidSnapshotFacts() {
    ClaimedWork snapshot = new ClaimedWork(TARGET, 1L, "token-1", T0.plusSeconds(30));

    assertEquals(TARGET, snapshot.target());
    assertEquals(1L, snapshot.claimedWakeVersion());
    assertEquals("token-1", snapshot.leaseToken());
    assertEquals(T0.plusSeconds(30), snapshot.leaseUntil());
  }

  @Test
  void rejectsInvalidSnapshotFacts() {
    assertThrows(NullPointerException.class, () -> new ClaimedWork(null, 1L, "t", T0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(TARGET, 0L, "t", T0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(TARGET, -1L, "t", T0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(TARGET, 1L, " ", T0));
    assertThrows(NullPointerException.class, () -> new ClaimedWork(TARGET, 1L, "t", null));
  }
}

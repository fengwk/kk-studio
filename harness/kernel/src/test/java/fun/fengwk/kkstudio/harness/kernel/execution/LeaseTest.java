package fun.fengwk.kkstudio.harness.kernel.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** Lease 边界与 isActiveAt 判定测试。 */
class LeaseTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void activeBeforeUntil() {
    Lease lease = new Lease("token", T0.plusSeconds(60));
    assertTrue(lease.isActiveAt(T0));
    assertTrue(lease.isActiveAt(T0.plusSeconds(59).plusMillis(999)));
  }

  @Test
  void inactiveAtOrAfterUntil() {
    Lease lease = new Lease("token", T0.plusSeconds(60));
    assertFalse(lease.isActiveAt(T0.plusSeconds(60)));
    assertFalse(lease.isActiveAt(T0.plusSeconds(120)));
  }

  @Test
  void isActiveAtRejectsNullObservedAt() {
    Lease lease = new Lease("token", T0.plusSeconds(60));
    assertThrows(NullPointerException.class, () -> lease.isActiveAt(null));
  }

  @Test
  void rejectsBlankToken() {
    assertThrows(IllegalArgumentException.class, () -> new Lease("", T0.plusSeconds(60)));
    assertThrows(IllegalArgumentException.class, () -> new Lease("   ", T0.plusSeconds(60)));
    assertThrows(NullPointerException.class, () -> new Lease(null, T0.plusSeconds(60)));
  }

  @Test
  void rejectsNullUntil() {
    assertThrows(NullPointerException.class, () -> new Lease("token", null));
  }
}

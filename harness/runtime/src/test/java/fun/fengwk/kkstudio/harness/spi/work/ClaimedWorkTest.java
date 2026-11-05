package fun.fengwk.kkstudio.harness.spi.work;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestWorkTypes;

import java.time.Instant;
import java.util.UUID;

/** ClaimedWork record snapshot 与构造器不变量。 */
class ClaimedWorkTest {

  private static final UUID TOKEN_1 = UUID.randomUUID();
  private static final UUID TOKEN_2 = UUID.randomUUID();

  @Test
  void acceptsValidSnapshotFacts() {
    EnvironmentId env = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    ClaimedWork snapshot =
        new ClaimedWork(
            new WorkTarget(WorkTargetType.TOOL, id(7L)),
            1L,
            TOKEN_1,
            Instant.parse("2026-01-01T00:00:30Z"),
            env);

    assertEquals(new WorkTarget(WorkTargetType.TOOL, id(7L)), snapshot.target());
    assertEquals(1L, snapshot.claimedWakeVersion());
    assertEquals(TOKEN_1, snapshot.leaseToken());
    assertEquals(Instant.parse("2026-01-01T00:00:30Z"), snapshot.leaseUntil());
    assertEquals(env, snapshot.requiredEnvironmentId());

    ClaimedWork unconstrained =
        new ClaimedWork(
            new WorkTarget(WorkTargetType.THREAD, id(8L)),
            2L,
            TOKEN_2,
            Instant.parse("2026-01-01T00:00:30Z"));
    assertNull(unconstrained.requiredEnvironmentId());
  }

  @Test
  void rejectsInvalidSnapshotFacts() {
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, id(7L));
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, id(8L));
    WorkTarget modelTarget = new WorkTarget(TestWorkTypes.MODEL, id(9L));
    EnvironmentId env = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

    assertThrows(NullPointerException.class, () -> new ClaimedWork(null, 1L, TOKEN_1, t0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(target, 0L, TOKEN_1, t0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(target, -1L, TOKEN_1, t0));
    assertThrows(NullPointerException.class, () -> new ClaimedWork(target, 1L, TOKEN_1, null));
    assertThrows(
        IllegalArgumentException.class, () -> new ClaimedWork(threadTarget, 1L, TOKEN_1, t0, env));
    assertThrows(
        IllegalArgumentException.class, () -> new ClaimedWork(modelTarget, 1L, TOKEN_1, t0, env));
  }
}

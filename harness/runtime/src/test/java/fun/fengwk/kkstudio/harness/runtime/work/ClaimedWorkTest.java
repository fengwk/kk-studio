package fun.fengwk.kkstudio.harness.runtime.work;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Instant;

/** ClaimedWork record snapshot 与构造器不变量。 */
class ClaimedWorkTest {

  @Test
  void acceptsValidSnapshotFacts() {
    EnvironmentName env = new EnvironmentName("env-1");
    ClaimedWork snapshot =
        new ClaimedWork(
            new WorkTarget(WorkTargetType.TOOL, id(7L)),
            1L,
            "token-1",
            Instant.parse("2026-01-01T00:00:30Z"),
            env);

    assertEquals(new WorkTarget(WorkTargetType.TOOL, id(7L)), snapshot.target());
    assertEquals(1L, snapshot.claimedWakeVersion());
    assertEquals("token-1", snapshot.leaseToken());
    assertEquals(Instant.parse("2026-01-01T00:00:30Z"), snapshot.leaseUntil());
    assertEquals(env, snapshot.requiredEnvironmentName());

    ClaimedWork unconstrained =
        new ClaimedWork(
            new WorkTarget(WorkTargetType.THREAD, id(8L)),
            2L,
            "token-2",
            Instant.parse("2026-01-01T00:00:30Z"));
    assertNull(unconstrained.requiredEnvironmentName());
  }

  @Test
  void rejectsInvalidSnapshotFacts() {
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, id(7L));
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, id(8L));
    EnvironmentName env = new EnvironmentName("env-1");

    assertThrows(NullPointerException.class, () -> new ClaimedWork(null, 1L, "t", t0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(target, 0L, "t", t0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(target, -1L, "t", t0));
    assertThrows(IllegalArgumentException.class, () -> new ClaimedWork(target, 1L, " ", t0));
    assertThrows(NullPointerException.class, () -> new ClaimedWork(target, 1L, "t", null));
    assertThrows(
        IllegalArgumentException.class, () -> new ClaimedWork(threadTarget, 1L, "t", t0, env));
  }
}

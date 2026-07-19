package fun.fengwk.kkstudio.core.harness.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.harness.goal.store.DatabaseGoalStore;
import fun.fengwk.kkstudio.harness.runtime.goal.GoalStatus;
import fun.fengwk.kkstudio.harness.runtime.goal.ThreadGoal;

import java.time.Instant;

/** Integration coverage for durable Thread goal create/replace/update. */
@SpringBootTest
class DatabaseGoalStoreIntegrationTest {
  private static final long THREAD_ID = 9_820_001L;
  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

  @Autowired private DatabaseGoalStore store;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_thread_goal");
  }

  @Test
  void createReplaceAndTerminalUpdate() {
    ThreadGoal created = store.createOrReplace(THREAD_ID, "Build control tools", 500L, NOW);
    assertEquals(GoalStatus.active, created.status());
    assertEquals(500L, created.tokenBudget());
    assertTrue(store.find(THREAD_ID).isPresent());

    ThreadGoal replaced =
        store.createOrReplace(THREAD_ID, "Build control tools v2", null, NOW.plusSeconds(10));
    assertEquals("Build control tools v2", replaced.objective());
    assertEquals(null, replaced.tokenBudget());
    assertEquals(created.createdAt(), replaced.createdAt());

    ThreadGoal complete =
        store.updateTerminal(
            THREAD_ID,
            GoalStatus.complete,
            "All criteria satisfied with tests.",
            NOW.plusSeconds(20));
    assertEquals(GoalStatus.complete, complete.status());
    assertEquals("All criteria satisfied with tests.", complete.reason());

    IllegalStateException blocked =
        assertThrows(
            IllegalStateException.class,
            () -> store.updateTerminal(THREAD_ID, GoalStatus.blocked, "nope", NOW.plusSeconds(30)));
    assertTrue(blocked.getMessage().contains("cannot be updated"));
  }

  @Test
  void updateWithoutGoalFailsClearly() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> store.updateTerminal(THREAD_ID, GoalStatus.complete, "done", NOW));
    assertEquals("No goal is set.", error.getMessage());
  }
}

package fun.fengwk.kkstudio.core.ai.runtime.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.ai.runtime.goal.store.DatabaseGoalStore;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.goal.GoalStatus;
import fun.fengwk.kkstudio.harness.runtime.goal.ThreadGoal;

import java.time.Instant;

/** Integration coverage for durable Thread goal create/replace/update. */
class DatabaseGoalStoreIntegrationTest extends PostgresSpringTestSupport {
  private static final long THREAD_ID = 9_820_001L;
  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

  @Autowired private DatabaseGoalStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void clean() {
    // Session/main-thread FK is DEFERRABLE; bootstrap must commit in one transaction.
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbc.update("delete from harness_thread_goal");
          jdbc.update("delete from harness_thread");
          jdbc.update("delete from harness_entry");
          jdbc.update("delete from harness_session");
          jdbc.update(
              "insert into harness_session (id, title, created_at) values (?, 'g', now())",
              THREAD_ID + 1);
          jdbc.update(
              "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                  + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, now())",
              THREAD_ID + 2,
              THREAD_ID + 1);
          jdbc.update(
              "insert into harness_thread (id, head_entry_id, input_sequence, runnable,"
                  + " execution_epoch, created_at, updated_at) values (?, ?, 0, false, 0,"
                  + " now(), now())",
              THREAD_ID,
              THREAD_ID + 2);
        });
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

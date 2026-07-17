package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2 contracts for Environment-only claim and cancellation lease extension predicates. */
@SpringBootTest(classes = CoreTestApplication.class)
class DatabaseEnvironmentToolInvocationWorkerStoreIntegrationTest {

  private static final long ENVIRONMENT_ID = 7_000_000_000_000_000_001L;
  private static final long ENVIRONMENT_RUN_ID = 7_000_000_000_000_000_011L;
  private static final long CLOUD_RUN_ID = 7_000_000_000_000_000_012L;
  private static final long ENVIRONMENT_INVOCATION_ID = 7_000_000_000_000_000_021L;
  private static final long CLOUD_INVOCATION_ID = 7_000_000_000_000_000_022L;
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

  @Autowired private DatabaseEnvironmentToolInvocationWorkerStore store;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** Environment claim never steals CLOUD work and retains the lease through CANCEL_REQUESTED. */
  @Test
  void claimsOnlyTargetEnvironmentAndHeartbeatsCancellation() {
    insertRun(ENVIRONMENT_RUN_ID, ENVIRONMENT_RUN_ID + 100, ENVIRONMENT_RUN_ID + 200);
    insertRun(CLOUD_RUN_ID, CLOUD_RUN_ID + 100, CLOUD_RUN_ID + 200);
    insertInvocation(
        ENVIRONMENT_INVOCATION_ID,
        ENVIRONMENT_RUN_ID,
        ENVIRONMENT_RUN_ID + 200,
        "environment-call",
        "ENVIRONMENT",
        ENVIRONMENT_ID);
    insertInvocation(
        CLOUD_INVOCATION_ID, CLOUD_RUN_ID, CLOUD_RUN_ID + 200, "cloud-call", "CLOUD", null);
    try {
      Optional<ClaimedToolInvocation> claimed =
          store.claimDue(ENVIRONMENT_ID, "environment-worker", NOW, Duration.ofSeconds(30));

      assertTrue(claimed.isPresent());
      assertEquals(ENVIRONMENT_INVOCATION_ID, claimed.orElseThrow().invocation().id());
      assertEquals(ToolInvocationStatus.RUNNING, claimed.orElseThrow().invocation().status());
      assertFalse(
          store
              .claimDue(ENVIRONMENT_ID, "environment-worker", NOW, Duration.ofSeconds(30))
              .isPresent());
      assertEquals(
          "QUEUED",
          jdbcTemplate.queryForObject(
              "select status from tool_invocation where id = ?",
              String.class,
              CLOUD_INVOCATION_ID));

      jdbcTemplate.update(
          "update tool_invocation set status = 'CANCEL_REQUESTED', cancel_requested_at = ? where id"
              + " = ?",
          timestamp(NOW),
          ENVIRONMENT_INVOCATION_ID);
      assertTrue(
          store.heartbeat(claimed.orElseThrow(), NOW.plusSeconds(1), Duration.ofSeconds(30)));
    } finally {
      jdbcTemplate.update(
          "delete from tool_invocation where id in (?, ?)",
          ENVIRONMENT_INVOCATION_ID,
          CLOUD_INVOCATION_ID);
      jdbcTemplate.update(
          "delete from harness_run where id in (?, ?)", ENVIRONMENT_RUN_ID, CLOUD_RUN_ID);
    }
  }

  private void insertRun(long id, long sessionId, long triggerEntryId) {
    jdbcTemplate.update(
        "insert into harness_run (id, session_id, trigger_entry_id, status, turn_index, attempt,"
            + " event_sequence, lease_owner, lease_until, next_attempt_at, cancel_requested_at,"
            + " gmt_create, started_at, finished_at, gmt_modified)"
            + " values (?, ?, ?, 'WAITING_TOOLS', 0, 1, 0, null, null, ?, null, ?, null, null, ?)",
        id,
        sessionId,
        triggerEntryId,
        timestamp(NOW),
        timestamp(NOW),
        timestamp(NOW));
  }

  private void insertInvocation(
      long id,
      long runId,
      long assistantEntryId,
      String callId,
      String targetType,
      Long environmentId) {
    jdbcTemplate.update(
        "insert into tool_invocation (id, run_id, assistant_entry_id, ordinal, tool_call_id,"
            + " tool_name, tool_version, target_type, environment_id, arguments_json, status,"
            + " permission_action, permission_decision, side_effect, deadline_at, lease_owner,"
            + " lease_until, cancel_requested_at, result_json, error_message, gmt_create,"
            + " started_at, finished_at, gmt_modified) values (?, ?, ?, 0, ?, 'read', '1', ?, ?,"
            + " '{}', 'QUEUED', 'ALLOW', null, 'READ_ONLY', ?, null, null, null, null, null, ?,"
            + " null, null, ?)",
        id,
        runId,
        assistantEntryId,
        callId,
        targetType,
        environmentId,
        timestamp(NOW.plusSeconds(60)),
        timestamp(NOW),
        timestamp(NOW));
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }
}

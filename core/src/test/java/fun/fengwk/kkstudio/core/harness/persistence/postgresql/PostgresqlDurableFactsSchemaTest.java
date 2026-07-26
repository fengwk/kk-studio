package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Verifies the small durable policy, goal, usage-ledger and artifact fact tables. */
class PostgresqlDurableFactsSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applySchema(conn);
    }
  }

  @Test
  void retryPolicyIsASingleton() throws SQLException {
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_retry_policy_singleton",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_retry_policy (id, max_retries, backoff_strategy,"
                        + " base_delay_millis, max_delay_millis) values"
                        + " (2, 3, 'EXPONENTIAL', 2000, 60000)")) {
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void goalBudgetAndTerminalReasonMatchTheRuntimeContract() throws SQLException {
    ThreadFixture thread = createThread();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into harness_thread_goal (thread_id, objective, token_budget, status)"
                    + " values (?, 'Ship the slice', 100, 'active')")) {
      ps.setLong(1, thread.threadId);
      assertEquals(1, ps.executeUpdate());
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_goal_token_budget_pos",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread_goal set token_budget = 0 where thread_id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_thread_goal_reason",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_thread_goal set status = 'complete' where thread_id = ?")) {
              ps.setLong(1, thread.threadId);
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_thread_goal set status = 'complete', reason = 'verified'"
                    + " where thread_id = ?")) {
      ps.setLong(1, thread.threadId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void artifactSizeAndDigestDescribeTheStoredBytes() throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into harness_artifact (media_type, encoding, content, size_bytes, sha256)"
                    + " values ('text/plain', 'identity', ?, 3, ?)")) {
      ps.setBytes(1, new byte[] {1, 2, 3});
      ps.setString(2, "a".repeat(64));
      assertEquals(1, ps.executeUpdate());
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_artifact_size",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_artifact (media_type, encoding, content, size_bytes,"
                        + " sha256) values ('text/plain', 'identity', ?, 1, ?)")) {
              ps.setBytes(1, new byte[] {1, 2});
              ps.setString(2, "b".repeat(64));
              ps.executeUpdate();
            }
          });
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_artifact_sha256_format",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_artifact (media_type, encoding, content, size_bytes,"
                        + " sha256) values ('text/plain', 'identity', ?, 1, 'not-a-digest')")) {
              ps.setBytes(1, new byte[] {1});
              ps.executeUpdate();
            }
          });
    }
  }

  @Test
  void usageLedgerAssistantEntryMustMatchCarrierSessionAndCostTotalStaysConsistent()
      throws SQLException {
    ThreadFixture first = createThread();
    ThreadFixture second = createThread();
    long firstAssistant = appendAssistant(first);
    long secondAssistant = appendAssistant(second);

    try (Connection conn = newConnection()) {
      insertUsage(conn, first.sessionId, first.threadId, firstAssistant);
    }

    // assistant_entry_id belongs to first.session but the carrier session_id is
    // second.session, so (session_id, assistant_entry_id) cannot pair on
    // harness_entry (session_id, id).
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_harness_model_usage_assistant_entry",
          () -> insertUsage(conn, second.sessionId, second.threadId, firstAssistant));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_model_usage_cost_total",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "update harness_model_usage set cost_input = 1 where assistant_entry_id = ?")) {
              ps.setLong(1, firstAssistant);
              ps.executeUpdate();
            }
          });
    }
  }

  private ThreadFixture createThread() throws SQLException {
    return ThreadFixture.insertFresh();
  }

  private long appendAssistant(ThreadFixture thread) throws SQLException {
    return thread.appendChild("MESSAGE");
  }

  private void insertUsage(Connection conn, long sessionId, long threadId, long assistantEntryId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_model_usage (session_id, thread_id, assistant_entry_id,"
                + " provider_resource_id, model_resource_id, provider_type, provider_model_id,"
                + " prompt_cache_mode, prompt_cache_retention, cache_eligible, stop_reason,"
                + " usage_input_tokens, usage_output_tokens, usage_cache_read_tokens,"
                + " usage_cache_write_tokens, usage_cache_write_long_tokens,"
                + " usage_reasoning_tokens, usage_provider_total_tokens, cost_currency,"
                + " cost_input, cost_output, cost_cache_read, cost_cache_write,"
                + " cost_cache_write_long, cost_reasoning, cost_total, pricing_currency,"
                + " pricing_tier, pricing_service_tier, pricing_service_tier_multiplier,"
                + " pricing_version, pricing_input_per_million_tokens,"
                + " pricing_output_per_million_tokens, pricing_cache_read_per_million_tokens,"
                + " pricing_cache_write_per_million_tokens,"
                + " pricing_cache_write_long_per_million_tokens,"
                + " pricing_reasoning_per_million_tokens, raw_usage) values"
                + " (?, ?, ?, 1, 1, 'openai', 'stub-model', 'NONE', 'NONE', false, 'STOP',"
                + " 0, 0, 0, 0, 0, 0, 0, 'USD', 0, 0, 0, 0, 0, 0, 0, 'USD', 'test',"
                + " 'default', 1, 'v1', 0, 0, 0, 0, 0, 0, '{}'::jsonb)")) {
      ps.setLong(1, sessionId);
      ps.setLong(2, threadId);
      ps.setLong(3, assistantEntryId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}

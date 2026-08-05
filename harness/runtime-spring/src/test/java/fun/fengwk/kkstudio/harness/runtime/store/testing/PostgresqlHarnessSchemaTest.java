package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

class PostgresqlHarnessSchemaTest {

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
  }

  @Test
  void schemaContainsExactlyTheSevenRuntimeTables() {
    List<String> tables =
        jdbc.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public' and table_type = 'BASE TABLE'
            order by table_name
            """,
            String.class);
    assertEquals(
        List.of(
            "harness_entry",
            "harness_model_invocation",
            "harness_session",
            "harness_thread",
            "harness_thread_command",
            "harness_tool_invocation",
            "harness_work"),
        tables);
  }

  @Test
  void threadTableStoresOnlyItsOwnedDurableState() {
    List<String> columns =
        jdbc.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'harness_thread'
            order by ordinal_position
            """,
            String.class);
    assertEquals(
        List.of(
            "id",
            "head_entry_id",
            "yolo_enabled",
            "next_command_sequence",
            "revision",
            "created_at",
            "updated_at"),
        columns);
  }

  @Test
  void everyStructuredDurablePayloadUsesJsonb() {
    List<String> jsonbColumns =
        jdbc.queryForList(
            """
            select table_name || '.' || column_name
            from information_schema.columns
            where table_schema = 'public' and data_type = 'jsonb'
            order by table_name, column_name
            """,
            String.class);
    assertEquals(
        List.of(
            "harness_entry.payload",
            "harness_model_invocation.error",
            "harness_model_invocation.request",
            "harness_model_invocation.result",
            "harness_model_invocation.stream_checkpoint",
            "harness_thread_command.payload",
            "harness_tool_invocation.approval",
            "harness_tool_invocation.error",
            "harness_tool_invocation.request",
            "harness_tool_invocation.result"),
        jsonbColumns);
  }

  @Test
  void schemaDefinesExactlyTheRequiredNamedIndexes() {
    List<String> indexes =
        jdbc.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = 'public'
              and (indexname like 'idx_harness_%' or indexname like 'uk_harness_%')
            order by indexname
            """,
            String.class);
    assertEquals(
        List.of(
            "idx_harness_entry_parent",
            "idx_harness_thread_command_queued",
            "idx_harness_work_available",
            "idx_harness_work_lease_until",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_model_invocation_result",
            "uk_harness_model_invocation_turn",
            "uk_harness_thread_command_client",
            "uk_harness_thread_command_sequence",
            "uk_harness_tool_invocation_ordinal",
            "uk_harness_tool_invocation_result"),
        indexes);

    String modelResult = indexDefinition("uk_harness_model_invocation_result");
    String toolResult = indexDefinition("uk_harness_tool_invocation_result");
    String workAvailable = indexDefinition("idx_harness_work_available");
    String workLease = indexDefinition("idx_harness_work_lease_until");
    assertTrue(modelResult.contains("WHERE (result_entry_id IS NOT NULL)"));
    assertTrue(toolResult.contains("WHERE (result_entry_id IS NOT NULL)"));
    assertTrue(workAvailable.contains("(available_at, target_type, target_id)"));
    assertTrue(workLease.contains("(lease_until, target_type, target_id)"));
    assertTrue(workLease.contains("WHERE (lease_until IS NOT NULL)"));
  }

  private String indexDefinition(String indexName) {
    return jdbc.queryForObject(
        "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
        String.class,
        indexName);
  }
}

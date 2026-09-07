package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

class PostgresqlHarnessSchemaTest {

  /**
   * Harness runtime 协议恰好七张表；业务表不使用 harness_ 前缀（V1 中唯一的应用层 Session blob 引用表为 session_blob_ref）， 因此
   * {@code harness_%} 全量查询结果必须精确等于该七表。
   */
  private static final List<String> RUNTIME_TABLES =
      List.of(
          "harness_entry",
          "harness_model_invocation",
          "harness_session",
          "harness_thread",
          "harness_thread_command",
          "harness_tool_invocation",
          "harness_work");

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
  }

  @Test
  void schemaContainsExactlyTheSevenRuntimeTables() {
    // 精确查询全部 harness_% 表：任何业务表（如 session_blob_ref）不得混入 runtime 协议空间。
    List<String> tables =
        jdbc.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
              and table_type = 'BASE TABLE'
              and table_name like 'harness\\_%'
            order by table_name
            """,
            String.class);
    assertEquals(RUNTIME_TABLES, tables);
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
            "session_id",
            "head_entry_id",
            "creation_request_hash",
            "yolo_enabled",
            "next_command_sequence",
            "version",
            "created_at",
            "updated_at"),
        columns);
  }

  @Test
  void everyStructuredDurablePayloadUsesJsonb() {
    // 只统计七张 runtime 表的 jsonb 列：session_blob_ref 无结构化载荷，不应出现。
    List<String> jsonbColumns =
        jdbc.queryForList(
            """
            select table_name || '.' || column_name
            from information_schema.columns
            where table_schema = 'public'
              and data_type = 'jsonb'
              and table_name like 'harness\\_%'
            order by table_name, column_name
            """,
            String.class);
    assertEquals(
        List.of(
            "harness_entry.payload",
            "harness_entry.provider_replay_state",
            "harness_model_invocation.error",
            "harness_model_invocation.failed_attempts",
            "harness_model_invocation.provider_replay_state",
            "harness_model_invocation.request_spec",
            "harness_model_invocation.result",
            "harness_model_invocation.stream_checkpoint",
            "harness_thread_command.payload",
            "harness_tool_invocation.approval",
            "harness_tool_invocation.binding",
            "harness_tool_invocation.call",
            "harness_tool_invocation.effects",
            "harness_tool_invocation.error",
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
              and tablename like 'harness\\_%'
              and indexname not like '%_pkey'
            order by indexname
            """,
            String.class);
    assertEquals(
        List.of(
            "idx_harness_entry_parent",
            "idx_harness_thread_command_queued",
            "idx_harness_thread_command_stop_request",
            "idx_harness_thread_session",
            "idx_harness_work_available",
            "idx_harness_work_lease_until",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_model_invocation_result",
            "uk_harness_model_invocation_turn",
            "uk_harness_thread_command_idempotency",
            "uk_harness_tool_invocation_call_index"),
        indexes);

    String modelResult = indexDefinition("uk_harness_model_invocation_result");
    String workAvailable = indexDefinition("idx_harness_work_available");
    String workLease = indexDefinition("idx_harness_work_lease_until");
    String threadSession = indexDefinition("idx_harness_thread_session");
    String stopRequest = indexDefinition("idx_harness_thread_command_stop_request");
    assertTrue(modelResult.contains("WHERE (result_entry_id IS NOT NULL)"));
    assertTrue(workAvailable.contains("(available_at, target_type, target_id)"));
    assertTrue(workLease.contains("(lease_until, target_type, target_id)"));
    assertTrue(workLease.contains("WHERE (lease_until IS NOT NULL)"));
    assertTrue(threadSession.contains("(session_id, created_at, id)"));
    // Stop 幂等键索引必须按 stop_request_id 聚合并只覆盖非 null 行。
    assertTrue(stopRequest.contains("(thread_id, stop_request_id, sequence)"));
    assertTrue(stopRequest.contains("WHERE (stop_request_id IS NOT NULL)"));
  }

  private String indexDefinition(String indexName) {
    return jdbc.queryForObject(
        "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
        String.class,
        indexName);
  }
}

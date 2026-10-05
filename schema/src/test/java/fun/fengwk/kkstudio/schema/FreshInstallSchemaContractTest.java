package fun.fengwk.kkstudio.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全新安装基线契约测试。
 *
 * <p>在测试自己拥有的隔离 Testcontainer 里对空库真实执行 {@code V1__schema.sql}（经 Flyway，即生产建库的同一条路径），断言 V1
 * 仍然是「一次全新安装 = 当前全部结构」的声明：
 *
 * <ul>
 *   <li>public schema 的表集合恰好是保留的共享表加 16 张目标业务表，旧业务对象、旧函数、旧触发器与任何 sequence 都不存在；
 *   <li>共享（Harness/Catalog/Chat/Environment/Storage/Settings）表的列、约束、索引与触发器与生成并提交的快照一致，即重写 V1
 *       只带入了声明的共享表变更（Harness Thread 复合唯一键、ToolInvocation WAITING_INPUT/input_receipt/pending 索引、
 *       chat.archived_at 与 chat_session 关联）；
 *   <li>SQL 探针（{@code fresh-install-probes.sql}）逐条验证目标约束与非法写入的拒绝路径。
 * </ul>
 *
 * <p>测试库守卫：连接串只由本测试拥有的临时容器和固定测试库名构造，绝不读取 {@code KK_STUDIO_DB_*} 等部署连接变量，也不接触任何
 * 共享数据库；容器除镜像外不依赖外部服务，测试结束即销毁。
 *
 * <p>快照是生成式断言：批准基线变更后运行 {@code mvn -pl schema test -Dkkstudio.schema.snapshot.update=true} 重新生成，提交前
 * 必须人工审阅 diff。
 */
class FreshInstallSchemaContractTest {

  private static final String POSTGRES_IMAGE = "postgres:17-alpine";
  private static final String DATABASE = "kkstudio_schema_fresh_install";
  private static final String USERNAME = "kkstudio_schema";
  private static final String MIGRATION_LOCATION = "classpath:db/migration";

  private static final String PROBE_RESOURCE =
      "fun/fengwk/kkstudio/schema/fresh-install-probes.sql";
  private static final String PROBE_CONTAINER_PATH = "/tmp/fresh-install-probes.sql";
  private static final int PROBE_ASSERTION_COUNT = 157;

  private static final String SNAPSHOT_RESOURCE =
      "fun/fengwk/kkstudio/schema/preserved-schema-snapshot.txt";
  private static final Path SNAPSHOT_PATH = Path.of("src", "test", "resources", SNAPSHOT_RESOURCE);
  private static final boolean UPDATE_SNAPSHOT =
      Boolean.getBoolean("kkstudio.schema.snapshot.update");

  /** 共享表：V1 必须原样保留（只允许 {@link #ALLOWED_SHARED_OBJECTS} 里的声明变更）。 */
  private static final List<String> PRESERVED_TABLES =
      List.of(
          "agent_definition",
          "agent_model",
          "agent_provider",
          "chat",
          "environment",
          "environment_connection",
          "harness_entry",
          "harness_model_invocation",
          "harness_session",
          "harness_thread",
          "harness_thread_command",
          "harness_thread_join",
          "harness_thread_stop_receipt",
          "harness_tool_invocation",
          "harness_work",
          "mcp_server",
          "mcp_tool",
          "plugin_credential",
          "session_blob_ref",
          "skill_package",
          "storage_blob",
          "storage_object_cleanup",
          "storage_upload",
          "system_setting");

  /** 16 张目标业务表：Canvas 7 张、Project/Issue 8 张、Chat Session 关联 1 张。 */
  private static final List<String> TARGET_TABLES =
      List.of(
          "canvas_command_dedup",
          "canvas_document",
          "canvas_function_resource_pin",
          "canvas_function_run",
          "canvas_group",
          "canvas_node",
          "canvas_resource",
          "chat_session",
          "project",
          "project_issue",
          "project_issue_activity",
          "project_issue_agent_thread",
          "project_issue_evidence",
          "project_issue_run",
          "project_issue_stage_budget",
          "project_issue_work");

  /** 被移除的旧业务关系（含设计过程中出现过但从未进入基线的候选名）。 */
  private static final List<String> REMOVED_RELATIONS =
      List.of(
          "canvas_link",
          "comfyui_workflow_api",
          "human_question",
          "project_issue_agent_session",
          "project_issue_branch",
          "project_issue_dependency",
          "project_state",
          "project_transition",
          "session_owner");

  /** 旧设计遗留的提示函数。 */
  private static final List<String> REMOVED_FUNCTIONS =
      List.of(
          "canvas_document_version_notify",
          "canvas_function_work_notify",
          "project_issue_changed_notify");

  /** V1 唯一应当存在的 NOTIFY 提示触发器（提交后回读提示，不是事件日志）。 */
  private static final List<String> NOTIFY_TRIGGERS =
      List.of(
          "trg_canvas_document_revision_notify",
          "trg_canvas_function_work_notify",
          "trg_harness_thread_version_notify",
          "trg_project_issue_work_due",
          "trg_skill_package_changed",
          "trg_system_setting_version_notify");

  /** 旧设计遗留的触发器名，重写后必须全部消失。 */
  private static final List<String> REMOVED_TRIGGERS =
      List.of(
          "trg_project_issue_changed_activity",
          "trg_project_issue_changed_agent_session",
          "trg_project_issue_changed_dependency",
          "trg_project_issue_changed_issue",
          "trg_project_issue_changed_project",
          "trg_project_issue_changed_run",
          "trg_project_issue_changed_session_owner",
          "trg_project_issue_changed_thread");

  /** 重写 V1 时唯一允许出现的共享表对象变更。 */
  private static final List<String> ALLOWED_SHARED_OBJECTS =
      List.of(
          "environment.install_config",
          "environment.ck_environment_install_config_object",
          "chat.archived_at",
          "chat.idx_chat_archived",
          "harness_thread.parent_thread_id",
          "harness_thread.execution_control",
          "harness_thread.input_through_sequence",
          "harness_thread.ck_harness_thread_parent_not_self",
          "harness_thread.ck_harness_thread_execution_control",
          "harness_thread.ck_harness_thread_input_through",
          "harness_thread.fk_harness_thread_parent",
          "harness_thread.idx_harness_thread_parent",
          "harness_thread.uk_harness_thread_session",
          "harness_thread_command.applied_entry_id",
          "harness_thread_join.ck_harness_thread_join_final_answer",
          "harness_thread_join.fk_harness_thread_join_source_command",
          "harness_thread_join.idx_harness_thread_join_child_pending",
          "harness_thread_join.idx_harness_thread_join_parent_pending",
          "harness_tool_invocation.input_receipt",
          "harness_tool_invocation.ck_harness_tool_input_receipt",
          "harness_tool_invocation.ck_harness_tool_waiting_input",
          "harness_tool_invocation.idx_harness_tool_invocation_pending");

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName(DATABASE)
          .withUsername(USERNAME)
          .withPassword(USERNAME)
          .withCopyFileToContainer(
              MountableFile.forClasspathResource(PROBE_RESOURCE, 0644), PROBE_CONTAINER_PATH);

  @BeforeAll
  static void applyFreshInstallBaseline() {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATION_LOCATION)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  @Test
  void onlyRunsAgainstTheOwnedEphemeralDatabase() throws SQLException {
    // 测试库守卫：连接串完全由本测试构造，部署连接变量（KK_STUDIO_DB_*）从不参与。
    assertTrue(
        POSTGRES
            .getJdbcUrl()
            .startsWith(
                "jdbc:postgresql://localhost:" + POSTGRES.getMappedPort(5432) + "/" + DATABASE),
        () -> "unexpected test JDBC url: " + POSTGRES.getJdbcUrl());
    try (Connection connection = open()) {
      assertEquals(DATABASE, queryString(connection, "select current_database()"));
      assertEquals(USERNAME, queryString(connection, "select current_user"));
    }
  }

  @Test
  void freshInstallAppliesExactlyOneVersionedBaseline() throws SQLException {
    try (Connection connection = open()) {
      assertEquals(1, queryInt(connection, "select count(*) from flyway_schema_history"));
      assertEquals(
          "1|SQL|t",
          queryRow(
              connection,
              "select version, type, success from flyway_schema_history order by installed_rank"));
    }
  }

  @Test
  void publicSchemaHoldsExactlyThePreservedAndTargetInventory() throws SQLException {
    assertEquals(16, TARGET_TABLES.size());
    Set<String> expected = new LinkedHashSet<>(PRESERVED_TABLES);
    expected.addAll(TARGET_TABLES);
    assertEquals(
        expected,
        queryStrings(
            "select tablename from pg_tables where schemaname = 'public'"
                + " and tablename <> 'flyway_schema_history'"));
  }

  @Test
  void removedProductObjectsAndIdentitySequencesAreGone() throws SQLException {
    try (Connection connection = open()) {
      for (String relation : REMOVED_RELATIONS) {
        assertNull(
            queryString(connection, "select to_regclass('public." + relation + "')::text"),
            relation + " must not exist");
      }
      for (String function : REMOVED_FUNCTIONS) {
        assertNull(
            queryString(connection, "select to_regprocedure('" + function + "()')::text"),
            function + " must not exist");
      }
      for (String trigger : REMOVED_TRIGGERS) {
        assertEquals(
            0,
            queryInt(
                connection,
                "select count(*) from pg_trigger where tgname = '"
                    + trigger
                    + "' and not tgisinternal"),
            trigger + " must not exist");
      }
      assertEquals(
          NOTIFY_TRIGGERS.size(),
          queryInt(connection, "select count(*) from pg_trigger where not tgisinternal"));
      assertEquals(
          NOTIFY_TRIGGERS.size(),
          queryInt(
              connection,
              "select count(*) from pg_trigger where not tgisinternal and tgname in ('"
                  + String.join("', '", NOTIFY_TRIGGERS)
                  + "')"));
      // 业务实体 id 全部由应用生成：基线里没有 create sequence / serial / identity。
      assertEquals(
          0,
          queryInt(connection, "select count(*) from pg_class where relkind = 'S'"),
          "no sequence may exist");
      assertEquals(
          0,
          queryInt(connection, "select count(*) from information_schema.sequences"),
          "no sequence may exist");
      assertEquals(
          0,
          queryInt(
              connection,
              "select count(*) from pg_attribute where attidentity <> ''"
                  + " and attnum > 0 and not attisdropped"),
          "no identity column may exist");
    }
  }

  @Test
  void sharedBaselineCarriesOnlyTheDeclaredChanges() throws SQLException {
    try (Connection connection = open()) {
      assertEquals("jsonb", columnType(connection, "harness_tool_invocation", "input_receipt"));
      assertEquals("YES", columnNullable(connection, "harness_tool_invocation", "input_receipt"));
      assertEquals("timestamp with time zone", columnType(connection, "chat", "archived_at"));
      assertEquals("YES", columnNullable(connection, "chat", "archived_at"));
      for (String allowed : ALLOWED_SHARED_OBJECTS) {
        String[] parts = allowed.split("\\.", 2);
        assertTrue(
            hasColumnConstraintOrIndex(connection, parts[0], parts[1]),
            allowed + " must be present after the rewrite");
      }
      assertTrue(
          objectDefinition(connection, "constraint", "harness_thread", "uk_harness_thread_session")
              .contains("UNIQUE (session_id, id)"));
      assertTrue(
          objectDefinition(
                  connection,
                  "constraint",
                  "harness_tool_invocation",
                  "ck_harness_tool_invocation_status")
              .contains("WAITING_INPUT"));
      assertTrue(
          objectDefinition(
                  connection,
                  "index",
                  "harness_tool_invocation",
                  "idx_harness_tool_invocation_model_nonterminal")
              .contains("WAITING_INPUT"));
      assertTrue(
          objectDefinition(
                  connection,
                  "index",
                  "harness_tool_invocation",
                  "idx_harness_tool_invocation_pending")
              .contains(
                  "'WAITING_APPROVAL'::character varying, 'WAITING_INPUT'::character varying"));
    }
  }

  @Test
  void preservedTablesMatchTheGeneratedBaselineSnapshot() throws Exception {
    String actual = snapshot();
    if (UPDATE_SNAPSHOT) {
      Files.createDirectories(SNAPSHOT_PATH.getParent());
      Files.writeString(SNAPSHOT_PATH, actual, StandardCharsets.UTF_8);
    }
    String expected =
        UPDATE_SNAPSHOT ? Files.readString(SNAPSHOT_PATH) : readResource(SNAPSHOT_RESOURCE);
    assertEquals(
        expected,
        actual,
        "shared tables drifted from the committed snapshot; review the diff, then regenerate with"
            + " -Dkkstudio.schema.snapshot.update=true");
  }

  @Test
  void postgresRejectsInvalidWritesThroughTheContractProbes() throws Exception {
    ExecResult result =
        POSTGRES.execInContainer(
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            USERNAME,
            "-d",
            DATABASE,
            "-f",
            PROBE_CONTAINER_PATH);
    assertEquals(
        0,
        result.getExitCode(),
        () -> "probe run failed:\n" + result.getStdout() + result.getStderr());
    Matcher matcher =
        Pattern.compile("PASS (\\d+) database contract assertions").matcher(result.getStdout());
    assertTrue(matcher.find(), () -> "probe run reported no PASS line:\n" + result.getStdout());
    // 精确断言计数：任何探针被静默跳过都会让这条失败。
    assertEquals(PROBE_ASSERTION_COUNT, Integer.parseInt(matcher.group(1)));
  }

  private static Connection open() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static String snapshot() throws SQLException {
    String tables = "array['" + String.join("', '", PRESERVED_TABLES) + "']";
    String sql =
        """
        select kind, table_name, object_name, definition from (
            select 'table' as kind, c.relname::text as table_name, '' as object_name,
                'preserved' as definition
                from pg_class c
                where c.relnamespace = 'public'::regnamespace and c.relkind = 'r'
                    and c.relname = any(%s)
            union all
            select 'column', c.relname, a.attname,
                format('type=%%s notnull=%%s default=%%s generated=%%s collation=%%s',
                    format_type(a.atttypid, a.atttypmod), a.attnotnull,
                    coalesce(pg_get_expr(d.adbin, d.adrelid), '<null>'),
                    a.attgenerated, a.attcollation::regcollation)
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where c.relnamespace = 'public'::regnamespace and c.relname = any(%s)
                    and a.attnum > 0 and not a.attisdropped
            union all
            select 'constraint', con.conrelid::regclass::text, con.conname,
                pg_get_constraintdef(con.oid)
                from pg_constraint con
                where con.connamespace = 'public'::regnamespace
                    and con.conrelid::regclass::text = any(%s)
            union all
            select 'index', i.tablename, i.indexname, i.indexdef
                from pg_indexes i
                where i.schemaname = 'public' and i.tablename = any(%s)
            union all
            select 'trigger', c.relname, tg.tgname,
                pg_get_triggerdef(tg.oid) || ' enabled=' || tg.tgenabled::text
                from pg_trigger tg
                join pg_class c on c.oid = tg.tgrelid
                where not tg.tgisinternal and c.relnamespace = 'public'::regnamespace
                    and c.relname = any(%s)
        ) snapshot
        order by kind, table_name, object_name
        """
            .formatted(tables, tables, tables, tables, tables);
    List<String> lines = new ArrayList<>();
    lines.add("# Generated metadata snapshot of the shared baseline tables of");
    lines.add("# schema/src/main/resources/db/migration/V1__schema.sql.");
    lines.add("# Regenerate after an approved baseline change:");
    lines.add("#   mvn -pl schema test -Dkkstudio.schema.snapshot.update=true");
    lines.add("#");
    lines.add("# kind\ttable\tobject\tdefinition");
    try (Connection connection = open();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        lines.add(
            String.join(
                "\t",
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4)));
      }
    }
    return String.join("\n", lines) + "\n";
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream stream =
        FreshInstallSchemaContractTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IOException("missing test resource: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String queryString(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      return resultSet.next() ? resultSet.getString(1) : null;
    }
  }

  private static String queryRow(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      if (!resultSet.next()) {
        return null;
      }
      List<String> values = new ArrayList<>();
      for (int index = 1; index <= resultSet.getMetaData().getColumnCount(); index++) {
        values.add(resultSet.getString(index));
      }
      return String.join("|", values);
    }
  }

  private static int queryInt(Connection connection, String sql) throws SQLException {
    return Integer.parseInt(queryString(connection, sql));
  }

  private static Set<String> queryStrings(String sql) throws SQLException {
    Set<String> values = new LinkedHashSet<>();
    try (Connection connection = open();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        values.add(resultSet.getString(1));
      }
    }
    return values;
  }

  private static String columnType(Connection connection, String table, String column)
      throws SQLException {
    return queryString(
        connection,
        "select data_type from information_schema.columns where table_schema = 'public'"
            + " and table_name = '"
            + table
            + "' and column_name = '"
            + column
            + "'");
  }

  private static String columnNullable(Connection connection, String table, String column)
      throws SQLException {
    return queryString(
        connection,
        "select is_nullable from information_schema.columns where table_schema = 'public'"
            + " and table_name = '"
            + table
            + "' and column_name = '"
            + column
            + "'");
  }

  private static boolean hasColumnConstraintOrIndex(
      Connection connection, String table, String objectName) throws SQLException {
    return queryInt(
            connection,
            "select (select count(*) from information_schema.columns where table_schema = 'public'"
                + " and table_name = '"
                + table
                + "' and column_name = '"
                + objectName
                + "')"
                + " + (select count(*) from pg_constraint con"
                + " join pg_class c on c.oid = con.conrelid"
                + " where c.relnamespace = 'public'::regnamespace and c.relname = '"
                + table
                + "' and con.conname = '"
                + objectName
                + "')"
                + " + (select count(*) from pg_indexes where schemaname = 'public'"
                + " and tablename = '"
                + table
                + "' and indexname = '"
                + objectName
                + "')")
        > 0;
  }

  private static String objectDefinition(
      Connection connection, String kind, String table, String objectName) throws SQLException {
    String sql =
        switch (kind) {
          case "constraint" -> "select pg_get_constraintdef(con.oid) from pg_constraint con"
              + " join pg_class c on c.oid = con.conrelid"
              + " where c.relnamespace = 'public'::regnamespace and c.relname = '"
              + table
              + "' and con.conname = '"
              + objectName
              + "'";
          case "index" -> "select indexdef from pg_indexes where schemaname = 'public'"
              + " and tablename = '"
              + table
              + "' and indexname = '"
              + objectName
              + "'";
          default -> throw new IllegalArgumentException("unsupported kind: " + kind);
        };
    String definition = queryString(connection, sql);
    assertNotNull(definition, () -> kind + " " + table + "." + objectName + " must exist");
    return definition;
  }
}

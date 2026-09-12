package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * V4 Skill 来源/操作 schema 的 fail-fast 契约与确定性回填。
 *
 * <p>测试意图：证明迁移在任何不满足前置事实的数据上整体失败，并且失败后不留下任何 V4 表面；在合法的既有数据上， 它为每个既有 Environment 生成一行 inventory 与一个
 * id 可确定复算的缺省 PATH 来源。
 *
 * <p>“允许迁移”的冻结请求用生产 {@link ModelRequestSpecJsonCodec} 生成，因此被接受的正是生产真正会持久化的形状， 而不是手写近似 JSON。
 */
class PostgresqlSkillSourceMigrationTest extends PostgresSchemaSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelRequestSpecJsonCodec MODEL_REQUEST_CODEC =
      new ModelRequestSpecJsonCodec();

  private static final List<String> V4_TABLES =
      List.of(
          "environment_inventory",
          "environment_skill_source",
          "environment_skill",
          "environment_operation");

  private static final UUID ENVIRONMENT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID SECOND_ENVIRONMENT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000102");
  private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
  private static final UUID ROOT_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
  private static final UUID TURN_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");
  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000106");
  private static final UUID MODEL_INVOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000107");
  private static final UUID TOOL_INVOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000108");
  private static final UUID SKILL_SOURCE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000109");
  private static final String CONTENT_REVISION = "a".repeat(64);

  @BeforeEach
  void resetSchema() throws SQLException {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
    }
  }

  /** 合法既有数据（空 legacy skills、空 skillBindings）必须迁移成功，并回填 inventory 与确定性缺省来源。 */
  @Test
  void migratesEmptyLegacySkillFactsAndBackfillsInventory() throws Exception {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateTo(connection, "3");
      seedLegacyData(connection, emptySkillBindings());

      migrate(connection, "classpath:db/migration");

      assertEquals(4, currentVersion(connection));
      for (String table : V4_TABLES) {
        assertNotNull(regclass(connection, table), () -> "V4 must create " + table);
      }

      // 每个既有 Environment 恰好一行、且只建立期望代际的初始 inventory：没有报告就没有已应用事实。
      assertEquals(2, singleInt(connection, "select count(*) from environment_inventory"));
      assertEquals(
          2,
          singleInt(
              connection,
              "select count(*) from environment_inventory"
                  + " where source_set_version = 0"
                  + " and applied_source_set_version is null"
                  + " and capabilities_version is null"
                  + " and operating_system is null"
                  + " and time_zone is null"
                  + " and note is null"
                  + " and root_path is null"
                  + " and owner_node_id is null"
                  + " and lease_token is null"
                  + " and reported_at is null"));
      assertEquals(
          2,
          singleInt(
              connection,
              "select count(*) from environment_inventory as inventory"
                  + " join environment as environment on environment.id = inventory.environment_id"));

      // 每个既有 Environment 恰好一个缺省 PATH 来源，形状与 V4 设计一致。
      assertEquals(2, singleInt(connection, "select count(*) from environment_skill_source"));
      assertEquals(
          2,
          singleInt(
              connection,
              "select count(*) from environment_skill_source"
                  + " where source_type = 'path'"
                  + " and path = '~/.agents/skills'"
                  + " and default_source"
                  + " and git_url is null"
                  + " and git_ref is null"
                  + " and scan_path is null"
                  + " and version = 0"
                  + " and status = 'UNAPPLIED'"
                  + " and applied_version is null"
                  + " and applied_revision is null"
                  + " and diagnostics = '[]'::jsonb"
                  + " and last_error_code is null"
                  + " and last_applied_at is null"));
      // id 由 environment id 与固定后缀派生：整个回填不依赖扩展、随机数或序列。
      assertEquals(
          2,
          singleInt(
              connection,
              "select count(*) from environment_skill_source"
                  + " where source_id = md5(environment_id::text || ':default-skill-source')::uuid"));
    }
  }

  /** 回填是逐 Environment 独立确定的：两个 Environment 各自得到不同的派生 id。 */
  @Test
  void backfillsDistinctDefaultSourcePerEnvironment() throws Exception {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateTo(connection, "3");
      seedLegacyData(connection, emptySkillBindings());

      migrate(connection, "classpath:db/migration");

      assertEquals(
          2,
          singleInt(connection, "select count(distinct source_id) from environment_skill_source"));
      // 同一环境内的缺省来源 identity 可被外部复算：md5 输入是 canonical UUID 文本加固定后缀。
      assertEquals(
          1,
          singleInt(
              connection,
              "select count(*) from environment_skill_source"
                  + " where source_id = md5('"
                  + ENVIRONMENT_ID
                  + ":default-skill-source')::uuid"
                  + " and environment_id = '"
                  + ENVIRONMENT_ID
                  + "'"));
      assertEquals(
          1,
          singleInt(
              connection,
              "select count(*) from environment_skill_source"
                  + " where source_id = md5('"
                  + SECOND_ENVIRONMENT_ID
                  + ":default-skill-source')::uuid"
                  + " and environment_id = '"
                  + SECOND_ENVIRONMENT_ID
                  + "'"));
    }
  }

  /** 任一未终结 Model 调用都阻止 schema 安装，并由 PostgreSQL 回滚 V4 的全部变更。 */
  @Test
  void rejectsMigrationWhileModelInvocationIsActive() throws Exception {
    for (String status : List.of("READY", "DISPATCHING", "RUNNING")) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, "3");
        seedLegacyData(connection, emptySkillBindings());
        execute(connection, "update harness_model_invocation set status = '" + status + "'");

        FlywayException failure =
            assertThrows(
                FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

        assertTrue(rootMessages(failure).contains("model invocations are active"), status);
        assertNoV4SurfaceSurvives(connection);
      }
    }
  }

  /** 待审批或执行中的 Tool 调用同样阻止安装：旧执行事实不能与新来源 schema 并存。 */
  @Test
  void rejectsMigrationWhileToolInvocationIsActive() throws Exception {
    for (String status : List.of("WAITING_APPROVAL", "READY", "DISPATCHING", "RUNNING")) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, "3");
        seedLegacyData(connection, emptySkillBindings());
        execute(connection, "update harness_tool_invocation set status = '" + status + "'");

        FlywayException failure =
            assertThrows(
                FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

        assertTrue(rootMessages(failure).contains("tool invocations are active"), status);
        assertNoV4SurfaceSurvives(connection);
      }
    }
  }

  /** agent_definition.config.skills 缺失或不是数组说明数据已损坏，迁移必须拒绝而不是猜测补齐。 */
  @Test
  void rejectsMalformedAgentDefinitionSkills() throws Exception {
    for (String sql :
        List.of(
            "update agent_definition set config = config - 'skills'",
            "update agent_definition set config = jsonb_set(config, '{skills}', '{}'::jsonb)")) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, "3");
        seedLegacyData(connection, emptySkillBindings());
        execute(connection, sql);

        FlywayException failure =
            assertThrows(
                FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

        assertTrue(rootMessages(failure).contains("malformed agent definition skill config"), sql);
        assertNoV4SurfaceSurvives(connection);
      }
    }
  }

  /** 非空 legacy skills 只有短名，无法映射到新的 (sourceId, name) 身份，因此必须显式阻止。 */
  @Test
  void rejectsNonEmptyLegacyAgentDefinitionSkills() throws Exception {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateTo(connection, "3");
      seedLegacyData(connection, emptySkillBindings());
      execute(
          connection,
          "update agent_definition set config ="
              + " jsonb_set(config, '{skills}', '[\"review\"]'::jsonb)");

      FlywayException failure =
          assertThrows(FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

      assertTrue(
          rootMessages(failure).contains("non-empty legacy agent definition skills"),
          rootMessages(failure));
      assertNoV4SurfaceSurvives(connection);
    }
  }

  /** request_spec.skillBindings 缺失或不是数组说明冻结请求已损坏，迁移必须拒绝。 */
  @Test
  void rejectsMalformedFrozenSkillBindings() throws Exception {
    for (String sql :
        List.of(
            "update harness_model_invocation set request_spec = request_spec - 'skillBindings'",
            "update harness_model_invocation"
                + " set request_spec = jsonb_set(request_spec, '{skillBindings}', '{}'::jsonb)")) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, "3");
        seedLegacyData(connection, emptySkillBindings());
        execute(connection, sql);

        FlywayException failure =
            assertThrows(
                FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

        assertTrue(rootMessages(failure).contains("malformed model request skill bindings"), sql);
        assertNoV4SurfaceSurvives(connection);
      }
    }
  }

  /** 非空冻结 skillBindings 携带可用的新身份，但来源行尚不存在，静默保留会让历史指向未知来源，故必须阻止。 */
  @Test
  void rejectsNonEmptyFrozenSkillBindings() throws Exception {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateTo(connection, "3");
      seedLegacyData(connection, frozenSkillBindings());

      FlywayException failure =
          assertThrows(FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

      assertTrue(
          rootMessages(failure).contains("frozen model skill bindings"), rootMessages(failure));
      assertNoV4SurfaceSurvives(connection);
    }
  }

  /** 空数组是唯一允许的既有形状：既不需要转换，也不应因“空”被拒绝；迁移不得改写既有冻结事实。 */
  @Test
  void preservesEmptyLegacySkillFactsUnchanged() throws Exception {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateTo(connection, "3");
      seedLegacyData(connection, emptySkillBindings());
      String requestBefore =
          singleString(
              connection,
              "select request_spec::text from harness_model_invocation"
                  + " where id = '"
                  + MODEL_INVOCATION_ID
                  + "'");
      String configBefore = singleString(connection, "select config::text from agent_definition");

      migrate(connection, "classpath:db/migration");

      assertEquals(4, currentVersion(connection));
      assertEquals(
          requestBefore,
          singleString(
              connection,
              "select request_spec::text from harness_model_invocation"
                  + " where id = '"
                  + MODEL_INVOCATION_ID
                  + "'"));
      assertEquals(
          configBefore, singleString(connection, "select config::text from agent_definition"));
    }
  }

  /** guard 失败必须整体回滚：版本停在 V3，且四张 V4 表一个都不存在。 */
  private static void assertNoV4SurfaceSurvives(Connection connection) throws SQLException {
    assertEquals(3, currentVersion(connection));
    for (String table : V4_TABLES) {
      assertNull(
          regclass(connection, table), () -> "rolled-back migration must not create " + table);
    }
  }

  /** 写入迁移前的合法既有数据：一个 Environment、一条 FAILED 冻结请求与一条 FAILED tool 调用。 */
  private static void seedLegacyData(Connection connection, LegacyFixtures fixtures)
      throws SQLException {
    executeUpdate(
        connection,
        "insert into environment (id, name, registration_token)"
            + " values (?, 'skill-migration-env', 'skill-migration-registration-value')",
        ENVIRONMENT_ID);
    executeUpdate(
        connection,
        "insert into environment (id, name, registration_token)"
            + " values (?, 'skill-migration-env-2', 'skill-migration-registration-value-2')",
        SECOND_ENVIRONMENT_ID);
    executeUpdate(
        connection,
        "insert into agent_provider (name, provider_type, config, connection_generation_id)"
            + " values ('skill-migration-provider', 'openai', '{}'::jsonb, ?)",
        UUID.fromString("00000000-0000-0000-0000-000000000120"));
    executeUpdate(
        connection,
        "insert into agent_model"
            + " (provider_name, name, model_id, config)"
            + " values ('skill-migration-provider', 'skill-migration-model', 'wire-model',"
            + " '{}'::jsonb)");
    executeUpdate(
        connection,
        "insert into agent_definition (name, model_provider_name, model_name, config)"
            + " values ('skill-migration-agent', 'skill-migration-provider',"
            + " 'skill-migration-model', '{\"toolIds\":[],\"skills\":[],\"subagents\":[]}'::jsonb)");
    executeUpdate(
        connection,
        "insert into harness_session (id, name, created_at)"
            + " values (?, 'skill migration session', current_timestamp)",
        SESSION_ID);
    executeUpdate(
        connection,
        "insert into harness_entry"
            + " (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{}'::jsonb, current_timestamp)",
        ROOT_ENTRY_ID,
        SESSION_ID);
    executeUpdate(
        connection,
        "insert into harness_entry"
            + " (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'TURN_START', '{}'::jsonb, current_timestamp)",
        TURN_ENTRY_ID,
        SESSION_ID,
        ROOT_ENTRY_ID);
    executeUpdate(
        connection,
        "insert into harness_thread"
            + " (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled,"
            + " next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, 'skill migration thread', false, 1, 0,"
            + " current_timestamp, current_timestamp)",
        THREAD_ID,
        SESSION_ID,
        ROOT_ENTRY_ID,
        "b".repeat(64));
    executeUpdate(
        connection,
        "insert into harness_model_invocation"
            + " (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
            + " status, attempt, failed_attempts, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?::jsonb, 'FAILED', 0, '[]'::jsonb,"
            + " current_timestamp, current_timestamp)",
        MODEL_INVOCATION_ID,
        THREAD_ID,
        TURN_ENTRY_ID,
        ROOT_ENTRY_ID,
        fixtures.modelRequestJson());
    executeUpdate(
        connection,
        "insert into harness_tool_invocation"
            + " (id, model_invocation_id, assistant_entry_id, call_index, call, binding,"
            + " status, attempt, effects, created_at, updated_at)"
            + " values (?, ?, ?, 0, '{}'::jsonb, null, 'FAILED', 0,"
            + " '{\"version\":1,\"customEntries\":[]}'::jsonb,"
            + " current_timestamp, current_timestamp)",
        TOOL_INVOCATION_ID,
        MODEL_INVOCATION_ID,
        ROOT_ENTRY_ID);
  }

  private static LegacyFixtures emptySkillBindings() throws Exception {
    return fixtures(List.of());
  }

  private static LegacyFixtures frozenSkillBindings() throws Exception {
    return fixtures(
        List.of(
            new SkillBinding(
                EnvironmentId.of(ENVIRONMENT_ID),
                SKILL_SOURCE_ID,
                "review",
                "Review code",
                "/host/skills/review",
                CONTENT_REVISION)));
  }

  private static LegacyFixtures fixtures(List<SkillBinding> skillBindings) throws Exception {
    ModelRequestSpec spec =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            UUID.fromString("00000000-0000-0000-0000-000000000110"),
            modelDescriptor(),
            new ModelVariant("default"),
            1024,
            List.of(),
            List.of(),
            skillBindings,
            List.of(),
            ProviderCacheControl.none());
    ObjectNode encoded = MODEL_REQUEST_CODEC.encodeNode(spec);
    return new LegacyFixtures(MAPPER.writeValueAsString(encoded));
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "skill-migration-provider",
        "skill-migration-model",
        "wire-model",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static void migrateTo(Connection connection, String version) {
    Flyway.configure()
        .dataSource(new SingleConnectionDataSource(connection, true))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(version))
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  private static void migrate(Connection connection, String location) {
    Flyway.configure()
        .dataSource(new SingleConnectionDataSource(connection, true))
        .locations(location)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  private static int currentVersion(Connection connection) throws SQLException {
    return singleInt(
        connection,
        "select max(version::integer) from flyway_schema_history"
            + " where success and version is not null");
  }

  private static String regclass(Connection connection, String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("select to_regclass('public.' || ?)::text")) {
      statement.setString(1, table);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  private static int singleInt(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private static String singleString(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      assertTrue(result.next());
      return result.getString(1);
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.executeUpdate();
    }
  }

  private static void executeUpdate(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      statement.executeUpdate();
    }
  }

  private static String rootMessages(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      if (current.getMessage() != null) {
        messages.append(current.getMessage()).append('\n');
      }
      current = current.getCause();
    }
    return messages.toString();
  }

  private record LegacyFixtures(String modelRequestJson) {}
}

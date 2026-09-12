package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolBindingJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** V3 Workspace 删除迁移的真实 PostgreSQL 数据转换与 fail-fast 契约。 */
class PostgresqlWorkspaceRemovalMigrationTest extends PostgresSchemaSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HistoryEntryPayloadJsonCodec HISTORY_CODEC =
      new HistoryEntryPayloadJsonCodec();
  private static final ModelRequestSpecJsonCodec MODEL_REQUEST_CODEC =
      new ModelRequestSpecJsonCodec();
  private static final ToolBindingJsonCodec TOOL_BINDING_CODEC = new ToolBindingJsonCodec();

  private static final UUID ENVIRONMENT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
  private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
  private static final UUID ROOT_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
  private static final UUID TURN_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");
  private static final UUID ASSISTANT_ENTRY_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000106");
  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000107");
  private static final UUID MODEL_INVOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000108");
  private static final UUID TOOL_INVOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000109");
  private static final UUID DIRECTORY_QUERY_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000110");

  @BeforeEach
  void resetSchema() throws SQLException {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
    }
  }

  /** 测试意图：V1/V2 存量均一次收敛为当前严格 codec 可读形状，且彻底删除 Workspace 表面。 */
  @Test
  void migratesLegacyWorkspaceDataFromEveryPriorVersion() throws Exception {
    for (String priorVersion : List.of("1", "2")) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, priorVersion);
        LegacyFixtures fixtures = legacyFixtures();
        seedLegacyData(connection, fixtures);

        migrate(connection, "classpath:db/migration");

        assertEquals(3, currentVersion(connection));
        assertFalse(columnExists(connection, "chat", "workspace_path"));
        assertNull(regclass(connection, "environment_directory_query"));
        assertEquals(
            0,
            singleInt(
                connection,
                "select count(*) from harness_thread_command"
                    + " where command_type = 'SET_ENVIRONMENT'"));
        assertEquals(
            1,
            singleInt(
                connection,
                "select count(*) from harness_thread_command"
                    + " where command_type = 'SET_AGENT'"));

        String migratedRoot =
            singleString(
                connection,
                "select payload::text from harness_entry where id = '" + ROOT_ENTRY_ID + "'");
        String migratedTurn =
            singleString(
                connection,
                "select payload::text from harness_entry where id = '" + TURN_ENTRY_ID + "'");
        assertFalse(migratedRoot.contains("workspacePath"));
        assertFalse(migratedTurn.contains("workspacePath"));
        assertEquals(fixtures.rootPayload(), HISTORY_CODEC.decode(EntryType.ROOT, migratedRoot));
        assertEquals(
            fixtures.turnStartPayload(), HISTORY_CODEC.decode(EntryType.TURN_START, migratedTurn));

        String migratedRequest =
            singleString(
                connection,
                "select request_spec::text from harness_model_invocation"
                    + " where id = '"
                    + MODEL_INVOCATION_ID
                    + "'");
        assertFalse(migratedRequest.contains("workspacePath"));
        assertFalse(migratedRequest.contains("\"environment\""));
        assertFalse(migratedRequest.contains("\"sourceEnvironment\""));
        assertEquals(fixtures.modelRequest(), MODEL_REQUEST_CODEC.decode(migratedRequest));

        String migratedBinding =
            singleString(
                connection,
                "select binding::text from harness_tool_invocation"
                    + " where id = '"
                    + TOOL_INVOCATION_ID
                    + "'");
        assertFalse(migratedBinding.contains("workspacePath"));
        assertFalse(migratedBinding.contains("\"environment\""));
        assertEquals(fixtures.environmentTool(), TOOL_BINDING_CODEC.decode(migratedBinding));
        assertEquals(
            1,
            singleInt(
                connection,
                "select count(*) from harness_model_invocation"
                    + " where id = '"
                    + MODEL_INVOCATION_ID
                    + "'"));
        assertEquals(
            1,
            singleInt(
                connection,
                "select count(*) from harness_tool_invocation"
                    + " where id = '"
                    + TOOL_INVOCATION_ID
                    + "'"));

        String settingsJson =
            singleString(connection, "select config::text from system_setting where id = 1");
        assertFalse(settingsJson.contains("directoryListTimeoutMillis"));
        assertEquals(SystemSettings.DEFAULT, new SystemSettingsCodec().decode(settingsJson));

        assertSetEnvironmentRejected(connection);
      }
    }
  }

  /** 测试意图：任一未终结 Model 调用都阻止破坏性协议切换，并由 PostgreSQL 回滚 V3 的全部变更。 */
  @Test
  void rejectsMigrationWhileModelInvocationIsActive() throws Exception {
    for (String status : List.of("READY", "DISPATCHING", "RUNNING")) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, "2");
        seedLegacyData(connection, legacyFixtures());
        execute(connection, "update harness_model_invocation set status = '" + status + "'");

        FlywayException failure =
            assertThrows(
                FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

        assertTrue(rootMessages(failure).contains("model invocations are active"), status);
        assertLegacySchemaWasPreserved(connection);
        assertLegacyEnvironmentSettingsWerePreserved(connection);
      }
    }
  }

  /** 测试意图：待审批或执行中的 Tool 调用不能被静默改写为新 workdir 协议。 */
  @Test
  void rejectsMigrationWhileToolInvocationIsActive() throws Exception {
    for (String status : List.of("WAITING_APPROVAL", "READY", "DISPATCHING", "RUNNING")) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, "2");
        seedLegacyData(connection, legacyFixtures());
        execute(connection, "update harness_tool_invocation set status = '" + status + "'");

        FlywayException failure =
            assertThrows(
                FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

        assertTrue(rootMessages(failure).contains("tool invocations are active"), status);
        assertLegacySchemaWasPreserved(connection);
        assertLegacyEnvironmentSettingsWerePreserved(connection);
      }
    }
  }

  /** 测试意图：缺失旧严格字段的数据损坏必须中止并回滚，而不是生成当前 codec 仍无法读取的半迁移 JSON。 */
  @Test
  void rejectsMalformedLegacyJsonWithoutPartialChanges() throws Exception {
    List<MalformedLegacyCase> cases =
        List.of(
            new MalformedLegacyCase(
                "update harness_entry set payload = payload #- '{settings,workspacePath}'"
                    + " where id = '"
                    + ROOT_ENTRY_ID
                    + "'",
                "malformed branch settings"),
            new MalformedLegacyCase(
                "update harness_model_invocation"
                    + " set request_spec = jsonb_set(request_spec, '{toolBindings}',"
                    + " '{}'::jsonb)",
                "malformed model request bindings"),
            new MalformedLegacyCase(
                "update harness_model_invocation"
                    + " set request_spec = request_spec"
                    + " #- '{toolBindings,0,environment,workspacePath}'",
                "malformed model tool binding"),
            new MalformedLegacyCase(
                "update harness_model_invocation"
                    + " set request_spec = request_spec"
                    + " #- '{skillBindings,0,sourceEnvironment,workspacePath}'",
                "malformed model skill binding"),
            new MalformedLegacyCase(
                "update harness_tool_invocation"
                    + " set binding = binding #- '{environment,workspacePath}'",
                "malformed tool invocation binding"),
            new MalformedLegacyCase(
                "update system_setting"
                    + " set config = config #- '{environment,directoryListTimeoutMillis}'",
                "malformed environment settings"));

    for (MalformedLegacyCase malformed : cases) {
      try (Connection connection = newConnection()) {
        resetDatabase(connection);
        migrateTo(connection, "2");
        seedLegacyData(connection, legacyFixtures());
        execute(connection, malformed.sql());

        FlywayException failure =
            assertThrows(
                FlywayException.class, () -> migrate(connection, "classpath:db/migration"));

        assertTrue(rootMessages(failure).contains(malformed.expectedMessage()), malformed.sql());
        assertLegacySchemaWasPreserved(connection);
      }
    }
  }

  private static void assertLegacySchemaWasPreserved(Connection connection) throws SQLException {
    assertEquals(2, currentVersion(connection));
    assertTrue(columnExists(connection, "chat", "workspace_path"));
    assertNotNull(regclass(connection, "environment_directory_query"));
    assertEquals(
        1,
        singleInt(
            connection,
            "select count(*) from harness_thread_command"
                + " where command_type = 'SET_ENVIRONMENT'"));
  }

  private static void assertLegacyEnvironmentSettingsWerePreserved(Connection connection)
      throws SQLException {
    assertTrue(
        singleString(connection, "select config::text from system_setting where id = 1")
            .contains("directoryListTimeoutMillis"));
  }

  private static void assertSetEnvironmentRejected(Connection connection) throws SQLException {
    assertTransactionConstraintViolation(
        connection,
        "ck_harness_thread_command_type",
        () ->
            executeUpdate(
                connection,
                "insert into harness_thread_command"
                    + " (thread_id, sequence, command_type, payload, idempotency_key,"
                    + " request_hash, created_at) values (?, 3, 'SET_ENVIRONMENT',"
                    + " '{\"workspacePath\":null}'::jsonb, ?, ?, current_timestamp)",
                THREAD_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000113"),
                "d".repeat(64)));
  }

  private static void seedLegacyData(Connection connection, LegacyFixtures fixtures)
      throws SQLException {
    executeUpdate(
        connection,
        "insert into environment (id, name, registration_token)"
            + " values (?, 'migration-env', 'fixture-registration-value')",
        ENVIRONMENT_ID);
    executeUpdate(
        connection,
        "insert into chat (id, title, agent_name, workspace_path)"
            + " values (?, 'legacy chat', 'assistant', 'projects/legacy')",
        CHAT_ID);
    executeUpdate(
        connection,
        "insert into harness_session (id, name, created_at)"
            + " values (?, 'migration session', current_timestamp)",
        SESSION_ID);
    executeUpdate(
        connection,
        "insert into harness_entry"
            + " (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', ?::jsonb, current_timestamp)",
        ROOT_ENTRY_ID,
        SESSION_ID,
        fixtures.legacyRootJson());
    executeUpdate(
        connection,
        "insert into harness_entry"
            + " (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'TURN_START', ?::jsonb, current_timestamp)",
        TURN_ENTRY_ID,
        SESSION_ID,
        ROOT_ENTRY_ID,
        fixtures.legacyTurnJson());
    executeUpdate(
        connection,
        "insert into harness_entry"
            + " (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{}'::jsonb, current_timestamp)",
        ASSISTANT_ENTRY_ID,
        SESSION_ID,
        TURN_ENTRY_ID);
    executeUpdate(
        connection,
        "insert into harness_thread"
            + " (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled,"
            + " next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, 'migration thread', false, 3, 0,"
            + " current_timestamp, current_timestamp)",
        THREAD_ID,
        SESSION_ID,
        ASSISTANT_ENTRY_ID,
        "a".repeat(64));
    executeUpdate(
        connection,
        "insert into harness_thread_command"
            + " (thread_id, sequence, command_type, payload, idempotency_key,"
            + " request_hash, created_at) values (?, 1, 'SET_ENVIRONMENT',"
            + " '{\"workspacePath\":\"projects/legacy\"}'::jsonb, ?, ?, current_timestamp)",
        THREAD_ID,
        UUID.fromString("00000000-0000-0000-0000-000000000111"),
        "b".repeat(64));
    executeUpdate(
        connection,
        "insert into harness_thread_command"
            + " (thread_id, sequence, command_type, payload, idempotency_key,"
            + " request_hash, created_at) values (?, 2, 'SET_AGENT',"
            + " '{\"agentName\":\"next-assistant\"}'::jsonb, ?, ?, current_timestamp)",
        THREAD_ID,
        UUID.fromString("00000000-0000-0000-0000-000000000112"),
        "c".repeat(64));
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
        ASSISTANT_ENTRY_ID,
        fixtures.legacyModelRequestJson());
    executeUpdate(
        connection,
        "insert into harness_tool_invocation"
            + " (id, model_invocation_id, assistant_entry_id, call_index, call, binding,"
            + " status, attempt, effects, created_at, updated_at)"
            + " values (?, ?, ?, 0, '{}'::jsonb, ?::jsonb, 'FAILED', 0,"
            + " '{\"version\":1,\"customEntries\":[]}'::jsonb,"
            + " current_timestamp, current_timestamp)",
        TOOL_INVOCATION_ID,
        MODEL_INVOCATION_ID,
        ASSISTANT_ENTRY_ID,
        fixtures.legacyToolBindingJson());
    executeUpdate(
        connection,
        "insert into environment_directory_query"
            + " (id, environment_id, path, status, deadline_at)"
            + " values (?, ?, 'projects/legacy', 'PENDING',"
            + " current_timestamp + interval '1 minute')",
        DIRECTORY_QUERY_ID,
        ENVIRONMENT_ID);
  }

  private static LegacyFixtures legacyFixtures() throws Exception {
    BranchSettings settings =
        new BranchSettings("assistant", new ModelSelection("provider", "model", "default"));
    RootPayload rootPayload = new RootPayload(settings);
    TurnStartPayload turnStartPayload =
        new TurnStartPayload(TurnStartReason.INPUT, settings, THREAD_ID);

    ObjectNode legacyRoot = HISTORY_CODEC.encodeNode(rootPayload);
    ((ObjectNode) legacyRoot.get("settings")).putNull("workspacePath");
    ObjectNode legacyTurn = HISTORY_CODEC.encodeNode(turnStartPayload);
    ((ObjectNode) legacyTurn.get("settings")).put("workspacePath", "projects/legacy");

    EnvironmentId environmentId = EnvironmentId.of(ENVIRONMENT_ID);
    ToolBinding environmentTool = toolBinding("read", true, environmentId);
    ToolBinding hostTool = toolBinding("host", false, null);
    ModelRequestSpec modelRequest =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            UUID.fromString("00000000-0000-0000-0000-000000000120"),
            modelDescriptor(),
            new ModelVariant("default"),
            1024,
            List.of(),
            List.of(environmentTool, hostTool),
            List.of(
                new SkillBinding("review", "Review code", environmentId),
                new SkillBinding("host-skill", "Host skill", null)),
            List.of(),
            ProviderCacheControl.none());

    ObjectNode legacyRequest = MODEL_REQUEST_CODEC.encodeNode(modelRequest);
    ArrayNode toolBindings = (ArrayNode) legacyRequest.get("toolBindings");
    for (JsonNode value : toolBindings) {
      toLegacyEnvironmentBinding((ObjectNode) value, "environmentId", "environment");
    }
    ArrayNode skillBindings = (ArrayNode) legacyRequest.get("skillBindings");
    for (JsonNode value : skillBindings) {
      toLegacyEnvironmentBinding((ObjectNode) value, "sourceEnvironmentId", "sourceEnvironment");
    }
    ObjectNode legacyTool = TOOL_BINDING_CODEC.encodeNode(environmentTool);
    toLegacyEnvironmentBinding(legacyTool, "environmentId", "environment");

    return new LegacyFixtures(
        rootPayload,
        turnStartPayload,
        modelRequest,
        environmentTool,
        MAPPER.writeValueAsString(legacyRoot),
        MAPPER.writeValueAsString(legacyTurn),
        MAPPER.writeValueAsString(legacyRequest),
        MAPPER.writeValueAsString(legacyTool));
  }

  private static void toLegacyEnvironmentBinding(
      ObjectNode node, String currentField, String legacyField) {
    JsonNode environmentId = node.remove(currentField);
    if (environmentId == null || environmentId.isNull()) {
      node.putNull(legacyField);
      return;
    }
    ObjectNode legacy = node.putObject(legacyField);
    legacy.set("environmentId", environmentId);
    legacy.put("workspacePath", "projects/legacy");
  }

  private static ToolBinding toolBinding(
      String name, boolean environmentRequired, EnvironmentId environmentId) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            "1",
            "Migration " + name,
            name,
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    return new ToolBinding(
        new AgentToolDefinition(
            new AgentToolId("migration." + name), descriptor, ToolVisibility.SELECTABLE),
        new ContributorBinding("migration", name, List.of()),
        environmentRequired,
        environmentId);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
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

  private static int currentVersion(Connection connection) throws SQLException {
    return singleInt(
        connection,
        "select max(version::integer) from flyway_schema_history"
            + " where success and version is not null");
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select count(*) from information_schema.columns"
                + " where table_schema = 'public' and table_name = ? and column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getInt(1) == 1;
      }
    }
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

  private record LegacyFixtures(
      RootPayload rootPayload,
      TurnStartPayload turnStartPayload,
      ModelRequestSpec modelRequest,
      ToolBinding environmentTool,
      String legacyRootJson,
      String legacyTurnJson,
      String legacyModelRequestJson,
      String legacyToolBindingJson) {}

  private record MalformedLegacyCase(String sql, String expectedMessage) {}
}

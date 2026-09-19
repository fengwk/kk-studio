package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 PostgreSQL schema 是否完全表达非 Harness 的业务数据。 */
class PostgresqlBusinessSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void agentResourcesKeepProviderAndModelOwnership() throws SQLException {
    String providerName = "provider-" + FIXTURE_IDS.incrementAndGet();
    String modelName = "model-" + FIXTURE_IDS.incrementAndGet();
    for (String invalid : new String[] {" provider ", "\tprovider\t", "provider/name"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn, "ck_agent_provider_name", () -> insertProvider(conn, invalid));
      }
    }
    try (Connection conn = newConnection()) {
      insertProvider(conn, providerName);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_agent_model_provider", () -> insertModel(conn, "missing-provider", modelName));
    }
    try (Connection conn = newConnection()) {
      insertModel(conn, providerName, modelName);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_agent_model_name", () -> insertModel(conn, providerName, "\nmodel\n"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_definition_name",
          () -> insertDefinition(conn, "\tagent\t", providerName, modelName));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_definition_name",
          () -> insertDefinition(conn, "agent/name", providerName, modelName));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_agent_definition_model",
          () -> insertDefinition(conn, "missing-agent", "missing-provider", "missing-model"));
    }
  }

  @Test
  void providerTypeCheckAllowsOnlyStableWireValues() throws SQLException {
    // 真实 PostgreSQL schema 必须接受四个稳定 wire 值，并在数据库边界拒绝大小写、空白和未知值。
    for (String providerType : new String[] {"openai", "openai_response", "anthropic", "google"}) {
      try (Connection conn = newConnection()) {
        insertProvider(conn, "provider-" + FIXTURE_IDS.incrementAndGet(), providerType);
      }
    }
    for (String invalid : new String[] {"OPENAI", "openai ", "OpenAI", "missing"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "ck_agent_provider_provider_type",
            () ->
                insertProvider(conn, "invalid-provider-" + FIXTURE_IDS.incrementAndGet(), invalid));
      }
    }
  }

  @Test
  void chatAgentNameContractIsCanonicalAndThreadVersionStaysAppOwned() throws SQLException {
    try (Connection conn = newConnection()) {
      insertChat(conn, uuid());
      insertChat(conn, uuid());
    }

    for (String invalid : new String[] {" ", " agent ", "\tagent\t", "agent/name"}) {
      try (Connection conn = newConnection();
          PreparedStatement ps =
              conn.prepareStatement(
                  "insert into chat (id, title, agent_name) values (?, 'schema-test', ?)")) {
        ps.setObject(1, uuid());
        ps.setString(2, invalid);
        assertTransactionConstraintViolation(conn, "ck_chat_agent_name", () -> ps.executeUpdate());
      }
    }

    UUID threadId = uuid();
    UUID sessionId = uuid();
    UUID entryId = uuid();
    try (Connection conn = newConnection()) {
      insertThread(conn, threadId, sessionId, entryId);
      assertEquals(
          0L, queryLong(conn, "select version from harness_thread where id = ?", threadId));
      try (PreparedStatement ps =
          conn.prepareStatement("update harness_thread set yolo_enabled = true where id = ?")) {
        ps.setObject(1, threadId);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(
          0L,
          queryLong(conn, "select version from harness_thread where id = ?", threadId),
          "version is owned by HarnessRuntime; an application UPDATE must never bump it");
    }
  }

  /** Thread 通知必须在提交后携带严格 threadId:version；回滚既不发通知，也不改变权威版本。 */
  @Test
  void threadVersionNotificationsPublishOnlyCommittedCanonicalPayloads() throws Exception {
    UUID threadId = uuid();
    UUID sessionId = uuid();
    UUID entryId = uuid();
    String insertedPayload = threadId + ":0";

    try (Connection listener = newConnection();
        Connection writer = newConnection();
        Statement listen = listener.createStatement()) {
      listener.setAutoCommit(true);
      PGConnection notifications = listener.unwrap(PGConnection.class);
      listen.execute("LISTEN harness_thread_version");

      writer.setAutoCommit(false);
      insertThread(writer, threadId, sessionId, entryId);
      assertNoNotification(notifications);
      writer.commit();
      assertEquals(
          List.of(insertedPayload),
          awaitPayloads(notifications, "harness_thread_version", insertedPayload),
          "INSERT notification must be exactly <uuid>:<nonnegative-version>");

      updateThreadVersion(writer, threadId, 1L);
      assertNoNotification(notifications);
      writer.rollback();
      assertNoNotification(notifications);
      assertEquals(
          0L,
          queryLong(writer, "select version from harness_thread where id = ?", threadId),
          "rolled-back version writes must leave the authoritative row unchanged");

      updateThreadVersion(writer, threadId, 1L);
      updateThreadVersion(writer, threadId, 2L);
      writer.commit();
      List<String> payloads =
          awaitPayloads(notifications, "harness_thread_version", threadId + ":2");
      assertTrue(
          Set.of(threadId + ":1", threadId + ":2").containsAll(payloads),
          () -> "transaction notifications must contain only written versions: " + payloads);
      assertTrue(
          payloads.contains(threadId + ":2"),
          () -> "the committed final thread version must be observable: " + payloads);
      assertEquals(
          2L,
          queryLong(writer, "select version from harness_thread where id = ?", threadId),
          "multiple updates in one transaction must preserve the final application version");
    }
  }

  /** Singleton settings 通知遵循 PostgreSQL 事务边界；同事务多次更新允许标准折叠或多通知，但最终版本必须可观察且数据库绝不代写版本。 */
  @Test
  void systemSettingsNotificationsRespectTransactionsAndFinalVersion() throws Exception {
    try (Connection listener = newConnection();
        Connection writer = newConnection();
        Statement listen = listener.createStatement()) {
      listener.setAutoCommit(true);
      PGConnection notifications = listener.unwrap(PGConnection.class);
      listen.execute("LISTEN system_settings_changed");

      writer.setAutoCommit(false);
      updateSystemSettingsVersion(writer, 1L);
      assertNoNotification(notifications);
      writer.commit();
      assertEquals(
          List.of("1"),
          awaitPayloads(notifications, "system_settings_changed", "1"),
          "committed settings notification payload must be NEW.version text");

      updateSystemSettingsVersion(writer, 2L);
      assertNoNotification(notifications);
      writer.rollback();
      assertNoNotification(notifications);
      assertEquals(
          1L,
          queryLong(writer, "select version from system_setting where id = ?", 1L),
          "rolled-back settings updates must neither notify nor change the authoritative version");

      updateSystemSettingsVersion(writer, 2L);
      updateSystemSettingsVersion(writer, 3L);
      writer.commit();
      List<String> payloads = awaitPayloads(notifications, "system_settings_changed", "3");
      assertTrue(
          Set.of("2", "3").containsAll(payloads),
          () -> "transaction notifications must contain only written versions: " + payloads);
      assertTrue(
          payloads.contains("3"),
          () -> "the committed final settings version must be observable: " + payloads);
      assertEquals(
          3L,
          queryLong(writer, "select version from system_setting where id = ?", 1L),
          "the trigger must preserve the final version written by the application");
    }
  }

  @Test
  void canvasLinkStaysWithinItsOwningCanvas() throws SQLException {
    UUID firstCanvasId = uuid();
    UUID secondCanvasId = uuid();
    UUID firstNodeId = uuid();
    UUID secondNodeId = uuid();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, firstCanvasId);
      insertCanvas(conn, secondCanvasId);
      insertNode(conn, firstNodeId, firstCanvasId);
      insertNode(conn, secondNodeId, secondCanvasId);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_link_target",
          () -> insertLink(conn, firstCanvasId, firstNodeId, secondNodeId));
    }

    UUID resourceId = uuid();
    try (Connection conn = newConnection()) {
      insertResource(conn, resourceId, secondCanvasId, secondNodeId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_resource_owner",
          () -> insertResource(conn, uuid(), firstCanvasId, secondNodeId));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_resource_blob",
          () -> insertResourceWithBlob(conn, uuid(), firstCanvasId, firstNodeId, uuid()));
    }
  }

  @Test
  void canvasSchemaConstraintsRejectBadInputs() throws SQLException {
    UUID canvasId = uuid();
    UUID nodeId = uuid();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertNode(conn, nodeId, canvasId);
    }

    // Document：空白标题被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_canvas_document_title_nonblank", () -> insertCanvasRow(conn, canvasId, " "));
    }

    // Node：空白名称被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_name_nonblank",
          () -> insertNodeRow(conn, nodeId, canvasId, "", 100, 100));
    }

    // Node：零宽度被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_geometry",
          () -> insertNodeRow(conn, nodeId, canvasId, "n", 0, 100));
    }

    // Node：零高度被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_geometry",
          () -> insertNodeRow(conn, nodeId, canvasId, "n", 100, 0));
    }

    // Link：自环被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_canvas_link_distinct", () -> insertLink(conn, canvasId, nodeId, nodeId));
    }

    // Dedup：request_hash 必须包含一个 SHA-256 十六进制摘要。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_command_dedup_request_hash",
          () -> insertDedup(conn, canvasId, "0".repeat(63)));
    }

    // 健全性检查：完全合规的 dedup 行可被插入。
    try (Connection conn = newConnection()) {
      insertDedup(conn, canvasId, "f".repeat(64));
    }
  }

  /** 操作信箱契约：同一资源至多一个未终结操作、终态事实与状态一致、参数必须是 object， 且 resource_id 故意不建外键，资源删除后历史仍可读。 */
  @Test
  void environmentOperationKeepsLifecycleAndSurvivesResourceDeletion() throws SQLException {
    UUID environmentId = uuid();
    UUID resourceId = uuid();
    insertEnvironment(environmentId);

    // 同一资源的第二个未终结操作被拒绝：PENDING 与 RUNNING 都不能并存。
    try (Connection conn = newConnection()) {
      insertOperation(conn, uuid(), environmentId, resourceId, "PENDING");
      assertTransactionConstraintViolation(
          conn,
          "uk_environment_operation_active",
          () -> insertOperation(conn, uuid(), environmentId, resourceId, "PENDING"));
      assertTransactionConstraintViolation(
          conn,
          "uk_environment_operation_active",
          () -> insertOperation(conn, uuid(), environmentId, resourceId, "RUNNING"));
    }
    // 终态不佔用未终结名额：失败历史之后仍可为同一资源新建操作。
    UUID terminalResourceId = uuid();
    try (Connection conn = newConnection()) {
      insertOperation(conn, uuid(), environmentId, terminalResourceId, "FAILED");
      insertOperation(conn, uuid(), environmentId, terminalResourceId, "PENDING");
    }
    // 终态事实必须与状态一致：PENDING 不得携带认领事实。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_state",
          () -> insertClaimedPendingOperation(conn, uuid(), environmentId, resourceId));
    }
    // arguments 必须是 JSON object。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_arguments_object",
          () -> insertOperationWithArguments(conn, uuid(), environmentId, resourceId, "[]"));
    }
    // 未知操作类型被拒绝（只允许 MCP_SERVER_DISCOVER）。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_type",
          () -> insertOperationWithType(conn, uuid(), environmentId, resourceId, "UNKNOWN_TYPE"));
    }
    // 未知状态被拒绝（status 与 state 都只接受同一组终态值，报出先命中者）。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_state",
          () -> insertOperationWithStatus(conn, uuid(), environmentId, resourceId, "CLAIMED"));
    }

    // resource_id 没有外键：删除资源后操作历史仍然存在且可读。
    UUID historyOperationId = uuid();
    UUID mcpServerId = uuid();
    try (Connection conn = newConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into mcp_server (id, name, connection_type, environment_id,"
                  + " connection_config, timeout_millis) values (?, ?, 'LOCAL', ?,"
                  + " '{\"command\":[\"node\",\"server.js\"],\"cwd\":\"/srv\",\"env\":{}}'::jsonb,"
                  + " 60000)")) {
        ps.setObject(1, mcpServerId);
        ps.setString(2, "mcp_" + uuid().toString().substring(0, 8));
        ps.setObject(3, environmentId);
        assertEquals(1, ps.executeUpdate());
      }
      insertOperation(conn, historyOperationId, environmentId, mcpServerId, "FAILED");
      try (PreparedStatement ps = conn.prepareStatement("delete from mcp_server where id = ?")) {
        ps.setObject(1, mcpServerId);
        assertEquals(
            1, ps.executeUpdate(), "mcp_server deletion must succeed while history exists");
      }
    }
    assertEquals(
        1L,
        queryLong("select count(*) from environment_operation where id = ?", historyOperationId),
        "operation history must survive resource deletion");
  }

  @Test
  void canvasNodeDeletionIsRestrictedUntilApplicationCleanup() throws SQLException {
    UUID canvasId = uuid();
    UUID sourceNode = uuid();
    UUID targetNode = uuid();
    UUID resourceId = uuid();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertNode(conn, sourceNode, canvasId);
      insertNode(conn, targetNode, canvasId);
      insertResource(conn, resourceId, canvasId, sourceNode);
      insertLink(conn, canvasId, sourceNode, targetNode);
    }

    // 校验 link 存在。
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select count(*) from canvas_link where canvas_id = ?"
                    + " and source_node_id = ? and target_node_id = ?")) {
      ps.setObject(1, canvasId);
      ps.setObject(2, sourceNode);
      ps.setObject(3, targetNode);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertEquals(1L, rs.getLong(1));
      }
    }

    // 删除源节点：存在 link 时 RESTRICT 拒绝（删除只能由应用按显式顺序驱动）。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_canvas_link_source", () -> deleteNode(conn, sourceNode, canvasId));
    }

    // 应用先删 link，再删节点：owned resource 使节点删除仍被 RESTRICT 拒绝。
    try (Connection conn = newConnection()) {
      deleteLink(conn, canvasId, sourceNode, targetNode);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_canvas_resource_owner", () -> deleteNode(conn, sourceNode, canvasId));
    }

    // 应用删除 owned resource 后节点删除成功；资源为硬删除（blob 引用由 StorageBlobManager 释放）。
    try (Connection conn = newConnection()) {
      deleteResource(conn, resourceId);
    }
    try (Connection conn = newConnection()) {
      assertEquals(1, deleteNode(conn, sourceNode, canvasId));
      assertEquals(
          0L,
          queryLong(conn, "select count(*) from canvas_resource where id = ?", resourceId),
          "resource rows are hard deleted by the application");
      assertEquals(
          1L, queryLong(conn, "select count(*) from canvas_node where id = ?", targetNode));
    }
  }

  private void insertProvider(Connection conn, String name) throws SQLException {
    insertProvider(conn, name, "openai");
  }

  private void insertProvider(Connection conn, String name, String providerType)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_provider (name, provider_type, config, connection_generation_id)"
                + " values (?, ?, '{}'::jsonb, ?::uuid)")) {
      ps.setString(1, name);
      ps.setString(2, providerType);
      ps.setObject(3, UUID.randomUUID());
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertModel(Connection conn, String providerName, String name) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_model (provider_name, name, model_id, config)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
      ps.setString(1, providerName);
      ps.setString(2, name);
      // modelId 与逻辑 name 独立：使用独立的 wire 序号，既不复制 name，也不受 name 的空白边界影响。
      ps.setString(3, "wire-" + FIXTURE_IDS.incrementAndGet());
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertDefinition(
      Connection conn, String name, String modelProviderName, String modelName)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_definition (name, model_provider_name, model_name, config)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
      ps.setString(1, name);
      ps.setString(2, modelProviderName);
      ps.setString(3, modelName);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static UUID uuid() {
    return new UUID(0L, FIXTURE_IDS.incrementAndGet());
  }

  private void insertEnvironment(UUID environmentId) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into environment (id, name, registration_token) values (?, ?, ?)")) {
      ps.setObject(1, environmentId);
      ps.setString(2, "skill-source-env-" + FIXTURE_IDS.incrementAndGet());
      ps.setString(3, "skill-source-token-" + FIXTURE_IDS.incrementAndGet());
      assertEquals(1, ps.executeUpdate());
    }
  }

  /** 插入一行操作，认领与终态事实严格按状态给出；PENDING 不携带任何认领事实。 */
  private void insertOperation(
      Connection conn, UUID operationId, UUID environmentId, UUID resourceId, String status)
      throws SQLException {
    boolean claimed =
        "RUNNING".equals(status) || "SUCCEEDED".equals(status) || "UNKNOWN".equals(status);
    boolean finished =
        "SUCCEEDED".equals(status) || "FAILED".equals(status) || "UNKNOWN".equals(status);
    boolean failed = "FAILED".equals(status) || "UNKNOWN".equals(status);
    // 认领与终态时刻都用数据库时间：与列默认值 created_at 同源，否则 JVM 时钟相对数据库的毫秒级偏差
    // 会让 ck_environment_operation_time_order 偶发失败。
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, resource_type, resource_id,"
                + " operation_type, status, resource_version, arguments, parameter_summary,"
                + " deadline_at, owner_node_id, lease_token, started_at, finished_at,"
                + " result_summary, failure_code, failure_message)"
                + " values (?, ?, 'MCP_SERVER', ?, 'MCP_SERVER_DISCOVER', ?, 3, '{}'::jsonb, '{}'::jsonb,"
                + " current_timestamp + interval '1 minute', ?, ?,"
                + " case when ?::boolean then current_timestamp end,"
                + " case when ?::boolean then current_timestamp end,"
                + " ?::jsonb, ?, ?)")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, resourceId);
      ps.setString(4, status);
      if (claimed) {
        ps.setObject(5, uuid());
        ps.setObject(6, uuid());
      } else {
        ps.setNull(5, Types.OTHER);
        ps.setNull(6, Types.OTHER);
      }
      ps.setBoolean(7, claimed);
      ps.setBoolean(8, finished);
      ps.setObject(9, "SUCCEEDED".equals(status) ? "{}" : null, Types.OTHER);
      ps.setObject(10, failed ? "DISCOVER_FAILED" : null, Types.VARCHAR);
      ps.setObject(11, failed ? "discover failed" : null, Types.VARCHAR);
      assertEquals(1, ps.executeUpdate());
    }
  }

  /** 插入一行已认领的 PENDING 操作：用于证明状态与认领事实必须一致。 */
  private void insertClaimedPendingOperation(
      Connection conn, UUID operationId, UUID environmentId, UUID resourceId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, resource_type, resource_id,"
                + " operation_type, status, resource_version, arguments, parameter_summary,"
                + " deadline_at, owner_node_id, lease_token, started_at)"
                + " values (?, ?, 'MCP_SERVER', ?, 'MCP_SERVER_DISCOVER', 'PENDING', 3, '{}'::jsonb,"
                + " '{}'::jsonb, current_timestamp + interval '1 minute', ?, ?, current_timestamp)")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, resourceId);
      ps.setObject(4, uuid());
      ps.setObject(5, uuid());
      ps.executeUpdate();
    }
  }

  private void insertOperationWithStatus(
      Connection conn, UUID operationId, UUID environmentId, UUID resourceId, String status)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, resource_type, resource_id,"
                + " operation_type, status, resource_version, arguments, parameter_summary,"
                + " deadline_at) values (?, ?, 'MCP_SERVER', ?, 'MCP_SERVER_DISCOVER', ?, 3,"
                + " '{}'::jsonb, '{}'::jsonb, current_timestamp + interval '1 minute')")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, resourceId);
      ps.setString(4, status);
      ps.executeUpdate();
    }
  }

  private void insertOperationWithType(
      Connection conn, UUID operationId, UUID environmentId, UUID resourceId, String type)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, resource_type, resource_id,"
                + " operation_type, status, resource_version, arguments, parameter_summary,"
                + " deadline_at) values (?, ?, 'MCP_SERVER', ?, ?, 'PENDING', 3, '{}'::jsonb,"
                + " '{}'::jsonb, current_timestamp + interval '1 minute')")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, resourceId);
      ps.setString(4, type);
      ps.executeUpdate();
    }
  }

  private void insertOperationWithArguments(
      Connection conn, UUID operationId, UUID environmentId, UUID resourceId, String argumentsJson)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, resource_type, resource_id,"
                + " operation_type, status, resource_version, arguments, parameter_summary,"
                + " deadline_at) values (?, ?, 'MCP_SERVER', ?, 'MCP_SERVER_DISCOVER', 'PENDING', 3,"
                + " ?::jsonb, '{}'::jsonb, current_timestamp + interval '1 minute')")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, resourceId);
      ps.setString(4, argumentsJson);
      ps.executeUpdate();
    }
  }

  private void insertChat(Connection conn, UUID id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into chat (id, title, agent_name)"
                + " values (?, 'schema-test', 'schema-agent')")) {
      ps.setObject(1, id);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertThread(Connection conn, UUID threadId, UUID sessionId, UUID entryId)
      throws SQLException {
    try (PreparedStatement session =
            conn.prepareStatement(
                "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)");
        PreparedStatement entry =
            conn.prepareStatement(
                "insert into harness_entry (id, session_id, entry_type, payload, created_at)"
                    + " values (?, ?, 'ROOT', '{}'::jsonb, current_timestamp)");
        PreparedStatement thread =
            conn.prepareStatement(
                "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
                    + " name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, '"
                    + "0".repeat(64)
                    + "', 'schema-business-test-thread', false, 1, 0, current_timestamp, current_timestamp)")) {
      session.setObject(1, sessionId);
      session.setObject(2, "schema-business-test-session");
      assertEquals(1, session.executeUpdate());
      entry.setObject(1, entryId);
      entry.setObject(2, sessionId);
      assertEquals(1, entry.executeUpdate());
      thread.setObject(1, threadId);
      thread.setObject(2, sessionId);
      thread.setObject(3, entryId);
      assertEquals(1, thread.executeUpdate());
    }
  }

  private static void updateThreadVersion(Connection conn, UUID threadId, long version)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("update harness_thread set version = ? where id = ?")) {
      ps.setLong(1, version);
      ps.setObject(2, threadId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static void updateSystemSettingsVersion(Connection conn, long version)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("update system_setting set version = ? where id = 1")) {
      ps.setLong(1, version);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static List<String> awaitPayloads(
      PGConnection connection, String channel, String finalPayload) throws SQLException {
    List<String> payloads = new ArrayList<>();
    long deadlineNanos = System.nanoTime() + 5_000_000_000L;
    while (!payloads.contains(finalPayload) && System.nanoTime() < deadlineNanos) {
      PGNotification[] received = connection.getNotifications(250);
      if (received == null) {
        continue;
      }
      for (PGNotification notification : received) {
        assertEquals(channel, notification.getName());
        payloads.add(notification.getParameter());
      }
    }
    assertTrue(
        payloads.contains(finalPayload),
        () -> "timed out waiting for final payload " + finalPayload + "; received=" + payloads);
    return payloads;
  }

  private static void assertNoNotification(PGConnection connection) throws SQLException {
    PGNotification[] notifications = connection.getNotifications(200);
    assertTrue(
        notifications == null || notifications.length == 0,
        "rolled-back or uncommitted writes must not notify");
  }

  private static long queryLong(String sql, UUID id) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getLong(1);
      }
    }
  }

  private long queryLong(Connection conn, String sql, UUID id) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long queryLong(Connection conn, String sql, long id) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setLong(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void insertCanvas(Connection conn, UUID id) throws SQLException {
    insertCanvasRow(conn, id, "schema-test");
  }

  private void insertCanvasRow(Connection conn, UUID id, String title) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("insert into canvas_document (id, title) values (?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, title);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertNode(Connection conn, UUID id, UUID canvasId) throws SQLException {
    String name = "node-" + id;
    insertNodeRow(conn, id, canvasId, name, 100, 100);
  }

  private void insertNodeRow(
      Connection conn, UUID id, UUID canvasId, String name, double width, double height)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_node (id, canvas_id, name, x, y, width, height)"
                + " values (?, ?, ?, 0, 0, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, canvasId);
      ps.setString(3, name);
      ps.setDouble(4, width);
      ps.setDouble(5, height);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertDedup(Connection conn, UUID canvasId, String requestHash) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_command_dedup (canvas_id, idempotency_key, request_hash)"
                + " values (?, ?, ?)")) {
      ps.setObject(1, canvasId);
      ps.setObject(2, uuid());
      ps.setString(3, requestHash);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertLink(Connection conn, UUID canvasId, UUID sourceNodeId, UUID targetNodeId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_link (canvas_id, source_node_id, target_node_id)"
                + " values (?, ?, ?)")) {
      ps.setObject(1, canvasId);
      ps.setObject(2, sourceNodeId);
      ps.setObject(3, targetNodeId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertResource(Connection conn, UUID id, UUID canvasId, UUID ownerNodeId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_resource"
                + " (id, canvas_id, owner_node_id, resource_index, name, text_content)"
                + " values (?, ?, ?, 0, 'image', 'x')")) {
      ps.setObject(1, id);
      ps.setObject(2, canvasId);
      ps.setObject(3, ownerNodeId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertResourceWithBlob(
      Connection conn, UUID id, UUID canvasId, UUID ownerNodeId, UUID blobId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_resource"
                + " (id, canvas_id, owner_node_id, resource_index, blob_id, name)"
                + " values (?, ?, ?, 0, ?, 'image')")) {
      ps.setObject(1, id);
      ps.setObject(2, canvasId);
      ps.setObject(3, ownerNodeId);
      ps.setObject(4, blobId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private int deleteNode(Connection conn, UUID nodeId, UUID canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("delete from canvas_node where id = ? and canvas_id = ?")) {
      ps.setObject(1, nodeId);
      ps.setObject(2, canvasId);
      return ps.executeUpdate();
    }
  }

  private void deleteLink(Connection conn, UUID canvasId, UUID sourceNodeId, UUID targetNodeId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "delete from canvas_link where canvas_id = ? and source_node_id = ?"
                + " and target_node_id = ?")) {
      ps.setObject(1, canvasId);
      ps.setObject(2, sourceNodeId);
      ps.setObject(3, targetNodeId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void deleteResource(Connection conn, UUID resourceId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("delete from canvas_resource where id = ?")) {
      ps.setObject(1, resourceId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}

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

  /**
   * V4 来源配置的业务契约：path/git 形状互斥、每 Environment 至多一个缺省来源、applied 围栏、 状态与已应用事实一致，以及 UNAPPLIED
   * 允许保留旧的已应用事实但不允许残留错误。
   */
  @Test
  void skillSourceKeepsTypeShapeAndAppliedFence() throws SQLException {
    UUID environmentId = uuid();
    insertEnvironment(environmentId);

    // path 与 git 字段互斥：两种类型的理想行都能插入。
    try (Connection conn = newConnection()) {
      insertSource(conn, uuid(), environmentId, "path", "/skills", false, 0L, "UNAPPLIED", null);
    }
    try (Connection conn = newConnection()) {
      insertGitSource(
          conn, uuid(), environmentId, "https://git.example/repo.git", "main", "skills", 0L);
    }

    // path 来源必须且只能携带 path。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_type_shape",
          () ->
              insertPathSourceWithGitUrl(
                  conn, uuid(), environmentId, "/skills", "https://git.example/repo.git"));
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_type_shape",
          () ->
              conn.prepareStatement(
                      "insert into environment_skill_source"
                          + " (source_id, environment_id, source_type, default_source)"
                          + " values ('"
                          + uuid()
                          + "', '"
                          + environmentId
                          + "', 'path', false)")
                  .executeUpdate());
    }
    // git 来源不能是缺省来源：缺省发现只允许平台约定的 PATH。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_type_shape",
          () ->
              insertGitSource(
                  conn,
                  uuid(),
                  environmentId,
                  "https://git.example/repo.git",
                  null,
                  null,
                  0L,
                  true));
    }
    // 空白 path 与未知来源类型都被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_path",
          () ->
              insertSource(
                  conn, uuid(), environmentId, "path", "  ", false, 0L, "UNAPPLIED", null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_type",
          () ->
              insertSource(conn, uuid(), environmentId, "http", "x", false, 0L, "UNAPPLIED", null));
    }

    // 每个 Environment 至多一个缺省来源。
    UUID defaultEnvironmentId = uuid();
    insertEnvironment(defaultEnvironmentId);
    try (Connection conn = newConnection()) {
      insertSource(
          conn, uuid(), defaultEnvironmentId, "path", "/skills", true, 0L, "UNAPPLIED", null);
      assertTransactionConstraintViolation(
          conn,
          "uk_environment_skill_source_default",
          () ->
              insertSource(
                  conn,
                  uuid(),
                  defaultEnvironmentId,
                  "path",
                  "/other",
                  true,
                  0L,
                  "UNAPPLIED",
                  null));
    }
  }

  /** V4 来源应用状态机：READY 必须命中当前版本，FAILED 必须带错误，UNAPPLIED 不得残留错误。 */
  @Test
  void skillSourceStatusMatchesAppliedFacts() throws SQLException {
    UUID environmentId = uuid();
    insertEnvironment(environmentId);

    // READY 的 applied_version 必须等于 version。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_state",
          () ->
              insertSource(conn, uuid(), environmentId, "path", "/skills", false, 2L, "READY", 1L));
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_state",
          () ->
              insertSource(
                  conn, uuid(), environmentId, "path", "/skills", false, 2L, "READY", null));
    }
    // 被应用事实不得超过配置版本。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_applied_fence",
          () ->
              insertSource(
                  conn, uuid(), environmentId, "path", "/skills", false, 1L, "UNAPPLIED", 2L));
    }
    // FAILED 必须携带非空错误码与描述。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_state",
          () ->
              insertSource(
                  conn, uuid(), environmentId, "path", "/skills", false, 1L, "FAILED", null));
    }
    // UNAPPLIED 不允许残留错误（配置刚改后错误必须先被清空）。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_state",
          () ->
              insertSourceWithError(
                  conn, uuid(), environmentId, "UNAPPLIED", 1L, "SCAN_FAILED", "boom"));
    }
    // 未知状态被拒绝。status 与 state 两个 check 都只接受同样三个值，PostgreSQL 报出先命中者，
    // 因此这里按 state 断言；两个约束存在是为了让错误信息定位到“取值”还是“状态组合”。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_source_state",
          () ->
              insertSource(
                  conn, uuid(), environmentId, "path", "/skills", false, 0L, "PENDING", null));
    }
    // 合法 FAILED 行可以插入：状态与错误并存是唯一允许的错误形状。
    try (Connection conn = newConnection()) {
      insertSourceWithError(conn, uuid(), environmentId, "FAILED", 1L, "SCAN_FAILED", "boom");
    }
  }

  /** V4 操作信箱契约：同一来源至多一个未终结操作、终态事实与状态一致、参数必须是 object， 且 source_id 故意不建外键，来源删除后历史仍可读。 */
  @Test
  void skillOperationKeepsLifecycleAndSurvivesSourceDeletion() throws SQLException {
    UUID environmentId = uuid();
    UUID sourceId = uuid();
    insertEnvironment(environmentId);
    try (Connection conn = newConnection()) {
      insertSource(conn, sourceId, environmentId, "path", "/skills", true, 3L, "UNAPPLIED", null);
    }

    // 同一来源的第二个未终结操作被拒绝：PENDING 与 RUNNING 都不能并存。
    try (Connection conn = newConnection()) {
      insertOperation(conn, uuid(), environmentId, sourceId, "PENDING");
      assertTransactionConstraintViolation(
          conn,
          "uk_environment_operation_active",
          () -> insertOperation(conn, uuid(), environmentId, sourceId, "PENDING"));
      assertTransactionConstraintViolation(
          conn,
          "uk_environment_operation_active",
          () -> insertOperation(conn, uuid(), environmentId, sourceId, "RUNNING"));
    }
    // 终态不佔用未终结名额：失败历史之后仍可为同一来源新建操作。
    UUID terminalSourceId = uuid();
    try (Connection conn = newConnection()) {
      insertSource(
          conn, terminalSourceId, environmentId, "path", "/terminal", false, 0L, "UNAPPLIED", null);
      insertOperation(conn, uuid(), environmentId, terminalSourceId, "FAILED");
      insertOperation(conn, uuid(), environmentId, terminalSourceId, "PENDING");
    }
    // 终态事实必须与状态一致：PENDING 不得携带认领事实。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_state",
          () -> insertClaimedPendingOperation(conn, uuid(), environmentId, sourceId));
    }
    // arguments 必须是 JSON object。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_arguments_object",
          () -> insertOperationWithArguments(conn, uuid(), environmentId, sourceId, "[]"));
    }
    // 未知操作类型被拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_type",
          () -> insertOperationWithType(conn, uuid(), environmentId, sourceId, "SKILL_UNINSTALL"));
    }
    // 未知状态被拒绝（status 与 state 都只接受同一组终态值，报出先命中者）。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_operation_state",
          () -> insertOperationWithStatus(conn, uuid(), environmentId, sourceId, "CLAIMED"));
    }

    // source_id 没有外键：删除来源后操作历史仍然存在且可读。
    UUID historyOperationId = uuid();
    try (Connection conn = newConnection()) {
      insertOperation(conn, historyOperationId, environmentId, sourceId, "FAILED");
      try (PreparedStatement ps =
          conn.prepareStatement("delete from environment_skill_source where source_id = ?")) {
        ps.setObject(1, sourceId);
        assertEquals(1, ps.executeUpdate(), "source deletion must succeed while history exists");
      }
    }
    assertEquals(
        1L,
        queryLong("select count(*) from environment_operation where id = ?", historyOperationId),
        "operation history must survive source deletion");
  }

  /** V4 持久 inventory：来源删除级联清理 Skill，且同一 Environment 内 Skill 名全局唯一。 */
  @Test
  void skillInventoryFollowsItsSourceAndKeepsNameUnique() throws SQLException {
    UUID environmentId = uuid();
    UUID sourceId = uuid();
    UUID otherSourceId = uuid();
    insertEnvironment(environmentId);
    try (Connection conn = newConnection()) {
      insertSource(conn, sourceId, environmentId, "path", "/skills", true, 0L, "UNAPPLIED", null);
      insertSource(
          conn, otherSourceId, environmentId, "path", "/other", false, 0L, "UNAPPLIED", null);
    }

    try (Connection conn = newConnection()) {
      insertSkill(conn, environmentId, sourceId, "review");
    }
    // 同一 Environment 内 Skill 名不能重复，即使是不同来源。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_environment_skill_environment_name",
          () -> insertSkill(conn, environmentId, otherSourceId, "review"));
    }
    // 复合 FK 阻止把 Skill 挂到不属于该 Environment 的来源上。
    try (Connection conn = newConnection()) {
      UUID foreignEnvironmentId = uuid();
      insertEnvironment(foreignEnvironmentId);
      assertTransactionConstraintViolation(
          conn,
          "fk_environment_skill_source",
          () -> insertSkill(conn, foreignEnvironmentId, sourceId, "orphan"));
    }
    // 非 64 位小写十六进制 revision 被拒绝：正文只能由精确 revision 定位。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_skill_content_revision",
          () ->
              insertSkillWithRevision(conn, environmentId, otherSourceId, "audit", "A".repeat(64)));
    }

    // 删除来源级联清理它的持久 inventory，但不影响同 Environment 的其它来源。
    try (Connection conn = newConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement("delete from environment_skill_source where source_id = ?")) {
        ps.setObject(1, sourceId);
        assertEquals(1, ps.executeUpdate());
      }
    }
    assertEquals(
        0L, queryLong("select count(*) from environment_skill where source_id = ?", sourceId));
    assertEquals(
        0L, queryLong("select count(*) from environment_skill where source_id = ?", otherSourceId));
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

  private void insertSource(
      Connection conn,
      UUID sourceId,
      UUID environmentId,
      String sourceType,
      String path,
      boolean defaultSource,
      long version,
      String status,
      Long appliedVersion)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_skill_source (source_id, environment_id, source_type, path,"
                + " default_source, version, status, applied_version, applied_revision,"
                + " last_applied_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " case when ?::bigint is null then null else current_timestamp end)")) {
      ps.setObject(1, sourceId);
      ps.setObject(2, environmentId);
      ps.setString(3, sourceType);
      ps.setString(4, path);
      ps.setBoolean(5, defaultSource);
      ps.setLong(6, version);
      ps.setString(7, status);
      if (appliedVersion == null) {
        ps.setNull(8, Types.BIGINT);
        ps.setNull(9, Types.VARCHAR);
        ps.setNull(10, Types.BIGINT);
      } else {
        ps.setLong(8, appliedVersion);
        ps.setString(9, "a".repeat(40));
        ps.setLong(10, appliedVersion);
      }
      assertEquals(1, ps.executeUpdate());
    }
  }

  /** 插入一行失败的来源配置：FAILED 状态必须携带非空错误码与描述。 */
  private void insertSourceWithError(
      Connection conn,
      UUID sourceId,
      UUID environmentId,
      String status,
      long version,
      String errorCode,
      String errorMessage)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_skill_source (source_id, environment_id, source_type, path,"
                + " version, status, last_error_code, last_error_message)"
                + " values (?, ?, 'path', '/skills', ?, ?, ?, ?)")) {
      ps.setObject(1, sourceId);
      ps.setObject(2, environmentId);
      ps.setLong(3, version);
      ps.setString(4, status);
      ps.setString(5, errorCode);
      ps.setString(6, errorMessage);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertGitSource(
      Connection conn,
      UUID sourceId,
      UUID environmentId,
      String gitUrl,
      String gitRef,
      String scanPath,
      long version)
      throws SQLException {
    insertGitSource(conn, sourceId, environmentId, gitUrl, gitRef, scanPath, version, false);
  }

  private void insertGitSource(
      Connection conn,
      UUID sourceId,
      UUID environmentId,
      String gitUrl,
      String gitRef,
      String scanPath,
      long version,
      boolean defaultSource)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_skill_source (source_id, environment_id, source_type,"
                + " default_source, git_url, git_ref, scan_path, version, status)"
                + " values (?, ?, 'git', ?, ?, ?, ?, ?, 'UNAPPLIED')")) {
      ps.setObject(1, sourceId);
      ps.setObject(2, environmentId);
      ps.setBoolean(3, defaultSource);
      ps.setString(4, gitUrl);
      ps.setString(5, gitRef);
      ps.setString(6, scanPath);
      ps.setLong(7, version);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertPathSourceWithGitUrl(
      Connection conn, UUID sourceId, UUID environmentId, String path, String gitUrl)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_skill_source (source_id, environment_id, source_type, path,"
                + " git_url, version, status) values (?, ?, 'path', ?, ?, 0, 'UNAPPLIED')")) {
      ps.setObject(1, sourceId);
      ps.setObject(2, environmentId);
      ps.setString(3, path);
      ps.setString(4, gitUrl);
      ps.executeUpdate();
    }
  }

  /** 插入一行操作，认领与终态事实严格按状态给出；PENDING 不携带任何认领事实。 */
  private void insertOperation(
      Connection conn, UUID operationId, UUID environmentId, UUID sourceId, String status)
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
            "insert into environment_operation (id, environment_id, source_id, operation_type,"
                + " status, source_version, source_set_version, arguments, parameter_summary,"
                + " deadline_at, owner_node_id, lease_token, started_at, finished_at,"
                + " result_summary, failure_code, failure_message)"
                + " values (?, ?, ?, 'SKILL_REFRESH', ?, 3, 0, '{}'::jsonb, '{}'::jsonb,"
                + " current_timestamp + interval '1 minute', ?, ?,"
                + " case when ?::boolean then current_timestamp end,"
                + " case when ?::boolean then current_timestamp end,"
                + " ?::jsonb, ?, ?)")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, sourceId);
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
      ps.setObject(10, failed ? "REFRESH_FAILED" : null, Types.VARCHAR);
      ps.setObject(11, failed ? "refresh failed" : null, Types.VARCHAR);
      assertEquals(1, ps.executeUpdate());
    }
  }

  /** 插入一行已认领的 PENDING 操作：用于证明状态与认领事实必须一致。 */
  private void insertClaimedPendingOperation(
      Connection conn, UUID operationId, UUID environmentId, UUID sourceId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, source_id, operation_type,"
                + " status, source_version, source_set_version, arguments, parameter_summary,"
                + " deadline_at, owner_node_id, lease_token, started_at)"
                + " values (?, ?, ?, 'SKILL_REFRESH', 'PENDING', 3, 0, '{}'::jsonb, '{}'::jsonb,"
                + " current_timestamp + interval '1 minute', ?, ?, current_timestamp)")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, sourceId);
      ps.setObject(4, uuid());
      ps.setObject(5, uuid());
      ps.executeUpdate();
    }
  }

  private void insertOperationWithStatus(
      Connection conn, UUID operationId, UUID environmentId, UUID sourceId, String status)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, source_id, operation_type,"
                + " status, source_version, source_set_version, arguments, parameter_summary,"
                + " deadline_at) values (?, ?, ?, 'SKILL_REFRESH', ?, 3, 0, '{}'::jsonb,"
                + " '{}'::jsonb, current_timestamp + interval '1 minute')")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, sourceId);
      ps.setString(4, status);
      ps.executeUpdate();
    }
  }

  private void insertOperationWithType(
      Connection conn, UUID operationId, UUID environmentId, UUID sourceId, String type)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, source_id, operation_type,"
                + " status, source_version, source_set_version, arguments, parameter_summary,"
                + " deadline_at) values (?, ?, ?, ?, 'PENDING', 3, 0, '{}'::jsonb,"
                + " '{}'::jsonb, current_timestamp + interval '1 minute')")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, sourceId);
      ps.setString(4, type);
      ps.executeUpdate();
    }
  }

  private void insertOperationWithArguments(
      Connection conn, UUID operationId, UUID environmentId, UUID sourceId, String argumentsJson)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_operation (id, environment_id, source_id, operation_type,"
                + " status, source_version, source_set_version, arguments, parameter_summary,"
                + " deadline_at) values (?, ?, ?, 'SKILL_REFRESH', 'PENDING', 3, 0,"
                + " ?::jsonb, '{}'::jsonb, current_timestamp + interval '1 minute')")) {
      ps.setObject(1, operationId);
      ps.setObject(2, environmentId);
      ps.setObject(3, sourceId);
      ps.setString(4, argumentsJson);
      ps.executeUpdate();
    }
  }

  private void insertSkill(Connection conn, UUID environmentId, UUID sourceId, String name)
      throws SQLException {
    insertSkillWithRevision(conn, environmentId, sourceId, name, "a".repeat(64));
  }

  private void insertSkillWithRevision(
      Connection conn, UUID environmentId, UUID sourceId, String name, String contentRevision)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_skill (environment_id, source_id, name, source_version,"
                + " description, base_directory, content_revision, discovered_at)"
                + " values (?, ?, ?, 0, 'Skill description', '/skills/' || ?, ?,"
                + " current_timestamp)")) {
      ps.setObject(1, environmentId);
      ps.setObject(2, sourceId);
      ps.setString(3, name);
      ps.setString(4, name);
      ps.setString(5, contentRevision);
      assertEquals(1, ps.executeUpdate());
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

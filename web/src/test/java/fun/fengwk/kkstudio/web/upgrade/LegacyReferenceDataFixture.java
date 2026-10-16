package fun.fengwk.kkstudio.web.upgrade;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;

import javax.crypto.SecretKey;
import javax.sql.DataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * J4 密集跨表引用与 Canvas V4 历史 fixture：在已有 provider / model / Agent / environment / storage blob / chat
 * / mcp / credential / settings 与旧 Harness 历史上，补齐其余可非空持久表的真实代表行。
 *
 * <p>它覆盖最容易被「全表复制」漏掉的引用形态：Environment 过期连接租约与终态更新操作、READY/PENDING 上传与对象墓碑、Chat-Session 关联、Session 的
 * blob 引用、Skill Package、Canvas V4 的 SUCCEEDED/FAILED/CANCELLED 终态 Run 及其 pin/resource/blob
 * 引用与命令幂等行、Project/Issue/Run/Activity/Evidence/Agent-Thread 完整引用链。
 *
 * <p>每个含 self-FK 的表都真实构造 child-first 物理顺序，而不是靠默认顺序碰巧成功：{@code harness_entry}（最深子行先插入的线性 历史）、{@code
 * harness_thread}（FOLLOW 子线程先于执行根）、{@code harness_thread_join}（被 supersede 的行先于接管它的行）。它们都通过单条多行
 * INSERT 写入，因为即时 FK 只在语句末校验，所以源库本身也合法；任何逐行 INSERT 的 COPY 实现都会在第一次遇到子行时被真实外键拒绝。
 *
 * <p>源端 Harness 历史是一条真实、可由公开 store 读回的线性 Turn：ROOT → INPUT TURN_START → 已知 CUSTOM/CUSTOM_MESSAGE →
 * USER → ASSISTANT（tool call）→ TOOL RESULT（旧 8 键，id 与 call 精确配对）→ COMPLETED TURN_END，head 指向该
 * TURN_END。{@code environment_connection} 的 READY metadata 按源 profile 使用各自 canonical 完整形态（DEV 版本
 * 4、MAIN 版本 5 + architecture），绝不写跨代假形状。
 *
 * <p>静态数据放同名前缀的资源；历史消息由对应 codec 构造，Skill 密文独立按旧格式 1 合成。
 *
 * <p>包内可见（package-private）：只是升级测试的源端 fixture，不是生产代码。
 */
final class LegacyReferenceDataFixture {

  private static final String RESOURCE_ROOT = "/fun/fengwk/kkstudio/web/upgrade/";

  private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
      new HistoryEntryPayloadJsonCodec();

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String TIMESTAMP = "timestamptz '2025-04-05 06:07:08.901+00'";

  /** 专属于本 fixture 的 Session：承载 child-first Entry 链、Project Run 与 Join。 */
  static final UUID SESSION = UUID.fromString("1e600000-0000-4000-8000-0000000000c0");

  static final UUID ROOT_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a0");

  static final UUID TURN_START_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a1");

  static final UUID USER_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a2");

  static final UUID ASSISTANT_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a3");

  static final UUID TOOL_RESULT_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a4");

  static final UUID TURN_END_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a5");

  static final UUID CUSTOM_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a6");

  static final UUID CUSTOM_MESSAGE_ENTRY = UUID.fromString("1e600000-0000-4000-8000-0000000000a7");

  /** tool result metadata 保留的旧 invocation id；drain 守卫要求 invocation 表已空，这里没有对应行。 */
  private static final UUID TOOL_RESULT_INVOCATION =
      UUID.fromString("1e600000-0000-4000-8000-0000000000d2");

  static final UUID ROOT_THREAD = UUID.fromString("1e600000-0000-4000-8000-0000000000b0");

  static final UUID CHILD_THREAD = UUID.fromString("1e600000-0000-4000-8000-0000000000b1");

  static final UUID APPLIED_COMMAND_IDEMPOTENCY =
      UUID.fromString("1e600000-0000-4000-8000-0000000000d0");

  static final UUID CANCELLED_COMMAND_IDEMPOTENCY =
      UUID.fromString("1e600000-0000-4000-8000-0000000000d1");

  static final UUID CHILD_STOP_REQUEST = UUID.fromString("1e600000-0000-4000-8000-0000000000e0");

  static final UUID ROOT_STOP_REQUEST = UUID.fromString("1e600000-0000-4000-8000-0000000000e1");

  static final UUID SUPERSEDED_JOIN = UUID.fromString("1e600000-0000-4000-8000-0000000000f0");

  static final UUID SUPERSEDING_JOIN = UUID.fromString("1e600000-0000-4000-8000-0000000000f1");

  static final UUID CANVAS_DOCUMENT = UUID.fromString("1e620000-0000-4000-8000-0000000000c0");

  static final UUID CANVAS_GROUP = UUID.fromString("1e620000-0000-4000-8000-0000000000c1");

  static final UUID CANVAS_SUCCEEDED_NODE = UUID.fromString("1e620000-0000-4000-8000-0000000000c2");

  static final UUID CANVAS_FAILED_NODE = UUID.fromString("1e620000-0000-4000-8000-0000000000c3");

  static final UUID CANVAS_CANCELLED_NODE = UUID.fromString("1e620000-0000-4000-8000-0000000000c4");

  static final UUID CANVAS_TEXT_NODE = UUID.fromString("1e620000-0000-4000-8000-0000000000c5");

  static final UUID CANVAS_REQUEST = UUID.fromString("1e620000-0000-4000-8000-0000000000d0");

  /** 旧 openai_response replay 别名，payload 内保留 1e309 / 1e-324 / 超长整数。 */
  private static final String LEGACY_REPLAY = "legacy-provider-replay-openai-response.json";

  static final String SKILL_TOKEN = "legacy-private-skill-token";

  private static final String LEGACY_GOAL_COMPLETE = "legacy-custom-goal-progress-complete.json";

  private static final String LEGACY_CORE_MESSAGE = "legacy-custom-core-message.json";

  private LegacyReferenceDataFixture() {}

  /** 在已经加载 provider / Agent / storage / chat 与旧 Harness 历史的源库上补齐全部密集行。 */
  static void load(DataSource source, String profile, SecretKey sourceKey) throws SQLException {
    insertHarness(source);
    execute(
        source,
        "legacy-reference-data-business.sql",
        Map.of(
            "runtimeInfo",
            runtimeInfo(profile),
            "skillTokenHex",
            HexFormat.of().formatHex(legacySkillEnvelope(sourceKey))));
    Map<String, String> tokens = new LinkedHashMap<>();
    tokens.put("session", SESSION.toString());
    tokens.put("rootThread", ROOT_THREAD.toString());
    tokens.put("turnStartEntry", TURN_START_ENTRY.toString());
    tokens.put("assistantEntry", ASSISTANT_ENTRY.toString());
    tokens.put("turnEndEntry", TURN_END_ENTRY.toString());
    execute(source, "legacy-reference-data-project.sql", tokens);
  }

  /** 该源真实可能留下的 READY 宿主 metadata；未知 profile 直接拒绝，绝不猜形状。 */
  private static String runtimeInfo(String profile) {
    return switch (profile) {
      case "dev" -> resource("legacy-dev-ready-metadata.json");
      case "main" -> resource("legacy-main-ready-metadata.json");
      default -> throw new IllegalArgumentException("unknown upgrade source profile: " + profile);
    };
  }

  private static byte[] legacySkillEnvelope(SecretKey sourceKey) {
    byte[] aad =
        LengthPrefixedAad.create().field("skill-package").field("legacy-skill").intValue(1).build();
    return AesGcmEnvelope.encrypt(sourceKey, 1, aad, SKILL_TOKEN.getBytes(StandardCharsets.UTF_8));
  }

  /** 把密集 fixture 中一个终态 Canvas Run 改写成指定的坏形态，用于源端 drain 守卫负例；只改被点名的行，不触碰 schema 或约束。 */
  static void rewriteCanvasRun(
      DataSource source, UUID nodeId, String status, String stateJson, String error)
      throws SQLException {
    boolean ready = "READY".equals(status);
    boolean running = "RUNNING".equals(status);
    String availableAt = ready ? "cast(? as timestamptz)" : "null";
    String leaseToken = running ? "cast(? as text)" : "null";
    String leaseUntil = running ? "cast(? as timestamptz)" : "null";
    String sql =
        "update canvas_function_run set status = cast(? as text), state_json = cast(? as jsonb),"
            + " available_at = "
            + availableAt
            + ", lease_token = "
            + leaseToken
            + ", lease_until = "
            + leaseUntil
            + ", error = "
            + (error == null ? "null" : "cast(? as text)")
            + " where node_id = cast(? as uuid)";
    try (Connection connection = source.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      statement.setString(index++, status);
      statement.setString(index++, stateJson);
      if (ready) {
        statement.setString(index++, "2026-01-01 00:00:00.000+00");
      }
      if (running) {
        statement.setString(index++, "legacy-canvas-lease-token");
        statement.setString(index++, "2026-01-01 00:10:00.000+00");
      }
      if (error != null) {
        statement.setString(index++, error);
      }
      statement.setString(index, nodeId.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("the dense canvas run row is missing");
      }
    }
  }

  /** 读取同名前缀测试资源（与现有 upgrade 夹具同目录）。 */
  static String resource(String name) {
    try (InputStream stream =
        LegacyReferenceDataFixture.class.getResourceAsStream(RESOURCE_ROOT + name)) {
      if (stream == null) {
        throw new IllegalStateException("missing upgrade test resource: " + name);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static void execute(DataSource source, String resource, Map<String, String> tokens)
      throws SQLException {
    String sql = resource(resource);
    for (Map.Entry<String, String> token : tokens.entrySet()) {
      sql = sql.replace("${" + token.getKey() + "}", token.getValue());
    }
    try (Connection connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection,
          new EncodedResource(
              new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }
  }

  private static void insertHarness(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "insert into harness_session (id, name, created_at) values"
                  + " (cast(? as uuid), ?, cast(? as timestamptz))")) {
        statement.setString(1, SESSION.toString());
        statement.setString(2, "legacy dense session");
        statement.setString(3, "2025-04-05 06:07:08.901+00");
        statement.executeUpdate();
      }
      insertEntries(connection);
      insertThreads(connection);
      insertCommands(connection);
      insertJoins(connection);
      insertStopReceipts(connection);
    }
  }

  /**
   * 单条多行 INSERT 按「最深的子行最先」写入 Session 的一条真实线性 Entry 历史：ROOT → INPUT TURN_START → 已知 CUSTOM → 已知
   * CUSTOM_MESSAGE → USER → ASSISTANT（带 tool call）→ TOOL RESULT（旧 8 键、id 与 call 精确一致）→ COMPLETED
   * TURN_END。即时 FK 在语句末校验，因此源库本身合法，而堆顺序对任何 COPY 都是 child-first；head 指向最后一个 TURN_END， 整条路径可由公开 store
   * 正常读回且无未关闭 Turn。
   */
  private static void insertEntries(Connection connection) throws SQLException {
    List<String[]> rows = new ArrayList<>();
    rows.add(
        entry(
            TURN_END_ENTRY,
            TOOL_RESULT_ENTRY,
            "TURN_END",
            ENTRY_CODEC.encode(
                new TurnEndPayload(TURN_START_ENTRY, TurnEndOutcome.COMPLETED, false, null, null)),
            null));
    rows.add(entry(TOOL_RESULT_ENTRY, ASSISTANT_ENTRY, "MESSAGE", legacyToolResultMessage(), null));
    rows.add(
        entry(ASSISTANT_ENTRY, USER_ENTRY, "MESSAGE", assistantMessage(), resource(LEGACY_REPLAY)));
    rows.add(entry(USER_ENTRY, CUSTOM_MESSAGE_ENTRY, "MESSAGE", userMessage(), null));
    rows.add(
        entry(
            CUSTOM_MESSAGE_ENTRY,
            CUSTOM_ENTRY,
            "CUSTOM_MESSAGE",
            resource(LEGACY_CORE_MESSAGE),
            null));
    rows.add(entry(CUSTOM_ENTRY, TURN_START_ENTRY, "CUSTOM", resource(LEGACY_GOAL_COMPLETE), null));
    rows.add(
        entry(
            TURN_START_ENTRY,
            ROOT_ENTRY,
            "TURN_START",
            ENTRY_CODEC.encode(
                new TurnStartPayload(
                    TurnStartReason.INPUT, branchSettings(), CHILD_THREAD, 100_000, 4_096, null)),
            null));
    rows.add(
        entry(
            ROOT_ENTRY, null, "ROOT", ENTRY_CODEC.encode(new RootPayload(branchSettings())), null));

    StringBuilder sql =
        new StringBuilder(
            "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                + " created_at, provider_replay_state) values ");
    for (int index = 0; index < rows.size(); index++) {
      if (index > 0) {
        sql.append(", ");
      }
      sql.append("(cast(? as uuid), cast(? as uuid), cast(? as uuid), cast(? as text),")
          .append(" cast(? as jsonb), cast(? as timestamptz), cast(? as jsonb))");
    }
    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      int parameter = 1;
      for (String[] row : rows) {
        statement.setString(parameter++, row[0]);
        statement.setString(parameter++, SESSION.toString());
        setNullableUuid(statement, parameter++, row[1]);
        statement.setString(parameter++, row[2]);
        statement.setString(parameter++, row[3]);
        statement.setString(parameter++, "2025-04-05 06:07:08.901+00");
        setNullableText(statement, parameter++, row[4]);
      }
      statement.executeUpdate();
    }
  }

  /** 单条多行 INSERT 先写 FOLLOW 子线程再写执行根线程，堆顺序即 child-first。 */
  private static void insertThreads(Connection connection) throws SQLException {
    String sql =
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id,"
            + " creation_request_hash, name, yolo_mode, yolo_root_thread_id, execution_control,"
            + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (cast(? as uuid), cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, ?, ?,"
            + " cast(? as uuid), ?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz)),"
            + " (cast(? as uuid), cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, ?, ?,"
            + " cast(? as uuid), ?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz))";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameter = 1;
      // 子线程（FOLLOW，必须携带真实执行根）先于根线程。
      statement.setString(parameter++, CHILD_THREAD.toString());
      statement.setString(parameter++, SESSION.toString());
      statement.setString(parameter++, ROOT_THREAD.toString());
      statement.setString(parameter++, TURN_END_ENTRY.toString());
      statement.setString(parameter++, hash('c'));
      statement.setString(parameter++, "legacy-child-thread");
      statement.setString(parameter++, "FOLLOW");
      statement.setString(parameter++, ROOT_THREAD.toString());
      statement.setString(parameter++, "STOPPED");
      statement.setInt(parameter++, 1);
      statement.setInt(parameter++, 3);
      statement.setInt(parameter++, 2);
      statement.setString(parameter++, "2025-04-05 06:07:08.901+00");
      statement.setString(parameter++, "2025-04-05 06:07:08.901+00");
      // 根线程。
      statement.setString(parameter++, ROOT_THREAD.toString());
      statement.setString(parameter++, SESSION.toString());
      statement.setNull(parameter++, Types.OTHER);
      statement.setString(parameter++, TURN_END_ENTRY.toString());
      statement.setString(parameter++, hash('d'));
      statement.setString(parameter++, "legacy-root-thread");
      statement.setString(parameter++, "ENABLE");
      statement.setNull(parameter++, Types.OTHER);
      statement.setString(parameter++, "STOPPED");
      statement.setInt(parameter++, 0);
      statement.setInt(parameter++, 1);
      statement.setInt(parameter++, 1);
      statement.setString(parameter++, "2025-04-05 06:07:08.901+00");
      statement.setString(parameter++, "2025-04-05 06:07:08.901+00");
      statement.executeUpdate();
    }
  }

  /** 两条命令：一条已应用，一条被真实 Stop 取消（Queued 判据必须仍为空）。 */
  private static void insertCommands(Connection connection) throws SQLException {
    String sql =
        "insert into harness_thread_command (thread_id, sequence, command_type, payload,"
            + " idempotency_key, request_hash, applied_entry_id, stop_request_id, cancelled_at,"
            + " created_at) values (cast(? as uuid), ?, cast(? as text), cast(? as jsonb),"
            + " cast(? as uuid), ?, cast(? as uuid), cast(? as uuid), cast(? as timestamptz),"
            + " cast(? as timestamptz))";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, CHILD_THREAD.toString());
      statement.setInt(2, 1);
      statement.setString(3, "USER_MESSAGE");
      statement.setString(4, userCommandPayload());
      statement.setString(5, APPLIED_COMMAND_IDEMPOTENCY.toString());
      statement.setString(6, hash('e'));
      statement.setString(7, USER_ENTRY.toString());
      statement.setNull(8, Types.OTHER);
      statement.setNull(9, Types.OTHER);
      statement.setString(10, "2025-04-05 06:07:08.901+00");
      statement.executeUpdate();

      statement.setString(1, CHILD_THREAD.toString());
      statement.setInt(2, 2);
      statement.setString(3, "USER_MESSAGE");
      statement.setString(4, userCommandPayload());
      statement.setString(5, CANCELLED_COMMAND_IDEMPOTENCY.toString());
      statement.setString(6, hash('f'));
      statement.setNull(7, Types.OTHER);
      statement.setString(8, CHILD_STOP_REQUEST.toString());
      statement.setString(9, "2025-04-05 06:08:08.901+00");
      statement.setString(10, "2025-04-05 06:07:08.901+00");
      statement.executeUpdate();
    }
  }

  /**
   * 单条多行 INSERT 先写被 supersede 的 Join（其 {@code superseded_by_invocation_id} 指向后一行），再写接管它的已匹配 Join；
   * 即时 self-FK 在语句末校验，堆顺序即 child-first。
   */
  private static void insertJoins(Connection connection) throws SQLException {
    String sql =
        "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id,"
            + " child_thread_id, source_command_sequence, agent, purpose,"
            + " superseded_by_invocation_id, max_turns, reminder_turn, terminal_entry_id,"
            + " final_answer_entry_id, delivery_command_sequence, created_at, updated_at) values"
            + " (cast(? as uuid), ?, cast(? as uuid), cast(? as uuid), ?, ?, cast(? as text),"
            + " cast(? as uuid), cast(? as int), ?, cast(? as uuid), cast(? as uuid),"
            + " cast(? as bigint), cast(? as timestamptz), cast(? as timestamptz)),"
            + " (cast(? as uuid), ?, cast(? as uuid), cast(? as uuid), ?, ?, cast(? as text),"
            + " cast(? as uuid), cast(? as int), ?, cast(? as uuid), cast(? as uuid),"
            + " cast(? as bigint), cast(? as timestamptz), cast(? as timestamptz))";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameter = 1;
      parameter =
          bindJoin(statement, parameter, SUPERSEDED_JOIN, '1', SUPERSEDING_JOIN, null, null, 0);
      bindJoin(
          statement, parameter, SUPERSEDING_JOIN, '2', null, TURN_END_ENTRY, TURN_END_ENTRY, 0);
      statement.executeUpdate();
    }
  }

  /** 被 supersede 的 Join 保留源命令与身份、terminal 为空；接管的 Join 已 matched，故两个源端谓词同时为空。 */
  private static int bindJoin(
      PreparedStatement statement,
      int parameter,
      UUID invocation,
      char hashFill,
      UUID supersededBy,
      UUID terminalEntry,
      UUID finalAnswerEntry,
      int reminderTurn)
      throws SQLException {
    statement.setString(parameter++, invocation.toString());
    statement.setString(parameter++, hash(hashFill));
    statement.setNull(parameter++, Types.OTHER);
    statement.setString(parameter++, CHILD_THREAD.toString());
    statement.setInt(parameter++, 1);
    statement.setString(parameter++, LegacyUpgradeSourceFixture.AGENT);
    statement.setString(parameter++, "compaction");
    setNullableUuid(statement, parameter++, supersededBy == null ? null : supersededBy.toString());
    statement.setNull(parameter++, Types.INTEGER);
    statement.setInt(parameter++, reminderTurn);
    setNullableUuid(
        statement, parameter++, terminalEntry == null ? null : terminalEntry.toString());
    setNullableUuid(
        statement, parameter++, finalAnswerEntry == null ? null : finalAnswerEntry.toString());
    statement.setNull(parameter++, Types.BIGINT);
    statement.setString(parameter++, "2025-04-05 06:07:08.901+00");
    statement.setString(parameter++, "2025-04-05 06:07:08.901+00");
    return parameter;
  }

  /** 一条真实 Stop 回执（1 个被取消输入）与一条空回执；两者都经当前严格 receipt 校验。 */
  private static void insertStopReceipts(Connection connection) throws SQLException {
    String sql =
        "insert into harness_thread_stop_receipt (root_thread_id, thread_id, stop_request_id,"
            + " root_stop_request_id, stopped_turn_end_entry_id, cancelled_command_count,"
            + " cancelled_inputs, created_at) values (cast(? as uuid), cast(? as uuid),"
            + " cast(? as uuid), cast(? as uuid), cast(? as uuid), ?, cast(? as jsonb),"
            + " cast(? as timestamptz))";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, ROOT_THREAD.toString());
      statement.setString(2, CHILD_THREAD.toString());
      statement.setString(3, CHILD_STOP_REQUEST.toString());
      statement.setString(4, CHILD_STOP_REQUEST.toString());
      statement.setString(5, TURN_END_ENTRY.toString());
      statement.setInt(6, 1);
      statement.setString(7, cancelledInputs());
      statement.setString(8, "2025-04-05 06:08:08.901+00");
      statement.executeUpdate();

      statement.setString(1, ROOT_THREAD.toString());
      statement.setString(2, ROOT_THREAD.toString());
      statement.setString(3, ROOT_STOP_REQUEST.toString());
      statement.setString(4, ROOT_STOP_REQUEST.toString());
      statement.setNull(5, Types.OTHER);
      statement.setInt(6, 0);
      statement.setString(7, "[]");
      statement.setString(8, "2025-04-05 06:09:08.901+00");
      statement.executeUpdate();
    }
  }

  private static String[] entry(
      UUID id, UUID parentId, String type, String payload, String replay) {
    return new String[] {
      id.toString(), parentId == null ? null : parentId.toString(), type, payload, replay
    };
  }

  private static String userMessage() {
    return ENTRY_CODEC.encode(
        new MessagePayload(AgentMessage.user("legacy dense question"), null, null));
  }

  /** 与已有历史夹具相同的真实 branch settings：指向冻结的 provider / model / environment。 */
  private static BranchSettings branchSettings() {
    return new BranchSettings(
        LegacyUpgradeSourceFixture.AGENT,
        new ModelSelection(
            LegacyUpgradeSourceFixture.PROVIDER, LegacyUpgradeSourceFixture.MODEL, "default"),
        "legacy-environment");
  }

  private static String assistantMessage() {
    return ENTRY_CODEC.encode(
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT,
                List.of(
                    new TextMessageContent("legacy dense answer"),
                    new ToolCallMessageContent(
                        "call_dense_1", "read", "tool.read", "{\"path\":\"legacy.txt\"}"))),
            new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE, new ModelUsage(11, 22, 0, 0, 0, 0, 33)),
            null));
  }

  /**
   * 与上面 ASSISTANT tool call 精确配对的旧 8 键 tool result：由当前 codec 生成真实负载后删掉 {@code binding}，得到旧源 writer
   * 会留下的字节形态；toolCallId / toolName 匹配 call，assistantEntryId 指向真实 ASSISTANT entry。
   */
  private static String legacyToolResultMessage() {
    String current =
        ENTRY_CODEC.encode(
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call_dense_1",
                            "read",
                            "tool.read",
                            List.of(new TextMessageContent("legacy file body")),
                            false,
                            "{}"))),
                null,
                new ToolResultMetadata(
                    TOOL_RESULT_INVOCATION,
                    ASSISTANT_ENTRY,
                    "call_dense_1",
                    0,
                    ToolResultStatus.SUCCEEDED,
                    false,
                    null,
                    null,
                    null)));
    return dropBinding(current);
  }

  /** 断言当前 codec 真的写出显式 null binding 后删掉该键，得到旧 8 键形态。 */
  private static String dropBinding(String toolResultMessage) {
    JsonNode parsed = MAPPER.readTree(toolResultMessage);
    ObjectNode metadata = (ObjectNode) parsed.get("toolResultMetadata");
    if (metadata == null || metadata.get("binding") == null) {
      throw new IllegalStateException("the current codec must encode an explicit null binding");
    }
    metadata.remove("binding");
    return MAPPER.writeValueAsString(parsed);
  }

  private static String userCommandPayload() {
    return "{\"message\":" + MESSAGE_CODEC.encode(AgentMessage.user("legacy dense question")) + "}";
  }

  /** 旧 stop 回执的冻结 4 键单项，payload 与命令行完全一致。 */
  private static String cancelledInputs() {
    ObjectNode item = MAPPER.createObjectNode();
    item.put("sequence", 2);
    item.put("idempotencyKey", CANCELLED_COMMAND_IDEMPOTENCY.toString());
    item.put("type", "USER_MESSAGE");
    item.set("payload", MAPPER.readTree(userCommandPayload()));
    return MAPPER.writeValueAsString(MAPPER.createArrayNode().add(item));
  }

  private static String hash(char fill) {
    return String.valueOf(fill).repeat(64);
  }

  private static void setNullableUuid(PreparedStatement statement, int index, String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setString(index, value);
    }
  }

  private static void setNullableText(PreparedStatement statement, int index, String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setString(index, value);
    }
  }
}

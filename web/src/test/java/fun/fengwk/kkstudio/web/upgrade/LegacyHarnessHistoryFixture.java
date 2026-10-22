package fun.fengwk.kkstudio.web.upgrade;

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

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一条已关闭 Turn 的真实遗留 Harness 历史：ROOT → TURN_START → user → assistant(tool call + 旧 replay state) →
 * tool result(旧 8 键 metadata) → TURN_END，外加一条已应用命令、一条已取消命令与对应 stop 回执。
 *
 * <p>payload 由当前生产 codec 编码后，只把 tool result metadata 的 {@code binding} 键删掉，就得到旧版真实字节形态；旧 replay
 * state 则用旧 wire 值 {@code openai_response} 手写，其余字段与当前格式一致。它们只经真实 codec 生成与校验，不猜字段。
 *
 * <p>包内可见（package-private）：只是升级测试的源端 fixture，不是生产代码。
 */
final class LegacyHarnessHistoryFixture {

  private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
      new HistoryEntryPayloadJsonCodec();

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  static final UUID SESSION_ID = UUID.fromString("2ab1b6a0-1d1e-4f2a-8b3c-4d5e6f708192");

  static final UUID THREAD_ID = UUID.fromString("3bc2c7b1-2e2f-4a3b-9c4d-5e6f708192a3");

  static final UUID ROOT_ENTRY_ID = UUID.fromString("4cd3d8c2-3f30-4b4c-8d5e-6f708192a3b4");

  static final UUID TURN_START_ENTRY_ID = UUID.fromString("5de4e9d3-4031-4c4d-9e6f-708192a3b4c5");

  static final UUID USER_ENTRY_ID = UUID.fromString("6ef5fae4-5142-4d4e-8f70-8192a3b4c5d6");

  static final UUID ASSISTANT_ENTRY_ID = UUID.fromString("7fa60bf5-6253-4e5f-9081-92a3b4c5d6e7");

  static final UUID TOOL_RESULT_ENTRY_ID = UUID.fromString("80b71c06-7364-4f60-a192-a3b4c5d6e7f8");

  static final UUID FINAL_ANSWER_ENTRY_ID = UUID.fromString("8ac1d2e3-8475-4061-b2a3-b4c5d6e7f809");

  static final UUID TURN_END_ENTRY_ID = UUID.fromString("91c82d17-8475-4061-b2a3-b4c5d6e7f809");

  static final UUID STOP_REQUEST_ID = UUID.fromString("a2d93e28-9586-4172-83b4-c5d6e7f8091a");

  static final UUID SOURCE_COMMAND_IDEMPOTENCY =
      UUID.fromString("b3ea4f39-a697-4283-94c5-d6e7f8091a2b");

  static final UUID CANCELLED_COMMAND_IDEMPOTENCY =
      UUID.fromString("c4fb5a4a-b7a8-4394-a5d6-e7f8091a2b3c");

  static final String CREATION_REQUEST_HASH =
      "1111111111111111111111111111111111111111111111111111111111111111";

  static final String COMMAND_REQUEST_HASH =
      "2222222222222222222222222222222222222222222222222222222222222222";

  static final String CANCELLED_REQUEST_HASH =
      "3333333333333333333333333333333333333333333333333333333333333333";

  /** 旧 wire 值的 provider replay state：格式与当前一致，只有 affinity.providerType 是已退役的旧值。 */
  static final String LEGACY_REPLAY_STATE =
      "{\"format\":\"openai_responses\",\"affinity\":{\"providerType\":\"openai_response\","
          + "\"providerName\":\"legacy-openai\","
          + "\"connectionGenerationId\":\"8f14e45f-ceea-467a-9c1d-2b0f5b5d5b01\","
          + "\"modelId\":\"gpt-legacy\"},\"payload\":{}}";

  static final String TIMESTAMP = "timestamptz '2025-03-04 05:06:07.891+00'";

  private LegacyHarnessHistoryFixture() {}

  /** 写入一条完整关闭的 Turn、一条已应用命令、一条已取消命令与对应 stop 回执。 */
  static void insert(DataSource dataSource) throws SQLException {
    AgentMessage userTurn = AgentMessage.user("legacy question");
    String userMessage = ENTRY_CODEC.encode(new MessagePayload(userTurn, null, null));
    // 命令 payload 内嵌的是裸 AgentMessage，不是整条 MESSAGE entry payload。
    String userCommandPayload = "{\"message\":" + MESSAGE_CODEC.encode(userTurn) + "}";
    String assistantMessage =
        ENTRY_CODEC.encode(
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new TextMessageContent("legacy answer"),
                        new ToolCallMessageContent(
                            "call_1", "read", "tool.read", "{\"path\":\"a.txt\"}"))),
                new AssistantMessageMetadata(
                    GenerationStopReason.COMPLETE, new ModelUsage(10, 20, 0, 0, Map.of(), 0L, 30L)),
                null));
    String toolResultMessage =
        ENTRY_CODEC.encode(
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call_1",
                            "read",
                            "tool.read",
                            List.of(new TextMessageContent("legacy file body")),
                            false,
                            "{}"))),
                null,
                new ToolResultMetadata(
                    UUID.fromString("d50c6b5b-c8b9-44a5-b6e7-f8091a2b3c4d"),
                    ASSISTANT_ENTRY_ID,
                    "call_1",
                    0,
                    ToolResultStatus.SUCCEEDED,
                    false,
                    null,
                    null,
                    null)));
    String legacyToolResultMessage = dropBinding(toolResultMessage);

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "insert into harness_session (id, name, created_at) values ('"
              + SESSION_ID
              + "', 'legacy session', "
              + TIMESTAMP
              + ")");
      statement.execute(
          entry(ROOT_ENTRY_ID, null, "ROOT", ENTRY_CODEC.encode(rootPayload()), null));
      statement.execute(
          entry(
              TURN_START_ENTRY_ID,
              ROOT_ENTRY_ID,
              "TURN_START",
              ENTRY_CODEC.encode(
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), THREAD_ID, 100_000, 4_096, null)),
              null));
      statement.execute(entry(USER_ENTRY_ID, TURN_START_ENTRY_ID, "MESSAGE", userMessage, null));
      statement.execute(
          entry(
              ASSISTANT_ENTRY_ID, USER_ENTRY_ID, "MESSAGE", assistantMessage, LEGACY_REPLAY_STATE));
      statement.execute(
          entry(
              TOOL_RESULT_ENTRY_ID, ASSISTANT_ENTRY_ID, "MESSAGE", legacyToolResultMessage, null));
      // 真实历史是线性追加：一个 turn 只有唯一 assistant 结果，tool 结果紧随其后，TURN_END 挂在它之后。
      statement.execute(
          entry(
              TURN_END_ENTRY_ID,
              TOOL_RESULT_ENTRY_ID,
              "TURN_END",
              ENTRY_CODEC.encode(
                  new TurnEndPayload(
                      TURN_START_ENTRY_ID, TurnEndOutcome.COMPLETED, false, null, null)),
              null));
      statement.execute(
          "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id,"
              + " creation_request_hash, name, yolo_mode, yolo_root_thread_id, execution_control,"
              + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
              + " values ('"
              + THREAD_ID
              + "', '"
              + SESSION_ID
              + "', null, '"
              + TURN_END_ENTRY_ID
              + "', '"
              + CREATION_REQUEST_HASH
              + "', 'legacy thread', 'DISABLE', null, 'STOPPED', 2, 3, 5, "
              + TIMESTAMP
              + ", "
              + TIMESTAMP
              + ")");
      statement.execute(
          "insert into harness_thread_command (thread_id, sequence, command_type, payload,"
              + " idempotency_key, request_hash, applied_entry_id, stop_request_id, cancelled_at,"
              + " created_at) values ('"
              + THREAD_ID
              + "', 1, 'USER_MESSAGE', cast('"
              + userCommandPayload
              + "' as jsonb), '"
              + SOURCE_COMMAND_IDEMPOTENCY
              + "', '"
              + COMMAND_REQUEST_HASH
              + "', '"
              + USER_ENTRY_ID
              + "', null, null, "
              + TIMESTAMP
              + ")");
      statement.execute(
          "insert into harness_thread_command (thread_id, sequence, command_type, payload,"
              + " idempotency_key, request_hash, applied_entry_id, stop_request_id, cancelled_at,"
              + " created_at) values ('"
              + THREAD_ID
              + "', 2, 'USER_MESSAGE', cast('"
              + userCommandPayload
              + "' as jsonb), '"
              + CANCELLED_COMMAND_IDEMPOTENCY
              + "', '"
              + CANCELLED_REQUEST_HASH
              + "', null, '"
              + STOP_REQUEST_ID
              + "', "
              + TIMESTAMP
              + ", "
              + TIMESTAMP
              + ")");
      statement.execute(
          "insert into harness_thread_stop_receipt (root_thread_id, thread_id, stop_request_id,"
              + " root_stop_request_id, stopped_turn_end_entry_id, cancelled_command_count,"
              + " cancelled_inputs, created_at) values ('"
              + THREAD_ID
              + "', '"
              + THREAD_ID
              + "', '"
              + STOP_REQUEST_ID
              + "', '"
              + STOP_REQUEST_ID
              + "', '"
              + TURN_END_ENTRY_ID
              + "', 1, cast('[{\"sequence\":2,\"idempotencyKey\":\""
              + CANCELLED_COMMAND_IDEMPOTENCY
              + "\",\"type\":\"USER_MESSAGE\",\"payload\":"
              + userCommandPayload
              + "}]' as jsonb), "
              + TIMESTAMP
              + ")");
    }
  }

  /** 期望的转换后 replay state：只把旧 provider wire 值改名为当前值。 */
  static String upgradedReplayState() {
    return LEGACY_REPLAY_STATE.replace("\"openai_response\"", "\"openai_responses\"");
  }

  /** 期望的转换后 tool result metadata：旧 8 键形态被显式补上 {@code "binding":null}。 */
  private static String dropBinding(String toolResultMessage) {
    JsonNode parsed = MAPPER.readTree(toolResultMessage);
    ObjectNode metadata = (ObjectNode) parsed.get("toolResultMetadata");
    if (metadata == null || metadata.get("binding") == null) {
      throw new IllegalStateException("the current codec must encode an explicit null binding");
    }
    metadata.remove("binding");
    return MAPPER.writeValueAsString(parsed);
  }

  private static RootPayload rootPayload() {
    return new RootPayload(branchSettings());
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings(
        LegacyUpgradeSourceFixture.AGENT,
        new ModelSelection(
            LegacyUpgradeSourceFixture.PROVIDER, LegacyUpgradeSourceFixture.MODEL, "default"),
        "legacy-environment");
  }

  private static String entry(
      UUID id, UUID parentId, String entryType, String payload, String replayState) {
    return "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
        + " created_at, provider_replay_state) values ('"
        + id
        + "', '"
        + SESSION_ID
        + "', "
        + (parentId == null ? "null" : "'" + parentId + "'")
        + ", '"
        + entryType
        + "', cast('"
        + payload
        + "' as jsonb), "
        + TIMESTAMP
        + ", "
        + (replayState == null ? "null" : "cast('" + replayState + "' as jsonb)")
        + ")";
  }
}

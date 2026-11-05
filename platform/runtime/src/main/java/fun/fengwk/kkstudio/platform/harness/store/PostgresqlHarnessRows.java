package fun.fengwk.kkstudio.platform.harness.store;

import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.codec.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.codec.ToolBindingJsonCodec;
import fun.fengwk.kkstudio.harness.codec.ToolEffectBatchJsonCodec;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.spi.store.EnvironmentToolWaitRow;
import fun.fengwk.kkstudio.harness.spi.store.PendingEnvironmentWaitRow;
import fun.fengwk.kkstudio.harness.spi.store.PendingToolInvocationRow;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Harness durable protocol 对应的 Spring JDBC {@link RowMapper} 与 JSON Codec 映射集。
 *
 * <p>集中管理 Java 领域实体与 PostgreSQL 关系行、JSONB 字段以及时间戳的双向编解码与映射；写入时间值必须已具备毫秒精度，拒绝静默截断。
 */
final class PostgresqlHarnessRows {

  static final HistoryEntryPayloadJsonCodec ENTRY_PAYLOADS = new HistoryEntryPayloadJsonCodec();
  static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS = new ThreadCommandPayloadJsonCodec();
  static final ProviderReplayStateJsonCodec PROVIDER_REPLAY_STATES =
      new ProviderReplayStateJsonCodec();
  static final ToolBindingJsonCodec TOOL_BINDINGS = new ToolBindingJsonCodec();
  static final ToolApprovalJsonCodec TOOL_APPROVALS = new ToolApprovalJsonCodec();
  static final ToolEffectBatchJsonCodec TOOL_EFFECTS = new ToolEffectBatchJsonCodec();

  /** wire 与领域 record 一致的列（tool call、checkpoint、错误、失败审计、输入回执）直接严格绑定。 */
  static final StrictJson JSON = StrictJson.standard();

  static final RowMapper<Session> SESSION =
      (resultSet, rowNumber) ->
          new Session(
              uuid(resultSet, "id"), resultSet.getString("name"), instant(resultSet, "created_at"));

  static final RowMapper<Entry> ENTRY =
      (resultSet, rowNumber) -> {
        EntryType type = EntryType.valueOf(resultSet.getString("entry_type"));
        return new Entry(
            uuid(resultSet, "id"),
            uuid(resultSet, "session_id"),
            nullableUuid(resultSet, "parent_entry_id"),
            ENTRY_PAYLOADS.decode(type, resultSet.getString("payload")),
            instant(resultSet, "created_at"),
            decodeNullable(
                resultSet.getString("provider_replay_state"), PROVIDER_REPLAY_STATES::decode));
      };

  static final RowMapper<ThreadState> THREAD =
      (resultSet, rowNumber) ->
          new ThreadState(
              uuid(resultSet, "id"),
              uuid(resultSet, "session_id"),
              nullableUuid(resultSet, "parent_thread_id"),
              uuid(resultSet, "head_entry_id"),
              resultSet.getString("creation_request_hash"),
              resultSet.getString("name"),
              uuid(resultSet, "root_thread_id"),
              resultSet.getBoolean("yolo_enabled"),
              SpawnTrigger.valueOf(resultSet.getString("spawn_trigger")),
              decodeConstraints(resultSet.getString("constraints")),
              ThreadExecutionControl.valueOf(resultSet.getString("execution_control")),
              resultSet.getLong("input_through_sequence"),
              resultSet.getLong("next_command_sequence"),
              resultSet.getLong("version"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"));

  /** {@code harness_thread.constraints} 的本模块自有持久协议：固定三个键的 JSON 对象。 */
  static String encodeConstraints(ThreadConstraints constraints) {
    ObjectNode node = NODES.objectNode();
    if (constraints.maxOutputTokens() == null) {
      node.putNull("maxOutputTokens");
    } else {
      node.put("maxOutputTokens", constraints.maxOutputTokens());
    }
    node.put("promptCacheDisabled", constraints.promptCacheDisabled());
    node.put("allowAutoCompaction", constraints.allowAutoCompaction());
    return node.toString();
  }

  static ThreadConstraints decodeConstraints(String json) {
    if (json == null) {
      throw new IllegalArgumentException("thread constraints must not be null");
    }
    try {
      JsonNode node = MAPPER.readTree(json);
      if (node == null || !node.isObject() || node.size() != 3) {
        throw new IllegalArgumentException("thread constraints must contain exactly three fields");
      }
      JsonNode cap = node.get("maxOutputTokens");
      JsonNode cacheDisabled = node.get("promptCacheDisabled");
      JsonNode autoCompaction = node.get("allowAutoCompaction");
      if (cap == null
          || (!cap.isNull() && (!cap.isIntegralNumber() || !cap.canConvertToInt()))
          || cacheDisabled == null
          || !cacheDisabled.isBoolean()
          || autoCompaction == null
          || !autoCompaction.isBoolean()) {
        throw new IllegalArgumentException("invalid thread constraints fields");
      }
      return new ThreadConstraints(
          cap.isNull() ? null : cap.intValue(),
          cacheDisabled.booleanValue(),
          autoCompaction.booleanValue());
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed thread constraints JSON", error);
    }
  }

  static final RowMapper<ThreadCommand> COMMAND =
      (resultSet, rowNumber) -> {
        ThreadCommandType type = ThreadCommandType.valueOf(resultSet.getString("command_type"));
        return new ThreadCommand(
            uuid(resultSet, "thread_id"),
            resultSet.getLong("sequence"),
            COMMAND_PAYLOADS.decode(type, resultSet.getString("payload")),
            uuid(resultSet, "idempotency_key"),
            resultSet.getString("request_hash"),
            nullableUuid(resultSet, "applied_entry_id"),
            nullableUuid(resultSet, "stop_request_id"),
            nullableInstant(resultSet, "cancelled_at"),
            instant(resultSet, "created_at"));
      };

  static final RowMapper<ThreadJoin> JOIN =
      (resultSet, rowNumber) ->
          new ThreadJoin(
              uuid(resultSet, "invocation_id"),
              resultSet.getString("request_hash").trim(),
              nullableUuid(resultSet, "parent_thread_id"),
              uuid(resultSet, "child_thread_id"),
              resultSet.getLong("source_command_sequence"),
              resultSet.getString("agent"),
              (Integer) resultSet.getObject("max_turns"),
              resultSet.getLong("reminder_turn"),
              nullableUuid(resultSet, "terminal_entry_id"),
              nullableUuid(resultSet, "final_answer_entry_id"),
              (Long) resultSet.getObject("delivery_command_sequence"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"),
              JoinDelivery.valueOf(resultSet.getString("delivery")),
              nullableUuid(resultSet, "superseded_by_invocation_id"));

  /** 本模块自有 JSON 列统一严格读取；{@code ObjectMapper} 同时服务 constraints 与自由 payload。 */
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static final RowMapper<ToolInvocation> TOOL_INVOCATION =
      (resultSet, rowNumber) ->
          new ToolInvocation(
              uuid(resultSet, "id"),
              uuid(resultSet, "thread_id"),
              uuid(resultSet, "turn_start_entry_id"),
              uuid(resultSet, "assistant_entry_id"),
              resultSet.getInt("call_index"),
              JSON.read(resultSet.getString("call"), ToolCall.class, "toolCall"),
              decodeNullable(resultSet.getString("binding"), TOOL_BINDINGS::decode),
              ToolInvocationStatus.valueOf(resultSet.getString("status")),
              resultSet.getInt("attempt"),
              decodeNullable(resultSet.getString("approval"), TOOL_APPROVALS::decode),
              decodeNullable(resultSet.getString("result"), ToolResultJsonCodec::decode),
              TOOL_EFFECTS.decode(resultSet.getString("effects")),
              decodeNullable(
                  resultSet.getString("error"),
                  json -> JSON.read(json, ToolInvocationError.class, "toolInvocationError")),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"),
              decodeNullable(
                  resultSet.getString("input_receipt"),
                  json -> JSON.read(json, ToolInputReceipt.class, "toolInputReceipt")));

  static final RowMapper<PendingToolInvocationRow> PENDING_TOOL_INVOCATION =
      (resultSet, rowNumber) ->
          new PendingToolInvocationRow(
              TOOL_INVOCATION.mapRow(resultSet, rowNumber),
              uuid(resultSet, "pending_thread_id"),
              uuid(resultSet, "pending_session_id"));

  static final RowMapper<PendingEnvironmentWaitRow> PENDING_ENVIRONMENT_WAIT =
      (resultSet, rowNumber) ->
          new PendingEnvironmentWaitRow(
              uuid(resultSet, "root_thread_id"),
              EnvironmentId.of(uuid(resultSet, "environment_id")),
              instant(resultSet, "representative_created_at"),
              uuid(resultSet, "representative_invocation_id"),
              resultSet.getInt("waiting_count"));

  static final RowMapper<ExecutionExitFact> EXECUTION_EXIT =
      (resultSet, rowNumber) ->
          new ExecutionExitFact(
              uuid(resultSet, "execution_id"),
              uuid(resultSet, "thread_id"),
              uuid(resultSet, "invocation_id"),
              resultSet.getInt("attempt"),
              resultSet.getObject("owner_lease_token", UUID.class),
              ExecutionExitKind.valueOf(resultSet.getString("kind")),
              nullableUuid(resultSet, "parent_execution_id"),
              nullableEnvironmentId(resultSet.getObject("environment_id")),
              ExecutionExitState.valueOf(resultSet.getString("state")),
              instant(resultSet, "registered_at"),
              nullableInstant(resultSet, "confirmed_at"),
              nullableInstant(resultSet, "abandoned_at"));

  static final RowMapper<EnvironmentToolWaitRow> ENVIRONMENT_TOOL_WAIT =
      (resultSet, rowNumber) ->
          new EnvironmentToolWaitRow(
              uuid(resultSet, "invocation_id"),
              nullableEnvironmentId(resultSet.getObject("required_environment_id")),
              resultSet.getBoolean("invocation_ready"),
              resultSet.getBoolean("work_available"),
              nullableInstant(resultSet, "work_freshness_at"));

  private PostgresqlHarnessRows() {}

  static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(requireMillisecondPrecision(instant));
  }

  static Instant requireMillisecondPrecision(Instant instant) {
    return HarnessStoreTime.requireMillisecondPrecision(instant);
  }

  private static EnvironmentId nullableEnvironmentId(Object value) {
    return value == null ? null : EnvironmentId.of((UUID) value);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  static UUID uuid(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, UUID.class);
  }

  static UUID nullableUuid(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, UUID.class);
  }

  private static <T> T decodeNullable(String json, Decoder<T> decoder) {
    return json == null ? null : decoder.decode(json);
  }

  @FunctionalInterface
  private interface Decoder<T> {
    T decode(String json);
  }
}

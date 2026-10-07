package fun.fengwk.kkstudio.harness.infra.postgresql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelAttemptFailuresJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.StreamCheckpointJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolBindingJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolCallJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolEffectBatchJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolInputReceiptJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.EnvironmentToolWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.PendingEnvironmentWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.PendingToolInvocationRow;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloMode;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 七表 durable protocol 对应的 Spring JDBC {@link RowMapper} 与 JSON Codec 映射集。
 *
 * <p>集中管理 Java 领域实体与 PostgreSQL 关系行、JSONB 字段以及时间戳的双向编解码与映射；写入时间值必须已具备毫秒精度，拒绝静默截断。
 */
final class PostgresqlHarnessRows {

  static final HistoryEntryPayloadJsonCodec ENTRY_PAYLOADS = new HistoryEntryPayloadJsonCodec();
  static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS = new ThreadCommandPayloadJsonCodec();
  static final ModelRequestSpecJsonCodec MODEL_REQUESTS = new ModelRequestSpecJsonCodec();
  static final StreamCheckpointJsonCodec STREAM_CHECKPOINTS = new StreamCheckpointJsonCodec();
  static final ModelAttemptFailuresJsonCodec MODEL_FAILED_ATTEMPTS =
      new ModelAttemptFailuresJsonCodec();
  static final ProviderResponseJsonCodec MODEL_RESULTS = new ProviderResponseJsonCodec();
  static final ModelInvocationErrorJsonCodec MODEL_ERRORS = new ModelInvocationErrorJsonCodec();
  static final ProviderReplayStateJsonCodec PROVIDER_REPLAY_STATES =
      new ProviderReplayStateJsonCodec();
  static final ToolCallJsonCodec TOOL_CALLS = new ToolCallJsonCodec();
  static final ToolBindingJsonCodec TOOL_BINDINGS = new ToolBindingJsonCodec();
  static final ToolApprovalJsonCodec TOOL_APPROVALS = new ToolApprovalJsonCodec();
  static final ToolEffectBatchJsonCodec TOOL_EFFECTS = new ToolEffectBatchJsonCodec();
  static final ToolInvocationErrorJsonCodec TOOL_ERRORS = new ToolInvocationErrorJsonCodec();
  static final ToolInputReceiptJsonCodec TOOL_INPUT_RECEIPTS = new ToolInputReceiptJsonCodec();

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
              new ThreadYoloPolicy(
                  ThreadYoloMode.valueOf(resultSet.getString("yolo_mode")),
                  nullableUuid(resultSet, "yolo_root_thread_id")),
              ThreadExecutionControl.valueOf(resultSet.getString("execution_control")),
              resultSet.getLong("input_through_sequence"),
              resultSet.getLong("next_command_sequence"),
              resultSet.getLong("version"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"));

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
              instant(resultSet, "updated_at"));

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static final RowMapper<StoppedThreadReceipt> STOPPED_THREAD_RECEIPT =
      (resultSet, rowNumber) ->
          new StoppedThreadReceipt(
              uuid(resultSet, "thread_id"),
              uuid(resultSet, "stop_request_id"),
              nullableUuid(resultSet, "stopped_turn_end_entry_id"),
              resultSet.getInt("cancelled_command_count"),
              decodeCancelledInputs(resultSet.getString("cancelled_inputs")));

  static List<CancelledThreadInput> decodeCancelledInputs(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      if (!(root instanceof ArrayNode arrayNode)) {
        throw new IllegalArgumentException("cancelled_inputs must be a JSON array");
      }
      List<CancelledThreadInput> list = new ArrayList<>(arrayNode.size());
      for (JsonNode item : arrayNode) {
        long sequence = item.get("sequence").asLong();
        UUID idempotencyKey = UUID.fromString(item.get("idempotencyKey").asText());
        ThreadCommandType type = ThreadCommandType.valueOf(item.get("type").asText());
        JsonNode payloadNode = item.get("payload");
        String payloadJson = MAPPER.writeValueAsString(payloadNode);
        ThreadCommandPayload payload = COMMAND_PAYLOADS.decode(type, payloadJson);
        list.add(new CancelledThreadInput(sequence, idempotencyKey, payload));
      }
      return list;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed cancelled_inputs JSON", error);
    }
  }

  static String encodeCancelledInputs(List<CancelledThreadInput> inputs) {
    ArrayNode arrayNode = NODES.arrayNode();
    for (CancelledThreadInput input : inputs) {
      ObjectNode node = arrayNode.addObject();
      node.put("sequence", input.sequence());
      node.put("idempotencyKey", input.idempotencyKey().toString());
      node.put("type", input.payload().type().name());
      try {
        node.set("payload", MAPPER.readTree(COMMAND_PAYLOADS.encode(input.payload())));
      } catch (JsonProcessingException error) {
        throw new IllegalArgumentException("cannot encode cancelled input payload", error);
      }
    }
    try {
      return MAPPER.writeValueAsString(arrayNode);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode cancelled_inputs JSON", error);
    }
  }

  static final RowMapper<ModelInvocation> MODEL_INVOCATION =
      (resultSet, rowNumber) ->
          new ModelInvocation(
              uuid(resultSet, "id"),
              uuid(resultSet, "thread_id"),
              uuid(resultSet, "turn_start_entry_id"),
              uuid(resultSet, "request_head_entry_id"),
              MODEL_REQUESTS.decode(resultSet.getString("request_spec")),
              ModelInvocationStatus.valueOf(resultSet.getString("status")),
              resultSet.getInt("attempt"),
              decodeNullable(resultSet.getString("stream_checkpoint"), STREAM_CHECKPOINTS::decode),
              decodeNullable(resultSet.getString("result"), MODEL_RESULTS::decode),
              decodeNullable(resultSet.getString("error"), MODEL_ERRORS::decode),
              nullableUuid(resultSet, "result_entry_id"),
              MODEL_FAILED_ATTEMPTS.decode(resultSet.getString("failed_attempts")),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"),
              decodeNullable(
                  resultSet.getString("provider_replay_state"), PROVIDER_REPLAY_STATES::decode));

  static final RowMapper<ToolInvocation> TOOL_INVOCATION =
      (resultSet, rowNumber) ->
          new ToolInvocation(
              uuid(resultSet, "id"),
              uuid(resultSet, "model_invocation_id"),
              uuid(resultSet, "assistant_entry_id"),
              resultSet.getInt("call_index"),
              TOOL_CALLS.decode(resultSet.getString("call")),
              decodeNullable(resultSet.getString("binding"), TOOL_BINDINGS::decode),
              ToolInvocationStatus.valueOf(resultSet.getString("status")),
              resultSet.getInt("attempt"),
              decodeNullable(resultSet.getString("approval"), TOOL_APPROVALS::decode),
              decodeNullable(resultSet.getString("result"), ToolResultJsonCodec::decode),
              TOOL_EFFECTS.decode(resultSet.getString("effects")),
              decodeNullable(resultSet.getString("error"), TOOL_ERRORS::decode),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"),
              decodeNullable(resultSet.getString("input_receipt"), TOOL_INPUT_RECEIPTS::decode));

  static final RowMapper<PendingToolInvocationRow> PENDING_TOOL_INVOCATION =
      (resultSet, rowNumber) ->
          new PendingToolInvocationRow(
              TOOL_INVOCATION.mapRow(resultSet, rowNumber),
              uuid(resultSet, "pending_thread_id"),
              uuid(resultSet, "pending_session_id"));

  static final RowMapper<Work> WORK =
      (resultSet, rowNumber) ->
          new Work(
              new WorkTarget(
                  WorkTargetType.valueOf(resultSet.getString("target_type")),
                  uuid(resultSet, "target_id")),
              instant(resultSet, "available_at"),
              resultSet.getLong("wake_version"),
              resultSet.getString("lease_token"),
              nullableInstant(resultSet, "lease_until"),
              nullableEnvironmentId(resultSet.getObject("required_environment_id")));

  static final RowMapper<PendingEnvironmentWaitRow> PENDING_ENVIRONMENT_WAIT =
      (resultSet, rowNumber) ->
          new PendingEnvironmentWaitRow(
              uuid(resultSet, "root_thread_id"),
              EnvironmentId.of(uuid(resultSet, "environment_id")),
              instant(resultSet, "representative_created_at"),
              uuid(resultSet, "representative_invocation_id"),
              resultSet.getInt("waiting_count"));

  static final RowMapper<EnvironmentToolWaitRow> ENVIRONMENT_TOOL_WAIT =
      (resultSet, rowNumber) ->
          new EnvironmentToolWaitRow(
              uuid(resultSet, "invocation_id"),
              nullableEnvironmentId(resultSet.getObject("required_environment_id")),
              resultSet.getBoolean("waiting_for_environment"));

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

  private static UUID uuid(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, UUID.class);
  }

  private static UUID nullableUuid(ResultSet resultSet, String column) throws SQLException {
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

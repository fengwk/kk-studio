package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.StreamCheckpointJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolEffectBatchJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
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

final class PostgresqlHarnessRows {

  static final HistoryEntryPayloadJsonCodec ENTRY_PAYLOADS = new HistoryEntryPayloadJsonCodec();
  static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS = new ThreadCommandPayloadJsonCodec();
  static final ModelInvocationRequestJsonCodec MODEL_REQUESTS =
      new ModelInvocationRequestJsonCodec();
  static final StreamCheckpointJsonCodec STREAM_CHECKPOINTS = new StreamCheckpointJsonCodec();
  static final ProviderResponseJsonCodec MODEL_RESULTS = new ProviderResponseJsonCodec();
  static final ModelInvocationErrorJsonCodec MODEL_ERRORS = new ModelInvocationErrorJsonCodec();
  static final ToolInvocationRequestJsonCodec TOOL_REQUESTS = new ToolInvocationRequestJsonCodec();
  static final ToolApprovalJsonCodec TOOL_APPROVALS = new ToolApprovalJsonCodec();
  static final ToolEffectBatchJsonCodec TOOL_EFFECTS = new ToolEffectBatchJsonCodec();
  static final ToolInvocationErrorJsonCodec TOOL_ERRORS = new ToolInvocationErrorJsonCodec();

  static final RowMapper<Session> SESSION =
      (resultSet, rowNumber) ->
          new Session(
              resultSet.getLong("id"),
              resultSet.getString("title"),
              instant(resultSet, "created_at"));

  static final RowMapper<Entry> ENTRY =
      (resultSet, rowNumber) -> {
        EntryType type = EntryType.valueOf(resultSet.getString("entry_type"));
        return new Entry(
            resultSet.getLong("id"),
            resultSet.getLong("session_id"),
            nullableLong(resultSet, "parent_entry_id"),
            ENTRY_PAYLOADS.decode(type, resultSet.getString("payload")),
            instant(resultSet, "created_at"));
      };

  static final RowMapper<ThreadState> THREAD =
      (resultSet, rowNumber) ->
          new ThreadState(
              resultSet.getLong("id"),
              resultSet.getLong("head_entry_id"),
              resultSet.getBoolean("yolo_enabled"),
              resultSet.getLong("next_command_sequence"),
              resultSet.getLong("revision"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"));

  static final RowMapper<ThreadCommand> COMMAND =
      (resultSet, rowNumber) -> {
        ThreadCommandType type = ThreadCommandType.valueOf(resultSet.getString("command_type"));
        return new ThreadCommand(
            resultSet.getLong("id"),
            resultSet.getLong("thread_id"),
            resultSet.getLong("sequence"),
            COMMAND_PAYLOADS.decode(type, resultSet.getString("payload")),
            resultSet.getString("client_command_id"),
            nullableLong(resultSet, "consumed_turn_start_entry_id"),
            nullableInstant(resultSet, "cancelled_at"),
            instant(resultSet, "created_at"));
      };

  static final RowMapper<ModelInvocation> MODEL_INVOCATION =
      (resultSet, rowNumber) ->
          new ModelInvocation(
              resultSet.getLong("id"),
              resultSet.getLong("thread_id"),
              resultSet.getLong("turn_start_entry_id"),
              resultSet.getLong("basis_head_entry_id"),
              MODEL_REQUESTS.decode(resultSet.getString("request")),
              ModelInvocationStatus.valueOf(resultSet.getString("status")),
              resultSet.getInt("attempt"),
              decodeNullable(resultSet.getString("stream_checkpoint"), STREAM_CHECKPOINTS::decode),
              decodeNullable(resultSet.getString("result"), MODEL_RESULTS::decode),
              decodeNullable(resultSet.getString("error"), MODEL_ERRORS::decode),
              nullableLong(resultSet, "result_entry_id"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"));

  static final RowMapper<ToolInvocation> TOOL_INVOCATION =
      (resultSet, rowNumber) ->
          new ToolInvocation(
              resultSet.getLong("id"),
              resultSet.getLong("model_invocation_id"),
              resultSet.getLong("assistant_entry_id"),
              resultSet.getInt("ordinal"),
              TOOL_REQUESTS.decode(resultSet.getString("request")),
              ToolInvocationStatus.valueOf(resultSet.getString("status")),
              resultSet.getInt("attempt"),
              decodeNullable(resultSet.getString("approval"), TOOL_APPROVALS::decode),
              decodeNullable(resultSet.getString("result"), ToolResultJsonCodec::decode),
              TOOL_EFFECTS.decode(resultSet.getString("effects")),
              decodeNullable(resultSet.getString("error"), TOOL_ERRORS::decode),
              nullableLong(resultSet, "result_entry_id"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"));

  static final RowMapper<Work> WORK =
      (resultSet, rowNumber) ->
          new Work(
              new WorkTarget(
                  WorkTargetType.valueOf(resultSet.getString("target_type")),
                  resultSet.getLong("target_id")),
              instant(resultSet, "available_at"),
              resultSet.getLong("wake_version"),
              resultSet.getString("lease_token"),
              nullableInstant(resultSet, "lease_until"));

  private PostgresqlHarnessRows() {}

  static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(requireMillisecondPrecision(instant));
  }

  static Instant requireMillisecondPrecision(Instant instant) {
    return HarnessStoreTime.requireMillisecondPrecision(instant);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private static <T> T decodeNullable(String json, Decoder<T> decoder) {
    return json == null ? null : decoder.decode(json);
  }

  @FunctionalInterface
  private interface Decoder<T> {
    T decode(String json);
  }
}

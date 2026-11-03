package fun.fengwk.kkstudio.platform.harness.builtin.store;

import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/** Builtin 模型状态的 PostgreSQL 行映射和持久化编解码。 */
final class PostgresqlBuiltinRows {

  static final ModelRequestSpecJsonCodec MODEL_REQUESTS = new ModelRequestSpecJsonCodec();
  static final ProviderResponseJsonCodec MODEL_RESULTS = new ProviderResponseJsonCodec();
  static final ProviderReplayStateJsonCodec PROVIDER_REPLAY_STATES =
      new ProviderReplayStateJsonCodec();
  static final StrictJson JSON = StrictJson.standard();

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
              decodeNullable(
                  resultSet.getString("stream_checkpoint"),
                  json -> JSON.read(json, StreamCheckpoint.class, "streamCheckpoint")),
              decodeNullable(resultSet.getString("result"), MODEL_RESULTS::decode),
              decodeNullable(
                  resultSet.getString("error"),
                  json -> JSON.read(json, ModelInvocationError.class, "modelInvocationError")),
              uuid(resultSet, "result_entry_id"),
              JSON.readList(
                  resultSet.getString("failed_attempts"),
                  ModelAttemptFailure.class,
                  "modelAttemptFailures"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"),
              decodeNullable(
                  resultSet.getString("provider_replay_state"), PROVIDER_REPLAY_STATES::decode),
              decodeNullable(
                  resultSet.getString("group_execution"),
                  json -> JSON.read(json, ModelGroupProgress.class, "groupExecution")),
              nullableInstant(resultSet, "requested_at"));

  private PostgresqlBuiltinRows() {}

  static Timestamp timestamp(Instant instant) {
    return instant == null
        ? null
        : Timestamp.from(HarnessStoreTime.requireMillisecondPrecision(instant));
  }

  private static UUID uuid(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, UUID.class);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static <T> T decodeNullable(String json, Function<String, T> decoder) {
    return json == null ? null : decoder.apply(json);
  }
}

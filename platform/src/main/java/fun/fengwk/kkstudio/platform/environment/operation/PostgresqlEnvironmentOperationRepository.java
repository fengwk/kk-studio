package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的 {@link EnvironmentOperationRepository} 实现。
 *
 * <p>所有状态转换与截止时间评估使用 PostgreSQL {@code statement_timestamp()}。 私有参数与凭据绝不在异常信息或日志中回显。
 */
@Repository
public class PostgresqlEnvironmentOperationRepository implements EnvironmentOperationRepository {

  private static final int MAX_JSON_CHARS = 256 * 1024;
  private static final int DEFAULT_LIST_LIMIT = 50;
  private static final int MAX_LIST_LIMIT = 500;

  private static final RowMapper<EnvironmentOperation> ROW_MAPPER =
      (rs, rowNum) -> {
        UUID id = rs.getObject("id", UUID.class);
        UUID environmentId = rs.getObject("environment_id", UUID.class);
        UUID sourceId = rs.getObject("source_id", UUID.class);
        EnvironmentOperationType operationType =
            EnvironmentOperationType.valueOf(rs.getString("operation_type"));
        EnvironmentOperationStatus status =
            EnvironmentOperationStatus.valueOf(rs.getString("status"));
        long sourceVersion = rs.getLong("source_version");
        long sourceSetVersion = rs.getLong("source_set_version");
        String arguments = rs.getString("arguments");
        String parameterSummary = rs.getString("parameter_summary");
        Instant deadlineAt = getInstant(rs, "deadline_at");
        UUID ownerNodeId = rs.getObject("owner_node_id", UUID.class);
        UUID leaseToken = rs.getObject("lease_token", UUID.class);
        Instant startedAt = getInstant(rs, "started_at");
        Instant finishedAt = getInstant(rs, "finished_at");
        String resultSummary = rs.getString("result_summary");
        String failureCode = rs.getString("failure_code");
        String failureMessage = rs.getString("failure_message");
        Instant createdAt = getInstant(rs, "created_at");
        Instant updatedAt = getInstant(rs, "updated_at");

        return new EnvironmentOperation(
            id,
            environmentId,
            sourceId,
            operationType,
            status,
            sourceVersion,
            sourceSetVersion,
            arguments,
            parameterSummary,
            deadlineAt,
            ownerNodeId,
            leaseToken,
            startedAt,
            finishedAt,
            resultSummary,
            failureCode,
            failureMessage,
            createdAt,
            updatedAt);
      };

  private static final String CREATE_PENDING_SQL =
      """
      insert into environment_operation (
          id, environment_id, source_id, operation_type,
          status, source_version, source_set_version,
          arguments, parameter_summary, deadline_at,
          created_at, updated_at
      ) values (
          ?, ?, ?, ?,
          'PENDING', ?, ?,
          ?::jsonb, ?::jsonb, ?,
          statement_timestamp(), statement_timestamp()
      )
      returning
          id, environment_id, source_id, operation_type,
          status, source_version, source_set_version,
          arguments, parameter_summary, deadline_at,
          owner_node_id, lease_token, started_at,
          finished_at, result_summary, failure_code,
          failure_message, created_at, updated_at
      """;

  private static final String FIND_BY_ID_SQL =
      """
      select
          id, environment_id, source_id, operation_type,
          status, source_version, source_set_version,
          arguments, parameter_summary, deadline_at,
          owner_node_id, lease_token, started_at,
          finished_at, result_summary, failure_code,
          failure_message, created_at, updated_at
      from environment_operation
      where id = ?
      """;

  private static final String LIST_BY_ENVIRONMENT_SQL =
      """
      select
          id, environment_id, source_id, operation_type,
          status, source_version, source_set_version,
          arguments, parameter_summary, deadline_at,
          owner_node_id, lease_token, started_at,
          finished_at, result_summary, failure_code,
          failure_message, created_at, updated_at
      from environment_operation
      where environment_id = ?
      order by created_at desc, id desc
      limit ?
      """;

  private static final String CLAIM_PENDING_SQL =
      """
      with eligible as (
          select op.id, conn.lease_token
          from environment_operation op
          join environment_connection conn
            on conn.environment_id = op.environment_id
          where op.status = 'PENDING'
            and op.deadline_at > statement_timestamp()
            and conn.owner_node_id = ?
            and conn.status = 'READY'
            and conn.lease_until > statement_timestamp()
          order by op.deadline_at asc, op.created_at asc, op.id asc
          limit ?
          for update of op skip locked
      )
      update environment_operation target
      set status = 'RUNNING',
          owner_node_id = ?,
          lease_token = eligible.lease_token,
          started_at = statement_timestamp(),
          updated_at = statement_timestamp()
      from eligible
      where target.id = eligible.id
      returning
          target.id, target.environment_id, target.source_id, target.operation_type,
          target.status, target.source_version, target.source_set_version,
          target.arguments, target.parameter_summary, target.deadline_at,
          target.owner_node_id, target.lease_token, target.started_at,
          target.finished_at, target.result_summary, target.failure_code,
          target.failure_message, target.created_at, target.updated_at
      """;

  private static final String RESCHEDULE_UNSENT_SQL =
      """
      update environment_operation
      set status = case
              when deadline_at > statement_timestamp() then 'PENDING'
              else 'FAILED'
          end,
          owner_node_id = null,
          lease_token = null,
          started_at = null,
          finished_at = case
              when deadline_at > statement_timestamp() then null
              else statement_timestamp()
          end,
          failure_code = case
              when deadline_at > statement_timestamp() then null
              else 'ENVIRONMENT_UNAVAILABLE_TIMEOUT'
          end,
          failure_message = case
              when deadline_at > statement_timestamp() then null
              else ?
          end,
          updated_at = statement_timestamp()
      where id = ?
        and status = 'RUNNING'
        and owner_node_id = ?
        and lease_token = ?
      returning status
      """;

  private static final String MARK_SUCCEEDED_SQL =
      """
      update environment_operation op
      set status = 'SUCCEEDED',
          result_summary = ?::jsonb,
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      from environment_connection conn
      where op.id = ?
        and op.status = 'RUNNING'
        and op.owner_node_id = ?
        and op.lease_token = ?
        and conn.environment_id = op.environment_id
        and conn.owner_node_id = ?
        and conn.lease_token = ?
        and conn.status = 'READY'
        and conn.lease_until > statement_timestamp()
      """;

  private static final String MARK_FAILED_SQL =
      """
      update environment_operation op
      set status = 'FAILED',
          failure_code = ?,
          failure_message = ?,
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      from environment_connection conn
      where op.id = ?
        and op.status = 'RUNNING'
        and op.owner_node_id = ?
        and op.lease_token = ?
        and conn.environment_id = op.environment_id
        and conn.owner_node_id = ?
        and conn.lease_token = ?
        and conn.status = 'READY'
        and conn.lease_until > statement_timestamp()
      """;

  private static final String MARK_UNKNOWN_SQL =
      """
      update environment_operation
      set status = 'UNKNOWN',
          failure_code = ?,
          failure_message = ?,
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      where id = ?
        and status = 'RUNNING'
        and owner_node_id = ?
        and lease_token = ?
      """;

  private static final String CANCEL_PENDING_SQL =
      """
      update environment_operation
      set status = 'CANCELLED',
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      where id = ?
        and status = 'PENDING'
      """;

  private static final String SWEEP_EXPIRED_PENDING_SQL =
      """
      update environment_operation
      set status = 'FAILED',
          failure_code = 'ENVIRONMENT_UNAVAILABLE_TIMEOUT',
          failure_message = ?,
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      where status = 'PENDING'
        and deadline_at <= statement_timestamp()
      """;

  private static final String SWEEP_EXPIRED_RUNNING_SQL =
      """
      update environment_operation
      set status = 'UNKNOWN',
          failure_code = 'RESULT_TIMEOUT',
          failure_message = ?,
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      where status = 'RUNNING'
        and deadline_at <= statement_timestamp()
      """;

  private static final String SHUTDOWN_RUNNING_UNKNOWN_SQL =
      """
      update environment_operation
      set status = 'UNKNOWN',
          failure_code = ?,
          failure_message = ?,
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      where status = 'RUNNING'
        and owner_node_id = ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public PostgresqlEnvironmentOperationRepository(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, new ObjectMapper());
  }

  @Autowired
  public PostgresqlEnvironmentOperationRepository(
      JdbcTemplate jdbcTemplate, @Autowired(required = false) ObjectMapper objectMapper) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
  }

  @Override
  public EnvironmentOperation createPending(CreatePendingOperationCommand command) {
    Objects.requireNonNull(command, "command");
    validateCommand(command);

    try {
      return jdbcTemplate.queryForObject(
          CREATE_PENDING_SQL,
          ROW_MAPPER,
          command.id(),
          command.environmentId(),
          command.sourceId(),
          command.operationType().name(),
          command.sourceVersion(),
          command.sourceSetVersion(),
          command.arguments(),
          command.parameterSummary(),
          OffsetDateTime.ofInstant(command.deadlineAt(), ZoneOffset.UTC));
    } catch (DataAccessException error) {
      classifyAndRethrow(error, command.environmentId(), command.sourceId());
      throw error;
    }
  }

  @Override
  public Optional<EnvironmentOperation> findById(UUID id) {
    Objects.requireNonNull(id, "id");
    List<EnvironmentOperation> results = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id);
    return results.stream().findFirst();
  }

  @Override
  public EnvironmentOperation getById(UUID id) {
    return findById(id)
        .orElseThrow(
            () ->
                new AiResourceNotFoundException(
                    "environment_operation", "operation not found: " + id));
  }

  @Override
  public List<EnvironmentOperation> listByEnvironment(UUID environmentId, int limit) {
    Objects.requireNonNull(environmentId, "environmentId");
    int boundedLimit = normalizeLimit(limit);
    return jdbcTemplate.query(LIST_BY_ENVIRONMENT_SQL, ROW_MAPPER, environmentId, boundedLimit);
  }

  @Override
  public List<SafeEnvironmentOperation> listSafeByEnvironment(UUID environmentId, int limit) {
    return listByEnvironment(environmentId, limit).stream()
        .map(EnvironmentOperation::toSafeProjection)
        .toList();
  }

  @Override
  public List<EnvironmentOperation> claimPending(UUID ownerNodeId, int limit) {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    if (limit <= 0) {
      return List.of();
    }
    int boundedLimit = Math.min(limit, MAX_LIST_LIMIT);
    return jdbcTemplate.query(
        CLAIM_PENDING_SQL, ROW_MAPPER, ownerNodeId, boundedLimit, ownerNodeId);
  }

  @Override
  public RescheduleOutcome rescheduleUnsent(UUID id, UUID ownerNodeId, UUID leaseToken) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");

    List<String> statuses =
        jdbcTemplate.query(
            RESCHEDULE_UNSENT_SQL,
            (rs, rowNum) -> rs.getString("status"),
            EnvironmentOperationFailureCodes.UNSENT_TIMEOUT_MESSAGE,
            id,
            ownerNodeId,
            leaseToken);

    if (statuses.isEmpty()) {
      return RescheduleOutcome.STALE;
    }
    String status = statuses.getFirst();
    if ("PENDING".equals(status)) {
      return RescheduleOutcome.RESCHEDULED;
    } else if ("FAILED".equals(status)) {
      return RescheduleOutcome.FAILED_TIMEOUT;
    }
    return RescheduleOutcome.STALE;
  }

  @Override
  public boolean markSucceeded(
      UUID id, UUID ownerNodeId, UUID leaseToken, String resultSummaryJson) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");

    String summary = resultSummaryJson != null ? resultSummaryJson : "{}";
    validateJsonObject("resultSummary", summary, true);

    int rows =
        jdbcTemplate.update(
            MARK_SUCCEEDED_SQL, summary, id, ownerNodeId, leaseToken, ownerNodeId, leaseToken);
    return rows == 1;
  }

  @Override
  public boolean markFailed(
      UUID id, UUID ownerNodeId, UUID leaseToken, String failureCode, String failureMessage) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    validateFailureCodeAndMessage(failureCode, failureMessage);

    int rows =
        jdbcTemplate.update(
            MARK_FAILED_SQL,
            failureCode,
            failureMessage,
            id,
            ownerNodeId,
            leaseToken,
            ownerNodeId,
            leaseToken);
    return rows == 1;
  }

  @Override
  public boolean markUnknown(
      UUID id, UUID ownerNodeId, UUID leaseToken, String failureCode, String failureMessage) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    validateFailureCodeAndMessage(failureCode, failureMessage);

    int rows =
        jdbcTemplate.update(
            MARK_UNKNOWN_SQL, failureCode, failureMessage, id, ownerNodeId, leaseToken);
    return rows == 1;
  }

  @Override
  public boolean cancelPending(UUID id) {
    Objects.requireNonNull(id, "id");
    int rows = jdbcTemplate.update(CANCEL_PENDING_SQL, id);
    return rows == 1;
  }

  @Override
  public int sweepExpiredPending() {
    return jdbcTemplate.update(
        SWEEP_EXPIRED_PENDING_SQL, EnvironmentOperationFailureCodes.UNCLAIMED_TIMEOUT_MESSAGE);
  }

  @Override
  public int sweepExpiredRunning() {
    return jdbcTemplate.update(
        SWEEP_EXPIRED_RUNNING_SQL, EnvironmentOperationFailureCodes.RESULT_TIMEOUT_MESSAGE);
  }

  @Override
  public DeadlineSweepResult sweepExpired() {
    int expiredPending = sweepExpiredPending();
    int expiredRunning = sweepExpiredRunning();
    return new DeadlineSweepResult(expiredPending, expiredRunning);
  }

  @Override
  public int markRunningUnknownOnShutdown(
      UUID ownerNodeId, String failureCode, String failureMessage) {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    validateFailureCodeAndMessage(failureCode, failureMessage);
    return jdbcTemplate.update(
        SHUTDOWN_RUNNING_UNKNOWN_SQL, failureCode, failureMessage, ownerNodeId);
  }

  @Override
  public int markRunningUnknownOnShutdown(UUID ownerNodeId) {
    return markRunningUnknownOnShutdown(
        ownerNodeId,
        EnvironmentOperationFailureCodes.DISPATCHER_SHUTDOWN,
        EnvironmentOperationFailureCodes.DISPATCHER_SHUTDOWN_MESSAGE);
  }

  private void validateCommand(CreatePendingOperationCommand command) {
    if (command.id() == null) {
      throw new AiValidationException("environment_operation", "id must not be null");
    }
    if (command.environmentId() == null) {
      throw new AiValidationException("environment_operation", "environmentId must not be null");
    }
    if (command.sourceId() == null) {
      throw new AiValidationException("environment_operation", "sourceId must not be null");
    }
    if (command.operationType() == null) {
      throw new AiValidationException("environment_operation", "operationType must not be null");
    }
    if (command.sourceVersion() < 0) {
      throw new AiValidationException(
          "environment_operation", "sourceVersion must be non-negative");
    }
    if (command.sourceSetVersion() < 0) {
      throw new AiValidationException(
          "environment_operation", "sourceSetVersion must be non-negative");
    }
    if (command.deadlineAt() == null) {
      throw new AiValidationException("environment_operation", "deadlineAt must not be null");
    }
    // Validate deadline shape: deadline must not be before current wall clock with 1-minute grace
    if (command.deadlineAt().isBefore(Instant.now().minusSeconds(60))) {
      throw new AiValidationException(
          "environment_operation", "deadlineAt must not be in the past");
    }
    validateJsonObject("arguments", command.arguments(), true);
    validateJsonObject("parameterSummary", command.parameterSummary(), true);
  }

  private void validateJsonObject(String fieldName, String rawJson, boolean required) {
    if (rawJson == null) {
      if (required) {
        throw new AiValidationException("environment_operation", fieldName + " must not be null");
      }
      return;
    }
    if (rawJson.length() > MAX_JSON_CHARS) {
      throw new AiValidationException(
          "environment_operation",
          fieldName + " exceeds maximum allowed length of " + MAX_JSON_CHARS);
    }
    try {
      JsonNode node = objectMapper.readTree(rawJson);
      if (node == null || !node.isObject()) {
        throw new AiValidationException(
            "environment_operation", fieldName + " must be a JSON object");
      }
    } catch (JsonProcessingException error) {
      // 绝不回显 rawJson 内容，避免泄露可能包含在 arguments 中的凭证
      throw new AiValidationException(
          "environment_operation", fieldName + " must be a valid JSON object");
    }
  }

  private void validateFailureCodeAndMessage(String failureCode, String failureMessage) {
    if (failureCode == null || failureCode.isBlank() || !failureCode.equals(failureCode.trim())) {
      throw new AiValidationException(
          "environment_operation", "failureCode must be non-blank and trimmed");
    }
    if (failureCode.length() > 64) {
      throw new AiValidationException(
          "environment_operation", "failureCode must not exceed 64 characters");
    }
    if (failureMessage == null
        || failureMessage.isBlank()
        || !failureMessage.equals(failureMessage.trim())) {
      throw new AiValidationException(
          "environment_operation", "failureMessage must be non-blank and trimmed");
    }
  }

  private void classifyAndRethrow(DataAccessException error, UUID environmentId, UUID sourceId) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        if ("23505".equals(sqlState)) {
          throw new DuplicateActiveOperationException(environmentId, sourceId, error);
        }
        if ("23503".equals(sqlState)) {
          throw new AiResourceNotFoundException(
              "environment", "environment not found: " + environmentId, error);
        }
        if ("23514".equals(sqlState)) {
          throw new AiValidationException(
              "environment_operation", "operation constraint violation", error);
        }
      }
    }
    throw new AiValidationException(
        "environment_operation", "failed to create pending operation", error);
  }

  private static Instant getInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
    return odt != null ? odt.toInstant() : null;
  }

  private static int normalizeLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_LIST_LIMIT;
    }
    return Math.min(limit, MAX_LIST_LIMIT);
  }
}

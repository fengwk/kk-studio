package fun.fengwk.kkstudio.platform.environment.operation;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 PostgreSQL 的 {@link EnvironmentOperationRepository} 实现（包内私有）。
 *
 * <p>所有状态推进与截止时间评估使用 PostgreSQL {@code statement_timestamp()}。 私有参数与凭据绝不在异常信息或日志中回显，异常原因链绝不保留底层
 * JDBC/SQL 详情。
 */
@Repository
class PostgresqlEnvironmentOperationRepository implements EnvironmentOperationRepository {

  private static final int MAX_JSON_CHARS = 256 * 1024;
  private static final int MAX_FAILURE_MESSAGE_CHARS = 1000;
  private static final int DEFAULT_LIST_LIMIT = 50;
  private static final int MAX_LIST_LIMIT = 500;

  private static final Pattern CONSTRAINT_NAME_PATTERN =
      Pattern.compile(
          "(?:constraint|violates [^ ]+ constraint) [\"']?([^\"'\\s]+)[\"']?",
          Pattern.CASE_INSENSITIVE);

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

  private static final RowMapper<SafeEnvironmentOperation> SAFE_ROW_MAPPER =
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
        String parameterSummary = rs.getString("parameter_summary");
        Instant deadlineAt = getInstant(rs, "deadline_at");
        Instant startedAt = getInstant(rs, "started_at");
        Instant finishedAt = getInstant(rs, "finished_at");
        String resultSummary = rs.getString("result_summary");
        String failureCode = rs.getString("failure_code");
        String failureMessage = rs.getString("failure_message");
        Instant createdAt = getInstant(rs, "created_at");
        Instant updatedAt = getInstant(rs, "updated_at");

        return new SafeEnvironmentOperation(
            id,
            environmentId,
            sourceId,
            operationType,
            status,
            sourceVersion,
            sourceSetVersion,
            parameterSummary,
            deadlineAt,
            startedAt,
            finishedAt,
            resultSummary,
            failureCode,
            failureMessage,
            createdAt,
            updatedAt);
      };

  private static final RowMapper<ClaimedOperation> CLAIMED_ROW_MAPPER =
      (rs, rowNum) -> {
        EnvironmentOperation op = ROW_MAPPER.mapRow(rs, rowNum);
        long remainingMillis = rs.getLong("remaining_millis");
        return new ClaimedOperation(op, Duration.ofMillis(remainingMillis));
      };

  private static final RowMapper<SweptOperationInfo> SWEPT_ROW_MAPPER =
      (rs, rowNum) -> {
        UUID id = rs.getObject("id", UUID.class);
        UUID ownerNodeId = rs.getObject("owner_node_id", UUID.class);
        UUID leaseToken = rs.getObject("lease_token", UUID.class);
        return new SweptOperationInfo(id, ownerNodeId, leaseToken);
      };

  private static final String CREATE_PENDING_SQL =
      """
      with candidate as (
          select
              ?::uuid as id,
              ?::uuid as env_id,
              ?::uuid as src_id,
              ?::varchar as op_type,
              ?::bigint as src_ver,
              ?::bigint as src_set_ver,
              ?::jsonb as args,
              ?::jsonb as param_sum,
              ?::timestamptz as dl
      ),
      inserted as (
          insert into environment_operation (
              id, environment_id, source_id, operation_type,
              status, source_version, source_set_version,
              arguments, parameter_summary, deadline_at,
              created_at, updated_at
          )
          select
              id, env_id, src_id, op_type,
              'PENDING', src_ver, src_set_ver,
              args, param_sum, dl,
              statement_timestamp(), statement_timestamp()
          from candidate
          where dl >= statement_timestamp()
          returning
              id, environment_id, source_id, operation_type,
              status, source_version, source_set_version,
              arguments, parameter_summary, deadline_at,
              owner_node_id, lease_token, started_at,
              finished_at, result_summary, failure_code,
              failure_message, created_at, updated_at
      ),
      notify as (
          select pg_notify('environment_operation_pending', id::text) as n, id
          from inserted
      )
      select inserted.*
      from inserted
      left join notify on notify.id = inserted.id
      """;

  private static final String CREATE_PENDING_TIMEOUT_SQL =
      """
      with candidate as (
          select
              ?::uuid as id,
              ?::uuid as env_id,
              ?::uuid as src_id,
              ?::varchar as op_type,
              ?::bigint as src_ver,
              ?::bigint as src_set_ver,
              ?::jsonb as args,
              ?::jsonb as param_sum,
              (?::bigint * interval '1 millisecond') as timeout_interval
      ),
      inserted as (
          insert into environment_operation (
              id, environment_id, source_id, operation_type,
              status, source_version, source_set_version,
              arguments, parameter_summary, deadline_at,
              created_at, updated_at
          )
          select
              id, env_id, src_id, op_type,
              'PENDING', src_ver, src_set_ver,
              args, param_sum, statement_timestamp() + timeout_interval,
              statement_timestamp(), statement_timestamp()
          from candidate
          where timeout_interval > interval '0 millisecond'
          returning
              id, environment_id, source_id, operation_type,
              status, source_version, source_set_version,
              arguments, parameter_summary, deadline_at,
              owner_node_id, lease_token, started_at,
              finished_at, result_summary, failure_code,
              failure_message, created_at, updated_at
      ),
      notify as (
          select pg_notify('environment_operation_pending', id::text) as n, id
          from inserted
      )
      select inserted.*
      from inserted
      left join notify on notify.id = inserted.id
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

  private static final String SAFE_LIST_SQL =
      """
      select
          id, environment_id, source_id, operation_type,
          status, source_version, source_set_version,
          parameter_summary, deadline_at, started_at,
          finished_at, result_summary, failure_code,
          failure_message, created_at, updated_at
      from environment_operation
      where environment_id = ?
      order by created_at desc, id desc
      limit ?
      """;

  private static final String FIND_SAFE_BY_ID_AND_ENV_SQL =
      """
      select
          id, environment_id, source_id, operation_type,
          status, source_version, source_set_version,
          parameter_summary, deadline_at, started_at,
          finished_at, result_summary, failure_code,
          failure_message, created_at, updated_at
      from environment_operation
      where environment_id = ?
        and id = ?
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

  private static final String CLAIM_PENDING_WITH_TIMEOUT_SQL =
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
          target.failure_message, target.created_at, target.updated_at,
          greatest(1::bigint, ceil(extract(epoch from (target.deadline_at - statement_timestamp())) * 1000)::bigint) as remaining_millis
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

  private static final String SWEEP_LOCAL_EXPIRED_RUNNING_SQL =
      """
      update environment_operation
      set status = 'UNKNOWN',
          failure_code = 'RESULT_TIMEOUT',
          failure_message = ?,
          finished_at = statement_timestamp(),
          updated_at = statement_timestamp()
      where status = 'RUNNING'
        and owner_node_id = ?
        and deadline_at <= statement_timestamp()
      returning id, owner_node_id, lease_token
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

  @Autowired
  public PostgresqlEnvironmentOperationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public EnvironmentOperation createPending(CreatePendingOperationCommand command) {
    Objects.requireNonNull(command, "command");
    validateCommand(command);

    List<EnvironmentOperation> results;
    try {
      results =
          jdbcTemplate.query(
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
      throw classifyCreateError(error, command);
    }

    if (results.isEmpty()) {
      throw new AiValidationException(
          "environment_operation",
          "deadlineAt must not be earlier than database current timestamp");
    }
    return results.getFirst();
  }

  @Override
  public EnvironmentOperation createPendingWithTimeout(
      CreatePendingOperationWithTimeoutCommand command) {
    Objects.requireNonNull(command, "command");
    validateCommandWithTimeout(command);

    List<EnvironmentOperation> results;
    try {
      results =
          jdbcTemplate.query(
              CREATE_PENDING_TIMEOUT_SQL,
              ROW_MAPPER,
              command.id(),
              command.environmentId(),
              command.sourceId(),
              command.operationType().name(),
              command.sourceVersion(),
              command.sourceSetVersion(),
              command.arguments(),
              command.parameterSummary(),
              command.timeoutMillis());
    } catch (DataAccessException error) {
      throw classifyCreateTimeoutError(error, command);
    }

    if (results.isEmpty()) {
      throw new AiValidationException(
          "environment_operation", "timeoutMillis must be greater than zero");
    }
    return results.getFirst();
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
  public Optional<SafeEnvironmentOperation> findSafe(UUID environmentId, UUID operationId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    List<SafeEnvironmentOperation> results =
        jdbcTemplate.query(
            FIND_SAFE_BY_ID_AND_ENV_SQL, SAFE_ROW_MAPPER, environmentId, operationId);
    return results.stream().findFirst();
  }

  @Override
  public SafeEnvironmentOperation getSafe(UUID environmentId, UUID operationId) {
    return findSafe(environmentId, operationId)
        .orElseThrow(
            () ->
                new AiResourceNotFoundException(
                    "environment_operation", "operation not found: " + operationId));
  }

  @Override
  public List<EnvironmentOperation> listByEnvironment(UUID environmentId, int limit) {
    Objects.requireNonNull(environmentId, "environmentId");
    int boundedLimit = normalizeLimit(limit);
    return jdbcTemplate.query(LIST_BY_ENVIRONMENT_SQL, ROW_MAPPER, environmentId, boundedLimit);
  }

  @Override
  public List<SafeEnvironmentOperation> listSafeByEnvironment(UUID environmentId, int limit) {
    Objects.requireNonNull(environmentId, "environmentId");
    int boundedLimit = normalizeLimit(limit);
    return jdbcTemplate.query(SAFE_LIST_SQL, SAFE_ROW_MAPPER, environmentId, boundedLimit);
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
  public List<ClaimedOperation> claimPendingWithTimeout(UUID ownerNodeId, int limit) {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    if (limit <= 0) {
      return List.of();
    }
    int boundedLimit = Math.min(limit, MAX_LIST_LIMIT);
    return jdbcTemplate.query(
        CLAIM_PENDING_WITH_TIMEOUT_SQL, CLAIMED_ROW_MAPPER, ownerNodeId, boundedLimit, ownerNodeId);
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
  public List<SweptOperationInfo> sweepLocalExpiredRunning(UUID ownerNodeId) {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    return jdbcTemplate.query(
        SWEEP_LOCAL_EXPIRED_RUNNING_SQL,
        SWEPT_ROW_MAPPER,
        EnvironmentOperationFailureCodes.RESULT_TIMEOUT_MESSAGE,
        ownerNodeId);
  }

  @Override
  public DeadlineSweepResult sweepExpired() {
    int expiredPending = sweepExpiredPending();
    int expiredRunning = sweepExpiredRunning();
    return new DeadlineSweepResult(expiredPending, expiredRunning);
  }

  @Override
  public int markRunningUnknownOnShutdown(UUID ownerNodeId) {
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    return jdbcTemplate.update(
        SHUTDOWN_RUNNING_UNKNOWN_SQL,
        EnvironmentOperationFailureCodes.DISPATCHER_SHUTDOWN,
        EnvironmentOperationFailureCodes.DISPATCHER_SHUTDOWN_MESSAGE,
        ownerNodeId);
  }

  private void validateCommand(CreatePendingOperationCommand command) {
    Objects.requireNonNull(command.id(), "id");
    Objects.requireNonNull(command.environmentId(), "environmentId");
    Objects.requireNonNull(command.sourceId(), "sourceId");
    Objects.requireNonNull(command.operationType(), "operationType");
    Objects.requireNonNull(command.deadlineAt(), "deadlineAt");
    if (command.sourceVersion() < 0) {
      throw new AiValidationException(
          "environment_operation", "sourceVersion must be non-negative");
    }
    if (command.sourceSetVersion() < 0) {
      throw new AiValidationException(
          "environment_operation", "sourceSetVersion must be non-negative");
    }
    validateJsonObject("arguments", command.arguments(), true);
    validateJsonObject("parameterSummary", command.parameterSummary(), true);
  }

  private void validateCommandWithTimeout(CreatePendingOperationWithTimeoutCommand command) {
    Objects.requireNonNull(command.id(), "id");
    Objects.requireNonNull(command.environmentId(), "environmentId");
    Objects.requireNonNull(command.sourceId(), "sourceId");
    Objects.requireNonNull(command.operationType(), "operationType");
    if (command.timeoutMillis() <= 0) {
      throw new AiValidationException(
          "environment_operation", "timeoutMillis must be greater than zero");
    }
    if (command.sourceVersion() < 0) {
      throw new AiValidationException(
          "environment_operation", "sourceVersion must be non-negative");
    }
    if (command.sourceSetVersion() < 0) {
      throw new AiValidationException(
          "environment_operation", "sourceSetVersion must be non-negative");
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
    if (required && rawJson.isBlank()) {
      throw new AiValidationException("environment_operation", fieldName + " must not be blank");
    }
    if (rawJson.length() > MAX_JSON_CHARS) {
      throw new AiValidationException(
          "environment_operation",
          fieldName + " exceeds maximum allowed length of " + MAX_JSON_CHARS);
    }
    try {
      JsonValues.requireJsonObject(rawJson, fieldName);
    } catch (IllegalArgumentException ignored) {
      throw new AiValidationException(
          "environment_operation", fieldName + " must be a valid JSON object");
    }
  }

  private void validateFailureCodeAndMessage(String failureCode, String failureMessage) {
    if (failureCode == null) {
      throw new AiValidationException("environment_operation", "failureCode must not be null");
    }
    try {
      EnvironmentCapabilityResultCodes.requireCode(failureCode);
    } catch (IllegalArgumentException ignored) {
      throw new AiValidationException("environment_operation", "invalid failure code format");
    }

    if (failureMessage == null || failureMessage.isBlank()) {
      throw new AiValidationException("environment_operation", "failure message must not be blank");
    }
    if (!failureMessage.equals(failureMessage.trim())) {
      throw new AiValidationException(
          "environment_operation",
          "failure message must not contain leading or trailing whitespace");
    }
    if (failureMessage.length() > MAX_FAILURE_MESSAGE_CHARS) {
      throw new AiValidationException(
          "environment_operation",
          "failure message exceeds maximum allowed length of " + MAX_FAILURE_MESSAGE_CHARS);
    }
    for (int i = 0; i < failureMessage.length(); i++) {
      char ch = failureMessage.charAt(i);
      if (Character.isISOControl(ch)) {
        throw new AiValidationException(
            "environment_operation", "failure message contains invalid control characters");
      }
    }
  }

  private RuntimeException classifyCreateError(
      DataAccessException error, CreatePendingOperationCommand command) {
    SQLException sqlEx = extractSqlException(error);
    String sqlState = sqlEx != null ? sqlEx.getSQLState() : null;
    String constraint = extractConstraintName(sqlEx);

    if ("23505".equals(sqlState)) {
      if ("uk_environment_operation_active".equalsIgnoreCase(constraint)) {
        return new DuplicateActiveOperationException(command.environmentId(), command.sourceId());
      }
      if ("environment_operation_pkey".equalsIgnoreCase(constraint)
          || (constraint != null && constraint.toLowerCase(Locale.ROOT).contains("pkey"))) {
        return new AiValidationException(
            "environment_operation", "operation id already exists: " + command.id());
      }
      return new AiValidationException(
          "environment_operation", "operation unique constraint violation");
    }
    if ("23503".equals(sqlState)) {
      return new AiResourceNotFoundException(
          "environment", "environment not found: " + command.environmentId());
    }
    if ("23514".equals(sqlState)) {
      return new AiValidationException("environment_operation", "operation constraint violation");
    }
    return new AiValidationException("environment_operation", "failed to create pending operation");
  }

  private RuntimeException classifyCreateTimeoutError(
      DataAccessException error, CreatePendingOperationWithTimeoutCommand command) {
    SQLException sqlEx = extractSqlException(error);
    String sqlState = sqlEx != null ? sqlEx.getSQLState() : null;
    String constraint = extractConstraintName(sqlEx);

    if ("23505".equals(sqlState)) {
      if ("uk_environment_operation_active".equalsIgnoreCase(constraint)) {
        return new DuplicateActiveOperationException(command.environmentId(), command.sourceId());
      }
      if ("environment_operation_pkey".equalsIgnoreCase(constraint)
          || (constraint != null && constraint.toLowerCase(Locale.ROOT).contains("pkey"))) {
        return new AiValidationException(
            "environment_operation", "operation id already exists: " + command.id());
      }
      return new AiValidationException(
          "environment_operation", "operation unique constraint violation");
    }
    if ("23503".equals(sqlState)) {
      return new AiResourceNotFoundException(
          "environment", "environment not found: " + command.environmentId());
    }
    if ("23514".equals(sqlState)) {
      return new AiValidationException("environment_operation", "operation constraint violation");
    }
    return new AiValidationException("environment_operation", "failed to create pending operation");
  }

  private static SQLException extractSqlException(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) {
        return sqlException;
      }
    }
    return null;
  }

  private static String extractConstraintName(SQLException sqlException) {
    if (sqlException instanceof PSQLException psqlException) {
      ServerErrorMessage serverErrorMessage = psqlException.getServerErrorMessage();
      if (serverErrorMessage != null && serverErrorMessage.getConstraint() != null) {
        return serverErrorMessage.getConstraint();
      }
    }
    if (sqlException != null && sqlException.getMessage() != null) {
      Matcher matcher = CONSTRAINT_NAME_PATTERN.matcher(sqlException.getMessage());
      if (matcher.find()) {
        return matcher.group(1);
      }
    }
    return null;
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

package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 {@link PostgresqlEnvironmentOperationRepository} 的参数校验、异常分类及边界防御契约。 */
class PostgresqlEnvironmentOperationRepositoryValidationTest {

  private JdbcTemplate jdbcTemplate;
  private PostgresqlEnvironmentOperationRepository repository;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID sourceId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    repository = new PostgresqlEnvironmentOperationRepository(jdbcTemplate);
  }

  private CreatePendingOperationCommand validCommand() {
    return new CreatePendingOperationCommand(
        opId,
        envId,
        sourceId,
        EnvironmentOperationType.SKILL_REFRESH,
        0L,
        0L,
        "{\"path\":\"/opt\"}",
        "{\"type\":\"PATH\"}",
        Instant.now().plusSeconds(60));
  }

  private CreatePendingOperationWithTimeoutCommand validTimeoutCommand(long timeoutMillis) {
    return new CreatePendingOperationWithTimeoutCommand(
        opId,
        envId,
        sourceId,
        EnvironmentOperationType.SKILL_REFRESH,
        0L,
        0L,
        "{\"path\":\"/opt\"}",
        "{\"type\":\"PATH\"}",
        timeoutMillis);
  }

  @Test
  void validateCommand_sourceVersionNegative_throwsAiValidationException() {
    CreatePendingOperationCommand cmd =
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            -1L,
            0L,
            "{}",
            "{}",
            Instant.now().plusSeconds(60));
    assertThrows(AiValidationException.class, () -> repository.createPending(cmd));
  }

  @Test
  void validateCommand_sourceSetVersionNegative_throwsAiValidationException() {
    CreatePendingOperationCommand cmd =
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            -1L,
            "{}",
            "{}",
            Instant.now().plusSeconds(60));
    assertThrows(AiValidationException.class, () -> repository.createPending(cmd));
  }

  @Test
  void validateCommand_argumentsInvalid_throwsAiValidationException() {
    // null arguments throws NPE at Command record construction
    assertThrows(
        NullPointerException.class,
        () ->
            new CreatePendingOperationCommand(
                opId,
                envId,
                sourceId,
                EnvironmentOperationType.SKILL_REFRESH,
                0L,
                0L,
                null,
                "{}",
                Instant.now().plusSeconds(60)));

    // blank arguments
    CreatePendingOperationCommand cmdBlank =
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "   ",
            "{}",
            Instant.now().plusSeconds(60));
    assertThrows(AiValidationException.class, () -> repository.createPending(cmdBlank));

    // non-json arguments
    CreatePendingOperationCommand cmdNonJson =
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "not-a-json",
            "{}",
            Instant.now().plusSeconds(60));
    assertThrows(AiValidationException.class, () -> repository.createPending(cmdNonJson));
  }

  @Test
  void validateCommandWithTimeout_timeoutZeroOrNegative_throwsAiValidationException() {
    assertThrows(
        AiValidationException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(0L)));
    assertThrows(
        AiValidationException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(-100L)));
  }

  @Test
  void validateCommandWithTimeout_versionsNegative_throwsAiValidationException() {
    CreatePendingOperationWithTimeoutCommand cmd1 =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            -1L,
            0L,
            "{}",
            "{}",
            60000L);
    assertThrows(AiValidationException.class, () -> repository.createPendingWithTimeout(cmd1));

    CreatePendingOperationWithTimeoutCommand cmd2 =
        new CreatePendingOperationWithTimeoutCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            -1L,
            "{}",
            "{}",
            60000L);
    assertThrows(AiValidationException.class, () -> repository.createPendingWithTimeout(cmd2));
  }

  @Test
  void createPending_emptyResults_throwsAiValidationException() {
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(List.of());
    assertThrows(AiValidationException.class, () -> repository.createPending(validCommand()));
  }

  @Test
  void createPendingWithTimeout_emptyResults_throwsAiValidationException() {
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(List.of());
    assertThrows(
        AiValidationException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(60000L)));
  }

  @Test
  void classifyCreateError_foreignKeyViolation_throwsAiResourceNotFoundException() {
    DataAccessException dae =
        new MockDataAccessException("fk violation", new SQLException("fk error", "23503"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    assertThrows(AiResourceNotFoundException.class, () -> repository.createPending(validCommand()));
  }

  @Test
  void classifyCreateError_uniqueConstraintActive_throwsDuplicateActiveOperationException() {
    DataAccessException dae =
        new MockDataAccessException(
            "duplicate active",
            new SQLException(
                "violates unique constraint \"uk_environment_operation_active\"", "23505"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    assertThrows(
        DuplicateActiveOperationException.class, () -> repository.createPending(validCommand()));
  }

  @Test
  void classifyCreateError_uniqueConstraintPkey_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException(
            "duplicate pkey",
            new SQLException("violates unique constraint \"environment_operation_pkey\"", "23505"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> repository.createPending(validCommand()));
    assertTrue(ex.getMessage().contains("operation id already exists"));
  }

  @Test
  void classifyCreateError_uniqueConstraintOther_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException(
            "duplicate other",
            new SQLException("violates unique constraint \"uk_other_column\"", "23505"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> repository.createPending(validCommand()));
    assertTrue(ex.getMessage().contains("operation unique constraint violation"));
  }

  @Test
  void classifyCreateError_unknownSqlState_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException("db fail", new SQLException("unknown error", "99999"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    AiValidationException ex =
        assertThrows(AiValidationException.class, () -> repository.createPending(validCommand()));
    assertTrue(ex.getMessage().contains("failed to create pending operation"));
  }

  @Test
  void classifyCreateError_checkConstraintViolation_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException("check violation", new SQLException("check error", "23514"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    assertThrows(AiValidationException.class, () -> repository.createPending(validCommand()));
  }

  @Test
  void classifyCreateTimeoutError_uniqueConstraintActive_throwsDuplicateActiveOperationException() {
    DataAccessException dae =
        new MockDataAccessException(
            "duplicate active",
            new SQLException(
                "violates unique constraint \"uk_environment_operation_active\"", "23505"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    assertThrows(
        DuplicateActiveOperationException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(60000L)));
  }

  @Test
  void classifyCreateTimeoutError_uniqueConstraintPkey_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException(
            "duplicate pkey",
            new SQLException("violates unique constraint \"environment_operation_pkey\"", "23505"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> repository.createPendingWithTimeout(validTimeoutCommand(60000L)));
    assertTrue(ex.getMessage().contains("operation id already exists"));
  }

  @Test
  void classifyCreateTimeoutError_uniqueConstraintOther_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException(
            "duplicate other",
            new SQLException("violates unique constraint \"uk_some_constraint\"", "23505"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> repository.createPendingWithTimeout(validTimeoutCommand(60000L)));
    assertTrue(ex.getMessage().contains("operation unique constraint violation"));
  }

  @Test
  void classifyCreateTimeoutError_unknownSqlState_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException("db fail", new SQLException("unknown error", "99999"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> repository.createPendingWithTimeout(validTimeoutCommand(60000L)));
    assertTrue(ex.getMessage().contains("failed to create pending operation"));
  }

  @Test
  void classifyCreateTimeoutError_foreignKeyViolation_throwsAiResourceNotFoundException() {
    DataAccessException dae =
        new MockDataAccessException("fk violation", new SQLException("fk error", "23503"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    assertThrows(
        AiResourceNotFoundException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(60000L)));
  }

  @Test
  void classifyCreateTimeoutError_checkConstraintViolation_throwsAiValidationException() {
    DataAccessException dae =
        new MockDataAccessException("check violation", new SQLException("check error", "23514"));
    when(jdbcTemplate.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(dae);

    assertThrows(
        AiValidationException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(60000L)));
  }

  @Test
  void markFailed_failureCodeAndMessageValidation() {
    // null failureCode
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, null, "error"));

    // invalid failureCode format (must be UPPER_SNAKE)
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, "invalid-code", "error"));
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, "code.with.dots", "error"));

    // null or blank failureMessage
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, "err.code", null));
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, "err.code", "   "));

    // untrimmed failureMessage
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, "err.code", " leading space"));

    // failureMessage exceeding 1000 characters
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, "err.code", "x".repeat(1001)));

    // failureMessage containing ISO control characters
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, nodeId, leaseToken, "err.code", "error\u0007bell"));
  }

  @Test
  void markUnknown_failureCodeAndMessageValidation() {
    assertThrows(
        AiValidationException.class,
        () -> repository.markUnknown(opId, nodeId, leaseToken, null, "error"));
    assertThrows(
        AiValidationException.class,
        () -> repository.markUnknown(opId, nodeId, leaseToken, "err.code", ""));
  }

  @Test
  void markSucceeded_nullResultSummaryDefaultsToEmptyObject() {
    when(jdbcTemplate.update(
            anyString(),
            eq("{}"),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(nodeId),
            eq(leaseToken)))
        .thenReturn(1);
    boolean success = repository.markSucceeded(opId, nodeId, leaseToken, null);
    assertTrue(success);
  }

  @Test
  void listByEnvironment_normalizesLimits() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(envId), anyInt()))
        .thenReturn(List.of());

    // limit <= 0 defaults to 50
    repository.listByEnvironment(envId, 0);
    verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(envId), eq(50));

    // limit > 500 clamps to 500
    repository.listByEnvironment(envId, 1000);
    verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(envId), eq(500));
  }

  @Test
  void listSafeByEnvironment_normalizesLimits() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(envId), anyInt()))
        .thenReturn(List.of());

    repository.listSafeByEnvironment(envId, -1);
    verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(envId), eq(50));

    repository.listSafeByEnvironment(envId, 9999);
    verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(envId), eq(500));
  }

  @Test
  void claimPending_zeroOrNegativeLimit_returnsEmptyWithoutQueryingDatabase() {
    List<EnvironmentOperation> res = repository.claimPending(nodeId, 0);
    assertTrue(res.isEmpty());
    verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), anyInt());

    List<ClaimedOperation> resTimeout = repository.claimPendingWithTimeout(nodeId, -5);
    assertTrue(resTimeout.isEmpty());
  }

  @Test
  void rescheduleUnsent_outcomes() {
    // Empty results -> STALE
    when(jdbcTemplate.query(
            anyString(), any(RowMapper.class), any(), eq(opId), eq(nodeId), eq(leaseToken)))
        .thenReturn(List.of());
    assertEquals(RescheduleOutcome.STALE, repository.rescheduleUnsent(opId, nodeId, leaseToken));

    // FAILED status -> FAILED_TIMEOUT
    when(jdbcTemplate.query(
            anyString(), any(RowMapper.class), any(), eq(opId), eq(nodeId), eq(leaseToken)))
        .thenReturn(List.of("FAILED"));
    assertEquals(
        RescheduleOutcome.FAILED_TIMEOUT, repository.rescheduleUnsent(opId, nodeId, leaseToken));

    // Non-pending status (e.g. UNKNOWN) -> STALE
    when(jdbcTemplate.query(
            anyString(), any(RowMapper.class), any(), eq(opId), eq(nodeId), eq(leaseToken)))
        .thenReturn(List.of("UNKNOWN"));
    assertEquals(RescheduleOutcome.STALE, repository.rescheduleUnsent(opId, nodeId, leaseToken));
  }

  @Test
  void findByIdAndSafe_notFound_throwsAiResourceNotFoundException() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(opId))).thenReturn(List.of());
    assertThrows(AiResourceNotFoundException.class, () -> repository.getById(opId));

    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(envId), eq(opId)))
        .thenReturn(List.of());
    assertThrows(AiResourceNotFoundException.class, () -> repository.getSafe(envId, opId));
  }

  private static class MockDataAccessException extends DataAccessException {
    MockDataAccessException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }
}

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

  /** 测试意图：验证创建挂起操作命令中 sourceVersion 为负数时拒绝并抛出 AiValidationException。 */
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

  /** 测试意图：验证创建挂起操作命令中 sourceSetVersion 为负数时拒绝并抛出 AiValidationException。 */
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

  /** 测试意图：验证创建挂起操作命令中 arguments 为空白或非合法 JSON 时抛出异常。 */
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

  /** 测试意图：验证带超时创建命令中 timeoutMillis 小于等于 0 时抛出 AiValidationException。 */
  @Test
  void validateCommandWithTimeout_timeoutZeroOrNegative_throwsAiValidationException() {
    assertThrows(
        AiValidationException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(0L)));
    assertThrows(
        AiValidationException.class,
        () -> repository.createPendingWithTimeout(validTimeoutCommand(-100L)));
  }

  /** 测试意图：验证带超时创建命令中版本号为负数时抛出 AiValidationException。 */
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

  /** 测试意图：验证创建挂起操作在数据库未返回插入行时抛出 AiValidationException。 */
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

  /** 测试意图：验证带超时创建挂起操作在数据库未返回插入行时抛出 AiValidationException。 */
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

  /** 测试意图：验证创建挂起操作触发外键约束异常（23503）时转换为 AiResourceNotFoundException。 */
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

  /**
   * 测试意图：验证创建挂起操作触发活跃操作唯一约束异常（uk_environment_operation_active）时转换为
   * DuplicateActiveOperationException。
   */
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

  /** 测试意图：验证创建挂起操作触发主键冲突时转换为提示 ID 已存在的 AiValidationException。 */
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

  /** 测试意图：验证创建挂起操作触发其他唯一约束异常时转换为通用唯一约束冲突 AiValidationException。 */
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

  /** 测试意图：验证创建挂起操作遇到未知 SQL 状态码时转换为创建失败 AiValidationException。 */
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

  /** 测试意图：验证创建挂起操作触发检查约束异常（23514）时转换为 AiValidationException。 */
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

  /** 测试意图：验证带超时创建操作触发活跃操作唯一约束异常时转换为 DuplicateActiveOperationException。 */
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

  /** 测试意图：验证带超时创建操作触发主键冲突时转换为提示 ID 已存在的 AiValidationException。 */
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

  /** 测试意图：验证带超时创建操作触发其他唯一约束异常时转换为通用唯一约束冲突 AiValidationException。 */
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

  /** 测试意图：验证带超时创建操作遇到未知 SQL 状态码时转换为创建失败 AiValidationException。 */
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

  /** 测试意图：验证带超时创建操作触发外键约束异常（23503）时转换为 AiResourceNotFoundException。 */
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

  /** 测试意图：验证带超时创建操作触发检查约束异常（23514）时转换为 AiValidationException。 */
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

  /** 测试意图：验证 markFailed 接口针对 failureCode 与 failureMessage 各项规则的校验与错误提示契约。 */
  @Test
  void markFailed_failureCodeAndMessageValidation() {
    // null failureCode
    AiValidationException exNullCode =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, nodeId, leaseToken, null, "error"));
    assertTrue(exNullCode.getMessage().contains("failureCode must not be null"));

    // invalid failureCode format (must be UPPER_SNAKE)
    AiValidationException exInvalidCode1 =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, nodeId, leaseToken, "invalid-code", "error"));
    assertTrue(exInvalidCode1.getMessage().contains("invalid failure code format"));

    AiValidationException exInvalidCode2 =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, nodeId, leaseToken, "code.with.dots", "error"));
    assertTrue(exInvalidCode2.getMessage().contains("invalid failure code format"));

    // null or blank failureMessage
    AiValidationException exNullMsg =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, nodeId, leaseToken, "EXECUTION_FAILED", null));
    assertTrue(exNullMsg.getMessage().contains("failure message must not be blank"));

    AiValidationException exBlankMsg =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, nodeId, leaseToken, "EXECUTION_FAILED", "   "));
    assertTrue(exBlankMsg.getMessage().contains("failure message must not be blank"));

    // untrimmed failureMessage
    AiValidationException exUntrimmedMsg =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.markFailed(
                    opId, nodeId, leaseToken, "EXECUTION_FAILED", " leading space"));
    assertTrue(
        exUntrimmedMsg
            .getMessage()
            .contains("failure message must not contain leading or trailing whitespace"));

    // failureMessage exceeding 1000 characters
    AiValidationException exTooLongMsg =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.markFailed(
                    opId, nodeId, leaseToken, "EXECUTION_FAILED", "x".repeat(1001)));
    assertTrue(
        exTooLongMsg.getMessage().contains("failure message exceeds maximum allowed length of"));

    // failureMessage containing ISO control characters
    AiValidationException exControlMsg =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.markFailed(
                    opId, nodeId, leaseToken, "EXECUTION_FAILED", "error\u0007bell"));
    assertTrue(
        exControlMsg.getMessage().contains("failure message contains invalid control characters"));
  }

  /** 测试意图：验证 markUnknown 接口针对 failureCode 与 failureMessage 校验规则的防御契约。 */
  @Test
  void markUnknown_failureCodeAndMessageValidation() {
    AiValidationException exNullCode =
        assertThrows(
            AiValidationException.class,
            () -> repository.markUnknown(opId, nodeId, leaseToken, null, "error"));
    assertTrue(exNullCode.getMessage().contains("failureCode must not be null"));

    AiValidationException exInvalidCode =
        assertThrows(
            AiValidationException.class,
            () -> repository.markUnknown(opId, nodeId, leaseToken, "invalid-code", "error"));
    assertTrue(exInvalidCode.getMessage().contains("invalid failure code format"));

    AiValidationException exBlankMsg =
        assertThrows(
            AiValidationException.class,
            () -> repository.markUnknown(opId, nodeId, leaseToken, "EXECUTION_FAILED", ""));
    assertTrue(exBlankMsg.getMessage().contains("failure message must not be blank"));
  }

  /** 测试意图：验证 markSucceeded 在 resultSummary 为 null 时安全回退为默认空对象 "{}"。 */
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

  /** 测试意图：验证 listByEnvironment 对查询分页 limit 进行保底与上限归一化（1-500，默认 50）。 */
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

  /** 测试意图：验证 listSafeByEnvironment 对查询分页 limit 进行保底与上限归一化（1-500，默认 50）。 */
  @Test
  void listSafeByEnvironment_normalizesLimits() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(envId), anyInt()))
        .thenReturn(List.of());

    repository.listSafeByEnvironment(envId, -1);
    verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(envId), eq(50));

    repository.listSafeByEnvironment(envId, 9999);
    verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(envId), eq(500));
  }

  /** 测试意图：验证认领挂起操作时若 limit 小于等于 0 则直接返回空列表，避免无意义的数据库查询。 */
  @Test
  void claimPending_zeroOrNegativeLimit_returnsEmptyWithoutQueryingDatabase() {
    List<EnvironmentOperation> res = repository.claimPending(nodeId, 0);
    assertTrue(res.isEmpty());
    verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), anyInt());

    List<ClaimedOperation> resTimeout = repository.claimPendingWithTimeout(nodeId, -5);
    assertTrue(resTimeout.isEmpty());
  }

  /** 测试意图：验证 rescheduleUnsent 在不同状态与查询结果下正确映射为对应的 RescheduleOutcome 枚举。 */
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

  /** 测试意图：验证 getById 与 getSafe 查询不到指定操作记录时抛出 AiResourceNotFoundException。 */
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

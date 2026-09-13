package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@link PostgresqlEnvironmentOperationRepository} 基于真实 PostgreSQL 的集成测试。
 *
 * <p>验证状态机原子推进、认领围栏、安全投影、并发唯一性、DB 时间边界、异常无 cause 保留及超时清扫。
 */
class PostgresqlEnvironmentOperationRepositoryIntegrationTest extends PostgresSchemaSupport {

  private JdbcTemplate jdbcTemplate;
  private PostgresqlEnvironmentOperationRepository repository;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(newConnection(), false);
    jdbcTemplate = new JdbcTemplate(dataSource);
    objectMapper = new ObjectMapper().findAndRegisterModules();
    repository = new PostgresqlEnvironmentOperationRepository(jdbcTemplate);
  }

  private CreatePendingOperationCommand pendingCmd(
      UUID id,
      UUID envId,
      UUID resourceId,
      EnvironmentOperationType type,
      long version,
      String arguments,
      String parameterSummary,
      Instant deadlineAt) {
    return new CreatePendingOperationCommand(
        id,
        envId,
        type.resourceType(),
        resourceId,
        type,
        version,
        arguments,
        parameterSummary,
        deadlineAt);
  }

  private CreatePendingOperationCommand pendingCmd(
      UUID id,
      UUID envId,
      UUID resourceId,
      EnvironmentOperationType type,
      long version,
      Instant deadlineAt) {
    return pendingCmd(id, envId, resourceId, type, version, "{}", "{}", deadlineAt);
  }

  private CreatePendingOperationWithTimeoutCommand timeoutCmd(
      UUID id,
      UUID envId,
      UUID resourceId,
      EnvironmentOperationType type,
      long version,
      String arguments,
      String parameterSummary,
      long timeoutMillis) {
    return new CreatePendingOperationWithTimeoutCommand(
        id,
        envId,
        type.resourceType(),
        resourceId,
        type,
        version,
        arguments,
        parameterSummary,
        timeoutMillis);
  }

  /** 测试意图：验证完整的创建与读取闭环，确保私有 arguments 与 leaseToken 绝不泄露到 toString、Jackson 序列化或公开投影中。 */
  @Test
  void roundTripWithoutToStringOrSerializationLeak() throws Exception {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    String sensitiveUrl = "https://user:mock-token-secret@example.com/repo.git";
    String arguments = "{\"gitUrl\":\"" + sensitiveUrl + "\",\"branch\":\"main\"}";
    String parameterSummary = "{\"gitRef\":\"main\"}";
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    CreatePendingOperationCommand command =
        pendingCmd(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_INSTALL,
            1L,
            arguments,
            parameterSummary,
            deadlineAt);

    // 验证 Command 的 toString 绝不包含敏感 arguments
    assertFalse(command.toString().contains("mock-token-secret"));
    assertFalse(command.toString().contains(sensitiveUrl));

    // 验证 Command 通过 Jackson 序列化时忽略 arguments 字段
    String cmdJson = objectMapper.writeValueAsString(command);
    assertFalse(cmdJson.contains("arguments"));
    assertFalse(cmdJson.contains("mock-token-secret"));

    EnvironmentOperation created = repository.createPending(command);
    assertEquals(opId, created.id());
    assertEquals(EnvironmentOperationStatus.PENDING, created.status());
    assertTrue(created.arguments().contains(sensitiveUrl));
    assertNull(created.ownerNodeId());
    assertNull(created.leaseToken());
    assertNull(created.startedAt());
    assertNull(created.finishedAt());

    // 验证 Model 的 toString 严格排除 arguments 与 leaseToken
    assertFalse(created.toString().contains("mock-token-secret"));
    assertFalse(created.toString().contains(sensitiveUrl));
    assertFalse(created.toString().contains("leaseToken="));

    // 验证 Model 通过 Jackson 序列化时物理忽略 arguments
    String opJson = objectMapper.writeValueAsString(created);
    assertFalse(opJson.contains("arguments"));
    assertFalse(opJson.contains("mock-token-secret"));

    EnvironmentOperation fetched = repository.getById(opId);
    assertTrue(fetched.arguments().contains(sensitiveUrl));
    assertFalse(fetched.toString().contains("mock-token-secret"));
    assertFalse(fetched.toString().contains("leaseToken="));

    // 认领后产生 leaseToken，再次验证 toString 与序列化均不泄露 arguments 与 leaseToken
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);
    List<EnvironmentOperation> claimedList = repository.claimPending(node, 1);
    assertEquals(1, claimedList.size());
    EnvironmentOperation claimed = claimedList.getFirst();
    assertEquals(leaseToken, claimed.leaseToken());
    assertFalse(claimed.toString().contains(leaseToken.toString()));
    assertFalse(claimed.toString().contains("mock-token-secret"));

    // 针对处于 RUNNING 且持有 leaseToken 的模型，通过 Jackson 序列化必须物理排除 arguments 与 leaseToken
    String claimedJson = objectMapper.writeValueAsString(claimed);
    assertFalse(claimedJson.contains("leaseToken"));
    assertFalse(claimedJson.contains(leaseToken.toString()));
    assertFalse(claimedJson.contains("arguments"));
    assertFalse(claimedJson.contains("mock-token-secret"));

    // 安全投影必须移除 arguments, leaseToken 与 ownerNodeId
    SafeEnvironmentOperation safe = fetched.toSafeProjection();
    assertNotNull(safe);
    assertFalse(safe.toString().contains("mock-token-secret"));
    assertFalse(safe.toString().contains(leaseToken.toString()));

    List<SafeEnvironmentOperation> safeList = repository.listSafeByEnvironment(envId, 10);
    assertEquals(1, safeList.size());
    SafeEnvironmentOperation listedSafe = safeList.getFirst();
    assertFalse(listedSafe.toString().contains("mock-token-secret"));
    assertFalse(listedSafe.toString().contains(leaseToken.toString()));
  }

  /** 测试意图：使用 PostgreSQL statement_timestamp() 判定截止时间，早于 DB 时间的被单句原子拒绝。 */
  @Test
  void dbTimeDeadlineAcceptanceBoundary() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();

    OffsetDateTime dbNow =
        jdbcTemplate.queryForObject("select statement_timestamp()", OffsetDateTime.class);
    assertNotNull(dbNow);
    Instant dbInstant = dbNow.toInstant();

    // 1. 过去时间边界：早于 DB statement_timestamp 1 秒 -> 拒绝并返回安全验证错误
    Instant pastDeadline = dbInstant.minusSeconds(1);
    CreatePendingOperationCommand pastCommand =
        pendingCmd(
            uuid(), envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, pastDeadline);
    AiValidationException pastEx =
        assertThrows(AiValidationException.class, () -> repository.createPending(pastCommand));
    assertTrue(pastEx.getMessage().contains("database current timestamp"));
    assertNull(pastEx.getCause(), "No cause chain may be retained");

    // 2. 未来时间边界：晚于 DB statement_timestamp 30 秒 -> 正常接受并创建
    Instant futureDeadline = dbInstant.plusSeconds(30);
    CreatePendingOperationCommand futureCommand =
        pendingCmd(
            uuid(), envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, futureDeadline);
    EnvironmentOperation created = repository.createPending(futureCommand);
    assertNotNull(created);
    assertEquals(futureCommand.id(), created.id());
  }

  /** 测试意图：绝不保留底层 JDBC/DataAccessException 原因链，确保即使底层包含参数或整行数据也不会泄露任何凭据。 */
  @Test
  void neverRetainCauseAndPreventAllLeaksOnConstraintFailures() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    String secret = "SUPER_SECRET_TOKEN_XYZ_123456789";
    String sensitiveArguments =
        "{\"gitUrl\":\"https://user:" + secret + "@git.internal/repo.git\"}";
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    UUID firstOpId = uuid();
    CreatePendingOperationCommand cmd1 =
        pendingCmd(
            firstOpId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            sensitiveArguments,
            "{}",
            deadlineAt);
    repository.createPending(cmd1);

    // 1. 活动冲突分支：uk_environment_operation_active -> DuplicateActiveOperationException
    CreatePendingOperationCommand cmdDuplicateActive =
        pendingCmd(
            uuid(),
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            sensitiveArguments,
            "{}",
            deadlineAt);
    DuplicateActiveOperationException activeEx =
        assertThrows(
            DuplicateActiveOperationException.class,
            () -> repository.createPending(cmdDuplicateActive));
    assertNoLeakInExceptionChain(activeEx, secret);

    // 2. 主键重复分支：environment_operation_pkey -> AiValidationException
    CreatePendingOperationCommand cmdDuplicatePk =
        pendingCmd(
            firstOpId,
            envId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            sensitiveArguments,
            "{}",
            deadlineAt);
    AiValidationException pkEx =
        assertThrows(AiValidationException.class, () -> repository.createPending(cmdDuplicatePk));
    assertTrue(pkEx.getMessage().contains("already exists"));
    assertNoLeakInExceptionChain(pkEx, secret);

    // 3. 外键缺失分支：fk_environment_operation_environment -> AiResourceNotFoundException
    UUID nonExistentEnvId = uuid();
    CreatePendingOperationCommand cmdFkViolation =
        pendingCmd(
            uuid(),
            nonExistentEnvId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            sensitiveArguments,
            "{}",
            deadlineAt);
    AiResourceNotFoundException fkEx =
        assertThrows(
            AiResourceNotFoundException.class, () -> repository.createPending(cmdFkViolation));
    assertNoLeakInExceptionChain(fkEx, secret);
  }

  /** 辅助断言：遍历异常原因链，确保原因链为空且任何异常消息或 toString 均不包含敏感串。 */
  private void assertNoLeakInExceptionChain(Throwable error, String secret) {
    assertNull(error.getCause(), "Outward exception must not retain a cause chain");
    for (Throwable current = error; current != null; current = current.getCause()) {
      String msg = current.getMessage();
      assertFalse(
          msg != null && msg.contains(secret),
          "Secret found in exception message: " + current.getClass().getName() + ": " + msg);
      assertFalse(
          current.toString().contains(secret), "Secret found in exception toString: " + current);
    }
  }

  /** 测试意图：复用 EnvironmentCapabilityResultCodes.requireCode 并对 failureMessage 长度与控制字符进行安全拦截。 */
  @Test
  void failureCodeAndMessageValidation() throws SQLException {
    UUID envId = createEnvironment();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    UUID opId = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        pendingCmd(opId, envId, uuid(), EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    repository.claimPending(node, 1);

    // 1. 非法 failureCode：小写、含空格、非 UPPER_SNAKE -> invalid failure code format
    AiValidationException codeEx1 =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.markFailed(opId, node, leaseToken, "lowercase_error", "Valid message"));
    assertEquals("invalid failure code format", codeEx1.getMessage());
    assertNull(codeEx1.getCause());

    AiValidationException codeEx2 =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, node, leaseToken, "BAD CODE", "Valid message"));
    assertEquals("invalid failure code format", codeEx2.getMessage());

    AiValidationException codeEx3 =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, node, leaseToken, null, "Valid message"));
    assertTrue(codeEx3.getMessage().contains("must not be null"));

    // 2. 非法 failureMessage：空白或前后带空格
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, node, leaseToken, "FAILED", "   "));
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, node, leaseToken, "FAILED", " leading_space"));

    // 3. 非法 failureMessage：包含换行、制表符或空字符等 ISO 控制字符（防止日志/头注入）
    AiValidationException ctrlEx1 =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, node, leaseToken, "FAILED", "line1\nline2"));
    assertTrue(ctrlEx1.getMessage().contains("control characters"));

    AiValidationException ctrlEx2 =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, node, leaseToken, "FAILED", "col1\tcol2"));
    assertTrue(ctrlEx2.getMessage().contains("control characters"));

    AiValidationException ctrlEx3 =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, node, leaseToken, "FAILED", "null\0char"));
    assertTrue(ctrlEx3.getMessage().contains("control characters"));

    // 4. 超长 failureMessage（超过 1000 字符）：绝不回显输入内容
    String sensitiveText = "SECRET_PAYLOAD_DATA";
    String oversizedMessage = sensitiveText + "X".repeat(1005);
    AiValidationException lenEx =
        assertThrows(
            AiValidationException.class,
            () -> repository.markFailed(opId, node, leaseToken, "FAILED", oversizedMessage));
    assertTrue(lenEx.getMessage().contains("maximum allowed length"));
    assertFalse(lenEx.getMessage().contains(sensitiveText), "Error message must not echo input");

    // 5. 合法 failureCode 与 failureMessage 正常流转为 FAILED 终态
    assertTrue(
        repository.markFailed(
            opId,
            node,
            leaseToken,
            EnvironmentCapabilityResultCodes.RESOURCE_CHANGED,
            "Resource is gone"));
    EnvironmentOperation failed = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, failed.status());
    assertEquals(EnvironmentCapabilityResultCodes.RESOURCE_CHANGED, failed.failureCode());
    assertEquals("Resource is gone", failed.failureMessage());
  }

  /** 测试意图：同一来源在未终结状态下创建第二条操作必须被分类为 DuplicateActiveOperationException 且不泄露 arguments。 */
  @Test
  void activeDuplicateClassification() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    String sensitiveSecret = "secret-token-12345";
    String arguments = "{\"secret\":\"" + sensitiveSecret + "\"}";
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    CreatePendingOperationCommand cmd1 =
        pendingCmd(
            uuid(),
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            arguments,
            "{}",
            deadlineAt);
    repository.createPending(cmd1);

    CreatePendingOperationCommand cmd2 =
        pendingCmd(
            uuid(),
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            arguments,
            "{}",
            deadlineAt);

    DuplicateActiveOperationException thrown =
        assertThrows(DuplicateActiveOperationException.class, () -> repository.createPending(cmd2));
    assertEquals(envId, thrown.getEnvironmentId());
    assertEquals(sourceId, thrown.getResourceId());
    assertEquals(EnvironmentOperationResourceType.SKILL_SOURCE, thrown.getResourceType());
    assertFalse(thrown.getMessage().contains(sensitiveSecret));
    assertTrue(thrown instanceof AiDuplicateException);
    assertNull(thrown.getCause());

    // 取消第一个操作，变为终态 CANCELLED
    assertTrue(repository.cancelPending(cmd1.id()));

    // 终态后应允许为同一来源创建新操作
    EnvironmentOperation createdAfterCancel = repository.createPending(cmd2);
    assertEquals(cmd2.id(), createdAfterCancel.id());
  }

  /** 测试意图：claimPending 必须严格校验当前节点在 environment_connection 表中具备未过期的 READY 路由。 */
  @Test
  void claimPendingRequiresCurrentLocalReadyRoute() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID nodeA = uuid();
    UUID nodeB = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    // 1. 无连接：无法认领
    assertTrue(repository.claimPending(nodeA, 10).isEmpty());

    // 2. CONNECTING 状态：无法认领
    insertConnection(envId, nodeA, uuid(), "CONNECTING", 60);
    assertTrue(repository.claimPending(nodeA, 10).isEmpty());

    // 3. 他人节点持有 READY 连接：nodeA 无法认领
    updateConnection(envId, nodeB, uuid(), "READY", 60);
    assertTrue(repository.claimPending(nodeA, 10).isEmpty());

    // 4. 连接过期：无法认领
    UUID leaseToken = uuid();
    updateConnection(envId, nodeA, leaseToken, "READY", -10);
    assertTrue(repository.claimPending(nodeA, 10).isEmpty());

    // 5. 本地节点持有 READY 且有效租约：成功认领并推进至 RUNNING
    UUID validLeaseToken = uuid();
    updateConnection(envId, nodeA, validLeaseToken, "READY", 60);
    List<EnvironmentOperation> claimed = repository.claimPending(nodeA, 10);
    assertEquals(1, claimed.size());
    EnvironmentOperation op = claimed.getFirst();
    assertEquals(opId, op.id());
    assertEquals(EnvironmentOperationStatus.RUNNING, op.status());
    assertEquals(nodeA, op.ownerNodeId());
    assertEquals(validLeaseToken, op.leaseToken());
    assertNotNull(op.startedAt());
    assertNull(op.finishedAt());

    // 已被认领的行不再被认领
    assertTrue(repository.claimPending(nodeA, 10).isEmpty());
  }

  /** 测试意图：并发 claim 竞争下，每行操作必须有且仅被一个线程认领一次。 */
  @Test
  void concurrentClaimUniqueness() throws Exception {
    UUID node = uuid();
    int count = 10;
    List<UUID> opIds = new ArrayList<>();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    for (int i = 0; i < count; i++) {
      UUID envId = createEnvironment();
      UUID sourceId = uuid();
      UUID opId = uuid();
      opIds.add(opId);
      insertConnection(envId, node, uuid(), "READY", 60);
      repository.createPending(
          pendingCmd(
              opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));
    }

    int threads = 4;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    List<Callable<List<EnvironmentOperation>>> tasks = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      tasks.add(() -> repository.claimPending(node, 10));
    }

    List<Future<List<EnvironmentOperation>>> futures = executor.invokeAll(tasks);
    executor.shutdown();

    Set<UUID> claimedIds = new HashSet<>();
    for (Future<List<EnvironmentOperation>> future : futures) {
      for (EnvironmentOperation op : future.get()) {
        assertTrue(claimedIds.add(op.id()), "Operation must not be claimed twice: " + op.id());
      }
    }

    assertEquals(count, claimedIds.size());
    assertTrue(claimedIds.containsAll(opIds));
  }

  /** 测试意图：已超期的 PENDING 操作绝不得被认领。 */
  @Test
  void expiredPendingNotClaimed() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    insertConnection(envId, node, uuid(), "READY", 60);

    // 强行把 deadline_at 改为过去时间（同时调整 created_at 保证满足 ck_environment_operation_time_order）
    jdbcTemplate.update(
        "update environment_operation set created_at = statement_timestamp() - interval '10"
            + " second', deadline_at = statement_timestamp() - interval '5 second' where id = ?",
        opId);

    List<EnvironmentOperation> claimed = repository.claimPending(node, 10);
    assertTrue(claimed.isEmpty(), "Expired PENDING operation must never be claimed");
  }

  /** 测试意图：明确未发送的 RUNNING 操作在未到期时回退 PENDING，已到期时终结 FAILED (ENVIRONMENT_UNAVAILABLE_TIMEOUT)。 */
  @Test
  void rescheduleVsDefinitelyUnsentTimeout() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    insertConnection(envId, node, leaseToken, "READY", 60);
    repository.claimPending(node, 10);

    // 1. deadline 在未来：回滚为 PENDING
    RescheduleOutcome outcome1 = repository.rescheduleUnsent(opId, node, leaseToken);
    assertEquals(RescheduleOutcome.RESCHEDULED, outcome1);

    EnvironmentOperation op1 = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.PENDING, op1.status());
    assertNull(op1.ownerNodeId());
    assertNull(op1.leaseToken());
    assertNull(op1.startedAt());
    assertNull(op1.finishedAt());

    // 2. 再次认领
    UUID leaseToken2 = uuid();
    updateConnection(envId, node, leaseToken2, "READY", 60);
    repository.claimPending(node, 10);

    // 3. 将 deadline 改为过去时间（同时调整 created_at 保证满足约束）
    jdbcTemplate.update(
        "update environment_operation set created_at = statement_timestamp() - interval '20"
            + " second', deadline_at = statement_timestamp() - interval '1 second' where id = ?",
        opId);

    // 4. deadline 在过去：回滚直接终结为 FAILED (ENVIRONMENT_UNAVAILABLE_TIMEOUT)
    RescheduleOutcome outcome2 = repository.rescheduleUnsent(opId, node, leaseToken2);
    assertEquals(RescheduleOutcome.FAILED_TIMEOUT, outcome2);

    EnvironmentOperation op2 = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, op2.status());
    assertEquals(
        EnvironmentOperationFailureCodes.ENVIRONMENT_UNAVAILABLE_TIMEOUT, op2.failureCode());
    assertEquals(EnvironmentOperationFailureCodes.UNSENT_TIMEOUT_MESSAGE, op2.failureMessage());
    assertNotNull(op2.finishedAt());
    assertNull(op2.ownerNodeId());
    assertNull(op2.leaseToken());

    // 5. 对已终结的操作再次尝试 reschedule 返回 STALE
    RescheduleOutcome outcome3 = repository.rescheduleUnsent(opId, node, leaseToken2);
    assertEquals(RescheduleOutcome.STALE, outcome3);
  }

  /** 测试意图：SUCCEEDED 与 FAILED 必须受权威节点和实时有效 READY 路由共同围栏。 */
  @Test
  void terminalRouteFenceRequiresActiveReadyConnection() throws Exception {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID nodeA = uuid();
    UUID nodeB = uuid();
    UUID leaseToken = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    insertConnection(envId, nodeA, leaseToken, "READY", 60);
    repository.claimPending(nodeA, 10);

    // 1. 他人 nodeB 无法推进终态
    assertFalse(repository.markSucceeded(opId, nodeB, leaseToken, "{}"));
    assertFalse(repository.markFailed(opId, nodeB, leaseToken, "ERROR", "err"));

    // 2. 连接租约已过期：无法推进终态
    updateConnection(envId, nodeA, leaseToken, "READY", -10);
    assertFalse(repository.markSucceeded(opId, nodeA, leaseToken, "{}"));

    // 3. 连接状态变为 CONNECTING：无法推进终态
    updateConnection(envId, nodeA, leaseToken, "CONNECTING", 60);
    assertFalse(repository.markSucceeded(opId, nodeA, leaseToken, "{}"));

    // 4. 恢复有效 READY 租约：成功推进为 SUCCEEDED
    updateConnection(envId, nodeA, leaseToken, "READY", 60);
    assertTrue(repository.markSucceeded(opId, nodeA, leaseToken, "{\"result\":\"ok\"}"));

    EnvironmentOperation op = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.SUCCEEDED, op.status());
    assertEquals(
        objectMapper.readTree("{\"result\":\"ok\"}"), objectMapper.readTree(op.resultSummary()));
    assertNotNull(op.finishedAt());
  }

  /** 测试意图：数据库 deadline 是硬围栏；清扫前到达的迟到成功或失败结果都不得抢先终结 RUNNING 操作。 */
  @Test
  void expiredRunningOperationRejectsAuthoritativeTerminalResult() throws Exception {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();

    repository.createPending(
        pendingCmd(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            Instant.now().plus(10, ChronoUnit.MINUTES)));
    insertConnection(envId, node, leaseToken, "READY", 60);
    repository.claimPending(node, 10);

    jdbcTemplate.update(
        "update environment_operation set created_at = statement_timestamp() - interval '10"
            + " second', deadline_at = statement_timestamp() - interval '1 second' where id = ?",
        opId);

    assertFalse(repository.markSucceeded(opId, node, leaseToken, "{\"late\":true}"));
    assertFalse(repository.markFailed(opId, node, leaseToken, "LATE_RESULT", "late result"));
    assertEquals(EnvironmentOperationStatus.RUNNING, repository.getById(opId).status());

    assertEquals(1, repository.sweepExpiredRunning());
    EnvironmentOperation swept = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.UNKNOWN, swept.status());
    assertEquals(EnvironmentOperationFailureCodes.RESULT_TIMEOUT, swept.failureCode());
  }

  /** 测试意图：被抢占的旧 owner / 租约代币无法提交终态。 */
  @Test
  void staleOwnerOrLeaseTokenCannotCommitTerminal() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID nodeA = uuid();
    UUID tokenA = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    insertConnection(envId, nodeA, tokenA, "READY", 60);
    repository.claimPending(nodeA, 10);

    // 错误的 leaseToken 无法提交
    UUID wrongToken = uuid();
    assertFalse(repository.markSucceeded(opId, nodeA, wrongToken, "{}"));
    assertFalse(repository.markFailed(opId, nodeA, wrongToken, "FAILED", "err"));
    assertFalse(repository.markUnknown(opId, nodeA, wrongToken, "FAILED", "err"));

    // 仍为 RUNNING
    assertEquals(EnvironmentOperationStatus.RUNNING, repository.getById(opId).status());
  }

  /** 测试意图：路由断开后 markUnknown 仅需要操作本身的 RUNNING 认领元组围栏（不强依赖活跃连接）。 */
  @Test
  void markUnknownAllowsMissingRoute() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID nodeA = uuid();
    UUID tokenA = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    insertConnection(envId, nodeA, tokenA, "READY", 60);
    repository.claimPending(nodeA, 10);

    // 模拟连接已断开且删除
    jdbcTemplate.update("delete from environment_connection where environment_id = ?", envId);

    // markSucceeded 失败（因缺少 READY 路由）
    assertFalse(repository.markSucceeded(opId, nodeA, tokenA, "{}"));

    // markUnknown 成功（因仅受操作本身认领元组围栏）
    assertTrue(repository.markUnknown(opId, nodeA, tokenA, "ROUTE_LOST", "connection dropped"));

    EnvironmentOperation op = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.UNKNOWN, op.status());
    assertEquals("ROUTE_LOST", op.failureCode());
    assertEquals("connection dropped", op.failureMessage());
    assertEquals(nodeA, op.ownerNodeId());
    assertEquals(tokenA, op.leaseToken());
    assertNotNull(op.finishedAt());
  }

  /** 测试意图：仅允许 PENDING 操作被取消；RUNNING 或已终结的操作 cancelPending 返回 false。 */
  @Test
  void cancelPendingOnly() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    // 1. PENDING 状态下成功取消
    assertTrue(repository.cancelPending(opId));
    EnvironmentOperation cancelled = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.CANCELLED, cancelled.status());
    assertNotNull(cancelled.finishedAt());

    // 2. 再次取消返回 false（已是终态）
    assertFalse(repository.cancelPending(opId));

    // 3. 创建新操作并流转至 RUNNING
    UUID opId2 = uuid();
    repository.createPending(
        pendingCmd(opId2, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));
    insertConnection(envId, node, leaseToken, "READY", 60);
    repository.claimPending(node, 10);

    // RUNNING 状态无法 cancelPending
    assertFalse(repository.cancelPending(opId2));
  }

  /** 测试意图：截止时间过期清扫：PENDING 清扫为 FAILED，RUNNING 清扫为 UNKNOWN。 */
  @Test
  void deadlineSweepExpired() throws SQLException {
    UUID envId = createEnvironment();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    UUID opRunning = uuid();
    UUID opPending = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    // 先创建 opRunning 并立即认领，确保它处于 RUNNING 状态
    repository.createPending(
        pendingCmd(
            opRunning, envId, uuid(), EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));
    repository.claimPending(node, 1);

    // 再创建 opPending，保持 PENDING 状态
    repository.createPending(
        pendingCmd(
            opPending, envId, uuid(), EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    // 人工将两个操作的截止时间调整为过去
    jdbcTemplate.update(
        "update environment_operation set created_at = statement_timestamp() - interval '20"
            + " second', deadline_at = statement_timestamp() - interval '2 second' where id in (?, ?)",
        opPending,
        opRunning);

    // 执行清扫
    DeadlineSweepResult result = repository.sweepExpired();
    assertEquals(1, result.expiredPendingCount());
    assertEquals(1, result.expiredRunningCount());

    EnvironmentOperation sweptPending = repository.getById(opPending);
    assertEquals(EnvironmentOperationStatus.FAILED, sweptPending.status());
    assertEquals(
        EnvironmentOperationFailureCodes.ENVIRONMENT_UNAVAILABLE_TIMEOUT,
        sweptPending.failureCode());
    assertNull(sweptPending.ownerNodeId());
    assertNull(sweptPending.leaseToken());

    EnvironmentOperation sweptRunning = repository.getById(opRunning);
    assertEquals(EnvironmentOperationStatus.UNKNOWN, sweptRunning.status());
    assertEquals(EnvironmentOperationFailureCodes.RESULT_TIMEOUT, sweptRunning.failureCode());
    assertEquals(node, sweptRunning.ownerNodeId());
    assertNotNull(sweptRunning.leaseToken());

    // 再次清扫不应产生新变更
    DeadlineSweepResult emptyResult = repository.sweepExpired();
    assertEquals(0, emptyResult.expiredPendingCount());
    assertEquals(0, emptyResult.expiredRunningCount());
  }

  /** 测试意图：终态记录不可再被修改，保证单终态语义。 */
  @Test
  void terminalStateImmutability() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    repository.createPending(
        pendingCmd(opId, envId, sourceId, EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    insertConnection(envId, node, leaseToken, "READY", 60);
    repository.claimPending(node, 10);
    assertTrue(repository.markSucceeded(opId, node, leaseToken, "{}"));

    // 试图对 SUCCEEDED 执行 markFailed、markUnknown、cancelPending、rescheduleUnsent 均应失败
    assertFalse(repository.markFailed(opId, node, leaseToken, "FAIL", "fail"));
    assertFalse(repository.markUnknown(opId, node, leaseToken, "UNKNOWN", "unk"));
    assertFalse(repository.cancelPending(opId));
    assertEquals(RescheduleOutcome.STALE, repository.rescheduleUnsent(opId, node, leaseToken));

    assertEquals(EnvironmentOperationStatus.SUCCEEDED, repository.getById(opId).status());
  }

  /**
   * 测试意图：listByEnvironment 与 listSafeByEnvironment 必须保证 created_at DESC, id DESC 确定性排序与 limit 边界。
   */
  @Test
  void listByEnvironmentDeterministicOrdering() throws SQLException {
    UUID envId = createEnvironment();
    int count = 5;
    List<UUID> opIds = new ArrayList<>();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    for (int i = 0; i < count; i++) {
      UUID opId = uuid();
      opIds.add(opId);
      repository.createPending(
          pendingCmd(opId, envId, uuid(), EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));
    }

    List<EnvironmentOperation> list = repository.listByEnvironment(envId, 3);
    assertEquals(3, list.size());

    // 默认按照创建时间逆序，最新插入的在前面
    assertEquals(opIds.get(4), list.get(0).id());
    assertEquals(opIds.get(3), list.get(1).id());
    assertEquals(opIds.get(2), list.get(2).id());

    List<SafeEnvironmentOperation> safeList = repository.listSafeByEnvironment(envId, 3);
    assertEquals(3, safeList.size());
    assertEquals(opIds.get(4), safeList.get(0).id());
    assertEquals(opIds.get(3), safeList.get(1).id());
    assertEquals(opIds.get(2), safeList.get(2).id());
  }

  /** 测试意图：停机处理 markRunningUnknownOnShutdown 能原子将本节点持有的所有 RUNNING 转为 UNKNOWN。 */
  @Test
  void shutdownHelper() throws SQLException {
    UUID envId1 = createEnvironment();
    UUID envId2 = createEnvironment();
    UUID nodeA = uuid();
    UUID nodeB = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    UUID opA = uuid();
    UUID opB = uuid();
    repository.createPending(
        pendingCmd(opA, envId1, uuid(), EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));
    repository.createPending(
        pendingCmd(opB, envId2, uuid(), EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    insertConnection(envId1, nodeA, uuid(), "READY", 60);
    insertConnection(envId2, nodeB, uuid(), "READY", 60);

    repository.claimPending(nodeA, 1);
    repository.claimPending(nodeB, 1);

    // nodeA 优雅停机
    int updated = repository.markRunningUnknownOnShutdown(nodeA);
    assertEquals(1, updated);

    EnvironmentOperation opAAfter = repository.getById(opA);
    assertEquals(EnvironmentOperationStatus.UNKNOWN, opAAfter.status());
    assertEquals(EnvironmentOperationFailureCodes.DISPATCHER_SHUTDOWN, opAAfter.failureCode());
    assertEquals(
        EnvironmentOperationFailureCodes.DISPATCHER_SHUTDOWN_MESSAGE, opAAfter.failureMessage());

    // nodeB 仍在 RUNNING（不受 nodeA 停机影响）
    assertEquals(EnvironmentOperationStatus.RUNNING, repository.getById(opB).status());

    // 再次调用停机辅助应返回 0（单终态语义）
    assertEquals(0, repository.markRunningUnknownOnShutdown(nodeA));
  }

  /** 测试意图：在进入 SQL 之前完成参数结构、版本非负与严格 JSON 形状（拦截重复 key 与尾随 token）校验。 */
  @Test
  void validationBeforeSql() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    // 1. 版本非负校验（进入 SQL 前拦截）
    AiValidationException srcVerEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        EnvironmentOperationResourceType.SKILL_SOURCE,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        -1L,
                        "{}",
                        "{}",
                        deadlineAt)));
    assertTrue(srcVerEx.getMessage().contains("resourceVersion must be non-negative"));
    assertNull(srcVerEx.getCause());

    // 资源类型与操作类型不匹配拦截
    AiValidationException pairEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        EnvironmentOperationResourceType.MCP_SERVER,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        0L,
                        "{}",
                        "{}",
                        deadlineAt)));
    assertTrue(pairEx.getMessage().contains("is incompatible with resourceType"));
    assertNull(pairEx.getCause());

    // 2. 非法的 arguments JSON（非对象）
    assertThrows(
        AiValidationException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    envId,
                    EnvironmentOperationResourceType.SKILL_SOURCE,
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    0L,
                    "[1, 2, 3]",
                    "{}",
                    deadlineAt)));

    // 3. 畸形 JSON 语法（确认错误信息不泄漏私有内容）
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        EnvironmentOperationResourceType.SKILL_SOURCE,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        0L,
                        "not-json-secret",
                        "{}",
                        deadlineAt)));
    assertFalse(ex.getMessage().contains("not-json-secret"));
    assertNull(ex.getCause());

    // 4. arguments 包含重复 object key（严格拦截，且不泄露输入值）
    AiValidationException dupKeyEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        EnvironmentOperationResourceType.SKILL_SOURCE,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        0L,
                        "{\"secretKey\":\"valueA\",\"secretKey\":\"valueB\"}",
                        "{}",
                        deadlineAt)));
    assertTrue(dupKeyEx.getMessage().contains("arguments must be a valid JSON object"));
    assertFalse(dupKeyEx.getMessage().contains("valueA"));
    assertFalse(dupKeyEx.getMessage().contains("valueB"));
    assertNull(dupKeyEx.getCause());

    // 5. arguments 包含尾随 token（严格拦截，且不泄露输入值）
    AiValidationException trailingEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        EnvironmentOperationResourceType.SKILL_SOURCE,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        0L,
                        "{\"secretKey\":\"value\"} trailing_secret_garbage",
                        "{}",
                        deadlineAt)));
    assertTrue(trailingEx.getMessage().contains("arguments must be a valid JSON object"));
    assertFalse(trailingEx.getMessage().contains("trailing_secret_garbage"));
    assertNull(trailingEx.getCause());

    // 6. 不存在的 Environment 抛出 AiResourceNotFoundException
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    uuid(),
                    EnvironmentOperationResourceType.SKILL_SOURCE,
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    0L,
                    "{}",
                    "{}",
                    deadlineAt)));

    // 7. parameterSummary 非对象、重复 key、尾随 token
    assertThrows(
        AiValidationException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    envId,
                    EnvironmentOperationResourceType.SKILL_SOURCE,
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    0L,
                    "{}",
                    "\"not-an-object\"",
                    deadlineAt)));

    AiValidationException paramDupEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        EnvironmentOperationResourceType.SKILL_SOURCE,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        0L,
                        "{}",
                        "{\"param\":1,\"param\":2}",
                        deadlineAt)));
    assertTrue(paramDupEx.getMessage().contains("parameterSummary must be a valid JSON object"));
    assertNull(paramDupEx.getCause());

    AiValidationException paramTrailingEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        EnvironmentOperationResourceType.SKILL_SOURCE,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        0L,
                        "{}",
                        "{\"param\":1} extra_token",
                        deadlineAt)));
    assertTrue(
        paramTrailingEx.getMessage().contains("parameterSummary must be a valid JSON object"));
    assertNull(paramTrailingEx.getCause());
  }

  /** 测试意图：覆盖各种边界输入（非法结果摘要、不存在的 ID 等）的防御性校验。 */
  @Test
  void additionalEdgeCasesAndValidation() throws SQLException {
    UUID envId = createEnvironment();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    UUID opId = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        pendingCmd(opId, envId, uuid(), EnvironmentOperationType.SKILL_REFRESH, 0L, deadlineAt));

    repository.claimPending(node, 10);

    // markSucceeded 校验：非法 resultSummary JSON（非对象、畸形、重复 key 与尾随 token）
    assertThrows(
        AiValidationException.class,
        () -> repository.markSucceeded(opId, node, leaseToken, "[1, 2, 3]"));
    assertThrows(
        AiValidationException.class,
        () -> repository.markSucceeded(opId, node, leaseToken, "not-json"));

    AiValidationException resDupEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.markSucceeded(
                    opId, node, leaseToken, "{\"secretRes\":\"1\",\"secretRes\":\"2\"}"));
    assertTrue(resDupEx.getMessage().contains("resultSummary must be a valid JSON object"));
    assertFalse(resDupEx.getMessage().contains("secretRes"));
    assertFalse(resDupEx.getMessage().contains("1"));
    assertNull(resDupEx.getCause());

    AiValidationException resTrailingEx =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.markSucceeded(
                    opId, node, leaseToken, "{\"secretRes\":\"1\"} trailing_secret"));
    assertTrue(resTrailingEx.getMessage().contains("resultSummary must be a valid JSON object"));
    assertFalse(resTrailingEx.getMessage().contains("trailing_secret"));
    assertNull(resTrailingEx.getCause());

    // getById 不存在的操作
    UUID nonExistentId = uuid();
    assertThrows(AiResourceNotFoundException.class, () -> repository.getById(nonExistentId));
    assertTrue(repository.findById(nonExistentId).isEmpty());

    // claimPending limit <= 0
    assertTrue(repository.claimPending(node, 0).isEmpty());
    assertTrue(repository.claimPending(node, -1).isEmpty());

    // rescheduleUnsent 不存在的操作返回 STALE
    assertEquals(
        RescheduleOutcome.STALE, repository.rescheduleUnsent(nonExistentId, node, leaseToken));
  }

  /** 测试意图：验证基于相对超时毫秒数创建 PENDING 操作时，截止时间由 PostgreSQL 服务端计算，且事务提交时发出 pg_notify 通知。 */
  @Test
  void createPendingWithTimeoutCalculatesDeadlineAndEmitsPgNotify() throws Exception {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();

    try (Connection listenConn = newConnection();
        Statement stmt = listenConn.createStatement()) {
      listenConn.setAutoCommit(true);
      stmt.execute("LISTEN " + EnvironmentOperationRepository.NOTIFY_CHANNEL);
      PGConnection pgConnection = listenConn.unwrap(PGConnection.class);

      CreatePendingOperationWithTimeoutCommand command =
          timeoutCmd(
              opId,
              envId,
              sourceId,
              EnvironmentOperationType.SKILL_REFRESH,
              1L,
              "{\"url\":\"https://example.com/repo.git\"}",
              "{\"sourceType\":\"git\"}",
              60000L);

      EnvironmentOperation created = repository.createPendingWithTimeout(command);
      assertNotNull(created);
      assertEquals(opId, created.id());
      assertEquals(EnvironmentOperationStatus.PENDING, created.status());
      assertTrue(created.deadlineAt().isAfter(Instant.now().plusSeconds(50)));

      // 验证收到了 pg_notify 通知
      PGNotification[] notifications = pgConnection.getNotifications(5000);
      assertNotNull(notifications, "必须收到 pg_notify 通知");
      assertTrue(notifications.length > 0);
      boolean found = false;
      for (PGNotification n : notifications) {
        if (EnvironmentOperationRepository.NOTIFY_CHANNEL.equals(n.getName())
            && opId.toString().equals(n.getParameter())) {
          found = true;
          break;
        }
      }
      assertTrue(found, "通知负载必须是 operationId 字符串");
    }
  }

  /** 测试意图：验证 claimPendingWithTimeout 成功推进为 RUNNING 并返回 PostgreSQL 服务端计算的剩余超时（严格正数）。 */
  @Test
  void claimPendingWithTimeoutReturnsPositiveRemainingDuration() throws Exception {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();

    insertConnection(envId, node, leaseToken, "READY", 300);

    CreatePendingOperationWithTimeoutCommand command =
        timeoutCmd(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            1L,
            "{\"type\":\"git\"}",
            "{\"sourceType\":\"git\"}",
            30000L);
    repository.createPendingWithTimeout(command);

    List<ClaimedOperation> claimed = repository.claimPendingWithTimeout(node, 10);
    assertEquals(1, claimed.size());
    ClaimedOperation first = claimed.getFirst();
    assertEquals(opId, first.operation().id());
    assertEquals(EnvironmentOperationStatus.RUNNING, first.operation().status());
    assertEquals(node, first.operation().ownerNodeId());
    assertFalse(first.remainingTimeout().isZero());
    assertFalse(first.remainingTimeout().isNegative());
    assertTrue(first.remainingTimeout().toMillis() > 0);
    assertTrue(first.remainingTimeout().toMillis() <= 30000L);
  }

  /** 测试意图：验证 sweepLocalExpiredRunning 仅清扫当前节点的超期 RUNNING 行，并返回 SweptOperationInfo。 */
  @Test
  void sweepLocalExpiredRunningOnlySweepsTargetOwner() throws Exception {
    UUID envId = createEnvironment();
    UUID sourceId1 = uuid();
    UUID sourceId2 = uuid();
    UUID opId1 = uuid();
    UUID opId2 = uuid();
    UUID nodeA = uuid();
    UUID nodeB = uuid();
    UUID leaseTokenA = uuid();
    UUID leaseTokenB = uuid();

    insertConnection(envId, nodeA, leaseTokenA, "READY", 300);

    // 插入已过期的 RUNNING 操作 1 (属于 nodeA)
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into environment_operation (id, environment_id, resource_type, resource_id,"
                    + " operation_type, status, resource_version, arguments, parameter_summary,"
                    + " deadline_at, owner_node_id, lease_token, started_at, created_at, updated_at)"
                    + " values (?, ?, 'SKILL_SOURCE', ?, 'SKILL_REFRESH', 'RUNNING', 1, '{}'::jsonb,"
                    + " '{}'::jsonb, statement_timestamp() - interval '10 second', ?, ?,"
                    + " statement_timestamp() - interval '20 second',"
                    + " statement_timestamp() - interval '30 second', statement_timestamp() - interval '20 second')")) {
      ps.setObject(1, opId1);
      ps.setObject(2, envId);
      ps.setObject(3, sourceId1);
      ps.setObject(4, nodeA);
      ps.setObject(5, leaseTokenA);
      ps.executeUpdate();
    }

    // 插入已过期的 RUNNING 操作 2 (属于 nodeB)
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into environment_operation (id, environment_id, resource_type, resource_id,"
                    + " operation_type, status, resource_version, arguments, parameter_summary,"
                    + " deadline_at, owner_node_id, lease_token, started_at, created_at, updated_at)"
                    + " values (?, ?, 'SKILL_SOURCE', ?, 'SKILL_REFRESH', 'RUNNING', 1, '{}'::jsonb,"
                    + " '{}'::jsonb, statement_timestamp() - interval '10 second', ?, ?,"
                    + " statement_timestamp() - interval '20 second',"
                    + " statement_timestamp() - interval '30 second', statement_timestamp() - interval '20 second')")) {
      ps.setObject(1, opId2);
      ps.setObject(2, envId);
      ps.setObject(3, sourceId2);
      ps.setObject(4, nodeB);
      ps.setObject(5, leaseTokenB);
      ps.executeUpdate();
    }

    // 执行 nodeA 的局部清扫
    List<SweptOperationInfo> swept = repository.sweepLocalExpiredRunning(nodeA);
    assertEquals(1, swept.size());
    assertEquals(opId1, swept.getFirst().id());
    assertEquals(nodeA, swept.getFirst().ownerNodeId());
    assertEquals(leaseTokenA, swept.getFirst().leaseToken());

    // 验证 opId1 变为 UNKNOWN，opId2 仍为 RUNNING
    assertEquals(EnvironmentOperationStatus.UNKNOWN, repository.getById(opId1).status());
    assertEquals(EnvironmentOperationStatus.RUNNING, repository.getById(opId2).status());
  }

  /** 测试意图：验证 findSafe 与 getSafe 安全投影，当环境不匹配或记录不存在时返回 empty 或抛出 404。 */
  @Test
  void findSafeAndGetSafeProjections() throws Exception {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();

    CreatePendingOperationWithTimeoutCommand command =
        timeoutCmd(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            1L,
            "{\"secretArg\":\"value\"}",
            "{\"sourceType\":\"git\"}",
            30000L);
    repository.createPendingWithTimeout(command);

    SafeEnvironmentOperation safe = repository.getSafe(envId, opId);
    assertNotNull(safe);
    assertEquals(opId, safe.id());
    assertEquals(envId, safe.environmentId());
    assertEquals(
        objectMapper.readTree("{\"sourceType\":\"git\"}"),
        objectMapper.readTree(safe.parameterSummary()));

    // 环境 ID 不匹配返回 empty
    UUID otherEnvId = createEnvironment();
    assertTrue(repository.findSafe(otherEnvId, opId).isEmpty());
    assertThrows(AiResourceNotFoundException.class, () -> repository.getSafe(otherEnvId, opId));

    // 不存在的 operationId
    UUID nonExistentOp = uuid();
    assertTrue(repository.findSafe(envId, nonExistentOp).isEmpty());
    assertThrows(AiResourceNotFoundException.class, () -> repository.getSafe(envId, nonExistentOp));
  }

  /**
   * 测试意图：直接在数据库层验证 V4 迁移引入的 CHECK 约束和部分唯一索引： 1. operation_type 与 resource_type 的枚举约束及 pairing 约束；
   * 2. 同一资源在 PENDING/RUNNING 状态下的部分唯一索引； 3. 证明 MCP_SERVER_DISCOVER 只能与 MCP_SERVER 配对，SKILL_* 只能与
   * SKILL_SOURCE 配对。
   */
  @Test
  void databaseConstraintsAndPairingEnforcedAtSqlLevel() throws SQLException {
    UUID envId = createEnvironment();
    UUID skillSourceId = uuid();
    UUID mcpServerId = uuid();

    String insertSql =
        """
        insert into environment_operation (
            id, environment_id, resource_type, resource_id,
            operation_type, status, resource_version,
            arguments, parameter_summary, deadline_at,
            created_at, updated_at
        ) values (
            ?, ?, ?, ?,
            ?, 'PENDING', 0,
            '{}'::jsonb, '{}'::jsonb, statement_timestamp() + interval '10 minutes',
            statement_timestamp(), statement_timestamp()
        )
        """;

    // 1. 合法插入：Skill 操作 + SKILL_SOURCE
    jdbcTemplate.update(insertSql, uuid(), envId, "SKILL_SOURCE", skillSourceId, "SKILL_REFRESH");
    jdbcTemplate.update(insertSql, uuid(), envId, "SKILL_SOURCE", uuid(), "SKILL_INSTALL");
    jdbcTemplate.update(insertSql, uuid(), envId, "SKILL_SOURCE", uuid(), "SKILL_UPDATE");

    // 2. 合法插入：MCP 操作 + MCP_SERVER
    jdbcTemplate.update(insertSql, uuid(), envId, "MCP_SERVER", mcpServerId, "MCP_SERVER_DISCOVER");

    // 3. 非法 operation_type: 触发 ck_environment_operation_type
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(insertSql, uuid(), envId, "SKILL_SOURCE", uuid(), "INVALID_TYPE"));

    // 4. 非法 resource_type: 触发 ck_environment_operation_resource_type
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                insertSql, uuid(), envId, "INVALID_RESOURCE", uuid(), "SKILL_REFRESH"));

    // 5. 配对错误：SKILL_REFRESH 与 MCP_SERVER 配对 -> 触发 ck_environment_operation_type_resource_pair
    assertThrows(
        DataAccessException.class,
        () -> jdbcTemplate.update(insertSql, uuid(), envId, "MCP_SERVER", uuid(), "SKILL_REFRESH"));

    // 6. 配对错误：MCP_SERVER_DISCOVER 与 SKILL_SOURCE 配对 -> 触发
    // ck_environment_operation_type_resource_pair
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                insertSql, uuid(), envId, "SKILL_SOURCE", uuid(), "MCP_SERVER_DISCOVER"));

    // 7. 活跃部分唯一索引：同一 (envId, 'MCP_SERVER', mcpServerId) 存在第二个 PENDING 操作 -> 触发
    // uk_environment_operation_active
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                insertSql, uuid(), envId, "MCP_SERVER", mcpServerId, "MCP_SERVER_DISCOVER"));

    // 8. 相同 resourceId 但不同 resource_type: 不冲突
    jdbcTemplate.update(insertSql, uuid(), envId, "SKILL_SOURCE", mcpServerId, "SKILL_REFRESH");
  }

  private UUID createEnvironment() throws SQLException {
    UUID id = uuid();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into environment (id, name, registration_token) values (?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, "env-" + FIXTURE_IDS.incrementAndGet());
      ps.setString(3, "token-" + FIXTURE_IDS.incrementAndGet());
      ps.executeUpdate();
    }
    return id;
  }

  private void insertConnection(
      UUID environmentId, UUID ownerNodeId, UUID leaseToken, String status, int leaseSeconds)
      throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into environment_connection (environment_id, owner_node_id, lease_token,"
                    + " status, runtime_info, last_seen_at, lease_until) values (?, ?, ?, ?,"
                    + " case when ? = 'READY' then '{}'::jsonb else null end,"
                    + " statement_timestamp(), statement_timestamp() + (? * interval '1 second'))")) {
      ps.setObject(1, environmentId);
      ps.setObject(2, ownerNodeId);
      ps.setObject(3, leaseToken);
      ps.setString(4, status);
      ps.setString(5, status);
      ps.setInt(6, leaseSeconds);
      ps.executeUpdate();
    }
  }

  private void updateConnection(
      UUID environmentId, UUID ownerNodeId, UUID leaseToken, String status, int leaseSeconds)
      throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update environment_connection set owner_node_id = ?, lease_token = ?, status = ?,"
                    + " runtime_info = case when ? = 'READY' then '{}'::jsonb else null end,"
                    + " last_seen_at = case when ?::int < 0 then statement_timestamp() + (? * 2 *"
                    + " interval '1 second') else statement_timestamp() end,"
                    + " lease_until = statement_timestamp() + (? * interval '1 second') where"
                    + " environment_id = ?")) {
      ps.setObject(1, ownerNodeId);
      ps.setObject(2, leaseToken);
      ps.setString(3, status);
      ps.setString(4, status);
      ps.setInt(5, leaseSeconds);
      ps.setInt(6, leaseSeconds);
      ps.setInt(7, leaseSeconds);
      ps.setObject(8, environmentId);
      ps.executeUpdate();
    }
  }

  private static UUID uuid() {
    return new UUID(0L, FIXTURE_IDS.incrementAndGet());
  }
}

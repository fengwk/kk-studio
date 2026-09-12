package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
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
 * <p>验证状态机原子推进、认领围栏、安全投影、并发唯一性与超时清扫。
 */
class PostgresqlEnvironmentOperationRepositoryIntegrationTest extends PostgresSchemaSupport {

  private JdbcTemplate jdbcTemplate;
  private PostgresqlEnvironmentOperationRepository repository;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(newConnection(), false);
    jdbcTemplate = new JdbcTemplate(dataSource);
    repository = new PostgresqlEnvironmentOperationRepository(jdbcTemplate);
  }

  /** 测试意图：验证完整的创建与读取闭环，确保私有 arguments 绝不泄露到 toString、异常或公开投影中。 */
  @Test
  void roundTripWithoutToStringLeak() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    String sensitiveUrl = "https://user:mock-token-secret@example.com/repo.git";
    String arguments = "{\"gitUrl\":\"" + sensitiveUrl + "\",\"branch\":\"main\"}";
    String parameterSummary = "{\"gitRef\":\"main\"}";
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    CreatePendingOperationCommand command =
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_INSTALL,
            1L,
            0L,
            arguments,
            parameterSummary,
            deadlineAt);

    // 验证 Command 的 toString 绝不包含敏感信息
    assertFalse(command.toString().contains("mock-token-secret"));
    assertFalse(command.toString().contains(sensitiveUrl));

    EnvironmentOperation created = repository.createPending(command);
    assertEquals(opId, created.id());
    assertEquals(EnvironmentOperationStatus.PENDING, created.status());
    assertTrue(created.arguments().contains(sensitiveUrl));
    assertNull(created.ownerNodeId());
    assertNull(created.leaseToken());
    assertNull(created.startedAt());
    assertNull(created.finishedAt());

    // 验证 Model 的 toString 绝不包含 arguments / 敏感信息
    assertFalse(created.toString().contains("mock-token-secret"));
    assertFalse(created.toString().contains(sensitiveUrl));

    EnvironmentOperation fetched = repository.getById(opId);
    assertTrue(fetched.arguments().contains(sensitiveUrl));
    assertFalse(fetched.toString().contains("mock-token-secret"));

    SafeEnvironmentOperation safe = fetched.toSafeProjection();
    assertNotNull(safe);
    assertFalse(safe.toString().contains("mock-token-secret"));

    List<SafeEnvironmentOperation> safeList = repository.listSafeByEnvironment(envId, 10);
    assertEquals(1, safeList.size());
    assertFalse(safeList.getFirst().toString().contains("mock-token-secret"));
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
        new CreatePendingOperationCommand(
            uuid(),
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            arguments,
            "{}",
            deadlineAt);
    repository.createPending(cmd1);

    CreatePendingOperationCommand cmd2 =
        new CreatePendingOperationCommand(
            uuid(),
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            arguments,
            "{}",
            deadlineAt);

    DuplicateActiveOperationException thrown =
        assertThrows(DuplicateActiveOperationException.class, () -> repository.createPending(cmd2));
    assertEquals(envId, thrown.getEnvironmentId());
    assertEquals(sourceId, thrown.getSourceId());
    assertFalse(thrown.getMessage().contains(sensitiveSecret));
    assertTrue(thrown instanceof AiDuplicateException);

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
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

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
          new CreatePendingOperationCommand(
              opId,
              envId,
              sourceId,
              EnvironmentOperationType.SKILL_REFRESH,
              0L,
              0L,
              "{}",
              "{}",
              deadlineAt));
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
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

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
    insertConnection(envId, node, leaseToken, "READY", 60);

    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

    List<EnvironmentOperation> claimed = repository.claimPending(node, 10);
    assertEquals(1, claimed.size());

    // 1. deadline 在未来：回滚为 PENDING 并清除认领元组
    RescheduleOutcome outcome1 = repository.rescheduleUnsent(opId, node, leaseToken);
    assertEquals(RescheduleOutcome.RESCHEDULED, outcome1);

    EnvironmentOperation opAfterReschedule = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.PENDING, opAfterReschedule.status());
    assertNull(opAfterReschedule.ownerNodeId());
    assertNull(opAfterReschedule.leaseToken());
    assertNull(opAfterReschedule.startedAt());
    assertNull(opAfterReschedule.finishedAt());

    // 再次认领
    UUID newLeaseToken = uuid();
    updateConnection(envId, node, newLeaseToken, "READY", 60);
    claimed = repository.claimPending(node, 10);
    assertEquals(1, claimed.size());

    // 2. deadline 已过去：终结为 FAILED (ENVIRONMENT_UNAVAILABLE_TIMEOUT)
    jdbcTemplate.update(
        "update environment_operation set created_at = statement_timestamp() - interval '10"
            + " second', deadline_at = statement_timestamp() - interval '5 second' where id = ?",
        opId);

    RescheduleOutcome outcome2 = repository.rescheduleUnsent(opId, node, newLeaseToken);
    assertEquals(RescheduleOutcome.FAILED_TIMEOUT, outcome2);

    EnvironmentOperation opAfterTimeout = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.FAILED, opAfterTimeout.status());
    assertEquals(
        EnvironmentOperationFailureCodes.ENVIRONMENT_UNAVAILABLE_TIMEOUT,
        opAfterTimeout.failureCode());
    assertNotNull(opAfterTimeout.finishedAt());
    assertNull(opAfterTimeout.ownerNodeId());
    assertNull(opAfterTimeout.leaseToken());
    assertNull(opAfterTimeout.startedAt());

    // 3. 对已终态的行调用 rescheduleUnsent 返回 STALE
    RescheduleOutcome outcome3 = repository.rescheduleUnsent(opId, node, newLeaseToken);
    assertEquals(RescheduleOutcome.STALE, outcome3);
  }

  /** 测试意图：authoritative daemon 终态转换由当前 RUNNING owner/token 与 DB 当前 READY 活跃连接共同围栏。 */
  @Test
  void authoritativeSuccessAndFailureRouteFence() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId1 = uuid();
    UUID opId2 = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        new CreatePendingOperationCommand(
            opId1,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

    repository.claimPending(node, 10);

    // 成功终态推进
    assertTrue(repository.markSucceeded(opId1, node, leaseToken, "{\"installed\":1}"));
    EnvironmentOperation op1 = repository.getById(opId1);
    assertEquals(EnvironmentOperationStatus.SUCCEEDED, op1.status());
    assertEquals("{\"installed\": 1}", op1.resultSummary().replaceAll("\\s+", " ").trim());
    assertNotNull(op1.finishedAt());
    assertEquals(node, op1.ownerNodeId());
    assertEquals(leaseToken, op1.leaseToken());
    assertNotNull(op1.startedAt());

    // 单终态：再次推进失败
    assertFalse(repository.markSucceeded(opId1, node, leaseToken, "{}"));
    assertFalse(repository.markFailed(opId1, node, leaseToken, "CODE", "message"));

    // 失败终态推进
    UUID sourceId2 = uuid();
    repository.createPending(
        new CreatePendingOperationCommand(
            opId2,
            envId,
            sourceId2,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));
    repository.claimPending(node, 10);

    assertTrue(repository.markFailed(opId2, node, leaseToken, "EXECUTION_ERROR", "failed to run"));
    EnvironmentOperation op2 = repository.getById(opId2);
    assertEquals(EnvironmentOperationStatus.FAILED, op2.status());
    assertEquals("EXECUTION_ERROR", op2.failureCode());
    assertEquals("failed to run", op2.failureMessage());
    assertNotNull(op2.finishedAt());
    assertEquals(node, op2.ownerNodeId());
    assertEquals(leaseToken, op2.leaseToken());
  }

  /** 测试意图：路由失效、token 变更或租约过期时拒绝 daemon 终态提交。 */
  @Test
  void staleOwnerLeaseRejection() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));
    repository.claimPending(node, 10);

    // 1. 错误的 node / token
    assertFalse(repository.markSucceeded(opId, uuid(), leaseToken, "{}"));
    assertFalse(repository.markSucceeded(opId, node, uuid(), "{}"));

    // 2. 连接表中的 lease_token 变化（被抢占）
    updateConnection(envId, node, uuid(), "READY", 60);
    assertFalse(repository.markSucceeded(opId, node, leaseToken, "{}"));

    // 恢复原 token 但令其过期
    updateConnection(envId, node, leaseToken, "READY", -10);
    assertFalse(repository.markSucceeded(opId, node, leaseToken, "{}"));

    // 连接表变为 CONNECTING
    updateConnection(envId, node, leaseToken, "CONNECTING", 60);
    assertFalse(repository.markSucceeded(opId, node, leaseToken, "{}"));

    // 验证状态依旧是 RUNNING，未被污染
    assertEquals(EnvironmentOperationStatus.RUNNING, repository.getById(opId).status());
  }

  /** 测试意图：路由丢失后，仅凭操作行本身的 owner/token 围栏即可标记为 UNKNOWN。 */
  @Test
  void unknownAfterRouteLoss() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));
    repository.claimPending(node, 10);

    // 彻底删除连接记录（模拟连接丢失）
    jdbcTemplate.update("delete from environment_connection where environment_id = ?", envId);

    // markSucceeded 无法成功（需要 READY 路由）
    assertFalse(repository.markSucceeded(opId, node, leaseToken, "{}"));

    // markUnknown 仅需操作行围栏，应成功
    assertTrue(
        repository.markUnknown(
            opId, node, leaseToken, "ROUTE_LOST", "Environment route disconnected"));

    EnvironmentOperation op = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.UNKNOWN, op.status());
    assertEquals("ROUTE_LOST", op.failureCode());
    assertEquals("Environment route disconnected", op.failureMessage());
    assertEquals(node, op.ownerNodeId());
    assertEquals(leaseToken, op.leaseToken());
    assertNotNull(op.finishedAt());

    // 单终态
    assertFalse(repository.markUnknown(opId, node, leaseToken, "ROUTE_LOST", "retry"));
  }

  /** 测试意图：cancelPending 仅允许取消从未发送的 PENDING 操作；RUNNING 或终态操作不可取消。 */
  @Test
  void cancelOnlyPending() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        new CreatePendingOperationCommand(
            opId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

    // 认领至 RUNNING
    repository.claimPending(node, 10);

    // RUNNING 操作不可被 cancelPending 取消
    assertFalse(repository.cancelPending(opId));

    // 回滚为 PENDING
    assertEquals(
        RescheduleOutcome.RESCHEDULED, repository.rescheduleUnsent(opId, node, leaseToken));

    // PENDING 操作允许被取消
    assertTrue(repository.cancelPending(opId));

    EnvironmentOperation op = repository.getById(opId);
    assertEquals(EnvironmentOperationStatus.CANCELLED, op.status());
    assertNotNull(op.finishedAt());
    assertNull(op.ownerNodeId());
    assertNull(op.leaseToken());
    assertNull(op.startedAt());

    // 终态操作不可再次取消
    assertFalse(repository.cancelPending(opId));
  }

  /** 测试意图：截止时间清扫：过期 PENDING 推进为 FAILED，过期 RUNNING 推进为 UNKNOWN，终态行与未过期行不受影响。 */
  @Test
  void deadlineSweeps() throws SQLException {
    UUID envId = createEnvironment();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    Instant futureDeadline = Instant.now().plus(10, ChronoUnit.MINUTES);

    // 1. 先创建 3 和 4 并由 node 认领至 RUNNING
    UUID expiredRunningId = uuid();
    repository.createPending(
        new CreatePendingOperationCommand(
            expiredRunningId,
            envId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            futureDeadline));

    UUID activeRunningId = uuid();
    repository.createPending(
        new CreatePendingOperationCommand(
            activeRunningId,
            envId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            futureDeadline));

    // 认领 3 和 4 为 RUNNING
    List<EnvironmentOperation> claimed = repository.claimPending(node, 10);
    assertEquals(2, claimed.size());

    // 2. 再创建 1 和 2（保持 PENDING 状态）
    UUID expiredPendingId = uuid();
    repository.createPending(
        new CreatePendingOperationCommand(
            expiredPendingId,
            envId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            futureDeadline));

    UUID activePendingId = uuid();
    repository.createPending(
        new CreatePendingOperationCommand(
            activePendingId,
            envId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            futureDeadline));

    // 将 1 和 3 的 deadline 改为过去（同时调整 created_at）
    jdbcTemplate.update(
        "update environment_operation set created_at = statement_timestamp() - interval '10"
            + " second', deadline_at = statement_timestamp() - interval '5 second' where id in (?, ?)",
        expiredPendingId,
        expiredRunningId);

    DeadlineSweepResult result = repository.sweepExpired();
    assertEquals(1, result.expiredPendingCount());
    assertEquals(1, result.expiredRunningCount());
    assertEquals(2, result.totalSwept());

    EnvironmentOperation op1 = repository.getById(expiredPendingId);
    assertEquals(EnvironmentOperationStatus.FAILED, op1.status());
    assertEquals(
        EnvironmentOperationFailureCodes.ENVIRONMENT_UNAVAILABLE_TIMEOUT, op1.failureCode());
    assertNull(op1.ownerNodeId());

    EnvironmentOperation op2 = repository.getById(activePendingId);
    assertEquals(EnvironmentOperationStatus.PENDING, op2.status());

    EnvironmentOperation op3 = repository.getById(expiredRunningId);
    assertEquals(EnvironmentOperationStatus.UNKNOWN, op3.status());
    assertEquals(EnvironmentOperationFailureCodes.RESULT_TIMEOUT, op3.failureCode());
    assertEquals(node, op3.ownerNodeId());
    assertEquals(leaseToken, op3.leaseToken());

    EnvironmentOperation op4 = repository.getById(activeRunningId);
    assertEquals(EnvironmentOperationStatus.RUNNING, op4.status());
  }

  /** 测试意图：终态行的不可变性，不可被后续的 claim、reschedule、markSucceeded、markFailed 等变更。 */
  @Test
  void terminalImmutability() throws SQLException {
    UUID envId = createEnvironment();
    UUID opId = uuid();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        new CreatePendingOperationCommand(
            opId,
            envId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

    repository.claimPending(node, 10);
    assertTrue(repository.markSucceeded(opId, node, leaseToken, "{}"));

    // 各种推进动作均应失败
    assertFalse(repository.markSucceeded(opId, node, leaseToken, "{}"));
    assertFalse(repository.markFailed(opId, node, leaseToken, "ERR", "msg"));
    assertFalse(repository.markUnknown(opId, node, leaseToken, "ERR", "msg"));
    assertEquals(RescheduleOutcome.STALE, repository.rescheduleUnsent(opId, node, leaseToken));
    assertFalse(repository.cancelPending(opId));

    // 终态保持 SUCCEEDED
    assertEquals(EnvironmentOperationStatus.SUCCEEDED, repository.getById(opId).status());
  }

  /** 测试意图：历史查询按 created_at DESC、id DESC 稳定排序，并遵循 limit 上限约束。 */
  @Test
  void deterministicBoundedList() throws SQLException {
    UUID envId = createEnvironment();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    List<UUID> createdIds = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID opId = uuid();
      createdIds.add(opId);
      repository.createPending(
          new CreatePendingOperationCommand(
              opId,
              envId,
              uuid(),
              EnvironmentOperationType.SKILL_REFRESH,
              0L,
              0L,
              "{}",
              "{}",
              deadlineAt));
    }

    List<EnvironmentOperation> list3 = repository.listByEnvironment(envId, 3);
    assertEquals(3, list3.size());
    // 逆序排列：最新创建的排在前面
    assertEquals(createdIds.get(4), list3.get(0).id());
    assertEquals(createdIds.get(3), list3.get(1).id());
    assertEquals(createdIds.get(2), list3.get(2).id());

    // 非法 limit 降级为默认
    List<EnvironmentOperation> listDefault = repository.listByEnvironment(envId, 0);
    assertEquals(5, listDefault.size());
  }

  /** 测试意图：调度器优雅停机辅助方法仅将本节点拥有的 RUNNING 操作更新为 UNKNOWN，不误伤其他节点或状态。 */
  @Test
  void shutdownHelper() throws SQLException {
    UUID envId1 = createEnvironment();
    UUID envId2 = createEnvironment();
    UUID envId3 = createEnvironment();

    UUID nodeA = uuid();
    UUID nodeB = uuid();

    insertConnection(envId1, nodeA, uuid(), "READY", 60);
    insertConnection(envId2, nodeA, uuid(), "READY", 60);
    insertConnection(envId3, nodeB, uuid(), "READY", 60);

    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    UUID opA1 = uuid();
    UUID opA2 = uuid();
    UUID opB = uuid();

    repository.createPending(
        new CreatePendingOperationCommand(
            opA1,
            envId1,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));
    repository.createPending(
        new CreatePendingOperationCommand(
            opA2,
            envId2,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));
    repository.createPending(
        new CreatePendingOperationCommand(
            opB,
            envId3,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

    repository.claimPending(nodeA, 10);
    repository.claimPending(nodeB, 10);

    int updated = repository.markRunningUnknownOnShutdown(nodeA);
    assertEquals(2, updated);

    assertEquals(EnvironmentOperationStatus.UNKNOWN, repository.getById(opA1).status());
    assertEquals(
        EnvironmentOperationFailureCodes.DISPATCHER_SHUTDOWN,
        repository.getById(opA1).failureCode());
    assertEquals(EnvironmentOperationStatus.UNKNOWN, repository.getById(opA2).status());
    assertEquals(EnvironmentOperationStatus.RUNNING, repository.getById(opB).status());
  }

  /** 测试意图：在进入 SQL 之前完成参数结构与 JSON 形状校验。 */
  @Test
  void validationBeforeSql() throws SQLException {
    UUID envId = createEnvironment();
    UUID sourceId = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);

    // 1. 非法的 arguments JSON（非对象）
    assertThrows(
        AiValidationException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    envId,
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    0L,
                    0L,
                    "[1, 2, 3]",
                    "{}",
                    deadlineAt)));

    // 2. 畸形 JSON 语法（确认错误信息不泄漏私有内容）
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                repository.createPending(
                    new CreatePendingOperationCommand(
                        uuid(),
                        envId,
                        sourceId,
                        EnvironmentOperationType.SKILL_REFRESH,
                        0L,
                        0L,
                        "not-json-secret",
                        "{}",
                        deadlineAt)));
    assertFalse(ex.getMessage().contains("not-json-secret"));

    // 3. 负数版本号
    assertThrows(
        AiValidationException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    envId,
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    -1L,
                    0L,
                    "{}",
                    "{}",
                    deadlineAt)));

    // 4. 不存在的 Environment 抛出 AiResourceNotFoundException
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    uuid(),
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    0L,
                    0L,
                    "{}",
                    "{}",
                    deadlineAt)));

    // 5. deadline 在明显过去的时刻
    assertThrows(
        AiValidationException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    envId,
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    0L,
                    0L,
                    "{}",
                    "{}",
                    Instant.now().minus(2, ChronoUnit.HOURS))));

    // 6. parameterSummary 非对象
    assertThrows(
        AiValidationException.class,
        () ->
            repository.createPending(
                new CreatePendingOperationCommand(
                    uuid(),
                    envId,
                    sourceId,
                    EnvironmentOperationType.SKILL_REFRESH,
                    0L,
                    0L,
                    "{}",
                    "\"not-an-object\"",
                    deadlineAt)));
  }

  /** 测试意图：覆盖各种边界输入（非法结果摘要、过长/未裁剪错误码、不存在的 ID 等）的防御性校验。 */
  @Test
  void additionalEdgeCasesAndValidation() throws SQLException {
    UUID envId = createEnvironment();
    UUID node = uuid();
    UUID leaseToken = uuid();
    insertConnection(envId, node, leaseToken, "READY", 60);

    UUID opId = uuid();
    Instant deadlineAt = Instant.now().plus(10, ChronoUnit.MINUTES);
    repository.createPending(
        new CreatePendingOperationCommand(
            opId,
            envId,
            uuid(),
            EnvironmentOperationType.SKILL_REFRESH,
            0L,
            0L,
            "{}",
            "{}",
            deadlineAt));

    repository.claimPending(node, 10);

    // markSucceeded 校验：非法 resultSummary JSON
    assertThrows(
        AiValidationException.class,
        () -> repository.markSucceeded(opId, node, leaseToken, "[1, 2, 3]"));
    assertThrows(
        AiValidationException.class,
        () -> repository.markSucceeded(opId, node, leaseToken, "not-json"));

    // markFailed 校验：空白、未裁剪或过长 failureCode
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, node, leaseToken, "  ", "msg"));
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, node, leaseToken, " un-trimmed ", "msg"));
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, node, leaseToken, "A".repeat(65), "msg"));
    assertThrows(
        AiValidationException.class,
        () -> repository.markFailed(opId, node, leaseToken, "CODE", "  "));

    // markUnknown 校验：非法 failureCode
    assertThrows(
        AiValidationException.class,
        () -> repository.markUnknown(opId, node, leaseToken, "", "msg"));

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

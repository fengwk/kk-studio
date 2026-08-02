package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * PostgreSQL 合约覆盖 {@link PostgresqlEnvironmentToolActivationQueue#activateOldestTool}：按
 * ToolInvocation 创建顺序推进队头、按队头状态分支处理、SKIP LOCKED 跳过被锁队头、读写参与 store 的同一张 activation 表但不污染通用 store 端口。
 */
class PostgresqlEnvironmentToolActivationQueueIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private PostgresqlExecutionActivationStore store;
  @Autowired private PostgresqlEnvironmentToolActivationQueue queue;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @Test
  void activateOldestToolUsesToolCreationOrderAndParksLaterSiblings() {
    Instant later = BASE.plus(Duration.ofMinutes(10));
    insertEnvironmentTools(
        "env-a",
        List.of(
            new ToolSeed(40L, 0, BASE),
            new ToolSeed(41L, 1, BASE.plusMillis(1)),
            new ToolSeed(42L, 2, BASE.plusMillis(2))));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 40L, "env-a", later));
    assertEquals(
        1,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 41L, "env-a", later.plus(Duration.ofMinutes(5))));
    assertEquals(
        1,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 42L, "env-a", later.minus(Duration.ofMinutes(5))));

    assertTrue(queue.activateOldestTool("env-a", BASE));
    List<ExecutionActivation> rows = store.findAll();
    assertEquals(BASE, row(rows, 40L).wakeAt());
    assertEquals(ActivationState.SCHEDULED, row(rows, 40L).activationState());
    assertEquals(later.plus(Duration.ofMinutes(5)), row(rows, 41L).wakeAt());
    assertEquals(ActivationState.PARKED, row(rows, 41L).activationState());
    assertEquals(later.minus(Duration.ofMinutes(5)), row(rows, 42L).wakeAt());
    assertEquals(ActivationState.PARKED, row(rows, 42L).activationState());
  }

  @Test
  void activateOldestToolKeepsQueuedHeadAndBlocksActiveHead() {
    insertEnvironmentTools(
        "env-a", List.of(new ToolSeed(50L, 0, BASE), new ToolSeed(51L, 1, BASE.plusMillis(1))));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 50L, "env-a", BASE));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 51L, "env-a", BASE));
    assertFalse(queue.activateOldestTool("env-a", BASE));
    assertEquals(ActivationState.PARKED, row(store.findAll(), 51L).activationState());

    insertEnvironmentTools(
        "env-b", List.of(new ToolSeed(52L, 0, BASE), new ToolSeed(53L, 1, BASE.plusMillis(1))));
    Instant runningAt = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 52L, "env-b", runningAt));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 53L, "env-b", BASE));
    markRunning(52L, BASE);

    assertFalse(queue.activateOldestTool("env-b", BASE));
    List<ExecutionActivation> rows = store.findAll();
    assertEquals(runningAt, row(rows, 52L).wakeAt());
    assertEquals(ActivationState.SCHEDULED, row(rows, 52L).activationState());
    assertEquals(ActivationState.PARKED, row(rows, 53L).activationState());
  }

  @Test
  void lockedFifoHeadDoesNotLetActivationSkipToLaterSibling() throws Exception {
    insertEnvironmentTools(
        "env-lock", List.of(new ToolSeed(90L, 0, BASE), new ToolSeed(91L, 1, BASE.plusMillis(1))));
    Instant parkedAt = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 90L, "env-lock", parkedAt));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 91L, "env-lock", parkedAt));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection lockConnection = newConnection();
        PreparedStatement lockStatement =
            lockConnection.prepareStatement(
                "select target_id from harness_execution_activation"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ? for update")) {
      lockConnection.setAutoCommit(false);
      lockStatement.setLong(1, 90L);
      try (var result = lockStatement.executeQuery()) {
        assertTrue(result.next(), "oldest activation must exist before locking");
      }

      Future<Boolean> activation =
          executor.submit(() -> queue.activateOldestTool("env-lock", BASE));
      assertFalse(
          activation.get(5, TimeUnit.SECONDS),
          "a locked FIFO head must be skipped, not replaced by a later sibling");
      assertEquals(ActivationState.PARKED, row(store.findAll(), 90L).activationState());
      assertEquals(ActivationState.PARKED, row(store.findAll(), 91L).activationState());

      lockConnection.commit();
    } finally {
      executor.shutdownNow();
    }

    assertTrue(queue.activateOldestTool("env-lock", BASE));
    List<ExecutionActivation> rows = store.findAll();
    assertEquals(ActivationState.SCHEDULED, row(rows, 90L).activationState());
    assertEquals(BASE, row(rows, 90L).wakeAt());
    assertEquals(ActivationState.PARKED, row(rows, 91L).activationState());
  }

  @Test
  void activateOldestToolNoOpWhenAlreadyEarlierOrAbsent() {
    Instant earlier = BASE;
    assertFalse(queue.activateOldestTool("env-a", earlier));

    insertEnvironmentTools("env-a", List.of(new ToolSeed(54L, 0, BASE)));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 54L, "env-a", earlier));
    // 记录已经在请求时间处于 SCHEDULED，因此这是正常的 no-op。
    assertFalse(queue.activateOldestTool("env-a", earlier));
  }

  private void insertEnvironmentTools(String environmentName, List<ToolSeed> seeds) {
    if (seeds.isEmpty()) {
      throw new IllegalArgumentException("seeds must not be empty");
    }
    long firstId = seeds.getFirst().invocationId();
    long sessionId = 1_000_000L + firstId * 10;
    long threadId = sessionId + 1;
    long rootEntryId = sessionId + 2;
    long assistantEntryId = sessionId + 3;
    try (Connection connection = newConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_session (id, title, created_at) values (?, 'fixture', ?)")) {
          statement.setLong(1, sessionId);
          statement.setObject(2, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, ?)")) {
          statement.setLong(1, rootEntryId);
          statement.setLong(2, sessionId);
          statement.setObject(3, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)")) {
          statement.setLong(1, assistantEntryId);
          statement.setLong(2, sessionId);
          statement.setLong(3, rootEntryId);
          statement.setObject(4, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_thread (id, head_entry_id, input_sequence, runnable,"
                    + " execution_epoch, created_at, updated_at) values (?, ?, 0, false, 0, ?, ?)")) {
          statement.setLong(1, threadId);
          statement.setLong(2, assistantEntryId);
          statement.setObject(3, offset(seeds.getFirst().createdAt()));
          statement.setObject(4, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        long modelInvocationId = seeds.getFirst().invocationId() + 1_000_000L;
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
                    + " execution_epoch, request, status, attempt, created_at)"
                    + " values (?, ?, ?, 0, '{}'::jsonb, 'QUEUED', 1, ?)")) {
          statement.setLong(1, modelInvocationId);
          statement.setLong(2, threadId);
          statement.setLong(3, rootEntryId);
          statement.setObject(4, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id,"
                    + " model_invocation_id, ordinal, tool_call_id, descriptor, arguments, environment_name,"
                    + " execution_epoch, status, attempt, created_at) values (?, ?, ?, ?, ?, ?,"
                    + " ?, '{}'::jsonb, '{}'::jsonb, ?, 0, 'QUEUED', 1, ?)")) {
          for (ToolSeed seed : seeds) {
            statement.setLong(1, seed.invocationId());
            statement.setLong(2, threadId);
            statement.setLong(3, sessionId);
            statement.setLong(4, assistantEntryId);
            statement.setLong(5, modelInvocationId);
            statement.setInt(6, seed.ordinal());
            statement.setString(7, "call-" + seed.invocationId());
            statement.setString(8, environmentName);
            statement.setObject(9, offset(seed.createdAt()));
            assertEquals(1, statement.executeUpdate());
          }
        }
        connection.commit();
      } catch (SQLException | RuntimeException error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private static void markRunning(long invocationId, Instant startedAt) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_tool_invocation set status = 'RUNNING', worker_token = ?,"
                    + " worker_until = ?, started_at = ?, deadline_at = ?, last_activity_at = ?"
                    + " where id = ?")) {
      statement.setString(1, "worker-" + invocationId);
      statement.setObject(2, offset(startedAt.plus(Duration.ofMinutes(5))));
      statement.setObject(3, offset(startedAt));
      statement.setObject(4, offset(startedAt.plus(Duration.ofMinutes(30))));
      statement.setObject(5, offset(startedAt));
      statement.setLong(6, invocationId);
      assertEquals(1, statement.executeUpdate());
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(value.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private static ExecutionActivation row(List<ExecutionActivation> rows, long id) {
    return rows.stream().filter(candidate -> candidate.targetId() == id).findFirst().orElseThrow();
  }

  private record ToolSeed(long invocationId, int ordinal, Instant createdAt) {}
}

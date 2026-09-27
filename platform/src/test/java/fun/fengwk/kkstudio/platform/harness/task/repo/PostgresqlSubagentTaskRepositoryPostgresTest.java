package fun.fengwk.kkstudio.platform.harness.task.repo;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTask;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskDraft;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskStatus;
import fun.fengwk.kkstudio.platform.harness.task.repo.impl.PostgresqlSubagentTaskRepository;
import fun.fengwk.kkstudio.platform.harness.task.repo.impl.mapper.SubagentTaskMapper;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 验证 {@code harness_subagent_task} 委派持久层的真实 PostgreSQL 行为与数据库约束。
 *
 * <p>测试意图：通过真实 Testcontainers 容器执行真实 SQL，严格锁定以下持久化契约：
 *
 * <ul>
 *   <li>insert + findByInvocationId 往返：时间由数据库 now() 填充、字段原样持久化、终态列初始为 null；
 *   <li>部分唯一索引 uk_harness_subagent_task_child_open：同一子 Thread 至多一行 OPEN，DELIVERED 后可重新打开；
 *   <li>settleResult 与 markDelivered 的 CAS 推进与终态保留；
 *   <li>updateReminderTurn 的 CAS 轮次条件更新；
 *   <li>listUndeliveredAfter 的 keyset 稳定排序、打破平局与游标推进；
 *   <li>countOpenByParentThreadId 与 countOpenByRootThreadId 的并发额度持久事实统计；
 *   <li>listUndeliveredParentThreadIdsInSubtree 递归 CTE 的展开、去重与状态过滤；
 *   <li>lockQuota 事务级 advisory 锁的互斥生效与提交释放；
 *   <li>底层兜底检查约束 ck_harness_subagent_task_state_shape 与 ck_harness_subagent_task_time_order。
 * </ul>
 */
class PostgresqlSubagentTaskRepositoryPostgresTest extends PostgresSpringTestSupport {

  @Autowired private PostgresqlSubagentTaskRepository repository;
  @Autowired private SubagentTaskMapper mapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void insertAndFindByInvocationIdRoundtrip() {
    UUID parentThreadId = UUID.randomUUID();
    UUID rootThreadId = UUID.randomUUID();
    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID sourceHeadEntryId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();

    insertHarnessThread(UUID.randomUUID(), parentThreadId);

    SubagentTaskDraft draft =
        new SubagentTaskDraft(
            invocationId,
            parentThreadId,
            rootThreadId,
            childSessionId,
            childThreadId,
            sourceHeadEntryId,
            "assistant",
            "perform task prompt",
            10,
            SubagentTaskStatus.OPEN,
            0L);

    assertTrue(repository.insert(draft));

    SubagentTask task = repository.findByInvocationId(invocationId);
    assertNotNull(task);
    assertEquals(invocationId, task.invocationId());
    assertEquals(parentThreadId, task.parentThreadId());
    assertEquals(rootThreadId, task.rootThreadId());
    assertEquals(childSessionId, task.childSessionId());
    assertEquals(childThreadId, task.childThreadId());
    assertEquals(sourceHeadEntryId, task.sourceHeadEntryId());
    assertEquals("assistant", task.agent());
    assertEquals("perform task prompt", task.prompt());
    assertEquals(10, task.maxTurns());
    assertEquals(SubagentTaskStatus.OPEN, task.status());
    assertEquals(0L, task.reminderTurn());

    assertNotNull(task.createdAt());
    assertNotNull(task.updatedAt());
    assertFalse(task.updatedAt().isBefore(task.createdAt()));

    assertNull(task.outcome());
    assertNull(task.report());
    assertNull(task.partialResult());
    assertNull(task.error());
    assertNull(task.settledAt());

    assertFalse(task.settled());
    assertFalse(task.delivered());
  }

  @Test
  void childOpenUniqueConstraintEnforcedAndDeliveredPermitsReopening() throws SQLException {
    UUID parentThreadId = UUID.randomUUID();
    UUID rootThreadId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), parentThreadId);

    UUID invId1 = UUID.randomUUID();
    SubagentTaskDraft draft1 =
        new SubagentTaskDraft(
            invId1,
            parentThreadId,
            rootThreadId,
            UUID.randomUUID(),
            childThreadId,
            UUID.randomUUID(),
            "agent-1",
            "prompt-1",
            null,
            SubagentTaskStatus.OPEN,
            0L);
    assertTrue(repository.insert(draft1));

    UUID invId2 = UUID.randomUUID();
    SubagentTaskDraft draft2 =
        new SubagentTaskDraft(
            invId2,
            parentThreadId,
            rootThreadId,
            UUID.randomUUID(),
            childThreadId,
            UUID.randomUUID(),
            "agent-2",
            "prompt-2",
            null,
            SubagentTaskStatus.OPEN,
            0L);

    DataIntegrityViolationException ex =
        assertThrows(DataIntegrityViolationException.class, () -> repository.insert(draft2));
    PSQLException rootCause = findRootCause(ex, PSQLException.class);
    assertNotNull(rootCause);
    assertTrue(rootCause.getSQLState().startsWith("23"));
    assertEquals(
        "uk_harness_subagent_task_child_open",
        rootCause.getServerErrorMessage() != null
            ? rootCause.getServerErrorMessage().getConstraint()
            : null);

    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "uk_harness_subagent_task_child_open",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    """
                    insert into harness_subagent_task (
                        invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
                        source_head_entry_id, agent, prompt, status, reminder_turn, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'agent', 'prompt', 'OPEN', 0, now(), now())
                    """)) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, parentThreadId);
              ps.setObject(3, rootThreadId);
              ps.setObject(4, UUID.randomUUID());
              ps.setObject(5, childThreadId);
              ps.setObject(6, UUID.randomUUID());
              ps.executeUpdate();
            }
          });
    }

    assertTrue(repository.settleResult(invId1, Outcome.COMPLETED, "done", null, null));
    assertTrue(repository.markDelivered(invId1));
    assertEquals(SubagentTaskStatus.DELIVERED, repository.findByInvocationId(invId1).status());

    assertTrue(repository.insert(draft2));
    assertEquals(SubagentTaskStatus.OPEN, repository.findByInvocationId(invId2).status());
  }

  @Test
  void settleResultCasTransitionsOpenToSettledAndRejectsRepeatedOrDelivered() {
    UUID parentThreadId = UUID.randomUUID();
    UUID invId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), parentThreadId);

    SubagentTaskDraft draft =
        new SubagentTaskDraft(
            invId,
            parentThreadId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L);
    assertTrue(repository.insert(draft));

    assertTrue(repository.settleResult(invId, Outcome.COMPLETED, "report 1", null, null));
    SubagentTask settled = repository.findByInvocationId(invId);
    assertEquals(SubagentTaskStatus.SETTLED, settled.status());
    assertEquals(Outcome.COMPLETED, settled.outcome());
    assertEquals("report 1", settled.report());
    assertNull(settled.partialResult());
    assertNull(settled.error());
    assertNotNull(settled.settledAt());
    assertTrue(settled.settled());
    assertFalse(settled.delivered());

    assertFalse(repository.settleResult(invId, Outcome.ERROR, "report 2", "partial", "error"));
    SubagentTask preserved = repository.findByInvocationId(invId);
    assertEquals(SubagentTaskStatus.SETTLED, preserved.status());
    assertEquals(Outcome.COMPLETED, preserved.outcome());
    assertEquals("report 1", preserved.report());
    assertNull(preserved.error());

    assertTrue(repository.markDelivered(invId));
    assertEquals(SubagentTaskStatus.DELIVERED, repository.findByInvocationId(invId).status());

    assertFalse(repository.settleResult(invId, Outcome.CANCELLED, null, null, "cancelled"));
    SubagentTask unchanged = repository.findByInvocationId(invId);
    assertEquals(SubagentTaskStatus.DELIVERED, unchanged.status());
    assertEquals(Outcome.COMPLETED, unchanged.outcome());
    assertEquals("report 1", unchanged.report());
  }

  @Test
  void markDeliveredCasTransitionsSettledToDeliveredAndRejectsRepeatedOrOpen() {
    UUID parentThreadId = UUID.randomUUID();
    UUID invId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), parentThreadId);

    SubagentTaskDraft draft =
        new SubagentTaskDraft(
            invId,
            parentThreadId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L);
    assertTrue(repository.insert(draft));

    assertFalse(repository.markDelivered(invId));
    assertEquals(SubagentTaskStatus.OPEN, repository.findByInvocationId(invId).status());

    assertTrue(repository.settleResult(invId, Outcome.COMPLETED, "done", null, null));
    assertEquals(SubagentTaskStatus.SETTLED, repository.findByInvocationId(invId).status());

    assertTrue(repository.markDelivered(invId));
    SubagentTask delivered = repository.findByInvocationId(invId);
    assertEquals(SubagentTaskStatus.DELIVERED, delivered.status());
    assertTrue(delivered.delivered());

    assertFalse(repository.markDelivered(invId));
    assertEquals(SubagentTaskStatus.DELIVERED, repository.findByInvocationId(invId).status());
  }

  @Test
  void updateReminderTurnCasRequiresOpenStatusAndMatchingPreviousTurn() {
    UUID parentThreadId = UUID.randomUUID();
    UUID invId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), parentThreadId);

    SubagentTaskDraft draft =
        new SubagentTaskDraft(
            invId,
            parentThreadId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L);
    assertTrue(repository.insert(draft));

    assertTrue(repository.updateReminderTurn(invId, 0L, 5L));
    assertEquals(5L, repository.findByInvocationId(invId).reminderTurn());

    assertFalse(repository.updateReminderTurn(invId, 0L, 10L));
    assertEquals(5L, repository.findByInvocationId(invId).reminderTurn());

    assertFalse(repository.updateReminderTurn(invId, 99L, 10L));
    assertEquals(5L, repository.findByInvocationId(invId).reminderTurn());

    assertTrue(repository.settleResult(invId, Outcome.COMPLETED, "done", null, null));
    assertFalse(repository.updateReminderTurn(invId, 5L, 10L));
    assertEquals(5L, repository.findByInvocationId(invId).reminderTurn());
  }

  @Test
  void listUndeliveredAfterKeysetPaginatesConsistentlyAndExcludesDelivered() {
    UUID parentThreadId = UUID.randomUUID();
    UUID rootThreadId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), parentThreadId);

    UUID inv1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID inv2A = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID inv2B = UUID.fromString("00000000-0000-0000-0000-000000000003");
    UUID inv4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    UUID invDelivered = UUID.fromString("00000000-0000-0000-0000-000000000005");

    repository.insert(createDraft(inv1, parentThreadId, rootThreadId));
    jdbcTemplate.update(
        "update harness_subagent_task set created_at = created_at - interval '20 seconds',"
            + " updated_at = updated_at - interval '20 seconds' where invocation_id = ?",
        inv1);

    newTransaction()
        .executeWithoutResult(
            status -> {
              repository.insert(createDraft(inv2A, parentThreadId, rootThreadId));
              repository.insert(createDraft(inv2B, parentThreadId, rootThreadId));
            });

    SubagentTask task2A = repository.findByInvocationId(inv2A);
    SubagentTask task2B = repository.findByInvocationId(inv2B);
    assertNotNull(task2A);
    assertNotNull(task2B);
    assertEquals(
        task2A.createdAt(),
        task2B.createdAt(),
        "tasks inserted in the same transaction must share identical created_at");

    repository.settleResult(inv2B, Outcome.COMPLETED, "report-2B", null, null);

    repository.insert(createDraft(inv4, parentThreadId, rootThreadId));
    jdbcTemplate.update(
        "update harness_subagent_task set created_at = created_at + interval '20 seconds',"
            + " updated_at = updated_at + interval '20 seconds' where invocation_id = ?",
        inv4);

    repository.insert(createDraft(invDelivered, parentThreadId, rootThreadId));
    repository.settleResult(invDelivered, Outcome.COMPLETED, "done", null, null);
    repository.markDelivered(invDelivered);

    List<SubagentTask> page1 = repository.listUndeliveredAfter(null, null, 2);
    assertEquals(2, page1.size());
    assertEquals(inv1, page1.get(0).invocationId());
    assertEquals(inv2A, page1.get(1).invocationId());

    Instant cursorCreatedAt = page1.get(1).createdAt();
    UUID cursorInvocationId = page1.get(1).invocationId();
    List<SubagentTask> page2 =
        repository.listUndeliveredAfter(cursorCreatedAt, cursorInvocationId, 2);
    assertEquals(2, page2.size());
    assertEquals(
        inv2B,
        page2.get(0).invocationId(),
        "tie-breaker must advance to inv2B on identical created_at without repeating inv2A");
    assertEquals(inv4, page2.get(1).invocationId());

    Instant cursor2CreatedAt = page2.get(1).createdAt();
    UUID cursor2InvocationId = page2.get(1).invocationId();
    List<SubagentTask> page3 =
        repository.listUndeliveredAfter(cursor2CreatedAt, cursor2InvocationId, 2);
    assertTrue(page3.isEmpty(), "no further undelivered tasks should exist");

    List<SubagentTask> allUndelivered = repository.listUndeliveredAfter(null, null, 100);
    assertEquals(4, allUndelivered.size());
    List<UUID> allIds = allUndelivered.stream().map(SubagentTask::invocationId).toList();
    assertEquals(List.of(inv1, inv2A, inv2B, inv4), allIds);
    assertFalse(
        allIds.contains(invDelivered), "DELIVERED task must not appear in undelivered scan");
  }

  @Test
  void countOpenByParentAndRootThreadIdFiltersOnlyOpenStatus() {
    UUID rootId = UUID.randomUUID();
    UUID parent1 = UUID.randomUUID();
    UUID parent2 = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), parent1);
    insertHarnessThread(UUID.randomUUID(), parent2);

    UUID inv1 = UUID.randomUUID();
    UUID inv2 = UUID.randomUUID();
    UUID inv3 = UUID.randomUUID();
    UUID inv4 = UUID.randomUUID();
    UUID inv5 = UUID.randomUUID();

    repository.insert(createDraft(inv1, parent1, rootId));
    repository.insert(createDraft(inv2, parent1, rootId));
    repository.insert(createDraft(inv3, parent1, rootId));
    repository.insert(createDraft(inv4, parent1, rootId));

    repository.settleResult(inv3, Outcome.COMPLETED, "done-3", null, null);
    repository.settleResult(inv4, Outcome.COMPLETED, "done-4", null, null);
    repository.markDelivered(inv4);

    assertEquals(2, repository.countOpenByParentThreadId(parent1));
    assertEquals(2, repository.countOpenByRootThreadId(rootId));

    repository.insert(createDraft(inv5, parent2, rootId));
    assertEquals(2, repository.countOpenByParentThreadId(parent1));
    assertEquals(1, repository.countOpenByParentThreadId(parent2));
    assertEquals(3, repository.countOpenByRootThreadId(rootId));

    repository.settleResult(inv1, Outcome.COMPLETED, "done-1", null, null);
    assertEquals(1, repository.countOpenByParentThreadId(parent1));
    assertEquals(2, repository.countOpenByRootThreadId(rootId));
  }

  @Test
  void hasOpenInSubtreeTraversesHierarchyAndIgnoresNonOpenStatuses() {
    // 测试意图：正在执行（OPEN）的存在性判定必须沿委派记录递归展开（child_thread_id 是下一层的父），且只认 OPEN：
    // 已结清待交付与已交付都不算"还在跑"，否则停止/退出判定会永远等一棵已经没在执行的子树。
    UUID rootThreadId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID grandchildThreadId = UUID.randomUUID();
    UUID greatGrandchildThreadId = UUID.randomUUID();
    UUID outsideThreadId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), rootThreadId);
    insertHarnessThread(UUID.randomUUID(), childThreadId);
    insertHarnessThread(UUID.randomUUID(), grandchildThreadId);
    insertHarnessThread(UUID.randomUUID(), greatGrandchildThreadId);
    insertHarnessThread(UUID.randomUUID(), outsideThreadId);

    // root 名下 OPEN；child 名下 OPEN；grandchild 名下 SETTLED 未交付；树外 OPEN。
    UUID openUnderRoot = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            openUnderRoot,
            rootThreadId,
            rootThreadId,
            UUID.randomUUID(),
            childThreadId,
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    UUID openUnderChild = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            openUnderChild,
            childThreadId,
            rootThreadId,
            UUID.randomUUID(),
            grandchildThreadId,
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    UUID settledUnderGrandchild = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            settledUnderGrandchild,
            grandchildThreadId,
            rootThreadId,
            UUID.randomUUID(),
            greatGrandchildThreadId,
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    repository.settleResult(settledUnderGrandchild, Outcome.COMPLETED, "done", null, null);
    UUID deliveredOutside = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            deliveredOutside,
            outsideThreadId,
            outsideThreadId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    repository.settleResult(deliveredOutside, Outcome.COMPLETED, "done", null, null);
    repository.markDelivered(deliveredOutside);

    assertTrue(repository.hasOpenInSubtree(rootThreadId));
    assertTrue(repository.hasOpenInSubtree(childThreadId));
    // 该子树里只剩一条 SETTLED 未交付（grandchild → greatGrandchild），没有 OPEN。
    assertFalse(repository.hasOpenInSubtree(grandchildThreadId));
    // 已交付的记录不属于"还在跑"，树外记录也不影响判定。
    assertFalse(repository.hasOpenInSubtree(outsideThreadId));

    // 两条 OPEN 都结清并交付后，子树不再有正在执行的委派（SETTLED 那条仍未交付，但它是另一层的事实）。
    repository.settleResult(openUnderRoot, Outcome.COMPLETED, "done", null, null);
    repository.markDelivered(openUnderRoot);
    repository.settleResult(openUnderChild, Outcome.COMPLETED, "done", null, null);
    repository.markDelivered(openUnderChild);

    assertFalse(repository.hasOpenInSubtree(rootThreadId));
  }

  @Test
  void listSettledParentThreadIdsInSubtreeReturnsOnlyUndeliveredSettledParents() {
    // 测试意图：待交付聚合只返回"仍未交付终态"的记录的父 Thread（去重），并沿子树递归：OPEN 记录的父不算待交付，
    // 已交付记录不出现，树外记录不影响结果。
    UUID rootThreadId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID grandchildThreadId = UUID.randomUUID();
    UUID outsideThreadId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), rootThreadId);
    insertHarnessThread(UUID.randomUUID(), childThreadId);
    insertHarnessThread(UUID.randomUUID(), grandchildThreadId);
    insertHarnessThread(UUID.randomUUID(), outsideThreadId);

    UUID openUnderRoot = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            openUnderRoot,
            rootThreadId,
            rootThreadId,
            UUID.randomUUID(),
            childThreadId,
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    UUID settledUnderRoot = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            settledUnderRoot,
            rootThreadId,
            rootThreadId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    repository.settleResult(settledUnderRoot, Outcome.COMPLETED, "done", null, null);
    UUID settledUnderChild = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            settledUnderChild,
            childThreadId,
            rootThreadId,
            UUID.randomUUID(),
            grandchildThreadId,
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    repository.settleResult(settledUnderChild, Outcome.COMPLETED, "done", null, null);
    UUID deliveredUnderGrandchild = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            deliveredUnderGrandchild,
            grandchildThreadId,
            rootThreadId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    repository.settleResult(deliveredUnderGrandchild, Outcome.COMPLETED, "done", null, null);
    repository.markDelivered(deliveredUnderGrandchild);
    UUID settledOutside = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            settledOutside,
            outsideThreadId,
            outsideThreadId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    repository.settleResult(settledOutside, Outcome.COMPLETED, "done", null, null);

    List<UUID> settledParents = repository.listSettledParentThreadIdsInSubtree(rootThreadId);
    assertEquals(2, settledParents.size());
    assertTrue(settledParents.contains(rootThreadId));
    assertTrue(settledParents.contains(childThreadId));
    assertFalse(settledParents.contains(grandchildThreadId));
    assertFalse(settledParents.contains(outsideThreadId));

    repository.markDelivered(settledUnderRoot);
    repository.markDelivered(settledUnderChild);
    assertEquals(List.of(), repository.listSettledParentThreadIdsInSubtree(rootThreadId));
  }

  @Test
  void hasUndeliveredByChildThreadIdDistinguishesDeliveredFromPending() {
    // 测试意图：继续委派（resume）只允许在上一次执行已完整结清并交付之后：OPEN 与 SETTLED 都算未交付，
    // 只有 DELIVERED 才允许在同一子 Thread 上开始新执行（否则旧结果会与新 prompt 串扰）。
    UUID rootThreadId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID otherChildThreadId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), rootThreadId);
    insertHarnessThread(UUID.randomUUID(), childThreadId);
    insertHarnessThread(UUID.randomUUID(), otherChildThreadId);

    assertFalse(repository.hasUndeliveredByChildThreadId(childThreadId));

    UUID open = UUID.randomUUID();
    repository.insert(
        new SubagentTaskDraft(
            open,
            rootThreadId,
            rootThreadId,
            UUID.randomUUID(),
            childThreadId,
            UUID.randomUUID(),
            "agent",
            "prompt",
            null,
            SubagentTaskStatus.OPEN,
            0L));
    assertTrue(repository.hasUndeliveredByChildThreadId(childThreadId));
    assertFalse(repository.hasUndeliveredByChildThreadId(otherChildThreadId));

    repository.settleResult(open, Outcome.COMPLETED, "done", null, null);
    assertTrue(repository.hasUndeliveredByChildThreadId(childThreadId));

    repository.markDelivered(open);
    assertFalse(repository.hasUndeliveredByChildThreadId(childThreadId));
  }

  @Test
  void lockQuotaAppliesAdvisoryLockInTransactionAndReleasesOnCommit() throws SQLException {
    UUID rootId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();

    String rootKey = "kk-studio/harness/subagent-task-quota/" + rootId;
    String parentKey = "kk-studio/harness/subagent-task-quota/" + parentId;

    assertDoesNotThrow(
        () -> {
          Object lockResult = mapper.lockQuotaKey(rootKey);
          assertNotNull(
              lockResult, "void return from pg_advisory_xact_lock maps to Object without error");
        });

    try (Connection otherConn = newConnection()) {
      newTransaction()
          .executeWithoutResult(
              status -> {
                repository.lockQuota(rootId, parentId);
                try {
                  assertFalse(
                      tryAdvisoryXactLock(otherConn, rootKey),
                      "root advisory lock must be held by current transaction");
                  assertFalse(
                      tryAdvisoryXactLock(otherConn, parentKey),
                      "parent advisory lock must be held by current transaction");
                } catch (SQLException e) {
                  throw new RuntimeException(e);
                }
              });

      otherConn.setAutoCommit(false);
      try {
        assertTrue(
            tryAdvisoryXactLock(otherConn, rootKey),
            "root advisory lock must be acquired after holding transaction commits");
        assertTrue(
            tryAdvisoryXactLock(otherConn, parentKey),
            "parent advisory lock must be acquired after holding transaction commits");
      } finally {
        otherConn.commit();
      }
    }
  }

  @Test
  void databaseConstraintsRejectInvalidStateShapeAndTimeOrder() throws SQLException {
    UUID parentThreadId = UUID.randomUUID();
    UUID rootThreadId = UUID.randomUUID();
    insertHarnessThread(UUID.randomUUID(), parentThreadId);

    try (Connection conn = newConnection()) {
      assertConstraintViolation(
          conn,
          "ck_harness_subagent_task_state_shape",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    """
                    insert into harness_subagent_task (
                        invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
                        source_head_entry_id, agent, prompt, status, outcome, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'agent', 'prompt', 'OPEN', 'COMPLETED', now(), now())
                    """)) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, parentThreadId);
              ps.setObject(3, rootThreadId);
              ps.setObject(4, UUID.randomUUID());
              ps.setObject(5, UUID.randomUUID());
              ps.setObject(6, UUID.randomUUID());
              ps.executeUpdate();
            }
          });

      assertConstraintViolation(
          conn,
          "ck_harness_subagent_task_state_shape",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    """
                    insert into harness_subagent_task (
                        invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
                        source_head_entry_id, agent, prompt, status, settled_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'agent', 'prompt', 'OPEN', now(), now(), now())
                    """)) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, parentThreadId);
              ps.setObject(3, rootThreadId);
              ps.setObject(4, UUID.randomUUID());
              ps.setObject(5, UUID.randomUUID());
              ps.setObject(6, UUID.randomUUID());
              ps.executeUpdate();
            }
          });

      assertConstraintViolation(
          conn,
          "ck_harness_subagent_task_state_shape",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    """
                    insert into harness_subagent_task (
                        invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
                        source_head_entry_id, agent, prompt, status, outcome, settled_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'agent', 'prompt', 'SETTLED', null, now(), now(), now())
                    """)) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, parentThreadId);
              ps.setObject(3, rootThreadId);
              ps.setObject(4, UUID.randomUUID());
              ps.setObject(5, UUID.randomUUID());
              ps.setObject(6, UUID.randomUUID());
              ps.executeUpdate();
            }
          });

      assertConstraintViolation(
          conn,
          "ck_harness_subagent_task_state_shape",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    """
                    insert into harness_subagent_task (
                        invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
                        source_head_entry_id, agent, prompt, status, outcome, settled_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'agent', 'prompt', 'SETTLED', 'COMPLETED', null, now(), now())
                    """)) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, parentThreadId);
              ps.setObject(3, rootThreadId);
              ps.setObject(4, UUID.randomUUID());
              ps.setObject(5, UUID.randomUUID());
              ps.setObject(6, UUID.randomUUID());
              ps.executeUpdate();
            }
          });

      assertConstraintViolation(
          conn,
          "ck_harness_subagent_task_time_order",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    """
                    insert into harness_subagent_task (
                        invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
                        source_head_entry_id, agent, prompt, status, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'agent', 'prompt', 'OPEN', now(), now() - interval '1 hour')
                    """)) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, parentThreadId);
              ps.setObject(3, rootThreadId);
              ps.setObject(4, UUID.randomUUID());
              ps.setObject(5, UUID.randomUUID());
              ps.setObject(6, UUID.randomUUID());
              ps.executeUpdate();
            }
          });

      assertConstraintViolation(
          conn,
          "ck_harness_subagent_task_time_order",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    """
                    insert into harness_subagent_task (
                        invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
                        source_head_entry_id, agent, prompt, status, outcome, settled_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'agent', 'prompt', 'SETTLED', 'COMPLETED', now() - interval '1 hour', now(), now())
                    """)) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, parentThreadId);
              ps.setObject(3, rootThreadId);
              ps.setObject(4, UUID.randomUUID());
              ps.setObject(5, UUID.randomUUID());
              ps.setObject(6, UUID.randomUUID());
              ps.executeUpdate();
            }
          });
    }
  }

  @FunctionalInterface
  private interface SqlRunnable {
    void run() throws SQLException;
  }

  private static void assertConstraintViolation(
      Connection conn, String expectedConstraint, SqlRunnable command) throws SQLException {
    boolean previousAutoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    SQLException thrown = null;
    try {
      command.run();
      conn.commit();
    } catch (SQLException e) {
      thrown = e;
    } catch (RuntimeException | Error e) {
      conn.rollback();
      throw e;
    } finally {
      if (thrown != null) {
        conn.rollback();
      }
      if (previousAutoCommit) {
        conn.setAutoCommit(true);
      }
    }
    if (thrown == null) {
      throw new AssertionError("expected constraint " + expectedConstraint + " to reject the SQL");
    }
    String sqlState = thrown.getSQLState();
    if (sqlState == null || !sqlState.startsWith("23")) {
      throw new AssertionError(
          expectedConstraint
              + " must fail with constraint violation SQLState (class 23*), but got SQLState="
              + sqlState
              + " message="
              + thrown.getMessage());
    }
    String actualConstraint =
        thrown instanceof PSQLException postgresError
                && postgresError.getServerErrorMessage() != null
            ? postgresError.getServerErrorMessage().getConstraint()
            : null;
    if (!expectedConstraint.equals(actualConstraint)) {
      throw new AssertionError(
          "expected constraint "
              + expectedConstraint
              + " to reject the SQL, but PostgreSQL reported constraint="
              + actualConstraint
              + " message="
              + thrown.getMessage());
    }
  }

  private void insertHarnessThread(UUID sessionId, UUID threadId) {
    jdbcTemplate.update(
        "insert into harness_session (id, name, created_at) values (?, ?, now())",
        sessionId,
        "session-" + sessionId);
    UUID rootEntryId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, now())",
        rootEntryId,
        sessionId);
    jdbcTemplate.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
            + " name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, true, 1, 0, now(), now())",
        threadId,
        sessionId,
        rootEntryId,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "thread-" + threadId);
  }

  private SubagentTaskDraft createDraft(UUID invocationId, UUID parentThreadId, UUID rootThreadId) {
    return new SubagentTaskDraft(
        invocationId,
        parentThreadId,
        rootThreadId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "worker",
        "task prompt",
        5,
        SubagentTaskStatus.OPEN,
        0L);
  }

  private boolean tryAdvisoryXactLock(Connection conn, String key) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("select pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
      ps.setString(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getBoolean(1);
      }
    }
  }

  private TransactionTemplate newTransaction() {
    return new TransactionTemplate(transactionManager);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T findRootCause(Throwable throwable, Class<T> targetClass) {
    Throwable current = throwable;
    while (current != null) {
      if (targetClass.isInstance(current)) {
        return (T) current;
      }
      current = current.getCause();
    }
    return null;
  }
}

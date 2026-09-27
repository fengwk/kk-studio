package fun.fengwk.kkstudio.platform.harness.task.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTask;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskDraft;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskStatus;
import fun.fengwk.kkstudio.platform.harness.task.repo.impl.PostgresqlSubagentTaskRepository;
import fun.fengwk.kkstudio.platform.harness.task.repo.impl.mapper.SubagentTaskMapper;
import fun.fengwk.kkstudio.platform.harness.task.repo.impl.model.SubagentTaskDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 验证 {@code harness_subagent_task} 端口实现的映射与写契约。
 *
 * <p>测试意图：锁定"写入路径不绑定 Java 时间类型（时间由数据库 {@code now()} 填充）"与"额度 advisory 锁按固定顺序（根 → 父）申请"这两条并发/时序
 * 前提；真实 SQL、时间戳类型与锁行为需要 PostgreSQL 集成测试覆盖，本测试不声称覆盖这些。
 */
class PostgresqlSubagentTaskRepositoryTest {

  private static final UUID INVOCATION_ID = UUID.randomUUID();
  private static final UUID PARENT_THREAD_ID = UUID.randomUUID();
  private static final UUID ROOT_THREAD_ID = UUID.randomUUID();

  private final SubagentTaskMapper mapper = mock(SubagentTaskMapper.class);
  private final PostgresqlSubagentTaskRepository repository =
      new PostgresqlSubagentTaskRepository(mapper);

  @Test
  void insertMapsDraftAndLeavesTimestampsToDatabase() {
    when(mapper.insert(any())).thenReturn(1);

    boolean inserted = repository.insert(draft());

    assertTrue(inserted);
    ArgumentCaptor<SubagentTaskDO> row = ArgumentCaptor.forClass(SubagentTaskDO.class);
    verify(mapper).insert(row.capture());
    assertEquals(INVOCATION_ID, row.getValue().getInvocationId());
    assertEquals(PARENT_THREAD_ID, row.getValue().getParentThreadId());
    assertEquals(ROOT_THREAD_ID, row.getValue().getRootThreadId());
    assertEquals("alpha", row.getValue().getAgent());
    assertEquals("do the work", row.getValue().getPrompt());
    assertEquals(7, row.getValue().getMaxTurns().intValue());
    assertEquals(SubagentTaskStatus.OPEN, row.getValue().getStatus());
    assertEquals(0L, row.getValue().getReminderTurn());
    // 时间戳必须留空：写入 SQL 使用 now()，避免宿主时钟与数据库时钟混用破坏 updated_at >= created_at。
    assertNull(row.getValue().getCreatedAt());
    assertNull(row.getValue().getUpdatedAt());
  }

  @Test
  void insertReportsWhetherExactlyOneRowWasWritten() {
    when(mapper.insert(any())).thenReturn(0);

    assertFalse(repository.insert(draft()));
  }

  @Test
  void lockQuotaLocksRootBeforeParent() {
    repository.lockQuota(ROOT_THREAD_ID, PARENT_THREAD_ID);

    // 固定顺序避免并发接受互相等待；key 带命名空间隔离，避免与其它 advisory 锁互相阻塞。
    InOrder order = inOrder(mapper);
    order.verify(mapper).lockQuotaKey("kk-studio/harness/subagent-task-quota/" + ROOT_THREAD_ID);
    order.verify(mapper).lockQuotaKey("kk-studio/harness/subagent-task-quota/" + PARENT_THREAD_ID);
  }

  @Test
  void findByInvocationIdMapsRowAndMissingRow() {
    when(mapper.findByInvocationId(INVOCATION_ID)).thenReturn(row());

    SubagentTask task = repository.findByInvocationId(INVOCATION_ID);

    assertEquals(INVOCATION_ID, task.invocationId());
    assertEquals(SubagentTaskStatus.OPEN, task.status());
    assertEquals(7, task.maxTurns().intValue());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), task.createdAt());
    assertEquals(3L, task.reminderTurn());
    assertFalse(task.delivered());

    when(mapper.findByInvocationId(INVOCATION_ID)).thenReturn(null);
    assertNull(repository.findByInvocationId(INVOCATION_ID));
  }

  @Test
  void listUndeliveredAfterForwardsCursorAndMapsRows() {
    when(mapper.listUndeliveredAfter(null, null, 10)).thenReturn(null);
    assertEquals(List.of(), repository.listUndeliveredAfter(null, null, 10));

    when(mapper.listUndeliveredAfter(null, null, 10)).thenReturn(List.of());
    assertEquals(List.of(), repository.listUndeliveredAfter(null, null, 10));

    // 公平扫描游标必须原样下传：批次轮转依赖 (created_at, invocation_id) keyset，不能退化为每轮都从头取。
    Instant cursorCreatedAt = Instant.parse("2026-01-01T00:00:05Z");
    UUID cursorInvocationId = UUID.randomUUID();
    when(mapper.listUndeliveredAfter(cursorCreatedAt, cursorInvocationId, 10))
        .thenReturn(List.of(row()));
    List<SubagentTask> tasks =
        repository.listUndeliveredAfter(cursorCreatedAt, cursorInvocationId, 10);
    assertEquals(1, tasks.size());
    assertEquals(INVOCATION_ID, tasks.get(0).invocationId());
  }

  @Test
  void settleResultWritesPersistedTerminalFactsAndReportsCasOutcome() {
    when(mapper.settleResult(INVOCATION_ID, Outcome.COMPLETED.name(), "report", null, null))
        .thenReturn(1);

    assertTrue(repository.settleResult(INVOCATION_ID, Outcome.COMPLETED, "report", null, null));

    // CAS 未命中（已被并发结算）必须返回 false，调用方据此回滚或跳过。
    when(mapper.settleResult(INVOCATION_ID, Outcome.ERROR.name(), null, "partial", "boom"))
        .thenReturn(0);
    assertFalse(repository.settleResult(INVOCATION_ID, Outcome.ERROR, null, "partial", "boom"));
  }

  @Test
  void reminderTurnNullIsReadAsZero() {
    SubagentTaskDO row = row();
    row.setReminderTurn(null);
    when(mapper.findByInvocationId(INVOCATION_ID)).thenReturn(row);

    assertEquals(0L, repository.findByInvocationId(INVOCATION_ID).reminderTurn());
  }

  @Test
  void countersDelegateToMapper() {
    when(mapper.countOpenByParentThreadId(PARENT_THREAD_ID)).thenReturn(2);
    when(mapper.countOpenByRootThreadId(ROOT_THREAD_ID)).thenReturn(5);

    assertEquals(2, repository.countOpenByParentThreadId(PARENT_THREAD_ID));
    assertEquals(5, repository.countOpenByRootThreadId(ROOT_THREAD_ID));
  }

  @Test
  void markDeliveredAndReminderTurnReportCasOutcome() {
    when(mapper.markDelivered(INVOCATION_ID)).thenReturn(1);
    assertTrue(repository.markDelivered(INVOCATION_ID));

    // CAS 未命中（已被并发交付）必须返回 false，调用方据此回滚或跳过。
    when(mapper.markDelivered(INVOCATION_ID)).thenReturn(0);
    assertFalse(repository.markDelivered(INVOCATION_ID));

    when(mapper.updateReminderTurn(INVOCATION_ID, 0L, 5L)).thenReturn(1);
    assertTrue(repository.updateReminderTurn(INVOCATION_ID, 0L, 5L));

    when(mapper.updateReminderTurn(INVOCATION_ID, 0L, 5L)).thenReturn(0);
    assertFalse(repository.updateReminderTurn(INVOCATION_ID, 0L, 5L));
  }

  @Test
  void subtreeQueriesAndUndeliveredGuardDelegateToMapper() {
    // 测试意图：活动聚合与"继续委派前必须已交付"的判定都直接建立在映射器查询上，这里锁定端口到映射器的委托
    // （真实递归 CTE 的展开/去重/状态过滤由 PostgreSQL 集成测试覆盖）。
    when(mapper.hasOpenInSubtree(ROOT_THREAD_ID)).thenReturn(true);
    when(mapper.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID))
        .thenReturn(List.of(PARENT_THREAD_ID));
    when(mapper.hasUndeliveredByChildThreadId(PARENT_THREAD_ID)).thenReturn(true);

    assertTrue(repository.hasOpenInSubtree(ROOT_THREAD_ID));
    assertEquals(
        List.of(PARENT_THREAD_ID), repository.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID));
    assertTrue(repository.hasUndeliveredByChildThreadId(PARENT_THREAD_ID));
  }

  @Test
  void nullSettledParentListIsReadAsEmpty() {
    // 测试意图：映射器返回 null 时按"没有待交付父"处理，绝不把 null 传给活动聚合。
    when(mapper.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID)).thenReturn(null);

    assertEquals(List.of(), repository.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID));
  }

  @Test
  void statusMappingDistinguishesDeliveredFromOpen() {
    SubagentTaskDO delivered = row();
    delivered.setStatus(SubagentTaskStatus.DELIVERED);
    delivered.setOutcome(Outcome.COMPLETED.name());
    delivered.setReport("report");
    delivered.setSettledAt(Instant.parse("2026-01-01T00:00:09Z"));
    when(mapper.findByInvocationId(INVOCATION_ID)).thenReturn(delivered);

    SubagentTask task = repository.findByInvocationId(INVOCATION_ID);

    assertEquals(SubagentTaskStatus.DELIVERED, task.status());
    assertEquals(Outcome.COMPLETED, task.outcome());
    assertEquals("report", task.report());
    assertEquals(Instant.parse("2026-01-01T00:00:09Z"), task.settledAt());
    assertTrue(task.delivered());
    assertTrue(task.settled());
  }

  private static SubagentTaskDraft draft() {
    return new SubagentTaskDraft(
        INVOCATION_ID,
        PARENT_THREAD_ID,
        ROOT_THREAD_ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "alpha",
        "do the work",
        7,
        SubagentTaskStatus.OPEN,
        0L);
  }

  private static SubagentTaskDO row() {
    SubagentTaskDO row = new SubagentTaskDO();
    row.setInvocationId(INVOCATION_ID);
    row.setParentThreadId(PARENT_THREAD_ID);
    row.setRootThreadId(ROOT_THREAD_ID);
    row.setChildSessionId(UUID.randomUUID());
    row.setChildThreadId(UUID.randomUUID());
    row.setSourceHeadEntryId(UUID.randomUUID());
    row.setAgent("alpha");
    row.setPrompt("do the work");
    row.setMaxTurns(7);
    row.setStatus(SubagentTaskStatus.OPEN);
    row.setReminderTurn(3L);
    row.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    row.setUpdatedAt(Instant.parse("2026-01-01T00:00:10Z"));
    return row;
  }
}

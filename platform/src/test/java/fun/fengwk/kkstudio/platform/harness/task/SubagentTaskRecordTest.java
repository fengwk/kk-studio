package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 验证委派记录值对象的不变量：写入草稿与持久投影都必须拒绝会让"额度统计/交付"失去意义的数据。
 *
 * <p>测试意图：这些约束是数据库约束的 Java 侧前置校验（非空归属、非空 agent/prompt、正数 max_turns、非负提醒阈值），一旦放宽就会让脏数据在到达 SQL
 * 之前被静默接受。
 */
class SubagentTaskRecordTest {

  private static final UUID INVOCATION_ID = UUID.randomUUID();
  private static final UUID PARENT_THREAD_ID = UUID.randomUUID();
  private static final UUID ROOT_THREAD_ID = UUID.randomUUID();
  private static final UUID CHILD_SESSION_ID = UUID.randomUUID();
  private static final UUID CHILD_THREAD_ID = UUID.randomUUID();
  private static final UUID BOUNDARY_ENTRY_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void draftAcceptsMinimalAndOptionalMaxTurns() {
    SubagentTaskDraft draft = draft(null, 0L);

    assertEquals(SubagentTaskStatus.OPEN, draft.status());
    assertEquals(0L, draft.reminderTurn());
    assertNull(draft.maxTurns());
  }

  @Test
  void draftRejectsMissingIdentityAndBlankText() {
    assertThrows(NullPointerException.class, () -> draft(builder -> builder.invocationId = null));
    assertThrows(NullPointerException.class, () -> draft(builder -> builder.parentThreadId = null));
    assertThrows(NullPointerException.class, () -> draft(builder -> builder.rootThreadId = null));
    assertThrows(NullPointerException.class, () -> draft(builder -> builder.childSessionId = null));
    assertThrows(NullPointerException.class, () -> draft(builder -> builder.childThreadId = null));
    assertThrows(
        NullPointerException.class, () -> draft(builder -> builder.sourceHeadEntryId = null));
    assertThrows(NullPointerException.class, () -> draft(builder -> builder.status = null));
    assertThrows(IllegalArgumentException.class, () -> draft(builder -> builder.agent = null));
    assertThrows(IllegalArgumentException.class, () -> draft(builder -> builder.agent = "  "));
    assertThrows(IllegalArgumentException.class, () -> draft(builder -> builder.prompt = null));
    assertThrows(IllegalArgumentException.class, () -> draft(builder -> builder.prompt = " "));
  }

  @Test
  void draftRejectsNonPositiveMaxTurnsAndNegativeReminder() {
    // max_turns 是预算，0 / 负数会让"软预算提醒"失去意义；提醒阈值不可回退到负数。
    assertThrows(IllegalArgumentException.class, () -> draft(builder -> builder.maxTurns = 0));
    assertThrows(IllegalArgumentException.class, () -> draft(builder -> builder.maxTurns = -1));
    assertThrows(
        IllegalArgumentException.class, () -> draft(builder -> builder.reminderTurn = -1L));
  }

  @Test
  void persistedTaskKeepsIdentityAndReportsDelivered() {
    SubagentTask task = task(SubagentTaskStatus.DELIVERED, 5L);

    assertEquals(INVOCATION_ID, task.invocationId());
    assertEquals(PARENT_THREAD_ID, task.parentThreadId());
    assertEquals(ROOT_THREAD_ID, task.rootThreadId());
    assertEquals(CHILD_SESSION_ID, task.childSessionId());
    assertEquals(CHILD_THREAD_ID, task.childThreadId());
    assertEquals(BOUNDARY_ENTRY_ID, task.sourceHeadEntryId());
    assertEquals(5L, task.reminderTurn());
    assertTrue(task.delivered());
    assertFalse(task(SubagentTaskStatus.OPEN, 0L).delivered());
  }

  @Test
  void persistedTaskRejectsInvalidFields() {
    // 持久投影与写入草稿共享同一组不变量：脏数据不得被静默接受。
    assertThrows(NullPointerException.class, () -> task(builder -> builder.status = null));
    assertThrows(IllegalArgumentException.class, () -> task(builder -> builder.agent = " "));
    assertThrows(IllegalArgumentException.class, () -> task(builder -> builder.prompt = " "));
    assertThrows(IllegalArgumentException.class, () -> task(builder -> builder.maxTurns = 0));
    assertThrows(IllegalArgumentException.class, () -> task(builder -> builder.reminderTurn = -1L));
  }

  @Test
  void persistedTaskKeepsExecutionOutcomeSeparateFromParentNotification() {
    // 两段状态机是本设计的关键：SETTLED（执行已终结、通知未入队）与 DELIVERED（通知已入队）携带同一份终态事实，
    // 停止的父 Thread 因此既不占并发额度、又不丢结果。
    SubagentTask settled = task(SubagentTaskStatus.SETTLED, 0L);
    assertTrue(settled.settled());
    assertFalse(settled.delivered());

    SubagentTask delivered = task(SubagentTaskStatus.DELIVERED, 0L);
    assertTrue(delivered.settled());
    assertTrue(delivered.delivered());

    SubagentTask open = task(SubagentTaskStatus.OPEN, 0L);
    assertFalse(open.settled());
    assertNull(open.outcome());
    assertNull(open.settledAt());
  }

  @Test
  void persistedTaskRejectsStateShapeMismatch() {
    // OPEN 行携带终态（或已终结行缺少终态/终结时间）会让结算扫描与额度统计失去意义，必须在构造期拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task(
                builder -> {
                  builder.status = SubagentTaskStatus.OPEN;
                  builder.outcome = Outcome.COMPLETED;
                  builder.settledAt = CREATED_AT;
                }));
    assertThrows(
        IllegalArgumentException.class,
        () -> task(builder -> builder.status = SubagentTaskStatus.SETTLED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task(
                builder -> {
                  builder.status = SubagentTaskStatus.DELIVERED;
                  builder.outcome = Outcome.COMPLETED;
                }));
  }

  @Test
  void persistedTaskRejectsMissingTimestamps() {
    SubagentTask valid = task(SubagentTaskStatus.OPEN, 0L);

    assertThrows(NullPointerException.class, () -> copy(valid, null, valid.updatedAt()));
    assertThrows(NullPointerException.class, () -> copy(valid, valid.createdAt(), null));
  }

  private static SubagentTask copy(SubagentTask task, Instant createdAt, Instant updatedAt) {
    return new SubagentTask(
        task.invocationId(),
        task.parentThreadId(),
        task.rootThreadId(),
        task.childSessionId(),
        task.childThreadId(),
        task.sourceHeadEntryId(),
        task.agent(),
        task.prompt(),
        task.maxTurns(),
        task.status(),
        task.outcome(),
        task.report(),
        task.partialResult(),
        task.error(),
        task.reminderTurn(),
        task.settledAt(),
        createdAt,
        updatedAt);
  }

  private static SubagentTask task(SubagentTaskStatus status, long reminderTurn) {
    Mutable mutable = new Mutable();
    mutable.status = status;
    mutable.reminderTurn = reminderTurn;
    if (status != SubagentTaskStatus.OPEN) {
      // 已终结状态必须成对携带终态与终结时间，才符合 ck_harness_subagent_task_state_shape。
      mutable.outcome = Outcome.COMPLETED;
      mutable.report = "done";
      mutable.settledAt = CREATED_AT;
    }
    return mutable.toTask();
  }

  private static SubagentTask task(Consumer<Mutable> mutation) {
    return mutable(mutation).toTask();
  }

  private static SubagentTaskDraft draft(Integer maxTurns, long reminderTurn) {
    Mutable mutable = new Mutable();
    mutable.maxTurns = maxTurns;
    mutable.reminderTurn = reminderTurn;
    return mutable.toDraft();
  }

  private static SubagentTaskDraft draft(Consumer<Mutable> mutation) {
    return mutable(mutation).toDraft();
  }

  private static Mutable mutable(Consumer<Mutable> mutation) {
    Mutable mutable = new Mutable();
    mutation.accept(mutable);
    return mutable;
  }

  /** 可逐字段改坏的最小构造器，用于表达每条不变量。 */
  private static final class Mutable {

    UUID invocationId = INVOCATION_ID;
    UUID parentThreadId = PARENT_THREAD_ID;
    UUID rootThreadId = ROOT_THREAD_ID;
    UUID childSessionId = CHILD_SESSION_ID;
    UUID childThreadId = CHILD_THREAD_ID;
    UUID sourceHeadEntryId = BOUNDARY_ENTRY_ID;
    String agent = "alpha";
    String prompt = "do the work";
    Integer maxTurns = 7;
    SubagentTaskStatus status = SubagentTaskStatus.OPEN;
    Outcome outcome = null;
    String report = null;
    String partialResult = null;
    String error = null;
    Instant settledAt = null;
    long reminderTurn = 0L;

    SubagentTaskDraft toDraft() {
      return new SubagentTaskDraft(
          invocationId,
          parentThreadId,
          rootThreadId,
          childSessionId,
          childThreadId,
          sourceHeadEntryId,
          agent,
          prompt,
          maxTurns,
          status,
          reminderTurn);
    }

    SubagentTask toTask() {
      return new SubagentTask(
          invocationId,
          parentThreadId,
          rootThreadId,
          childSessionId,
          childThreadId,
          sourceHeadEntryId,
          agent,
          prompt,
          maxTurns,
          status,
          outcome,
          report,
          partialResult,
          error,
          reminderTurn,
          settledAt,
          CREATED_AT,
          CREATED_AT);
    }
  }
}

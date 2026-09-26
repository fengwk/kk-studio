package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@link IssueRun} 冻结快照与 {@link IssueRunEntryBounds} 区间契约。 */
class IssueRunTest {

  private static final UUID ISSUE_ID = ProjectDomainFixtures.id(10L);
  private static final Instant START = ProjectDomainFixtures.START;

  /** 六种状态都必须能表达合法形状，活动与终态的判定含义固定。 */
  @Test
  void acceptsEveryStatusShape() {
    for (IssueRunStatus status : IssueRunStatus.values()) {
      IssueRun run = ProjectDomainFixtures.run(ISSUE_ID, "DESIGN", 1L, status);

      assertEquals(status, run.status());
      assertTrue(run.status().isActive() || run.status().isTerminal());
      assertEquals(!status.isActive(), run.status().isTerminal());
      assertEquals(ISSUE_ID, run.issueId());
      assertEquals(ProjectStateCode.of("DESIGN"), run.state());
      assertEquals(1L, run.ordinal());
    }
    // CANCELLED 允许记录停止原因，仍是终态
    IssueRun cancelled =
        run(
            IssueRunStatus.CANCELLED,
            null,
            0L,
            60_000L,
            null,
            "用户停止",
            START.plusSeconds(1),
            closedBounds(1L),
            1L);
    assertEquals("用户停止", cancelled.error());
    assertTrue(cancelled.status().isTerminal());
  }

  /** ordinal、活动游标与剩余额度都不能为负，活动 Run 也不能在额度耗尽时继续运行。 */
  @Test
  void rejectsInvalidOrdinalAndCounters() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(IssueRunStatus.RUNNING, null, 0L, 60_000L, START, null, null, openBounds(1L), 0L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(IssueRunStatus.RUNNING, null, -1L, 60_000L, START, null, null, openBounds(1L), 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> run(IssueRunStatus.RUNNING, null, 0L, -1L, START, null, null, openBounds(1L), 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> run(IssueRunStatus.RUNNING, null, 0L, 0L, START, null, null, openBounds(1L), 1L));
  }

  /** 活动 Run 在安全收尾前不能带结束区间、结束时间或原因；RUNNING 必须计时，WAITING 必须停止计时。 */
  @Test
  void rejectsActiveRunWithTerminalFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> run(IssueRunStatus.RUNNING, null, 0L, 60_000L, null, null, null, openBounds(1L), 1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(IssueRunStatus.WAITING, null, 0L, 60_000L, START, null, null, openBounds(1L), 1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.RUNNING,
                null,
                0L,
                60_000L,
                START,
                null,
                START.plusSeconds(1),
                openBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.RUNNING,
                null,
                0L,
                60_000L,
                START,
                null,
                null,
                closedBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.RUNNING,
                null,
                0L,
                60_000L,
                START,
                "boom",
                null,
                openBounds(1L),
                1L));
  }

  /** 终态 Run 必须冻结结束区间与时间、停止计时；FAILED/UNKNOWN 必须给出原因，COMPLETED 不能带原因。 */
  @Test
  void rejectsTerminalRunWithActiveOrIncompleteFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.COMPLETED,
                null,
                0L,
                60_000L,
                START,
                null,
                START.plusSeconds(1),
                closedBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.COMPLETED,
                null,
                0L,
                60_000L,
                null,
                null,
                null,
                closedBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.COMPLETED,
                null,
                0L,
                60_000L,
                null,
                null,
                START.plusSeconds(1),
                openBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.COMPLETED,
                null,
                0L,
                60_000L,
                null,
                null,
                START.minusSeconds(1),
                closedBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.COMPLETED,
                null,
                0L,
                60_000L,
                null,
                "boom",
                START.plusSeconds(1),
                closedBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.FAILED,
                null,
                0L,
                60_000L,
                null,
                null,
                START.plusSeconds(1),
                closedBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.UNKNOWN,
                null,
                0L,
                60_000L,
                null,
                null,
                START.plusSeconds(1),
                closedBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.COMPLETED,
                null,
                0L,
                60_000L,
                null,
                " ",
                START.plusSeconds(1),
                closedBounds(1L),
                1L));
  }

  /** 交接目标必须不同于当前阶段且不能是 BLOCKED；DONE 等正常阶段可以冻结为待提交目标。 */
  @Test
  void rejectsInvalidHandoffTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.WAITING,
                ProjectStateCode.of("DESIGN"),
                0L,
                60_000L,
                null,
                null,
                null,
                openBounds(1L),
                1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                IssueRunStatus.WAITING,
                ProjectStateCode.of("BLOCKED"),
                0L,
                60_000L,
                null,
                null,
                null,
                openBounds(1L),
                1L));
    assertEquals(
        ProjectStateCode.of("DONE"),
        run(
                IssueRunStatus.WAITING,
                ProjectStateCode.of("DONE"),
                0L,
                60_000L,
                null,
                null,
                null,
                openBounds(1L),
                1L)
            .nextState());
  }

  /** Run 身份与区间在构造期固定：null 坐标与非法区间（无结束区间、报告落在区间外）都拒绝。 */
  @Test
  void rejectsNullCoordinatesAndInvalidBounds() {
    assertThrows(
        NullPointerException.class,
        () ->
            new IssueRun(
                null,
                ISSUE_ID,
                1L,
                ProjectStateCode.of("DESIGN"),
                ProjectDomainFixtures.id(900L),
                ProjectDomainFixtures.id(901L),
                IssueRunStatus.RUNNING,
                openBounds(1L),
                null,
                0L,
                60_000L,
                START,
                null,
                START,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new IssueRun(
                ProjectDomainFixtures.id(1L),
                ISSUE_ID,
                1L,
                ProjectStateCode.of("DESIGN"),
                ProjectDomainFixtures.id(900L),
                ProjectDomainFixtures.id(901L),
                null,
                openBounds(1L),
                null,
                0L,
                60_000L,
                START,
                null,
                START,
                null));
    assertThrows(NullPointerException.class, () -> new IssueRunEntryBounds(null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IssueRunEntryBounds(
                ProjectDomainFixtures.id(1L), null, ProjectDomainFixtures.id(2L)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IssueRunEntryBounds(
                ProjectDomainFixtures.id(1L),
                ProjectDomainFixtures.id(2L),
                ProjectDomainFixtures.id(1L)));
    assertEquals(
        ProjectDomainFixtures.id(2L),
        new IssueRunEntryBounds(
                ProjectDomainFixtures.id(1L),
                ProjectDomainFixtures.id(2L),
                ProjectDomainFixtures.id(2L))
            .finalAnswerEntryId());
  }

  /** 同一 Issue 只能有一个活动主 Run：这里提供事务内前置校验，最终由数据库部分唯一索引保证。 */
  @Test
  void requiresSingleActiveRunPerIssue() {
    UUID otherIssueId = ProjectDomainFixtures.id(11L);
    IssueRun running = ProjectDomainFixtures.run(ISSUE_ID, "DESIGN", 1L, IssueRunStatus.RUNNING);
    IssueRun waiting = ProjectDomainFixtures.run(ISSUE_ID, "DESIGN", 2L, IssueRunStatus.WAITING);
    IssueRun completed =
        ProjectDomainFixtures.run(ISSUE_ID, "DESIGN", 3L, IssueRunStatus.COMPLETED);
    IssueRun otherIssueActive =
        ProjectDomainFixtures.run(otherIssueId, "DESIGN", 2L, IssueRunStatus.WAITING);

    assertDoesNotThrow(
        () ->
            IssueRun.requireSingleActiveRunPerIssue(List.of(running, completed, otherIssueActive)));
    assertThrows(
        IllegalArgumentException.class,
        () -> IssueRun.requireSingleActiveRunPerIssue(List.of(running, waiting)));
    assertThrows(NullPointerException.class, () -> IssueRun.requireSingleActiveRunPerIssue(null));
  }

  private static IssueRun run(
      IssueRunStatus status,
      ProjectStateCode nextState,
      long observedActivitySequence,
      long remainingExecutionMs,
      Instant activeSince,
      String error,
      Instant endedAt,
      IssueRunEntryBounds entryBounds,
      long ordinal) {
    return new IssueRun(
        ProjectDomainFixtures.id(1L),
        ISSUE_ID,
        ordinal,
        ProjectStateCode.of("DESIGN"),
        ProjectDomainFixtures.id(900L),
        ProjectDomainFixtures.id(901L),
        status,
        entryBounds,
        nextState,
        observedActivitySequence,
        remainingExecutionMs,
        activeSince,
        error,
        START,
        endedAt);
  }

  private static IssueRunEntryBounds openBounds(long ordinal) {
    return new IssueRunEntryBounds(ProjectDomainFixtures.id(100L + ordinal), null, null);
  }

  private static IssueRunEntryBounds closedBounds(long ordinal) {
    return new IssueRunEntryBounds(
        ProjectDomainFixtures.id(100L + ordinal), ProjectDomainFixtures.id(200L + ordinal), null);
  }
}

package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 原子 Run 接受与收尾：同事务提交 Harness 事实与业务行，任一步失败整体回滚；请求键幂等以原 RUN 活动精确重放。
 *
 * <p>测试意图：覆盖目标表行与即时 FK（start entry 属于冻结 Session、Run 的 Thread/State 均有归属行）、请求键精确重放（不新建
 * Session/Run、不重复扣额度、判定先于状态与额度校验）、请求键复用冲突在 Harness 派发前失败、业务写失败后的整体回滚（崩溃路径）、区间按 Entry
 * 父链解释（收尾校验）、WAITING/RUNNING 的空重放与暂停门禁。
 */
class IssueRunAcceptanceIntegrationTest extends ProjectTestSupport {

  /** 首次接受创建 Session/ROOT/Thread、稳定绑定、Run、RUN 活动与 Issue Work，且 FK 即时成立。 */
  @Test
  void acceptRunCommitsHarnessFactsBindingRunActivityAndWork() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("接受 Run", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    assertEquals(IssueRunStatus.RUNNING, run.getStatus());
    assertEquals(1L, run.getOrdinal());
    assertNull(run.getEndEntryId());
    assertNotNull(run.getActiveSince());
    assertEquals(
        1L, count("select count(*) from harness_session where id = ?", run.getSessionId()));
    assertEquals(
        1L,
        count(
            "select count(*) from harness_thread where id = ? and session_id = ?",
            run.getThreadId(),
            run.getSessionId()));
    assertEquals(
        1L,
        count(
            "select count(*) from harness_entry where id = ? and session_id = ?",
            run.getStartEntryId(),
            run.getSessionId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id = ? and agent_name = ?"
                + " and thread_id = ?",
            issue.getId(),
            agent,
            run.getThreadId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and issue_id = ? and state = ?"
                + " and session_id = ? and thread_id = ?",
            run.getId(),
            issue.getId(),
            "DESIGN",
            run.getSessionId(),
            run.getThreadId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_stage_budget where issue_id = ? and state = ?",
            issue.getId(),
            "DESIGN"));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and run_id = ?"
                + " and kind = 'RUN'",
            issue.getId(),
            run.getId()));
    // 进入有 Agent 的工作阶段即登记 Work（设计 §4.5），接受 Run 时再次唤醒同一 mailbox 行。
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_work where issue_id = ? and wake_version >= 1",
            issue.getId()));
  }

  /** 丢响应后的精确重试：同请求键与同指纹返回原 Run，不新建 Session/Thread/Entry、不重复插入 RUN 活动、不重扣额度、不推进 ordinal 与 Work。 */
  @Test
  void acceptRunSameRequestKeyReplaysWithoutNewHarnessOrBudgetUse() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("接受重放", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    IssueRun accepted = issueRunService.acceptRun(issue.getId(), acceptKey);
    long nextRunOrdinalAfterAccept =
        jdbc.queryForObject(
            "select next_run_ordinal from project_issue where id = ?", Long.class, issue.getId());
    long wakeVersionAfterAccept =
        jdbc.queryForObject(
            "select wake_version from project_issue_work where issue_id = ?",
            Long.class,
            issue.getId());

    IssueRun replayed = issueRunService.acceptRun(issue.getId(), acceptKey);

    assertEquals(accepted.getId(), replayed.getId());
    assertEquals(IssueRunStatus.RUNNING, replayed.getStatus());
    assertEquals(1L, count("select count(*) from harness_session"));
    assertEquals(1L, count("select count(*) from harness_thread"));
    assertEquals(1L, count("select count(*) from harness_entry"));
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(
        1L,
        count("select count(*) from project_issue_agent_thread where issue_id = ?", issue.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'RUN'",
            issue.getId()));
    // 重放不推进 Work：mailbox 的唤醒版本保持接受时的值（阶段转移已先行登记同一行）。
    assertEquals(
        wakeVersionAfterAccept,
        jdbc.queryForObject(
            "select wake_version from project_issue_work where issue_id = ?",
            Long.class,
            issue.getId()));
    assertEquals(
        nextRunOrdinalAfterAccept,
        jdbc.queryForObject(
            "select next_run_ordinal from project_issue where id = ?", Long.class, issue.getId()));
    assertEquals(1L, budgetUsed(issue.getId()));
  }

  /** 精确重试先于状态与额度校验：原 Run 已收尾、阶段已交接、额度已耗尽、已有新活动 Run 时仍重放原 Run。 */
  @Test
  void acceptRunReplayPrecedesStageAndBudgetChecks() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("重放先于校验", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    IssueRun accepted = issueRunService.acceptRun(issue.getId(), acceptKey);
    UUID endEntryId = appendHistoryEntry(accepted.getThreadId());
    issueRunService.completeRun(
        accepted.getId(), accepted.getVersion(), key("complete"), endEntryId, endEntryId, "REVIEW");
    assertEquals(0L, issueService.getStageBudget(issue.getId(), "DESIGN").remainingRuns());

    IssueRun replayed = issueRunService.acceptRun(issue.getId(), acceptKey);

    assertEquals(accepted.getId(), replayed.getId());
    assertEquals(IssueRunStatus.COMPLETED, replayed.getStatus());
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(1L, count("select count(*) from harness_session"));
  }

  /** 暂停门禁关闭时新请求被拒绝，但同一请求键的精确重试仍然重放原 Run。 */
  @Test
  void acceptRunReplayIgnoresPauseGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("重放与门禁", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    IssueRun accepted = issueRunService.acceptRun(issue.getId(), acceptKey);
    Issue inDesign = issueService.getIssue(issue.getId());
    issueService.pauseIssue(
        inDesign.getId(), inDesign.getVersion(), key("pause"), PauseReason.USER, "人工暂停");

    assertEquals(accepted.getId(), issueRunService.acceptRun(issue.getId(), acceptKey).getId());
    assertThrows(
        ProjectValidationException.class,
        () -> issueRunService.acceptRun(issue.getId(), key("accept")));
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 每 Issue 唯一活动 Run：已存在活动 Run 时新的接受被拒绝，且不新建 Harness 事实或第二条 Run 行。 */
  @Test
  void acceptRunRejectedWhileActiveRunExists() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("唯一活动 Run", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun active = issueRunService.acceptRun(issue.getId(), key("accept"));

    ProjectValidationException rejected =
        assertThrows(
            ProjectValidationException.class,
            () -> issueRunService.acceptRun(issue.getId(), key("accept")));

    assertTrue(rejected.getMessage().contains("active run"), rejected.getMessage());
    assertEquals(active.getId(), issueRunService.getActiveRun(issue.getId()).getId());
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(1L, count("select count(*) from harness_session"));
    assertEquals(1L, budgetUsed(issue.getId()));
  }

  /**
   * 请求键复用（同键异指纹）在 Harness 派发之前确定性冲突：不创建 Session/Thread/Entry、不插入 Run、不消耗额度。
   *
   * <p>防御性用例：{@code run:} 键名空间里已存在不同指纹的活动时（例如指纹规则升级或历史行被替换），接受必须失败而不是按新请求继续。 真实 RUN 活动必须引用真实 Run
   * 行，因此这里用 CONTROL 行占位来制造同键异指纹。
   */
  @Test
  void acceptRunRejectsReusedRequestKeyWithDifferentFingerprintBeforeDispatch() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("指纹冲突", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    long sequence = nextActivitySequence(issue.getId());
    jdbc.update(
        "insert into project_issue_activity (issue_id, sequence, kind, actor_type, run_id, body,"
            + " data, idempotency_key, request_hash)"
            + " values (?, ?, 'CONTROL', 'SYSTEM', null, null, '{}'::jsonb, ?, ?)",
        issue.getId(),
        sequence,
        "run:" + acceptKey,
        "b".repeat(64));

    assertThrows(
        ProjectDuplicateException.class, () -> issueRunService.acceptRun(issue.getId(), acceptKey));

    assertEquals(0L, count("select count(*) from harness_session"));
    assertEquals(0L, count("select count(*) from harness_thread"));
    assertEquals(0L, count("select count(*) from harness_entry"));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(
        0L,
        count("select count(*) from project_issue_stage_budget where issue_id = ?", issue.getId()));
    assertEquals(
        0L,
        count("select count(*) from project_issue_agent_thread where issue_id = ?", issue.getId()));
  }

  /**
   * 崩溃路径：Harness 已接受 Session/Thread 之后业务写失败，则整个事务回滚——不留下 Session/Thread/Entry、绑定、Run、额度行或 Work。
   *
   * <p>用占用下一个 activity sequence 的无关活动制造业务写失败，确保失败点发生在 Harness 写入之后。
   */
  @Test
  void acceptRunRollsBackHarnessWritesWhenBusinessWriteFails() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("回滚", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    jdbc.update(
        "insert into project_issue_activity (issue_id, sequence, kind, actor_type, run_id, body,"
            + " data, idempotency_key, request_hash)"
            + " values (?, ?, 'CONTROL', 'SYSTEM', null, null, '{}'::jsonb, ?, ?)",
        issue.getId(),
        nextActivitySequence(issue.getId()),
        "control:" + key("planted"),
        "c".repeat(64));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> issueRunService.acceptRun(issue.getId(), key("accept")));

    assertEquals(0L, count("select count(*) from harness_session"));
    assertEquals(0L, count("select count(*) from harness_thread"));
    assertEquals(0L, count("select count(*) from harness_entry"));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(
        0L,
        count("select count(*) from project_issue_agent_thread where issue_id = ?", issue.getId()));
    assertEquals(
        0L,
        count("select count(*) from project_issue_stage_budget where issue_id = ?", issue.getId()));
    // 接受失败整体回滚：不新增 mailbox，也不推进阶段转移已经登记的那一行。
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select wake_version from project_issue_work where issue_id = ?",
            Long.class,
            issue.getId()));
    assertEquals(
        2L,
        jdbc.queryForObject(
            "select next_activity_sequence from project_issue where id = ?",
            Long.class,
            issue.getId()));
  }

  /** 正常收尾冻结区间与报告并交接阶段；丢响应后的同键重试重放同一终态而不重复写入活动或推进 Issue 版本。 */
  @Test
  void completeRunFreezesIntervalAndReplaysWithoutDuplicateWrites() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("收尾重放", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntryId = appendHistoryEntry(run.getThreadId());
    String completeKey = key("complete");
    IssueRun completed =
        issueRunService.completeRun(
            run.getId(), run.getVersion(), completeKey, endEntryId, endEntryId, "REVIEW");
    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());
    Issue afterComplete = issueService.getIssue(issue.getId());

    IssueRun replayed =
        issueRunService.completeRun(
            run.getId(), run.getVersion(), completeKey, endEntryId, endEntryId, "REVIEW");

    assertEquals(IssueRunStatus.COMPLETED, replayed.getStatus());
    assertEquals(endEntryId, replayed.getEndEntryId());
    assertEquals(endEntryId, replayed.getFinalAnswerEntryId());
    assertEquals("REVIEW", replayed.getNextState());
    Issue afterReplay = issueService.getIssue(issue.getId());
    assertEquals("REVIEW", afterReplay.getState());
    assertEquals(afterComplete.getVersion(), afterReplay.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where idempotency_key = ?",
            "state_change:" + completeKey));
    assertEquals(3L, activityCount(issue.getId()));
  }

  /**
   * 区间按 Entry 父链解释：Session 内不在 head 父链上的 Entry、或"空区间"都不能作为正常收尾的结束点。
   *
   * <p>外键只保证 Entry 属于同一 Session，因此必须沿父链校验；失败时 Run 与 Issue 均保持原状。
   */
  @Test
  void completeRunRejectsEndEntryOutsideRunInterval() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("区间校验", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID detachedEntryId = insertDetachedEntry(run.getThreadId());

    long activityCountBeforeRejection = activityCount(run.getIssueId());
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueRunService.completeRun(
                run.getId(), run.getVersion(), key("complete"), detachedEntryId, null, "REVIEW"));
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueRunService.completeRun(
                run.getId(),
                run.getVersion(),
                key("complete"),
                run.getStartEntryId(),
                null,
                "REVIEW"));

    IssueRun unchanged = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.RUNNING, unchanged.getStatus());
    assertNull(unchanged.getEndEntryId());
    assertEquals("DESIGN", issueService.getIssue(issue.getId()).getState());
    assertEquals(activityCountBeforeRejection, activityCount(run.getIssueId()));
  }

  /** 最终答复必须落在同一区间内：区间之前的 Entry 不被接受为可见回答。 */
  @Test
  void completeRunRejectsFinalAnswerOutsideInterval() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("报告区间", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntryId = appendHistoryEntry(run.getThreadId());

    assertThrows(
        ProjectValidationException.class,
        () ->
            issueRunService.completeRun(
                run.getId(),
                run.getVersion(),
                key("complete"),
                endEntryId,
                run.getStartEntryId(),
                "REVIEW"));
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());
  }

  /** 失败与不明收尾允许空区间（接受后还没有任何产出就要能安全停止），并与对应暂停门禁同事务写入。 */
  @Test
  void terminalFailureAllowsEmptyIntervalAndWritesPauseGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("失败收尾", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun failedRun = issueRunService.acceptRun(issue.getId(), key("accept"));

    IssueRun failed =
        issueRunService.failRun(
            failedRun.getId(),
            failedRun.getVersion(),
            key("fail"),
            failedRun.getStartEntryId(),
            "provider exploded");

    assertEquals(IssueRunStatus.FAILED, failed.getStatus());
    assertEquals("provider exploded", failed.getError());
    assertNotNull(failed.getEndedAt());
    assertEquals("ERROR", issueService.getIssue(issue.getId()).getPauseReason());

    Issue afterFail = issueService.getIssue(issue.getId());
    issueService.resumeIssue(afterFail.getId(), afterFail.getVersion(), key("resume"));
    IssueRun unknownRun = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID unknownEnd = appendHistoryEntry(unknownRun.getThreadId());
    IssueRun unknown =
        issueRunService.markUnknown(
            unknownRun.getId(),
            unknownRun.getVersion(),
            key("unknown"),
            unknownEnd,
            "side effect lost");

    assertEquals(IssueRunStatus.UNKNOWN, unknown.getStatus());
    assertEquals("side effect lost", unknown.getError());
    assertEquals("UNKNOWN", issueService.getIssue(issue.getId()).getPauseReason());
  }

  /** 人工停止收尾为 CANCELLED 并保留 USER 暂停门禁，避免轮询立即启动新 Run。 */
  @Test
  void cancelRunKeepsUserPauseGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("停止", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntryId = appendHistoryEntry(run.getThreadId());

    IssueRun cancelled =
        issueRunService.cancelRun(run.getId(), run.getVersion(), key("cancel"), endEntryId);

    assertEquals(IssueRunStatus.CANCELLED, cancelled.getStatus());
    assertEquals(endEntryId, cancelled.getEndEntryId());
    assertNull(cancelled.getError());
    assertEquals("USER", issueService.getIssue(issue.getId()).getPauseReason());
    assertThrows(
        ProjectValidationException.class,
        () -> issueRunService.acceptRun(issue.getId(), key("accept")));
  }

  /** WAITING/RUNNING 的重复置位是空重放：丢响应后的旧版本重试不再冲突，也不重复消耗额度或推进 Run 版本。 */
  @Test
  void waitAndResumeAreIdempotentOnLostResponseRetry() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("等待恢复", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    IssueRun waiting = issueRunService.waitRun(run.getId(), run.getVersion());
    assertEquals(IssueRunStatus.WAITING, waiting.getStatus());
    assertNull(waiting.getActiveSince());
    IssueRun waitingRetry = issueRunService.waitRun(run.getId(), run.getVersion());
    assertEquals(IssueRunStatus.WAITING, waitingRetry.getStatus());
    assertEquals(waiting.getVersion(), waitingRetry.getVersion());

    IssueRun resumed = issueRunService.resumeRun(waiting.getId(), waiting.getVersion());
    assertEquals(IssueRunStatus.RUNNING, resumed.getStatus());
    assertNotNull(resumed.getActiveSince());
    IssueRun resumedRetry = issueRunService.resumeRun(waiting.getId(), waiting.getVersion());
    assertEquals(IssueRunStatus.RUNNING, resumedRetry.getStatus());
    assertEquals(resumed.getVersion(), resumedRetry.getVersion());
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(1L, budgetUsed(issue.getId()));
  }

  /** 暂停门禁关闭时不得恢复 Run：恢复不消耗阶段额度，但必须等门禁真正打开。 */
  @Test
  void resumeRunRejectedWhileIssuePauseGateClosed() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("门禁恢复", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    IssueRun waiting = issueRunService.waitRun(run.getId(), run.getVersion());
    Issue waitingIssue = issueService.getIssue(issue.getId());
    issueService.pauseIssue(
        waitingIssue.getId(),
        waitingIssue.getVersion(),
        key("pause"),
        PauseReason.UNKNOWN,
        "副作用不明");

    assertThrows(
        ProjectValidationException.class,
        () -> issueRunService.resumeRun(waiting.getId(), waiting.getVersion()));

    IssueRun unchanged = issueRunService.getRun(waiting.getId());
    assertEquals(IssueRunStatus.WAITING, unchanged.getStatus());
    assertNull(unchanged.getActiveSince());
    assertEquals("UNKNOWN", issueService.getIssue(issue.getId()).getPauseReason());
    assertEquals(1L, budgetUsed(issue.getId()));
  }

  private long activityCount(UUID issueId) {
    return count("select count(*) from project_issue_activity where issue_id = ?", issueId);
  }

  private long budgetUsed(UUID issueId) {
    return count(
        "select count(*) from project_issue_run where issue_id = ? and state = 'DESIGN'", issueId);
  }

  private long nextActivitySequence(UUID issueId) {
    return jdbc.queryForObject(
        "select next_activity_sequence from project_issue where id = ?", Long.class, issueId);
  }

  /** 在同一 Session 内插入一个不在 head 父链上的 MESSAGE Entry：外键意义上合法，但绝不属于 Run 的冻结区间。 */
  private UUID insertDetachedEntry(UUID threadId) {
    UUID sessionId =
        jdbc.queryForObject(
            "select session_id from harness_thread where id = ?", UUID.class, threadId);
    UUID detachedId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)",
        detachedId,
        sessionId,
        headEntry(threadId),
        Timestamp.from(Instant.now()));
    return detachedId;
  }
}

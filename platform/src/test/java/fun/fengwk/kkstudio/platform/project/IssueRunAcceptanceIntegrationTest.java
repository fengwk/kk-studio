package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
   * 无交接完成写入 CONTROL/COMPLETE_RUN receipt 并推进活动游标；同键同 payload 重放不新增活动、不重复推进游标。
   *
   * <p>有交接目标时仍只写一条 HANDOFF，不混入完成 receipt。
   */
  @Test
  void completeRunWithoutHandoffWritesReceiptAndReplaysOnce() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("无交接完成", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    Issue before = issueService.getIssue(issue.getId());
    UUID endEntryId = appendHistoryEntry(run.getThreadId());
    String completeKey = key("complete");

    IssueRun completed =
        issueRunService.completeRun(
            run.getId(), run.getVersion(), completeKey, endEntryId, null, null);

    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());
    assertNull(completed.getNextState());
    Issue after = issueService.getIssue(issue.getId());
    assertEquals("DESIGN", after.getState());
    assertEquals(before.getNextActivitySequence() + 1, after.getNextActivitySequence());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'CONTROL'"
                + " and idempotency_key = ? and data->>'action' = 'COMPLETE_RUN'",
            issue.getId(),
            "control:" + completeKey));

    IssueRun replayed =
        issueRunService.completeRun(
            run.getId(), completed.getVersion(), completeKey, endEntryId, null, null);

    assertEquals(completed.getId(), replayed.getId());
    Issue afterReplay = issueService.getIssue(issue.getId());
    assertEquals(after.getVersion(), afterReplay.getVersion());
    assertEquals(after.getNextActivitySequence(), afterReplay.getNextActivitySequence());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where idempotency_key = ?",
            "control:" + completeKey));
  }

  /**
   * 两线程同时接受同一请求键：只创建一条活动、一个 Run 与一个显式命令批，双方都返回同一个 Run。
   *
   * <p>锁前 receipt 检查不能关闭这个窗口；锁内再次检查后，后到请求必须重放而不是版本冲突。
   */
  @Test
  void concurrentAcceptWithSameKeyCreatesOneRunAndOneCommand() throws Exception {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("并发接受", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    List<IssueRun> accepted = race(() -> issueRunService.acceptRun(issue.getId(), acceptKey));
    IssueRun first = accepted.get(0);
    IssueRun second = accepted.get(1);

    assertEquals(first.getId(), second.getId());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'RUN'",
            issue.getId()));
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(1L, count("select count(*) from harness_session"));
    // 每次接受显式提交 5 条命令（SET_AGENT/SET_MODEL/SET_ENVIRONMENT/SET_CONTRIBUTOR_STATE/CUSTOM_MESSAGE）与 1 条
    // root Join。
    assertEquals(5L, count("select count(*) from harness_thread_command"));
    assertEquals(1L, count("select count(*) from harness_thread_join"));
  }

  /**
   * 同键收尾重放必须在观察到 receipt 之后返回权威当前 Run：并发首次收尾落在「锁前已读到旧 Run」与「锁前 receipt 检查」之间时， 重放绝不能把本事务早先读到的旧
   * Run（仍是 RUNNING、版本滞后）当作当前事实返回。
   *
   * <p>本用例用 spy 把第二个请求精确停在锁前 receipt 检查上，等第一个请求完整提交后再放行，因此是确定性复现，而不是概率竞争。
   */
  @Test
  void concurrentSameCompleteKeyReplaysCommittedRunWhenReceiptArrivesAfterFirstRead()
      throws Exception {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("并发收尾重放", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntryId = appendHistoryEntry(run.getThreadId());
    String completeKey = key("complete");
    ThreadLocal<Boolean> gated = new ThreadLocal<>();
    CountDownLatch awaitingReceipt = new CountDownLatch(1);
    CountDownLatch firstCommitted = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              if (Boolean.TRUE.equals(gated.get())) {
                awaitingReceipt.countDown();
                assertTrue(firstCommitted.await(10, TimeUnit.SECONDS));
              }
              return invocation.callRealMethod();
            })
        .when(issueActivityRepository)
        .findByIdempotencyKey(any(), any());
    AtomicReference<IssueRun> replayed = new AtomicReference<>();
    AtomicReference<Throwable> replayedError = new AtomicReference<>();
    Thread replayer =
        new Thread(
            () -> {
              gated.set(true);
              try {
                replayed.set(
                    issueRunService.completeRun(
                        run.getId(), run.getVersion(), completeKey, endEntryId, null, null));
              } catch (Throwable error) {
                replayedError.set(error);
              }
            });
    IssueRun first;
    replayer.start();
    try {
      assertTrue(awaitingReceipt.await(10, TimeUnit.SECONDS), "第二个请求应停在锁前 receipt 检查");
      // 第一个请求在读走旧快照之后完整收尾：Run 行与 receipt 一起提交。
      first =
          issueRunService.completeRun(
              run.getId(), run.getVersion(), completeKey, endEntryId, null, null);
      assertEquals(IssueRunStatus.COMPLETED, first.getStatus());
      firstCommitted.countDown();
      replayer.join(15_000);
    } finally {
      firstCommitted.countDown();
      replayer.join(15_000);
      gated.remove();
    }
    assertFalse(replayer.isAlive(), "重放请求不应悬挂");
    assertNull(replayedError.get());
    assertEquals(first.getId(), replayed.get().getId());
    assertEquals(IssueRunStatus.COMPLETED, replayed.get().getStatus(), "重放必须返回已提交的权威事实");
    assertEquals(endEntryId, replayed.get().getEndEntryId());
    assertEquals(first.getVersion(), replayed.get().getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where idempotency_key = ?",
            "control:" + completeKey));
  }

  /** 同键但收尾区间不同是稳定冲突：不完成 Run，也不留下半截活动。 */
  @Test
  void completeRunDifferentPayloadConflictsWithoutTerminalWrite() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("完成冲突", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID firstEnd = appendHistoryEntry(run.getThreadId());
    UUID secondEnd = appendHistoryEntry(run.getThreadId());
    String completeKey = key("complete");
    issueRunService.completeRun(run.getId(), run.getVersion(), completeKey, firstEnd, null, null);

    assertThrows(
        ProjectDuplicateException.class,
        () ->
            issueRunService.completeRun(
                run.getId(), run.getVersion(), completeKey, secondEnd, null, null));

    IssueRun stored = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.COMPLETED, stored.getStatus());
    assertEquals(firstEnd, stored.getEndEntryId());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where idempotency_key = ?",
            "control:" + completeKey));
  }

  /** 新请求键仍走版本 CAS：陈旧版本不能完成、取消、失败或标记不明。 */
  @Test
  void runTerminalRequestsRejectStaleVersion() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("收尾版本", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID end = appendHistoryEntry(run.getThreadId());
    long stale = run.getVersion();
    issueRunService.waitRun(run.getId(), stale);
    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueRunService.completeRun(run.getId(), stale, key("complete"), end, null, null));
    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueRunService.cancelRun(run.getId(), stale, key("cancel"), end));
    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueRunService.failRun(run.getId(), stale, key("fail"), end, "失败"));
    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueRunService.markUnknown(run.getId(), stale, key("unknown"), end, "不明"));
    assertEquals(IssueRunStatus.WAITING, issueRunService.getRun(run.getId()).getStatus());
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

  /** 两线程在同一屏障后提交同一调用；任一方的冲突或执行异常都原样抛出。 */
  private static <T> List<T> race(Callable<T> call) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<T> left =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return call.call();
              });
      Future<T> right =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return call.call();
              });
      List<T> results = new ArrayList<>(2);
      results.add(unwrap(left));
      results.add(unwrap(right));
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  private static <T> T unwrap(Future<T> future) throws Exception {
    try {
      return future.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      throw error;
    }
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

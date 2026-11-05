package fun.fengwk.kkstudio.project;

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
import fun.fengwk.kkstudio.project.model.Project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    assertEquals(IssueRunStatus.RUNNING, run.getStatus());
    assertEquals(1L, run.getOrdinal());
    assertNull(run.getEndEntryId());
    assertNotNull(run.getActiveSince());
    // 阶段未声明 environment：Run 显式清空环境选择，而不是沿用 Agent 默认。
    assertNull(runEnvironmentName(run.getThreadId()));
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
            "select count(*) from durable_work where target_type = 'ISSUE' and target_id = ? and wake_version >= 1",
            issue.getId()));
  }

  /** 测试意图：Run 的 SET_ENVIRONMENT 严格取自本阶段声明的 environment，而不是 Agent 默认（root 物化恒为 null）。 */
  @Test
  void setEnvironmentComesFromStageDeclaration() {
    String agent = createAgent();
    Project project = projectService.createProject("阶段环境", "描述", true);
    projectService.updateWorkflow(
        project.getId(),
        project.getVersion(),
        "{"
            + "\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
            + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\""
            + agent
            + "\",\"environment\":\"run-env-a\",\"maxRuns\":3,\"next\":[\"REVIEW\"]},"
            + "{\"state\":\"REVIEW\",\"name\":\"检查\",\"agent\":\""
            + agent
            + "\",\"maxRuns\":1,\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}");
    Issue issue = createIssue(project.getId());
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    assertEquals("run-env-a", runEnvironmentName(run.getThreadId()));
  }

  /** 丢响应后的精确重试：同请求键与同指纹返回原 Run，不新建 Session/Thread/Entry、不重复插入 RUN 活动、不重扣额度、不推进 ordinal 与 Work。 */
  @Test
  void acceptRunSameRequestKeyReplaysWithoutNewHarnessOrBudgetUse() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("接受重放", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    IssueRun accepted = issueRunService.acceptRun(issue.getId(), acceptKey);
    long nextRunOrdinalAfterAccept =
        jdbc.queryForObject(
            "select next_run_ordinal from project_issue where id = ?", Long.class, issue.getId());
    long wakeVersionAfterAccept =
        jdbc.queryForObject(
            "select wake_version from durable_work where target_type = 'ISSUE' and target_id = ?",
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
            "select wake_version from durable_work where target_type = 'ISSUE' and target_id = ?",
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    IssueRun accepted = issueRunService.acceptRun(issue.getId(), acceptKey);
    UUID endEntryId = appendHistoryEntry(accepted.getThreadId());
    issueRunService.finishRun(
        accepted.getId(),
        accepted.getVersion(),
        key("complete"),
        IssueRunStatus.COMPLETED,
        endEntryId,
        endEntryId,
        "REVIEW",
        null);
    assertEquals(
        0L, issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN").remainingRuns());

    IssueRun replayed = issueRunService.acceptRun(issue.getId(), acceptKey);

    assertEquals(accepted.getId(), replayed.getId());
    assertEquals(IssueRunStatus.COMPLETED, replayed.getStatus());
    assertEquals("REVIEW", issueEditingService.getIssue(issue.getId()).getState());
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    String acceptKey = key("accept");
    IssueRun accepted = issueRunService.acceptRun(issue.getId(), acceptKey);
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    Issue stopped =
        issueLifecycleService.stopIssue(
            inDesign.getId(), inDesign.getVersion(), key("stop"), "人工终止");
    assertEquals("USER", stopped.getPauseReason());
    assertEquals(IssueRunStatus.CANCELLED, issueRunService.getRun(accepted.getId()).getStatus());

    IssueRun replayed = issueRunService.acceptRun(issue.getId(), acceptKey);
    assertEquals(accepted.getId(), replayed.getId());
    assertEquals(IssueRunStatus.CANCELLED, replayed.getStatus());
    ProjectValidationException rejected =
        assertThrows(
            ProjectValidationException.class,
            () -> issueRunService.acceptRun(issue.getId(), key("accept")));
    assertTrue(rejected.getMessage().contains("gate is closed"), rejected.getMessage());
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 每 Issue 唯一活动 Run：已存在活动 Run 时新的接受被拒绝，且不新建 Harness 事实或第二条 Run 行。 */
  @Test
  void acceptRunRejectedWhileActiveRunExists() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("唯一活动 Run", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
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
        1L,
        count(
            "select count(*) from durable_work where target_type = 'ISSUE' and target_id = ?",
            issue.getId()));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select wake_version from durable_work where target_type = 'ISSUE' and target_id = ?",
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntryId = appendHistoryEntry(run.getThreadId());
    String completeKey = key("complete");
    IssueRun completed =
        issueRunService.finishRun(
            run.getId(),
            run.getVersion(),
            completeKey,
            IssueRunStatus.COMPLETED,
            endEntryId,
            endEntryId,
            "REVIEW",
            null);
    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());
    Issue afterComplete = issueEditingService.getIssue(issue.getId());

    IssueRun replayed =
        issueRunService.finishRun(
            run.getId(),
            run.getVersion(),
            completeKey,
            IssueRunStatus.COMPLETED,
            endEntryId,
            endEntryId,
            "REVIEW",
            null);

    assertEquals(IssueRunStatus.COMPLETED, replayed.getStatus());
    assertEquals(endEntryId, replayed.getEndEntryId());
    assertEquals(endEntryId, replayed.getFinalAnswerEntryId());
    assertEquals("REVIEW", replayed.getNextState());
    Issue afterReplay = issueEditingService.getIssue(issue.getId());
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    Issue before = issueEditingService.getIssue(issue.getId());
    UUID endEntryId = appendHistoryEntry(run.getThreadId());
    String completeKey = key("complete");

    IssueRun completed =
        issueRunService.finishRun(
            run.getId(),
            run.getVersion(),
            completeKey,
            IssueRunStatus.COMPLETED,
            endEntryId,
            null,
            null,
            null);

    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());
    assertNull(completed.getNextState());
    Issue after = issueEditingService.getIssue(issue.getId());
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
        issueRunService.finishRun(
            run.getId(),
            completed.getVersion(),
            completeKey,
            IssueRunStatus.COMPLETED,
            endEntryId,
            null,
            null,
            null);

    assertEquals(completed.getId(), replayed.getId());
    Issue afterReplay = issueEditingService.getIssue(issue.getId());
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
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
    // 每次接受显式提交 4 条命令（SET_AGENT/SET_EXECUTION/SET_ENVIRONMENT/CUSTOM_MESSAGE）与 1 条 root Join。
    assertEquals(4L, count("select count(*) from harness_thread_command"));
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
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
                    issueRunService.finishRun(
                        run.getId(),
                        run.getVersion(),
                        completeKey,
                        IssueRunStatus.COMPLETED,
                        endEntryId,
                        null,
                        null,
                        null));
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
          issueRunService.finishRun(
              run.getId(),
              run.getVersion(),
              completeKey,
              IssueRunStatus.COMPLETED,
              endEntryId,
              null,
              null,
              null);
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID firstEnd = appendHistoryEntry(run.getThreadId());
    UUID secondEnd = appendHistoryEntry(run.getThreadId());
    String completeKey = key("complete");
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        completeKey,
        IssueRunStatus.COMPLETED,
        firstEnd,
        null,
        null,
        null);

    assertThrows(
        ProjectDuplicateException.class,
        () ->
            issueRunService.finishRun(
                run.getId(),
                run.getVersion(),
                completeKey,
                IssueRunStatus.COMPLETED,
                secondEnd,
                null,
                null,
                null));

    IssueRun stored = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.COMPLETED, stored.getStatus());
    assertEquals(firstEnd, stored.getEndEntryId());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where idempotency_key = ?",
            "control:" + completeKey));
  }

  /** 新请求键仍走版本 CAS：陈旧版本不能完成、取消或失败。 */
  @Test
  void runTerminalRequestsRejectStaleVersion() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("收尾版本", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID end = appendHistoryEntry(run.getThreadId());
    long stale = run.getVersion();
    issueRunService.waitRun(run.getId(), stale);
    assertThrows(
        ProjectVersionConflictException.class,
        () ->
            issueRunService.finishRun(
                run.getId(),
                stale,
                key("complete"),
                IssueRunStatus.COMPLETED,
                end,
                null,
                null,
                null));
    assertThrows(
        ProjectVersionConflictException.class,
        () ->
            issueRunService.finishRun(
                run.getId(),
                stale,
                key("cancel"),
                IssueRunStatus.CANCELLED,
                end,
                null,
                null,
                null));
    assertThrows(
        ProjectVersionConflictException.class,
        () ->
            issueRunService.finishRun(
                run.getId(), stale, key("fail"), IssueRunStatus.FAILED, end, null, null, "失败"));
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
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID detachedEntryId = insertDetachedEntry(run.getThreadId());

    long activityCountBeforeRejection = activityCount(run.getIssueId());
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueRunService.finishRun(
                run.getId(),
                run.getVersion(),
                key("complete"),
                IssueRunStatus.COMPLETED,
                detachedEntryId,
                null,
                "REVIEW",
                null));
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueRunService.finishRun(
                run.getId(),
                run.getVersion(),
                key("complete"),
                IssueRunStatus.COMPLETED,
                run.getStartEntryId(),
                null,
                "REVIEW",
                null));

    IssueRun unchanged = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.RUNNING, unchanged.getStatus());
    assertNull(unchanged.getEndEntryId());
    assertEquals("DESIGN", issueEditingService.getIssue(issue.getId()).getState());
    assertEquals(activityCountBeforeRejection, activityCount(run.getIssueId()));
  }

  /** 最终答复必须落在同一区间内：区间之前的 Entry 不被接受为可见回答。 */
  @Test
  void completeRunRejectsFinalAnswerOutsideInterval() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("报告区间", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntryId = appendHistoryEntry(run.getThreadId());

    assertThrows(
        ProjectValidationException.class,
        () ->
            issueRunService.finishRun(
                run.getId(),
                run.getVersion(),
                key("complete"),
                IssueRunStatus.COMPLETED,
                endEntryId,
                run.getStartEntryId(),
                "REVIEW",
                null));
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());
  }

  /** 失败允许空区间（接受后还没有任何产出就要能安全停止），并与 ERROR 暂停门禁同事务写入；恢复必须显式。 */
  @Test
  void terminalFailureAllowsEmptyIntervalAndWritesPauseGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("失败收尾", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun failedRun = issueRunService.acceptRun(issue.getId(), key("accept"));

    IssueRun failed =
        issueRunService.finishRun(
            failedRun.getId(),
            failedRun.getVersion(),
            key("fail"),
            IssueRunStatus.FAILED,
            failedRun.getStartEntryId(),
            null,
            null,
            "Run stopped with in-flight side effects; outcome unknown");

    assertEquals(IssueRunStatus.FAILED, failed.getStatus());
    assertEquals("Run stopped with in-flight side effects; outcome unknown", failed.getError());
    assertNotNull(failed.getEndedAt());
    assertEquals("ERROR", issueEditingService.getIssue(issue.getId()).getPauseReason());

    // 门禁关闭期间不自动重试：新 Run 被拒绝，必须由显式 resume 解除。
    assertThrows(
        ProjectValidationException.class,
        () -> issueRunService.acceptRun(issue.getId(), key("accept")));

    Issue afterFail = issueEditingService.getIssue(issue.getId());
    Issue resumed =
        issueLifecycleService.resumeIssue(afterFail.getId(), afterFail.getVersion(), key("resume"));
    assertNull(resumed.getPauseReason());
    assertEquals(
        IssueRunStatus.RUNNING,
        issueRunService.acceptRun(issue.getId(), key("accept")).getStatus());
  }

  /** 人工停止收尾为 CANCELLED 并保留 USER 暂停门禁，避免轮询立即启动新 Run。 */
  @Test
  void cancelRunKeepsUserPauseGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("停止", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntryId = appendHistoryEntry(run.getThreadId());

    IssueRun cancelled =
        issueRunService.finishRun(
            run.getId(),
            run.getVersion(),
            key("cancel"),
            IssueRunStatus.CANCELLED,
            endEntryId,
            null,
            null,
            null);

    assertEquals(IssueRunStatus.CANCELLED, cancelled.getStatus());
    assertEquals(endEntryId, cancelled.getEndEntryId());
    assertNull(cancelled.getError());
    assertEquals("USER", issueEditingService.getIssue(issue.getId()).getPauseReason());
    assertThrows(
        ProjectValidationException.class,
        () -> issueRunService.acceptRun(issue.getId(), key("accept")));
  }

  /** 失败与取消的 receipt 必须绑定精确终态字段，重试不重复写入或解除暂停。 */
  @Test
  void failedAndCancelledReceiptsUseExactHashesAndReplay() throws Exception {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("终态收据", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    for (IssueRunStatus status : List.of(IssueRunStatus.FAILED, IssueRunStatus.CANCELLED)) {
      String acceptKey = key("accept");
      IssueRun run = issueRunService.acceptRun(issue.getId(), acceptKey);
      String terminalKey = key("terminal");
      String error = status == IssueRunStatus.FAILED ? "failure detail" : null;
      UUID end = run.getStartEntryId();
      IssueRun finished =
          issueRunService.finishRun(
              run.getId(), run.getVersion(), terminalKey, status, end, null, null, error);
      Issue after = issueEditingService.getIssue(issue.getId());
      String canonical =
          (status == IssueRunStatus.FAILED ? "FAIL_RUN" : "CANCEL_RUN")
              + "\u001f"
              + run.getId()
              + "\u001f"
              + end
              + "\u001f"
              + (error == null ? "USER" : error);
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.getBytes(StandardCharsets.UTF_8)));
      assertEquals(
          hash,
          jdbc.queryForObject(
              "select request_hash from project_issue_activity where issue_id = ? and idempotency_key = ?",
              String.class,
              issue.getId(),
              "control:" + terminalKey));
      assertEquals(
          finished.getVersion(),
          issueRunService
              .finishRun(run.getId(), run.getVersion(), terminalKey, status, end, null, null, error)
              .getVersion());
      assertEquals(status, issueRunService.acceptRun(issue.getId(), acceptKey).getStatus());
      UUID differentEnd = UUID.randomUUID();
      assertThrows(
          ProjectDuplicateException.class,
          () ->
              issueRunService.finishRun(
                  run.getId(),
                  run.getVersion(),
                  terminalKey,
                  status,
                  differentEnd,
                  null,
                  null,
                  error));
      if (error != null) {
        assertThrows(
            ProjectDuplicateException.class,
            () ->
                issueRunService.finishRun(
                    run.getId(), run.getVersion(), terminalKey, status, end, null, null, "other"));
      }
      assertEquals(after.getVersion(), issueEditingService.getIssue(issue.getId()).getVersion());
      assertEquals(
          1L,
          count(
              "select count(*) from project_issue_activity where issue_id = ? and idempotency_key = ?",
              issue.getId(),
              "control:" + terminalKey));
      issueLifecycleService.resumeIssue(issue.getId(), after.getVersion(), key("resume"));
    }
  }

  /** WAITING/RUNNING 的重复置位是空重放：丢响应后的旧版本重试不再冲突，也不重复消耗额度或推进 Run 版本。 */
  @Test
  void waitAndResumeAreIdempotentOnLostResponseRetry() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("等待恢复", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
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

  /** 业务阻塞时不得恢复 Run；恢复阻塞后继续同一 Run，不消耗新额度。 */
  @Test
  void resumeRunRejectedWhileIssueBlocked() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("门禁恢复", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    IssueRun waiting = issueRunService.waitRun(run.getId(), run.getVersion());
    Issue waitingIssue = issueEditingService.getIssue(issue.getId());
    Issue blocked =
        issueLifecycleService.blockIssue(
            waitingIssue.getId(), waitingIssue.getVersion(), key("block"), "等待外部依赖");

    ProjectValidationException rejected =
        assertThrows(
            ProjectValidationException.class,
            () -> issueRunService.resumeRun(waiting.getId(), waiting.getVersion()));
    assertTrue(rejected.getMessage().contains("gate is closed"), rejected.getMessage());

    IssueRun unchanged = issueRunService.getRun(waiting.getId());
    assertEquals(IssueRunStatus.WAITING, unchanged.getStatus());
    assertNull(unchanged.getActiveSince());
    assertTrue(issueEditingService.getIssue(issue.getId()).isBlocked());
    assertEquals(1L, budgetUsed(issue.getId()));

    issueLifecycleService.recoverIssue(blocked.getId(), blocked.getVersion(), key("recover"));
    IssueRun resumed = issueRunService.resumeRun(unchanged.getId(), unchanged.getVersion());
    assertEquals(IssueRunStatus.RUNNING, resumed.getStatus());
    assertEquals(run.getId(), resumed.getId());
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

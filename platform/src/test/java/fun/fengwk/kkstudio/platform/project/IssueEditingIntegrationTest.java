package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 目标 Project/Issue 编辑、活动流与请求键幂等契约的集成测试（真实 PostgreSQL Testcontainers）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>Issue 编辑：需求事实（标题/描述）修改与版本推进、过期版本 CAS 拒绝、归档 Issue 禁止编辑。
 *   <li>Issue 归档/恢复：切换 {@code archived_at}、列表过滤成员变化、归档态拒绝执行与写操作、有活动 Run 时禁止归档、重放安全。
 *   <li>普通评论追加：写入单条 COMMENT 活动且绝对不生成/推进 Work 行（不唤醒 Agent）。
 *   <li>定向指示追加：有活动 Run 时记录 INSTRUCTION 活动并唤醒 Work；无活动 Run 时拒绝且不留下未来阶段队列。
 *   <li>活动流分页读取：按序号严格升序、严格 {@code > afterSequence} 过滤、有界 limit 截断与合法性校验。
 *   <li>Agent Thread 稳定绑定读取：仅返回属于当前 Issue 的稳定 Thread 绑定列表。
 *   <li>写操作请求键幂等（§4.6）：同键同指纹精确重放不重复写表且绕过版本校验；同键异指纹确定性冲突拒绝。
 *   <li>Project 配置与生命周期：配置修改与版本推进、归档项目禁止修改、归档/恢复切换、有 Issue 时禁止删除、删除清空行。
 * </ul>
 */
class IssueEditingIntegrationTest extends ProjectTestSupport {

  // =========================================================================
  // 1. updateIssue: 标题与描述修改、版本推进、CAS 冲突与归档保护
  // =========================================================================

  /** updateIssue 正常修改标题和描述并推进版本，数据库行同步更新。 */
  @Test
  void updateIssueEditsTitleAndDescriptionAndAdvancesVersion() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    assertEquals(0L, issue.getVersion());

    Issue updated = issueService.updateIssue(issue.getId(), 0L, "新标题-需求A", "新描述-详细内容说明");

    assertEquals("新标题-需求A", updated.getTitle());
    assertEquals("新描述-详细内容说明", updated.getDescription());
    assertEquals(1L, updated.getVersion());

    Map<String, Object> row =
        jdbc.queryForMap(
            "select title, description, version from project_issue where id = ?", issue.getId());
    assertEquals("新标题-需求A", row.get("title"));
    assertEquals("新描述-详细内容说明", row.get("description"));
    assertEquals(1L, ((Number) row.get("version")).longValue());
  }

  /** updateIssue 使用过期的 expectedVersion 被确定性拒绝，数据库行完全保持不变。 */
  @Test
  void updateIssueRejectsStaleExpectedVersionWithoutModifyingRow() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    String originalTitle = issue.getTitle();
    String originalDesc = issue.getDescription();

    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueService.updateIssue(issue.getId(), 999L, "冲突标题", "冲突描述"));

    Map<String, Object> row =
        jdbc.queryForMap(
            "select title, description, version from project_issue where id = ?", issue.getId());
    assertEquals(originalTitle, row.get("title"));
    assertEquals(originalDesc, row.get("description"));
    assertEquals(0L, ((Number) row.get("version")).longValue());
  }

  /** updateIssue 尝试修改已归档的 Issue 被确定性拒绝，数据库行不被修改。 */
  @Test
  void updateIssueRejectsArchivedIssueWithoutModifyingRow() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    Issue archived = issueService.archiveIssue(issue.getId(), 0L);
    assertEquals(1L, archived.getVersion());

    assertThrows(
        ProjectValidationException.class,
        () -> issueService.updateIssue(issue.getId(), 1L, "归档后修改", "描述"));

    Map<String, Object> row =
        jdbc.queryForMap("select title, version from project_issue where id = ?", issue.getId());
    assertEquals(issue.getTitle(), row.get("title"));
    assertEquals(1L, ((Number) row.get("version")).longValue());
  }

  // =========================================================================
  // 2. archiveIssue / unarchiveIssue: 归档状态切换、列表过滤与执行保护
  // =========================================================================

  /** archiveIssue 与 unarchiveIssue 切换 archived_at，并正确影响 listIssues(archived) 过滤结果。 */
  @Test
  void archiveAndUnarchiveIssueTogglesArchivedAtAndFilterMembership() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);

    assertEquals(1, issueService.listIssues(projectId, false).size());
    assertEquals(0, issueService.listIssues(projectId, true).size());
    assertEquals(
        0L,
        count(
            "select count(*) from project_issue where id = ? and archived_at is not null",
            issue.getId()));

    // 归档
    Issue archived = issueService.archiveIssue(issue.getId(), 0L);
    assertTrue(archived.isArchived());
    assertNotNull(archived.getArchivedAt());
    assertEquals(1L, archived.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue where id = ? and archived_at is not null",
            issue.getId()));
    assertEquals(0, issueService.listIssues(projectId, false).size());
    assertEquals(1, issueService.listIssues(projectId, true).size());

    // 恢复归档
    Issue unarchived = issueService.unarchiveIssue(issue.getId(), 1L);
    assertFalse(unarchived.isArchived());
    assertNull(unarchived.getArchivedAt());
    assertEquals(2L, unarchived.getVersion());
    assertEquals(
        0L,
        count(
            "select count(*) from project_issue where id = ? and archived_at is not null",
            issue.getId()));
    assertEquals(1, issueService.listIssues(projectId, false).size());
    assertEquals(0, issueService.listIssues(projectId, true).size());
  }

  /** 已归档的 Issue 拒绝执行流转、阻塞、评论、暂停等写操作。 */
  @Test
  void archivedIssueRejectsExecutionAndWorkflowWrites() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    issueService.archiveIssue(issue.getId(), 0L);

    assertThrows(
        ProjectValidationException.class,
        () -> issueService.transition(issue.getId(), 1L, key("t"), "WORK"));
    assertThrows(
        ProjectValidationException.class,
        () -> issueService.blockIssue(issue.getId(), 1L, key("b"), "阻塞原因"));
    assertThrows(
        ProjectValidationException.class,
        () -> issueService.appendComment(issue.getId(), 1L, key("c"), "普通评论"));
    assertThrows(
        ProjectValidationException.class,
        () -> issueService.pauseIssue(issue.getId(), 1L, key("p"), PauseReason.USER, "暂停详情"));

    assertEquals(
        0L, count("select count(*) from project_issue_activity where issue_id = ?", issue.getId()));
    assertEquals("INIT", issueService.getIssue(issue.getId()).getState());
    assertEquals(1L, issueService.getIssue(issue.getId()).getVersion());
  }

  /** 存在活动 Run 时拒绝归档 Issue。 */
  @Test
  void archiveIssueRejectsWhenActiveRunExists() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("活动Run归档保护", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueService.getIssue(issue.getId());
    IssueRun run = issueRunService.acceptRun(inDesign.getId(), key("accept"));
    assertEquals(IssueRunStatus.RUNNING, run.getStatus());

    Issue current = issueService.getIssue(issue.getId());
    assertThrows(
        ProjectValidationException.class,
        () -> issueService.archiveIssue(current.getId(), current.getVersion()));

    assertEquals(
        0L,
        count(
            "select count(*) from project_issue where id = ? and archived_at is not null",
            issue.getId()));
  }

  /** 当前版本重复归档是 no-op；陈旧版本即使目标已经归档也必须版本冲突。 */
  @Test
  void archiveReplayWhenAlreadyArchived() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);

    Issue archived = issueService.archiveIssue(issue.getId(), 0L);
    assertEquals(1L, archived.getVersion());
    assertTrue(archived.isArchived());

    Issue replayed = issueService.archiveIssue(issue.getId(), 1L);
    assertEquals(1L, replayed.getVersion());
    assertThrows(
        ProjectVersionConflictException.class, () -> issueService.archiveIssue(issue.getId(), 0L));
    assertTrue(replayed.isArchived());
    assertEquals(
        1L,
        count("select count(*) from project_issue where id = ? and version = 1", issue.getId()));
  }

  /** 当前版本重复恢复归档是 no-op；陈旧版本必须版本冲突，不能借 no-op 绕过 CAS。 */
  @Test
  void unarchiveReplayWhenAlreadyUnarchived() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    assertEquals(0L, issue.getVersion());

    // 对初始未归档状态的 Issue 调用 unarchiveIssue
    Issue replayed = issueService.unarchiveIssue(issue.getId(), 0L);
    assertEquals(0L, replayed.getVersion());
    assertFalse(replayed.isArchived());

    // 归档后再恢复归档
    issueService.archiveIssue(issue.getId(), 0L);
    Issue unarchived = issueService.unarchiveIssue(issue.getId(), 1L);
    assertEquals(2L, unarchived.getVersion());
    assertFalse(unarchived.isArchived());

    // 恢复归档后的重复调用直接返回当前事实
    Issue reUnarchived = issueService.unarchiveIssue(issue.getId(), 2L);
    assertEquals(2L, reUnarchived.getVersion());
    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueService.unarchiveIssue(issue.getId(), 1L));
    assertFalse(reUnarchived.isArchived());
  }

  // =========================================================================
  // 3. appendComment: 追加普通评论与 Work 隔离
  // =========================================================================

  /** appendComment 写入恰好一条 COMMENT 活动，推进 next_activity_sequence 与版本，且绝不产生 Work 行。 */
  @Test
  void appendCommentInsertsSingleActivityRowAndLeavesNoWorkRow() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);

    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_activity where issue_id = ?", issue.getId()));

    String commentKey = key("cmt");
    Issue afterComment = issueService.appendComment(issue.getId(), 0L, commentKey, "这是第一条需求讨论评论");

    assertEquals(1L, afterComment.getVersion());
    assertEquals(2L, afterComment.getNextActivitySequence());

    // 验证恰好写入 1 条 COMMENT 活动，且绝对不产生 Work 行
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
            issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));

    Map<String, Object> activityRow =
        jdbc.queryForMap(
            "select sequence, kind, actor_type, body, run_id, idempotency_key from"
                + " project_issue_activity where issue_id = ?",
            issue.getId());
    assertEquals(1L, ((Number) activityRow.get("sequence")).longValue());
    assertEquals("COMMENT", activityRow.get("kind"));
    assertEquals(IssueActivityActorType.HUMAN.name(), activityRow.get("actor_type"));
    assertEquals("这是第一条需求讨论评论", activityRow.get("body"));
    assertNull(activityRow.get("run_id"));
    assertEquals("comment:" + commentKey, activityRow.get("idempotency_key"));
  }

  // =========================================================================
  // 4. appendInstruction: 定向指示投递与无活动 Run 保护
  // =========================================================================

  /** 有活动 Run 时，appendInstruction 记录 INSTRUCTION 活动并唤醒 Work 调度。 */
  @Test
  void appendInstructionWithActiveRunRecordsActivityAndRequestsWork() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("指示投递", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueService.getIssue(issue.getId());
    IssueRun run = issueRunService.acceptRun(inDesign.getId(), key("accept"));
    assertEquals(IssueRunStatus.RUNNING, run.getStatus());

    long initialWorkWakeVersion =
        jdbc.queryForObject(
            "select wake_version from project_issue_work where issue_id = ?",
            Long.class,
            issue.getId());

    Issue current = issueService.getIssue(issue.getId());
    String instKey = key("inst");
    String instructionText = "请在方案中补充数据库索引设计";
    Issue updated =
        issueService.appendInstruction(
            current.getId(), current.getVersion(), instKey, instructionText);

    assertEquals(current.getVersion() + 1, updated.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'INSTRUCTION'"
                + " and run_id = ?",
            issue.getId(),
            run.getId()));

    Map<String, Object> activityRow =
        jdbc.queryForMap(
            "select sequence, kind, actor_type, body, run_id, idempotency_key from"
                + " project_issue_activity where issue_id = ? and kind = 'INSTRUCTION'",
            issue.getId());
    assertEquals("INSTRUCTION", activityRow.get("kind"));
    assertEquals(IssueActivityActorType.HUMAN.name(), activityRow.get("actor_type"));
    assertEquals(instructionText, activityRow.get("body"));
    assertEquals(run.getId(), activityRow.get("run_id"));
    assertEquals("instruction:" + instKey, activityRow.get("idempotency_key"));

    long newWorkWakeVersion =
        jdbc.queryForObject(
            "select wake_version from project_issue_work where issue_id = ?",
            Long.class,
            issue.getId());
    assertTrue(newWorkWakeVersion > initialWorkWakeVersion, "Work 邮箱应被唤醒以投递指示");
  }

  /** 无活动 Run 时，appendInstruction 拒绝执行且绝不静默排队未来阶段指令。 */
  @Test
  void appendInstructionWithoutActiveRunRejectsWithoutQueuingOrRecordingActivity() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);

    ProjectValidationException ex =
        assertThrows(
            ProjectValidationException.class,
            () ->
                issueService.appendInstruction(
                    issue.getId(), issue.getVersion(), key("inst"), "没有 Run 时的指示"));
    assertTrue(ex.getMessage().contains("Cannot deliver an instruction without an active run"));

    assertEquals(
        0L, count("select count(*) from project_issue_activity where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
  }

  // =========================================================================
  // 5. listActivities: 有界窗口、严格升序与分页过滤
  // =========================================================================

  /** listActivities 返回严格升序、严格大于 afterSequence 且受 limit 截断的活动流窗口。 */
  @Test
  void listActivitiesReturnsAscendingWindowStrictlyAfterSequenceAndBoundedByLimit() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);

    for (int i = 1; i <= 5; i++) {
      Issue current = issueService.getIssue(issue.getId());
      issueService.appendComment(
          current.getId(), current.getVersion(), key("cmt-" + i), "评论正文 " + i);
    }

    // 从头读取，limit 大于总条数
    List<IssueActivity> all = issueService.listActivities(issue.getId(), 0L, 10);
    assertEquals(5, all.size());
    for (int i = 0; i < 5; i++) {
      assertEquals((long) (i + 1), all.get(i).getSequence());
      assertEquals("评论正文 " + (i + 1), all.get(i).getBody());
      assertEquals(IssueActivityKind.COMMENT, all.get(i).getKind());
    }

    // 严格过滤：afterSequence = 2 且 limit = 2，应返回 [3, 4]
    List<IssueActivity> page2 = issueService.listActivities(issue.getId(), 2L, 2);
    assertEquals(2, page2.size());
    assertEquals(3L, page2.get(0).getSequence());
    assertEquals(4L, page2.get(1).getSequence());

    // afterSequence 到达末尾
    List<IssueActivity> emptyPage = issueService.listActivities(issue.getId(), 5L, 10);
    assertTrue(emptyPage.isEmpty());

    // 参数边界校验
    assertThrows(
        ProjectValidationException.class,
        () -> issueService.listActivities(issue.getId(), -1L, 10));
    assertThrows(
        ProjectValidationException.class, () -> issueService.listActivities(issue.getId(), 0L, 0));
    assertThrows(
        ProjectValidationException.class, () -> issueService.listActivities(issue.getId(), 0L, -5));
    assertThrows(
        ProjectNotFoundException.class,
        () -> issueService.listActivities(UUID.randomUUID(), 0L, 10));
  }

  // =========================================================================
  // 6. listAgentThreads: 稳定 Thread 绑定隔离读取
  // =========================================================================

  /** listAgentThreads 返回且仅返回属于本 Issue 的稳定 Agent Thread 绑定事实。 */
  @Test
  void listAgentThreadsReturnsStableBindingsForThisIssueOnly() {
    String agentA = createAgent();
    String agentB = createAgent();
    UUID projectId = createProjectWithStages("多Issue多Agent绑定", agentA, agentB, 3);

    // Issue 1：先跑 Agent A（DESIGN），再交接到 REVIEW 跑 Agent B
    Issue issue1 = createIssue(projectId);
    issueService.transition(issue1.getId(), issue1.getVersion(), key("t"), "DESIGN");
    IssueRun run1A = issueRunService.acceptRun(issue1.getId(), key("accept"));
    issueRunService.completeRun(
        run1A.getId(),
        run1A.getVersion(),
        key("c"),
        appendHistoryEntry(run1A.getThreadId()),
        null,
        "REVIEW");
    Issue inReview1 = issueService.getIssue(issue1.getId());
    IssueRun run1B = issueRunService.acceptRun(inReview1.getId(), key("accept"));

    // Issue 2：只跑 Agent A（DESIGN）
    Issue issue2 = createIssue(projectId);
    issueService.transition(issue2.getId(), issue2.getVersion(), key("t"), "DESIGN");
    IssueRun run2A = issueRunService.acceptRun(issue2.getId(), key("accept"));

    List<IssueAgentThread> threads1 = issueService.listAgentThreads(issue1.getId());
    assertEquals(2, threads1.size());
    assertTrue(
        threads1.stream()
            .anyMatch(
                t -> t.agentName().equals(agentA) && t.threadId().equals(run1A.getThreadId())));
    assertTrue(
        threads1.stream()
            .anyMatch(
                t -> t.agentName().equals(agentB) && t.threadId().equals(run1B.getThreadId())));

    List<IssueAgentThread> threads2 = issueService.listAgentThreads(issue2.getId());
    assertEquals(1, threads2.size());
    assertEquals(agentA, threads2.get(0).agentName());
    assertEquals(run2A.getThreadId(), threads2.get(0).threadId());

    // 确保与数据库行一一对应
    assertEquals(
        2L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id = ?", issue1.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id = ?", issue2.getId()));
    assertNotEquals(run1A.getThreadId(), run2A.getThreadId(), "不同 Issue 即使同一 Agent 也不共享 Thread");
  }

  // =========================================================================
  // 7. 幂等契约（§4.6）: 评论与阶段流转两个独立写操作的同键重放与冲突拒绝
  // =========================================================================

  /** appendComment 幂等：同键同指纹精确重试直接返回原 Issue 且不重复写入活动；同键异指纹确定性冲突。 */
  @Test
  void appendCommentIdempotencyReplaysExactAndRejectsReusedKeyWithConflict() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    String commentKey = key("cmt-idem");
    String commentBody = "幂等评论正文";

    // 首次写评论
    Issue firstResult = issueService.appendComment(issue.getId(), 0L, commentKey, commentBody);
    assertEquals(1L, firstResult.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
            issue.getId()));

    // 同键同指纹重试（模拟丢响应后重发）：即使传入旧 expectedVersion 也必须精确重放成功，不重复写活动行，版本不增加
    Issue replayedResult = issueService.appendComment(issue.getId(), 0L, commentKey, commentBody);
    assertEquals(1L, replayedResult.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
            issue.getId()));

    // 同键异指纹（请求键复用）：抛出 ProjectDuplicateException 冲突，且不写入新活动
    assertThrows(
        ProjectDuplicateException.class,
        () -> issueService.appendComment(issue.getId(), 1L, commentKey, "被篡改的不同评论正文"));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
            issue.getId()));
  }

  /** transition 幂等：同键同指纹精确重试直接返回已流转状态且不重复写入活动；同键异指纹确定性冲突。 */
  @Test
  void transitionIdempotencyReplaysExactAndRejectsReusedKeyWithConflict() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    String transitionKey = key("trans-idem");

    // 首次流转到 WORK
    Issue firstTransition = issueService.transition(issue.getId(), 0L, transitionKey, "WORK");
    assertEquals("WORK", firstTransition.getState());
    assertEquals(1L, firstTransition.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'STATE_CHANGE'",
            issue.getId()));

    // 同键同指纹重发（传入旧 expectedVersion 0）：精确重放，状态仍为 WORK，版本与活动数均不递增
    Issue replayedTransition = issueService.transition(issue.getId(), 0L, transitionKey, "WORK");
    assertEquals("WORK", replayedTransition.getState());
    assertEquals(1L, replayedTransition.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'STATE_CHANGE'",
            issue.getId()));

    // 同键异指纹（同一个 key 试图流转到 DONE 或不同目标状态）：抛出冲突，状态不改变
    assertThrows(
        ProjectDuplicateException.class,
        () -> issueService.transition(issue.getId(), 1L, transitionKey, "DONE"));
    assertEquals("WORK", issueService.getIssue(issue.getId()).getState());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'STATE_CHANGE'",
            issue.getId()));
  }

  /** blockIssue 幂等：同键同指纹精确重试直接返回 BLOCKED；同键异指纹抛出冲突。 */
  @Test
  void blockIssueIdempotencyReplaysExactAndRejectsReusedKeyWithConflict() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), 0L, key("t"), "WORK");
    Issue inWork = issueService.getIssue(issue.getId());
    String blockKey = key("block-idem");
    String reason = "等待下游第三方 API 开通";

    Issue blocked = issueService.blockIssue(inWork.getId(), inWork.getVersion(), blockKey, reason);
    assertEquals("BLOCKED", blocked.getState());
    assertEquals(2L, blocked.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'CONTROL'",
            issue.getId()));

    // 同键同指纹重试
    Issue replayed = issueService.blockIssue(inWork.getId(), 0L, blockKey, reason);
    assertEquals("BLOCKED", replayed.getState());
    assertEquals(2L, replayed.getVersion());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'CONTROL'",
            issue.getId()));

    // 同键异指纹（不同 reason）
    assertThrows(
        ProjectDuplicateException.class,
        () -> issueService.blockIssue(inWork.getId(), 2L, blockKey, "篡改后的阻塞原因"));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'CONTROL'",
            issue.getId()));
  }

  // =========================================================================
  // 8. Project 端: updateConfiguration / archiveProject / unarchiveProject / deleteProject
  // =========================================================================

  /** updateConfiguration 修改项目标题与描述并推进版本，过期版本 CAS 冲突。 */
  @Test
  void updateConfigurationEditsTitleAndDescriptionAndBumpsVersion() {
    Project project = projectService.createProject("初始项目标题", "初始描述", true);
    assertEquals(0L, project.getVersion());

    Project updated =
        projectService.updateConfiguration(project.getId(), 0L, "更新后的项目名", "更新后的项目描述");
    assertEquals("更新后的项目名", updated.getTitle());
    assertEquals("更新后的项目描述", updated.getDescription());
    assertEquals(1L, updated.getVersion());

    Map<String, Object> row =
        jdbc.queryForMap(
            "select title, description, version from project where id = ?", project.getId());
    assertEquals("更新后的项目名", row.get("title"));
    assertEquals("更新后的项目描述", row.get("description"));
    assertEquals(1L, ((Number) row.get("version")).longValue());

    // 过期版本 CAS 冲突
    assertThrows(
        ProjectVersionConflictException.class,
        () -> projectService.updateConfiguration(project.getId(), 0L, "再次更新", "描述"));
  }

  /** 归档项目拒绝配置修改、workflow 更新、YOLO 更新以及在其下创建/修改 Issue。 */
  @Test
  void archivedProjectRejectsEditsAndExecutionWrites() {
    Project project = projectService.createProject("待归档项目", "描述", true);
    Issue issue = createIssue(project.getId());
    String agent = createAgent();

    Project archived = projectService.archiveProject(project.getId(), 0L);
    assertTrue(archived.isArchived());
    assertNotNull(archived.getArchivedAt());
    assertEquals(1L, archived.getVersion());

    // 项目配置类修改被拒绝
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateConfiguration(project.getId(), 1L, "归档后标题", "归档后描述"));
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateWorkflow(project.getId(), 1L, workflowJson(agent, agent, 3)));
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateYolo(project.getId(), 1L, false));

    // 归档项目下的 Issue 写操作与新建 Issue 被拒绝
    assertThrows(
        ProjectValidationException.class,
        () -> issueService.createIssue(project.getId(), "新需求", "描述"));
    assertThrows(
        ProjectValidationException.class,
        () -> issueService.updateIssue(issue.getId(), 0L, "修改需求", "描述"));
  }

  /** archiveProject 与 unarchiveProject 切换项目归档状态并在 listProjects(archived) 间迁移。 */
  @Test
  void archiveAndUnarchiveProjectTogglesArchivedAtAndFilterMembership() {
    Project project = projectService.createProject("生命周期项目", "描述", true);

    assertTrue(
        projectService.listProjects(false).stream()
            .anyMatch(p -> p.getId().equals(project.getId())));
    assertFalse(
        projectService.listProjects(true).stream()
            .anyMatch(p -> p.getId().equals(project.getId())));

    // 归档
    Project archived = projectService.archiveProject(project.getId(), 0L);
    assertTrue(archived.isArchived());
    assertFalse(
        projectService.listProjects(false).stream()
            .anyMatch(p -> p.getId().equals(project.getId())));
    assertTrue(
        projectService.listProjects(true).stream()
            .anyMatch(p -> p.getId().equals(project.getId())));

    // 当前版本重复归档返回当前事实；陈旧版本冲突。
    Project reArchived = projectService.archiveProject(project.getId(), 1L);
    assertTrue(reArchived.isArchived());
    assertEquals(1L, reArchived.getVersion());
    assertThrows(
        ProjectVersionConflictException.class,
        () -> projectService.archiveProject(project.getId(), 0L));

    // 恢复归档
    Project unarchived = projectService.unarchiveProject(project.getId(), 1L);
    assertFalse(unarchived.isArchived());
    assertTrue(
        projectService.listProjects(false).stream()
            .anyMatch(p -> p.getId().equals(project.getId())));
    assertFalse(
        projectService.listProjects(true).stream()
            .anyMatch(p -> p.getId().equals(project.getId())));

    // 当前版本重复恢复归档返回当前事实；陈旧版本冲突。
    Project reUnarchived = projectService.unarchiveProject(project.getId(), 2L);
    assertFalse(reUnarchived.isArchived());
    assertEquals(2L, reUnarchived.getVersion());
    assertThrows(
        ProjectVersionConflictException.class,
        () -> projectService.unarchiveProject(project.getId(), 1L));
  }

  /** 下属 Issue 存在活动 Run 时拒绝归档项目。 */
  @Test
  void archiveProjectRejectsWhenIssueHasActiveRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("活动Run项目归档", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueService.getIssue(issue.getId());
    issueRunService.acceptRun(inDesign.getId(), key("accept"));

    Project current = projectService.getProject(projectId);
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.archiveProject(current.getId(), current.getVersion()));

    assertEquals(
        0L,
        count("select count(*) from project where id = ? and archived_at is not null", projectId));
  }

  /**
   * 并发同键同 payload 必须在 owner 锁内重放：两个请求都返回同一 Issue，且只留下一条评论活动。
   *
   * <p>只在锁前查 receipt 时，后到的请求会穿过重放窗口，在版本校验处误报 409。
   */
  @Test
  void concurrentSameCommentKeyAndPayloadReplaysAfterOwnerLock() throws Exception {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    String commentKey = key("cmt");
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Issue> first =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return issueService.appendComment(issue.getId(), 0L, commentKey, "同一条评论");
              });
      Future<Issue> second =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return issueService.appendComment(issue.getId(), 0L, commentKey, "同一条评论");
              });
      Issue left = first.get(15, TimeUnit.SECONDS);
      Issue right = second.get(15, TimeUnit.SECONDS);
      assertEquals(left.getId(), right.getId());
      assertEquals(left.getVersion(), right.getVersion());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and idempotency_key = ?",
            issue.getId(),
            "comment:" + commentKey));
  }

  /** 并发同键但正文不同必须稳定冲突，且只保留先提交的那一条评论。 */
  @Test
  void concurrentSameCommentKeyWithDifferentPayloadConflicts() throws Exception {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    String commentKey = key("cmt");
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> first =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return invokeComment(issue.getId(), commentKey, "第一条");
              });
      Future<Object> second =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return invokeComment(issue.getId(), commentKey, "第二条");
              });
      Object left = first.get(15, TimeUnit.SECONDS);
      Object right = second.get(15, TimeUnit.SECONDS);
      assertTrue(left instanceof Issue ^ right instanceof Issue);
      assertTrue(
          left instanceof ProjectDuplicateException || right instanceof ProjectDuplicateException);
    } finally {
      executor.shutdownNow();
    }
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and idempotency_key = ?",
            issue.getId(),
            "comment:" + commentKey));
  }

  private Object invokeComment(UUID issueId, String commentKey, String body) {
    try {
      return issueService.appendComment(issueId, 0L, commentKey, body);
    } catch (ProjectDuplicateException conflict) {
      return conflict;
    }
  }

  /**
   * 同键重放必须在观察到 receipt 之后返回权威当前事实：并发首次提交落在「锁前已读到旧快照」与「锁前 receipt 检查」之间时，重放绝不能把本事务早先读到的旧 Issue
   * 当作当前版本返回（MyBatis 一级缓存会遮蔽并发已提交的版本推进）。
   *
   * <p>本用例用 spy 把第二个请求精确停在锁前 receipt 检查上，等第一个请求完整提交后再放行，因此是与 {@link
   * #concurrentSameCommentKeyAndPayloadReplaysAfterOwnerLock()} 同一交错窗口的确定性复现，而不是概率竞争。
   */
  @Test
  void concurrentSameCommentKeyReplaysCommittedVersionWhenReceiptArrivesAfterFirstRead()
      throws Exception {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    String commentKey = key("cmt");
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
    AtomicReference<Issue> replayed = new AtomicReference<>();
    AtomicReference<Throwable> replayedError = new AtomicReference<>();
    Thread replayer =
        new Thread(
            () -> {
              gated.set(true);
              try {
                replayed.set(issueService.appendComment(issue.getId(), 0L, commentKey, "同一条评论"));
              } catch (Throwable error) {
                replayedError.set(error);
              }
            });
    replayer.start();
    try {
      assertTrue(awaitingReceipt.await(10, TimeUnit.SECONDS), "第二个请求应停在锁前 receipt 检查");
      // 第一个请求在读走旧快照之后完整提交：数据库版本与 receipt 一起推进。
      Issue first = issueService.appendComment(issue.getId(), 0L, commentKey, "同一条评论");
      assertEquals(1L, first.getVersion());
      firstCommitted.countDown();
      replayer.join(15_000);
    } finally {
      firstCommitted.countDown();
      replayer.join(15_000);
      gated.remove();
    }
    assertFalse(replayer.isAlive(), "重放请求不应悬挂");
    assertNull(replayedError.get());
    assertEquals(issue.getId(), replayed.get().getId());
    assertEquals(1L, replayed.get().getVersion(), "重放必须返回已提交的权威版本");
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and idempotency_key = ?",
            issue.getId(),
            "comment:" + commentKey));
  }

  /** deleteProject 深删除项目及其下属 Issue；过期版本 CAS 冲突。 */
  @Test
  void deleteProjectDeletesRowAndRejectsWhenOwningIssuesOrStaleVersion() {
    // 拥有 Issue 的项目支持深删除
    Project projectWithIssue = projectService.createProject("含Issue项目", "描述", true);
    Issue issue = createIssue(projectWithIssue.getId());
    projectService.deleteProject(projectWithIssue.getId(), projectWithIssue.getVersion());
    assertEquals(0L, count("select count(*) from project_issue where id = ?", issue.getId()));
    assertEquals(0L, count("select count(*) from project where id = ?", projectWithIssue.getId()));

    // 无 Issue 的项目支持删除
    Project cleanProject = projectService.createProject("空项目", "描述", true);
    // 过期版本拒绝
    assertThrows(
        ProjectVersionConflictException.class,
        () -> projectService.deleteProject(cleanProject.getId(), 999L));
    assertEquals(1L, count("select count(*) from project where id = ?", cleanProject.getId()));

    // 正确版本删除
    projectService.deleteProject(cleanProject.getId(), cleanProject.getVersion());
    assertEquals(0L, count("select count(*) from project where id = ?", cleanProject.getId()));
    assertThrows(
        ProjectNotFoundException.class, () -> projectService.getProject(cleanProject.getId()));
  }
}

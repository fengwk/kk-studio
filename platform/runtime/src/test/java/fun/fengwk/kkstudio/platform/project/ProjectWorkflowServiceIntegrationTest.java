package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;

import java.util.UUID;

/**
 * Project workflow 配置的整体保存、严格校验与版本 CAS。
 *
 * <p>意图：workflow 是唯一事实源，必须经严格 JSON 编解码；非法配置整体回滚（不留半成品），CAS 失败确定性冲突，活动 Run 期间禁止改职责。
 */
class ProjectWorkflowServiceIntegrationTest extends ProjectTestSupport {

  private final ProjectWorkflowJsonCodec codec = new ProjectWorkflowJsonCodec();

  /** 创建项目提供可解码的 INIT→WORK→DONE 默认工作流，并可由 get 读回同一事实。 */
  @Test
  void createProjectProvidesDecodableDefaultWorkflow() {
    Project project = projectService.createProject("默认流程", "描述", true);

    assertNotNull(project.getId());
    ProjectWorkflow workflow =
        codec.decode(projectService.getProject(project.getId()).getWorkflowJson());
    assertEquals(4, workflow.states().size());
    assertTrue(workflow.find(ProjectStateCode.of("WORK")).isPresent());
    assertEquals(0L, project.getVersion());
  }

  /** 合法 workflow 整体替换后按规范文本落库并推进版本；再次读取得到等价配置。 */
  @Test
  void updateWorkflowPersistsCanonicalConfigAndBumpsVersion() {
    Project project = projectService.createProject("配置更新", "描述", true);
    String agent = createAgent();
    String json = workflowJson(agent, agent, 3);

    Project updated = projectService.updateWorkflow(project.getId(), project.getVersion(), json);

    assertEquals(1L, updated.getVersion());
    ProjectWorkflow decoded = codec.decode(updated.getWorkflowJson());
    assertEquals(json, codec.encode(decoded));
    assertEquals(agent, decoded.require(ProjectStateCode.of("DESIGN")).agent());
  }

  /** 非法 workflow（自身边 / 未知字段 / 缺失保留状态）确定性拒绝，且不留下部分写入。 */
  @Test
  void updateWorkflowRejectsInvalidConfigWithoutPartialWrite() {
    Project project = projectService.createProject("非法配置", "描述", true);
    String agent = createAgent();
    String original = project.getWorkflowJson();

    String selfEdge = workflowJson(agent, agent, 3).replace("[\"DESIGN\"]", "[\"INIT\"]");
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateWorkflow(project.getId(), project.getVersion(), selfEdge));

    String unknownField =
        workflowJson(agent, agent, 3).replace("\"name\":\"设计\"", "\"name\":\"设计\",\"bogus\":1");
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateWorkflow(project.getId(), project.getVersion(), unknownField));

    String missingDone = "{\"states\":[{\"state\":\"INIT\",\"name\":\"待开始\"}]}";
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateWorkflow(project.getId(), project.getVersion(), missingDone));

    assertEquals(0L, projectService.getProject(project.getId()).getVersion());
    assertEquals(original, projectService.getProject(project.getId()).getWorkflowJson());
  }

  /** 过期版本 CAS 冲突，且不改变已提交配置。 */
  @Test
  void updateWorkflowRejectsStaleVersion() {
    Project project = projectService.createProject("CAS", "描述", true);
    String agent = createAgent();
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));

    assertThrows(
        ProjectVersionConflictException.class,
        () ->
            projectService.updateWorkflow(
                project.getId(), project.getVersion(), workflowJson(agent, agent, 5)));
  }

  /**
   * 现存 Issue 正在使用的工作阶段、阻塞来源和已归档引用都不能从新 workflow 中消失。
   *
   * <p>没有被任何 Issue 引用的阶段可以删除；保留状态本来就必须存在。
   */
  @Test
  void updateWorkflowRejectsRemovalOfReferencedStatesIncludingArchivedIssues() {
    String agent = createAgent();
    Project project = projectService.createProject("引用完整性", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));
    Issue live = createIssue(project.getId());
    issueService.transition(live.getId(), live.getVersion(), key("t"), "DESIGN");
    Issue blocked =
        issueService.blockIssue(
            issueService.getIssue(live.getId()).getId(),
            issueService.getIssue(live.getId()).getVersion(),
            key("block"),
            "等待外部");
    Issue archived = createIssue(project.getId());
    issueService.transition(archived.getId(), archived.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueService.getIssue(archived.getId());
    IssueRun archivedRun = issueRunService.acceptRun(inDesign.getId(), key("accept"));
    UUID archivedEnd = appendHistoryEntry(archivedRun.getThreadId());
    issueRunService.completeRun(
        archivedRun.getId(),
        archivedRun.getVersion(),
        key("complete"),
        archivedEnd,
        null,
        "REVIEW");
    Issue inReview = issueService.getIssue(archived.getId());
    issueService.archiveIssue(inReview.getId(), inReview.getVersion());
    Project current = projectService.getProject(project.getId());
    String withoutDesign =
        "{"
            + "\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"REVIEW\"]},"
            + "{\"state\":\"REVIEW\",\"name\":\"检查\",\"agent\":\""
            + agent
            + "\",\"instructions\":\"检查\",\"maxRuns\":1,\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}";
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateWorkflow(current.getId(), current.getVersion(), withoutDesign));

    String withoutReview =
        "{"
            + "\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
            + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\""
            + agent
            + "\",\"instructions\":\"完成可交付方案\",\"maxRuns\":3,\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}";
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.updateWorkflow(current.getId(), current.getVersion(), withoutReview));
    assertEquals(
        blocked.getBlockedFromState(),
        issueService.getIssue(blocked.getId()).getBlockedFromState());
    assertEquals("REVIEW", issueService.getIssue(archived.getId()).getState());
    assertEquals(
        current.getWorkflowJson(), projectService.getProject(project.getId()).getWorkflowJson());
  }

  /** 未被任何现存 Issue 引用的工作阶段可以删除，保留状态仍然留在新 workflow 中。 */
  @Test
  void updateWorkflowAllowsRemovingUnreferencedStage() {
    String agent = createAgent();
    Project project = projectService.createProject("删除空阶段", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));
    Issue issue = createIssue(project.getId());
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Project current = projectService.getProject(project.getId());
    String withoutReview =
        "{"
            + "\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
            + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\""
            + agent
            + "\",\"instructions\":\"完成可交付方案\",\"maxRuns\":3,\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}";

    Project updated =
        projectService.updateWorkflow(current.getId(), current.getVersion(), withoutReview);

    assertEquals(
        withoutReview,
        new ProjectWorkflowJsonCodec().encode(codec.decode(updated.getWorkflowJson())));
    assertEquals("DESIGN", issueService.getIssue(issue.getId()).getState());
  }

  /** 存在活动主 Run 时拒绝影响职责/结构的工作流调整。 */
  @Test
  void updateWorkflowRejectedWhileIssueHasActiveRun() {
    String agent = createAgent();
    Project project = projectService.createProject("活动 Run", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));
    Issue issue = createIssue(project.getId());
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueService.getIssue(issue.getId());
    issueRunService.acceptRun(inDesign.getId(), key("accept"));

    Project current = projectService.getProject(project.getId());
    assertThrows(
        ProjectValidationException.class,
        () ->
            projectService.updateWorkflow(
                current.getId(), current.getVersion(), workflowJson(agent, agent, 9)));
  }

  /** 同键同指纹是精确重试：旧版本重试返回已提交事实，不重复写活动、不改变门禁。 */
  @Test
  void sameRequestKeyAndPayloadReplaysWithoutNewWrites() {
    String agent = createAgent();
    Project project = projectService.createProject("精确重试", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));
    Issue issue = createIssue(project.getId());
    String pauseKey = key("pause");
    Issue paused =
        issueService.pauseIssue(
            issue.getId(), issue.getVersion(), pauseKey, PauseReason.USER, "人工暂停");
    long activityCount = activityCount(issue.getId());

    // 用第一次请求前的旧版本重试：精确重试先于版本校验，因此必须成功且返回同一事实。
    Issue replayed = issueService.pauseIssue(issue.getId(), 0L, pauseKey, PauseReason.USER, "人工暂停");

    assertEquals("USER", replayed.getPauseReason());
    assertEquals("人工暂停", replayed.getPauseDetail());
    assertEquals(paused.getVersion(), replayed.getVersion());
    assertEquals(activityCount, activityCount(issue.getId()));
  }

  /** 状态写同样按请求键精确重放：旧版本重试不重复写 STATE_CHANGE，也不重复改变阶段。 */
  @Test
  void stageTransitionReplaysWithoutDuplicateStateChange() {
    String agent = createAgent();
    Project project = projectService.createProject("阶段重放", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));
    Issue issue = createIssue(project.getId());
    String transitionKey = key("t");
    Issue inDesign =
        issueService.transition(issue.getId(), issue.getVersion(), transitionKey, "DESIGN");
    long activityCount = activityCount(issue.getId());

    Issue replayed = issueService.transition(issue.getId(), 0L, transitionKey, "DESIGN");

    assertEquals("DESIGN", replayed.getState());
    assertEquals(inDesign.getVersion(), replayed.getVersion());
    assertEquals(activityCount, activityCount(issue.getId()));
  }

  /** 缺失的同键活动不构成重放：新请求仍必须通过迁移校验与版本校验。 */
  @Test
  void unknownRequestKeyStillValidatesTransitionAndVersion() {
    String agent = createAgent();
    Project project = projectService.createProject("非法迁移", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));
    Issue issue = createIssue(project.getId());

    assertThrows(
        ProjectValidationException.class,
        () -> issueService.transition(issue.getId(), issue.getVersion(), key("t"), "REVIEW"));
    Issue inDesign = issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueService.transition(issue.getId(), issue.getVersion(), key("t"), "REVIEW"));
    assertEquals("DESIGN", inDesign.getState());
  }

  /** 同键异指纹是请求键复用：确定性冲突，且不追加活动、不改变已提交事实。 */
  @Test
  void sameRequestKeyWithDifferentPayloadConflictsWithoutWrites() {
    String agent = createAgent();
    Project project = projectService.createProject("键复用", "描述", true);
    projectService.updateWorkflow(
        project.getId(), project.getVersion(), workflowJson(agent, agent, 3));
    Issue issue = createIssue(project.getId());
    String pauseKey = key("pause");
    Issue paused =
        issueService.pauseIssue(
            issue.getId(), issue.getVersion(), pauseKey, PauseReason.USER, "第一次暂停");
    long activityCount = activityCount(issue.getId());

    Issue stillPaused = issueService.getIssue(issue.getId());
    assertThrows(
        ProjectDuplicateException.class,
        () ->
            issueService.pauseIssue(
                stillPaused.getId(),
                stillPaused.getVersion(),
                pauseKey,
                PauseReason.USER,
                "第二次暂停"));

    Issue after = issueService.getIssue(issue.getId());
    assertEquals("第一次暂停", after.getPauseDetail());
    assertEquals(stillPaused.getVersion(), after.getVersion());
    assertEquals(activityCount, activityCount(issue.getId()));
    assertEquals(paused.getVersion(), after.getVersion());
  }

  private long activityCount(UUID issueId) {
    return count("select count(*) from project_issue_activity where issue_id = ?", issueId);
  }
}

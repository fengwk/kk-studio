package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 角色工具（{@code issue_read} / {@code issue_request_input} / {@code issue_review}）的业务执行。
 *
 * <p>每次调用都按 owner context 冻结的 {@code runId} 重新核验活动 Run 与 Agent 身份：归属随 IssueAgentSession 稳定存在，权限随当前
 * Run 改变，旧 Run 的迟到调用不借用新 Run 权限。Issue/Project 正文只作为工具结果的业务输入返回，不提升为 systemInstruction。
 */
@Service
public class ProjectRoleToolService {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  /** 未显式指定时的 Activity 分页大小。 */
  private static final int DEFAULT_ACTIVITY_LIMIT = 50;

  /** 单次 {@code issue_read} 允许读取的 Activity 上限，绝不无界加载整条事实流。 */
  private static final int MAX_ACTIVITY_LIMIT = 200;

  private final ProjectService projectService;
  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final IssueEvidenceService issueEvidenceService;

  public ProjectRoleToolService(
      ProjectService projectService,
      IssueService issueService,
      IssueRunService issueRunService,
      IssueEvidenceService issueEvidenceService) {
    this.projectService = Objects.requireNonNull(projectService, "projectService");
    this.issueService = Objects.requireNonNull(issueService, "issueService");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.issueEvidenceService =
        Objects.requireNonNull(issueEvidenceService, "issueEvidenceService");
  }

  /**
   * 读取当前 Issue 的要求、验收依据、所属 Project 摘要、依赖、本 Run 摘要与分页 Activity。
   *
   * <p>可选的 {@code issueId} 只用于确认调用方指向的就是当前 Issue，不接受跨 Issue 读取。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> issueRead(
      ProjectThreadOwnerContext owner, UUID requestedIssueId, Long afterSequence, Integer limit) {
    IssueRun run = requireActiveRun(owner);
    if (requestedIssueId != null && !owner.issueId().equals(requestedIssueId)) {
      throw new IllegalArgumentException("issue_id must match the current issue context");
    }
    long after = requireAfterSequence(afterSequence);
    int pageSize = requireActivityLimit(limit);

    Issue issue = issueService.getIssue(owner.issueId());
    requireIssue(owner, issue);
    Project project = projectService.getProject(owner.projectId());
    requireProject(owner, project);

    List<IssueActivity> page =
        issueService.listActivitiesPage(owner.issueId(), after, pageSize + 1);
    boolean hasMore = page.size() > pageSize;
    List<IssueActivity> items = hasMore ? page.subList(0, pageSize) : page;
    long nextAfterSequence = items.isEmpty() ? after : items.get(items.size() - 1).getSequence();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("project", toProjectMap(project));
    data.put("issue", toIssueMap(issue, issueService.countRejections(owner.issueId()), project));
    data.put("run", toRunMap(run));
    if (run.getSubmissionRunId() != null) {
      data.put("submission_run", toRunMap(requireSubmissionRun(owner, run.getSubmissionRunId())));
    }
    data.put("agent_session", toAgentSessionMap(run));
    data.put(
        "evidence",
        issueEvidenceService.listEvidence(owner.issueId()).stream()
            .map(ProjectRoleToolService::toEvidenceMap)
            .toList());
    data.put(
        "dependencies",
        issueService.listDependencies(owner.issueId()).stream()
            .map(dependency -> toDependencyMap(owner, dependency))
            .sorted(Comparator.comparingLong(entry -> (Long) entry.get("number")))
            .toList());
    data.put("activities", toActivityPage(after, nextAfterSequence, hasMore, items));
    return data;
  }

  /** 请求人工输入：把当前 RUNNING Run 转入 WAITING_HUMAN，并把提问作为有来源的 Activity 记录。 */
  @Transactional
  public Map<String, Object> issueRequestInput(
      ProjectThreadOwnerContext owner, String question, String context) {
    IssueRun run = requireActiveRun(owner);
    IssueRun updated = issueRunService.requestInput(run.getId(), question, context);
    requireUpdatedRun(owner, updated);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("run", toRunMap(updated));
    return data;
  }

  /** 提交正式审查决定：绑定当前被审查提交，由业务事务完成阈值判定与状态迁移。 */
  @Transactional
  public Map<String, Object> issueReview(
      ProjectThreadOwnerContext owner,
      String terminalActionId,
      ReviewDecision decision,
      String reason) {
    IssueRun run = requireActiveRun(owner);
    if (!run.isReviewer() || run.getSubmissionRunId() == null) {
      throw new IllegalArgumentException(
          "issue_review requires the current REVIEWER run bound to a submission");
    }
    IssueRun updated =
        issueRunService.reviewByAgent(
            run.getId(), owner.agentName(), terminalActionId, decision, reason);
    requireUpdatedRun(owner, updated);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("run", toRunMap(updated));
    Issue issue = issueService.getIssue(owner.issueId());
    requireIssue(owner, issue);
    Project project = projectService.getProject(owner.projectId());
    requireProject(owner, project);
    data.put("issue", toIssueMap(issue, issueService.countRejections(owner.issueId()), project));
    return data;
  }

  /**
   * 按 owner context 的 {@code runId} 重新核验活动 Run：Run 必须仍属于该 Issue 与 Agent 身份且未终结。
   *
   * <p>归属稳定性与调用期权限分离——Session 长期存在，权限只对当前活动 Run 生效。
   */
  private IssueRun requireActiveRun(ProjectThreadOwnerContext owner) {
    Objects.requireNonNull(owner, "owner");
    IssueRun run = issueRunService.getRun(owner.runId());
    if (!owner.issueId().equals(run.getIssueId())
        || !Objects.equals(owner.agentName(), run.getAgentName())
        || (run.isExecutor() ? ProjectRole.EXECUTOR : ProjectRole.REVIEWER) != owner.role()
        || !run.isActive()) {
      throw inconsistent();
    }
    return run;
  }

  private IssueRun requireSubmissionRun(ProjectThreadOwnerContext owner, UUID submissionRunId) {
    IssueRun submission = issueRunService.getRun(submissionRunId);
    if (!owner.issueId().equals(submission.getIssueId()) || !submission.isExecutor()) {
      throw inconsistent();
    }
    return submission;
  }

  private void requireUpdatedRun(ProjectThreadOwnerContext owner, IssueRun run) {
    if (run == null
        || !owner.runId().equals(run.getId())
        || !owner.issueId().equals(run.getIssueId())
        || !Objects.equals(owner.agentName(), run.getAgentName())) {
      throw inconsistent();
    }
  }

  private void requireIssue(ProjectThreadOwnerContext owner, Issue issue) {
    if (issue == null
        || !owner.issueId().equals(issue.getId())
        || !owner.projectId().equals(issue.getProjectId())) {
      throw inconsistent();
    }
  }

  private void requireProject(ProjectThreadOwnerContext owner, Project project) {
    if (project == null || !owner.projectId().equals(project.getId())) {
      throw inconsistent();
    }
  }

  private static long requireAfterSequence(Long afterSequence) {
    if (afterSequence == null) {
      return 0L;
    }
    if (afterSequence < 0) {
      throw new IllegalArgumentException("activity_after_sequence must not be negative");
    }
    return afterSequence;
  }

  private static int requireActivityLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_ACTIVITY_LIMIT;
    }
    if (limit < 1 || limit > MAX_ACTIVITY_LIMIT) {
      throw new IllegalArgumentException(
          "activity_limit must be between 1 and " + MAX_ACTIVITY_LIMIT);
    }
    return limit;
  }

  private Map<String, Object> toActivityPage(
      long afterSequence, long nextAfterSequence, boolean hasMore, List<IssueActivity> items) {
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("after_sequence", afterSequence);
    page.put("next_after_sequence", nextAfterSequence);
    page.put("has_more", hasMore);
    List<Map<String, Object>> activities = new ArrayList<>(items.size());
    for (IssueActivity activity : items) {
      activities.add(toActivityMap(activity));
    }
    page.put("items", activities);
    return page;
  }

  private Map<String, Object> toActivityMap(IssueActivity activity) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("sequence", activity.getSequence());
    map.put("kind", name(activity.getKind()));
    map.put("actor_type", name(activity.getActorType()));
    map.put("actor_agent_name", activity.getActorAgentName());
    map.put("target_role", name(activity.getTargetRole()));
    map.put("run_id", text(activity.getRunId()));
    map.put("submission_run_id", text(activity.getSubmissionRunId()));
    map.put("decision", name(activity.getDecision()));
    map.put("body", activity.getBody());
    map.put("created_at", text(activity.getCreatedAt()));
    return map;
  }

  private Map<String, Object> toProjectMap(Project project) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", project.getId().toString());
    map.put("title", project.getTitle());
    map.put("description", project.getDescription());
    map.put("yolo_enabled", project.isYoloEnabled());
    map.put("max_review_rejections", project.getMaxReviewRejections());
    map.put("archived", project.isArchived());
    return map;
  }

  private Map<String, Object> toIssueMap(Issue issue, long rejectionCount, Project project) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", issue.getId().toString());
    map.put("project_id", issue.getProjectId().toString());
    map.put("number", issue.getNumber());
    map.put("title", issue.getTitle());
    map.put("description", issue.getDescription());
    map.put("status", name(issue.getStatus()));
    map.put("assignee_agent_name", issue.getAssigneeAgentName());
    map.put("reviewer_agent_name", issue.getReviewerAgentName());
    map.put("rejection_count", rejectionCount);
    map.put("max_review_rejections", project.getMaxReviewRejections());
    map.put("archived", issue.isArchived());
    map.put("version", issue.getVersion());
    map.put("created_at", text(issue.getCreatedAt()));
    map.put("updated_at", text(issue.getUpdatedAt()));
    return map;
  }

  private Map<String, Object> toAgentSessionMap(IssueRun run) {
    Map<String, Object> map = new LinkedHashMap<>();
    IssueAgentSession agentSession =
        issueRunService.getAgentSession(run.getIssueId(), run.getAgentName());
    if (agentSession != null) {
      map.put("agent_name", agentSession.getAgentName());
      map.put("session_id", text(agentSession.getSessionId()));
      map.put("branch_id", text(agentSession.getThreadId()));
    }
    return map;
  }

  private static Map<String, Object> toEvidenceMap(IssueEvidence evidence) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("uri", SessionResourceUri.format(evidence.getBlobId()));
    map.put("origin", name(evidence.getOrigin()));
    map.put("name", evidence.getName());
    map.put("run_id", text(evidence.getRunId()));
    map.put("published_at", text(evidence.getCreatedAt()));
    return map;
  }

  private Map<String, Object> toDependencyMap(
      ProjectThreadOwnerContext owner, IssueDependency dependency) {
    if (dependency == null
        || !owner.issueId().equals(dependency.getIssueId())
        || !owner.projectId().equals(dependency.getProjectId())
        || dependency.getDependsOnIssueId() == null) {
      throw inconsistent();
    }
    Issue target = issueService.getIssue(dependency.getDependsOnIssueId());
    if (target == null
        || !dependency.getDependsOnIssueId().equals(target.getId())
        || !owner.projectId().equals(target.getProjectId())) {
      throw inconsistent();
    }
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("issue_id", target.getId().toString());
    map.put("number", target.getNumber());
    map.put("title", target.getTitle());
    map.put("status", name(target.getStatus()));
    map.put("archived", target.isArchived());
    map.put("satisfied", target.getStatus() == IssueStatus.DONE);
    return map;
  }

  private Map<String, Object> toRunMap(IssueRun run) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", run.getId().toString());
    map.put("issue_id", run.getIssueId().toString());
    map.put("ordinal", run.getOrdinal());
    map.put("role", name(run.getRole()));
    map.put("agent_name", run.getAgentName());
    map.put("submission_run_id", text(run.getSubmissionRunId()));
    map.put("status", name(run.getStatus()));
    map.put("outcome", name(run.getOutcome()));
    map.put("observed_activity_sequence", run.getObservedActivitySequence());
    map.put("continuation_count", run.getContinuationCount());
    map.put("max_continuations", run.getMaxContinuations());
    map.put("deadline", text(run.getDeadline()));
    map.put("waiting_reason", run.getWaitingReason());
    map.put("result", run.getResult());
    map.put("created_at", text(run.getCreatedAt()));
    map.put("completed_at", text(run.getCompletedAt()));
    return map;
  }

  private static String name(Enum<?> value) {
    return value != null ? value.name() : null;
  }

  private static String text(Object value) {
    return value != null ? value.toString() : null;
  }

  private static IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
  }
}

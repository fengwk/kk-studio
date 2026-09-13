package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueService;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 线程感知的 Project 动态角色系统上下文投影器。
 *
 * <p>供 R1 注入 system context。根据 Thread 反查 durable owner，并在交付围栏内（sequence &lt;=
 * observedInputSequence）投影结构化角色上下文与游标指引。
 */
@Component
public class ProjectRoleContextProjector {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  private final ProjectThreadOwnerResolver ownerResolver;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueDependencyRepository issueDependencyRepository;
  private final IssueInputRepository issueInputRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueService issueService;

  public ProjectRoleContextProjector(
      ProjectThreadOwnerResolver ownerResolver,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueDependencyRepository issueDependencyRepository,
      IssueInputRepository issueInputRepository,
      IssueRunRepository issueRunRepository,
      IssueService issueService) {
    this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueDependencyRepository =
        Objects.requireNonNull(issueDependencyRepository, "issueDependencyRepository");
    this.issueInputRepository =
        Objects.requireNonNull(issueInputRepository, "issueInputRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.issueService = Objects.requireNonNull(issueService, "issueService");
  }

  @Transactional(readOnly = true)
  public Optional<String> project(UUID threadId) {
    if (threadId == null) {
      return Optional.empty();
    }
    Optional<ProjectThreadOwnerContext> ownerOpt = ownerResolver.resolve(threadId);
    if (ownerOpt.isEmpty()) {
      return Optional.empty();
    }

    ProjectThreadOwnerContext owner = ownerOpt.get();
    return switch (owner.role()) {
      case COORDINATOR -> Optional.of(projectCoordinator(owner));
      case EXECUTOR -> Optional.of(projectExecutor(owner));
      case REVIEWER -> Optional.of(projectReviewer(owner));
    };
  }

  private String projectCoordinator(ProjectThreadOwnerContext owner) {
    Project project = projectRepository.getById(owner.projectId());
    if (project == null || !owner.projectId().equals(project.getId())) {
      throw inconsistent();
    }
    List<Issue> issues = issueRepository.listByProjectIdAndArchived(owner.projectId(), false);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("project", toProjectMap(project));
    data.put(
        "issues",
        issues.stream()
            .sorted(Comparator.comparingLong(Issue::getNumber))
            .map(
                i -> {
                  requireIssueInProject(owner.projectId(), i);
                  return toIssueSummaryMap(i, issueService.isBlocked(i.getId()));
                })
            .toList());

    return """
        # Project Coordinator Context

        ## Directives
        - You are the project coordinator. Only structured tools (`project_read`, `issue_*`) can mutate system state.
        - Natural language conversation text does not modify project entities or status directly.

        ## Data
        ```json
        """
        + ProjectToolExecutionSupport.toJson(data)
        + "\n```\n";
  }

  private String projectExecutor(ProjectThreadOwnerContext owner) {
    Issue issue = issueRepository.getById(owner.issueId());
    IssueRun run = issueRunRepository.getById(owner.runId());
    validateIssueRunOwner(owner, issue, run, IssueRunRole.EXECUTOR);

    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(owner.issueId());
    List<IssueInput> allInputs = issueInputRepository.listByIssueId(owner.issueId());
    requireInputsForIssue(owner.issueId(), allInputs);
    List<IssueInput> deliveredInputs =
        allInputs.stream()
            .filter(in -> in.getSequence() <= run.getObservedInputSequence())
            .sorted(Comparator.comparingLong(IssueInput::getSequence))
            .toList();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("issue", toObservedIssueDetailMap(issue, issueService.isBlocked(issue.getId()), run));
    data.put("run", toRunMap(run));
    data.put(
        "dependencies",
        deps.stream()
            .map(dep -> toDependencyDetailMap(owner.projectId(), owner.issueId(), dep))
            .sorted(Comparator.comparingLong(d -> (Long) d.get("number")))
            .toList());
    data.put("inputs", deliveredInputs.stream().map(this::toInputMap).toList());

    return String.format(
        """
        # Issue Execution Context: Executor

        ## Directives
        - You are the issue executor.
        - Required Cursors for terminal actions:
          - observed_spec_revision: %d
          - observed_input_sequence: %d
        - You must complete your execution run using `issue_submit` (providing the above cursors, summary, and verification).
        - If blocked or requiring clarification, call `issue_request_input` (providing the above cursors and your question).

        ## Data
        ```json
        %s
        ```
        """,
        run.getObservedSpecRevision(),
        run.getObservedInputSequence(),
        ProjectToolExecutionSupport.toJson(data));
  }

  private String projectReviewer(ProjectThreadOwnerContext owner) {
    Issue issue = issueRepository.getById(owner.issueId());
    IssueRun run = issueRunRepository.getById(owner.runId());
    validateIssueRunOwner(owner, issue, run, IssueRunRole.REVIEWER);

    IssueRun submissionRun =
        run.getSubmissionRunId() != null
            ? issueRunRepository.getById(run.getSubmissionRunId())
            : null;
    if (submissionRun == null
        || !run.getSubmissionRunId().equals(submissionRun.getId())
        || !owner.issueId().equals(submissionRun.getIssueId())
        || submissionRun.getRole() != IssueRunRole.EXECUTOR
        || submissionRun.getStatus() != IssueRunStatus.COMPLETED
        || submissionRun.getOutcome() != IssueRunOutcome.SUBMITTED) {
      throw inconsistent();
    }

    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(owner.issueId());
    List<IssueInput> allInputs = issueInputRepository.listByIssueId(owner.issueId());
    requireInputsForIssue(owner.issueId(), allInputs);
    List<IssueInput> deliveredInputs =
        allInputs.stream()
            .filter(in -> in.getSequence() <= run.getObservedInputSequence())
            .sorted(Comparator.comparingLong(IssueInput::getSequence))
            .toList();

    List<IssueRun> allRuns = issueRunRepository.listByIssueId(owner.issueId());
    for (IssueRun historyRun : allRuns) {
      if (historyRun == null || !owner.issueId().equals(historyRun.getIssueId())) {
        throw inconsistent();
      }
    }
    List<IssueRun> reviewerHistory =
        allRuns.stream()
            .filter(r -> r.getRole() == IssueRunRole.REVIEWER && !owner.runId().equals(r.getId()))
            .sorted(Comparator.comparingLong(IssueRun::getOrdinal))
            .toList();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("issue", toObservedIssueDetailMap(issue, issueService.isBlocked(issue.getId()), run));
    data.put("run", toRunMap(run));
    data.put("submission", toRunMap(submissionRun));
    data.put(
        "dependencies",
        deps.stream()
            .map(dep -> toDependencyDetailMap(owner.projectId(), owner.issueId(), dep))
            .sorted(Comparator.comparingLong(d -> (Long) d.get("number")))
            .toList());
    data.put("inputs", deliveredInputs.stream().map(this::toInputMap).toList());
    data.put("reviewer_history", reviewerHistory.stream().map(this::toRunMap).toList());

    return String.format(
        """
        # Issue Review Context: Reviewer

        ## Directives
        - You are the issue reviewer.
        - Required Cursors for review decision:
          - observed_spec_revision: %d
          - observed_input_sequence: %d
        - You must finalize your review using `issue_review` with decision (`APPROVE` or `REQUEST_CHANGES`), summary, and optional verification.

        ## Data
        ```json
        %s
        ```
        """,
        run.getObservedSpecRevision(),
        run.getObservedInputSequence(),
        ProjectToolExecutionSupport.toJson(data));
  }

  private IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
  }

  private Map<String, Object> toProjectMap(Project project) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", project.getId().toString());
    map.put("title", project.getTitle());
    map.put("description", project.getDescription());
    map.put("coordinator_agent_name", project.getCoordinatorAgentName());
    map.put("next_issue_number", project.getNextIssueNumber());
    map.put("version", project.getVersion());
    map.put("archived", project.isArchived());
    map.put(
        "created_at", project.getCreatedAt() != null ? project.getCreatedAt().toString() : null);
    map.put(
        "updated_at", project.getUpdatedAt() != null ? project.getUpdatedAt().toString() : null);
    return map;
  }

  private Map<String, Object> toIssueSummaryMap(Issue issue, boolean blocked) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", issue.getId().toString());
    map.put("number", issue.getNumber());
    map.put("title", issue.getTitle());
    map.put("status", issue.getStatus() != null ? issue.getStatus().name() : null);
    map.put("blocked", blocked);
    map.put("assignee_agent_name", issue.getAssigneeAgentName());
    map.put("reviewer_agent_name", issue.getReviewerAgentName());
    map.put("version", issue.getVersion());
    map.put("spec_revision", issue.getSpecRevision());
    map.put("input_sequence", issue.getInputSequence());
    return map;
  }

  private Map<String, Object> toIssueDetailMap(Issue issue, boolean blocked) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", issue.getId().toString());
    map.put("project_id", issue.getProjectId().toString());
    map.put("number", issue.getNumber());
    map.put("title", issue.getTitle());
    map.put("description", issue.getDescription());
    map.put("status", issue.getStatus() != null ? issue.getStatus().name() : null);
    map.put("blocked", blocked);
    map.put("assignee_agent_name", issue.getAssigneeAgentName());
    map.put("reviewer_agent_name", issue.getReviewerAgentName());
    map.put("version", issue.getVersion());
    map.put("spec_revision", issue.getSpecRevision());
    map.put("input_sequence", issue.getInputSequence());
    map.put("archived", issue.isArchived());
    map.put("created_at", issue.getCreatedAt() != null ? issue.getCreatedAt().toString() : null);
    map.put("updated_at", issue.getUpdatedAt() != null ? issue.getUpdatedAt().toString() : null);
    return map;
  }

  private Map<String, Object> toObservedIssueDetailMap(Issue issue, boolean blocked, IssueRun run) {
    if (issue.getSpecRevision() != run.getObservedSpecRevision()
        || run.getObservedInputSequence() < 0
        || run.getObservedInputSequence() > issue.getInputSequence()) {
      throw inconsistent();
    }
    Map<String, Object> map = toIssueDetailMap(issue, blocked);
    map.put("spec_revision", run.getObservedSpecRevision());
    map.put("input_sequence", run.getObservedInputSequence());
    return map;
  }

  private Map<String, Object> toDependencyDetailMap(
      UUID projectId, UUID issueId, IssueDependency dependency) {
    if (dependency == null
        || !issueId.equals(dependency.getIssueId())
        || !projectId.equals(dependency.getProjectId())
        || dependency.getDependsOnIssueId() == null) {
      throw inconsistent();
    }
    Issue target = issueRepository.getById(dependency.getDependsOnIssueId());
    if (target == null
        || !dependency.getDependsOnIssueId().equals(target.getId())
        || !projectId.equals(target.getProjectId())) {
      throw inconsistent();
    }
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("issue_id", dependency.getIssueId().toString());
    map.put("depends_on_issue_id", dependency.getDependsOnIssueId().toString());
    map.put("number", target.getNumber());
    map.put("title", target.getTitle());
    map.put("status", target.getStatus() != null ? target.getStatus().name() : null);
    map.put("archived", target.isArchived());
    return map;
  }

  private void validateIssueRunOwner(
      ProjectThreadOwnerContext owner, Issue issue, IssueRun run, IssueRunRole expectedRole) {
    if (issue == null
        || run == null
        || !owner.issueId().equals(issue.getId())
        || !owner.projectId().equals(issue.getProjectId())
        || !owner.runId().equals(run.getId())
        || !owner.issueId().equals(run.getIssueId())
        || run.getRole() != expectedRole
        || run.getActorType() != IssueRunActorType.AGENT
        || !owner.agentName().equals(run.getAgentName())) {
      throw inconsistent();
    }
  }

  private void requireIssueInProject(UUID projectId, Issue issue) {
    if (issue == null || issue.getId() == null || !projectId.equals(issue.getProjectId())) {
      throw inconsistent();
    }
  }

  private void requireInputsForIssue(UUID issueId, List<IssueInput> inputs) {
    for (IssueInput input : inputs) {
      if (input == null || !issueId.equals(input.getIssueId())) {
        throw inconsistent();
      }
    }
  }

  private Map<String, Object> toInputMap(IssueInput in) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("sequence", in.getSequence());
    map.put("kind", in.getKind() != null ? in.getKind().name() : null);
    map.put("body", in.getBody());
    map.put("created_at", in.getCreatedAt() != null ? in.getCreatedAt().toString() : null);
    return map;
  }

  private Map<String, Object> toRunMap(IssueRun run) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", run.getId().toString());
    map.put("issue_id", run.getIssueId().toString());
    map.put("ordinal", run.getOrdinal());
    map.put("role", run.getRole() != null ? run.getRole().name() : null);
    map.put("actor_type", run.getActorType() != null ? run.getActorType().name() : null);
    map.put("agent_name", run.getAgentName());
    map.put(
        "submission_run_id",
        run.getSubmissionRunId() != null ? run.getSubmissionRunId().toString() : null);
    map.put("status", run.getStatus() != null ? run.getStatus().name() : null);
    map.put("outcome", run.getOutcome() != null ? run.getOutcome().name() : null);
    map.put("observed_spec_revision", run.getObservedSpecRevision());
    map.put("observed_input_sequence", run.getObservedInputSequence());
    map.put("continuation_count", run.getContinuationCount());
    map.put("max_continuations", run.getMaxContinuations());
    map.put("deadline", run.getDeadline() != null ? run.getDeadline().toString() : null);
    map.put("waiting_reason", run.getWaitingReason());
    map.put("result", run.getResult());
    map.put("version", run.getVersion());
    map.put("created_at", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
    map.put("updated_at", run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : null);
    map.put("completed_at", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
    return map;
  }
}

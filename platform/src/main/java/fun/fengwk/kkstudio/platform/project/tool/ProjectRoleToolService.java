package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 负责 12 个 Project/Issue 角色工具的具体业务执行、读聚合与事务边界。 */
@Service
public class ProjectRoleToolService {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  private final ProjectService projectService;
  private final IssueService issueService;
  private final IssueRunService issueRunService;

  public ProjectRoleToolService(
      ProjectService projectService, IssueService issueService, IssueRunService issueRunService) {
    this.projectService = Objects.requireNonNull(projectService, "projectService");
    this.issueService = Objects.requireNonNull(issueService, "issueService");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
  }

  @Transactional(readOnly = true)
  public Map<String, Object> projectRead(ProjectThreadOwnerContext owner) {
    validateRole(owner, ProjectRole.COORDINATOR);
    Project project = projectService.getProject(owner.projectId());
    requireProject(owner.projectId(), project);
    List<Issue> issues = issueService.listIssues(owner.projectId(), false);
    List<Map<String, Object>> issueSummaries =
        issues.stream()
            .sorted(Comparator.comparingLong(Issue::getNumber))
            .map(
                i -> {
                  requireIssueInProject(owner.projectId(), i);
                  return toIssueSummaryMap(i, issueService.isBlocked(i.getId()));
                })
            .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("project", toProjectMap(project));
    result.put("issues", issueSummaries);
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> issueRead(ProjectThreadOwnerContext owner, UUID issueId) {
    validateRole(owner, ProjectRole.COORDINATOR);
    Objects.requireNonNull(issueId, "issueId");
    Issue issue = issueService.getIssue(issueId);
    if (issue == null
        || !issueId.equals(issue.getId())
        || !owner.projectId().equals(issue.getProjectId())) {
      throw new AiResourceNotFoundException("issue");
    }

    boolean blocked = issueService.isBlocked(issueId);
    List<IssueDependency> deps = issueService.listDependencies(issueId);
    List<Map<String, Object>> depDetails =
        deps.stream()
            .map(dep -> toDependencyDetailMap(owner.projectId(), issueId, dep))
            .sorted(Comparator.comparingLong(d -> (Long) d.get("number")))
            .toList();

    List<IssueInput> inputs = issueService.listInputs(issueId);
    List<Map<String, Object>> inputDetails =
        inputs.stream()
            .sorted(Comparator.comparingLong(IssueInput::getSequence))
            .map(
                input -> {
                  requireInputForIssue(issueId, input);
                  return toInputMap(input);
                })
            .toList();

    List<IssueRun> runs = issueRunService.listRuns(issueId);
    List<Map<String, Object>> runDetails =
        runs.stream()
            .sorted(Comparator.comparingLong(IssueRun::getOrdinal))
            .map(
                run -> {
                  requireRunForIssue(issueId, run);
                  return toRunMap(run);
                })
            .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issue", toIssueDetailMap(issue, blocked));
    result.put("dependencies", depDetails);
    result.put("inputs", inputDetails);
    result.put("runs", runDetails);
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> issueList(
      ProjectThreadOwnerContext owner, IssueStatus statusFilter, boolean includeArchived) {
    validateRole(owner, ProjectRole.COORDINATOR);
    List<Issue> issues = issueService.listIssues(owner.projectId(), includeArchived);
    if (statusFilter != null) {
      issues = issues.stream().filter(i -> i.getStatus() == statusFilter).toList();
    }
    List<Map<String, Object>> summaries =
        issues.stream()
            .sorted(Comparator.comparingLong(Issue::getNumber))
            .map(
                i -> {
                  requireIssueInProject(owner.projectId(), i);
                  return toIssueSummaryMap(i, issueService.isBlocked(i.getId()));
                })
            .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issues", summaries);
    return result;
  }

  @Transactional
  public Map<String, Object> issueCreate(
      ProjectThreadOwnerContext owner,
      String title,
      String description,
      String assigneeAgentName,
      String reviewerAgentName,
      IssueStatus initialStatus) {
    validateRole(owner, ProjectRole.COORDINATOR);
    if (initialStatus != null
        && initialStatus != IssueStatus.BACKLOG
        && initialStatus != IssueStatus.TODO) {
      throw new IllegalArgumentException("Invalid initial_status: only BACKLOG or TODO is allowed");
    }
    IssueStatus status = initialStatus != null ? initialStatus : IssueStatus.BACKLOG;
    Issue created =
        issueService.createIssue(
            owner.projectId(), title, description, assigneeAgentName, reviewerAgentName, status);
    requireIssueInProject(owner.projectId(), created);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issue", toIssueDetailMap(created, false));
    return result;
  }

  @Transactional
  public Map<String, Object> issueUpdate(
      ProjectThreadOwnerContext owner,
      UUID issueId,
      long expectedVersion,
      UpdateIssueCommand command) {
    validateRole(owner, ProjectRole.COORDINATOR);
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(command, "command");

    if (!command.hasAnyMutableField()) {
      throw new IllegalArgumentException("At least one mutable field must be provided for update");
    }

    Issue current = issueService.getIssue(issueId);
    if (current == null
        || !issueId.equals(current.getId())
        || !owner.projectId().equals(current.getProjectId())) {
      throw new AiResourceNotFoundException("issue");
    }

    String newTitle = current.getTitle();
    if (command.hasTitle()) {
      if (command.title() == null || command.title().isBlank()) {
        throw new IllegalArgumentException("title must not be blank");
      }
      newTitle = command.title().trim();
    }

    String newDescription = current.getDescription();
    if (command.hasDescription()) {
      newDescription = command.description() != null ? command.description() : "";
    }

    String newAssignee = current.getAssigneeAgentName();
    if (command.hasAssignee()) {
      newAssignee =
          (command.assignee() == null || command.assignee().isBlank())
              ? null
              : command.assignee().trim();
    }

    String newReviewer = current.getReviewerAgentName();
    if (command.hasReviewer()) {
      newReviewer =
          (command.reviewer() == null || command.reviewer().isBlank())
              ? null
              : command.reviewer().trim();
    }

    Issue updated =
        issueService.updateIssue(
            issueId, expectedVersion, newTitle, newDescription, newAssignee, newReviewer);
    requireIssue(owner.projectId(), issueId, updated);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issue", toIssueDetailMap(updated, issueService.isBlocked(issueId)));
    return result;
  }

  @Transactional
  public Map<String, Object> issueAddDependency(
      ProjectThreadOwnerContext owner, UUID issueId, UUID dependsOnIssueId, long expectedVersion) {
    validateRole(owner, ProjectRole.COORDINATOR);
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(dependsOnIssueId, "dependsOnIssueId");

    Issue target = issueService.getIssue(issueId);
    Issue depends = issueService.getIssue(dependsOnIssueId);
    if (target == null
        || !issueId.equals(target.getId())
        || !owner.projectId().equals(target.getProjectId())
        || depends == null
        || !dependsOnIssueId.equals(depends.getId())
        || !owner.projectId().equals(depends.getProjectId())) {
      throw new AiResourceNotFoundException("issue");
    }

    issueService.addDependency(issueId, dependsOnIssueId, expectedVersion);
    Issue fresh = issueService.getIssue(issueId);
    requireIssue(owner.projectId(), issueId, fresh);
    List<IssueDependency> deps = issueService.listDependencies(issueId);
    List<Map<String, Object>> depDetails =
        deps.stream()
            .map(dep -> toDependencyDetailMap(owner.projectId(), issueId, dep))
            .sorted(Comparator.comparingLong(d -> (Long) d.get("number")))
            .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issue", toIssueDetailMap(fresh, issueService.isBlocked(issueId)));
    result.put("dependencies", depDetails);
    return result;
  }

  @Transactional
  public Map<String, Object> issueRemoveDependency(
      ProjectThreadOwnerContext owner, UUID issueId, UUID dependsOnIssueId, long expectedVersion) {
    validateRole(owner, ProjectRole.COORDINATOR);
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(dependsOnIssueId, "dependsOnIssueId");

    Issue target = issueService.getIssue(issueId);
    Issue depends = issueService.getIssue(dependsOnIssueId);
    if (target == null
        || !issueId.equals(target.getId())
        || !owner.projectId().equals(target.getProjectId())
        || depends == null
        || !dependsOnIssueId.equals(depends.getId())
        || !owner.projectId().equals(depends.getProjectId())) {
      throw new AiResourceNotFoundException("issue");
    }

    issueService.removeDependency(issueId, dependsOnIssueId, expectedVersion);
    Issue fresh = issueService.getIssue(issueId);
    requireIssue(owner.projectId(), issueId, fresh);
    List<IssueDependency> deps = issueService.listDependencies(issueId);
    List<Map<String, Object>> depDetails =
        deps.stream()
            .map(dep -> toDependencyDetailMap(owner.projectId(), issueId, dep))
            .sorted(Comparator.comparingLong(d -> (Long) d.get("number")))
            .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issue", toIssueDetailMap(fresh, issueService.isBlocked(issueId)));
    result.put("dependencies", depDetails);
    return result;
  }

  @Transactional
  public Map<String, Object> issueSetStatus(
      ProjectThreadOwnerContext owner, UUID issueId, IssueStatus newStatus, long expectedVersion) {
    validateRole(owner, ProjectRole.COORDINATOR);
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(newStatus, "newStatus");
    if (newStatus != IssueStatus.BACKLOG && newStatus != IssueStatus.TODO) {
      throw new IllegalArgumentException("Invalid status: only BACKLOG or TODO is allowed");
    }

    Issue current = issueService.getIssue(issueId);
    if (current == null
        || !issueId.equals(current.getId())
        || !owner.projectId().equals(current.getProjectId())) {
      throw new AiResourceNotFoundException("issue");
    }

    Issue updated = issueService.setStatus(issueId, expectedVersion, newStatus);
    requireIssue(owner.projectId(), issueId, updated);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issue", toIssueDetailMap(updated, issueService.isBlocked(issueId)));
    return result;
  }

  @Transactional
  public Map<String, Object> issueCancel(
      ProjectThreadOwnerContext owner, UUID issueId, long expectedVersion, String reason) {
    validateRole(owner, ProjectRole.COORDINATOR);
    Objects.requireNonNull(issueId, "issueId");

    Issue current = issueService.getIssue(issueId);
    if (current == null
        || !issueId.equals(current.getId())
        || !owner.projectId().equals(current.getProjectId())) {
      throw new AiResourceNotFoundException("issue");
    }

    Issue updated = issueService.cancelIssue(issueId, expectedVersion, reason);
    requireIssue(owner.projectId(), issueId, updated);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issue", toIssueDetailMap(updated, issueService.isBlocked(issueId)));
    return result;
  }

  @Transactional
  public Map<String, Object> issueSubmit(
      ProjectThreadOwnerContext owner,
      String terminalActionId,
      long observedSpecRevision,
      long observedInputSequence,
      String summary,
      String verification) {
    validateRole(owner, ProjectRole.EXECUTOR);
    if (observedSpecRevision < 0) {
      throw new IllegalArgumentException("observed_spec_revision must not be negative");
    }
    if (observedInputSequence < 0) {
      throw new IllegalArgumentException("observed_input_sequence must not be negative");
    }
    Objects.requireNonNull(summary, "summary");

    IssueRun run =
        issueRunService.submitRun(
            owner.runId(),
            terminalActionId,
            observedSpecRevision,
            observedInputSequence,
            summary,
            verification);
    requireRunOwner(owner, run, IssueRunRole.EXECUTOR);

    Issue freshIssue = issueService.getIssue(owner.issueId());
    requireIssue(owner.projectId(), owner.issueId(), freshIssue);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("run", toRunMap(run));
    result.put("issue", toIssueDetailMap(freshIssue, issueService.isBlocked(freshIssue.getId())));
    return result;
  }

  @Transactional
  public Map<String, Object> issueRequestInput(
      ProjectThreadOwnerContext owner,
      long observedSpecRevision,
      long observedInputSequence,
      String question,
      String context) {
    validateRole(owner, ProjectRole.EXECUTOR);
    if (observedSpecRevision < 0) {
      throw new IllegalArgumentException("observed_spec_revision must not be negative");
    }
    if (observedInputSequence < 0) {
      throw new IllegalArgumentException("observed_input_sequence must not be negative");
    }
    Objects.requireNonNull(question, "question");

    IssueRun run =
        issueRunService.requestInput(
            owner.runId(), observedSpecRevision, observedInputSequence, question, context);
    requireRunOwner(owner, run, IssueRunRole.EXECUTOR);

    Issue freshIssue = issueService.getIssue(owner.issueId());
    requireIssue(owner.projectId(), owner.issueId(), freshIssue);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("run", toRunMap(run));
    result.put("issue", toIssueDetailMap(freshIssue, issueService.isBlocked(freshIssue.getId())));
    return result;
  }

  @Transactional
  public Map<String, Object> issueReview(
      ProjectThreadOwnerContext owner,
      String terminalActionId,
      long observedSpecRevision,
      long observedInputSequence,
      ReviewDecision decision,
      String summary,
      String verification) {
    validateRole(owner, ProjectRole.REVIEWER);
    if (observedSpecRevision < 0) {
      throw new IllegalArgumentException("observed_spec_revision must not be negative");
    }
    if (observedInputSequence < 0) {
      throw new IllegalArgumentException("observed_input_sequence must not be negative");
    }
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(summary, "summary");

    IssueRun run =
        issueRunService.reviewRun(
            owner.issueId(),
            owner.runId(),
            IssueRunActorType.AGENT,
            owner.agentName(),
            terminalActionId,
            observedSpecRevision,
            observedInputSequence,
            decision,
            summary,
            verification);
    requireRunOwner(owner, run, IssueRunRole.REVIEWER);

    Issue freshIssue = issueService.getIssue(owner.issueId());
    requireIssue(owner.projectId(), owner.issueId(), freshIssue);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("run", toRunMap(run));
    result.put("issue", toIssueDetailMap(freshIssue, issueService.isBlocked(freshIssue.getId())));
    return result;
  }

  private void validateRole(ProjectThreadOwnerContext owner, ProjectRole expectedRole) {
    Objects.requireNonNull(owner, "owner");
    if (owner.role() != expectedRole) {
      throw new IllegalArgumentException("Tool not permitted for current role");
    }
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

  private Map<String, Object> toDependencyDetailMap(
      UUID projectId, UUID issueId, IssueDependency dependency) {
    if (dependency == null
        || !issueId.equals(dependency.getIssueId())
        || !projectId.equals(dependency.getProjectId())
        || dependency.getDependsOnIssueId() == null) {
      throw inconsistent();
    }
    Issue target;
    try {
      target = issueService.getIssue(dependency.getDependsOnIssueId());
    } catch (AiResourceNotFoundException e) {
      throw inconsistent();
    }
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

  private void requireProject(UUID projectId, Project project) {
    if (project == null || !projectId.equals(project.getId())) {
      throw inconsistent();
    }
  }

  private void requireIssueInProject(UUID projectId, Issue issue) {
    if (issue == null || issue.getId() == null || !projectId.equals(issue.getProjectId())) {
      throw inconsistent();
    }
  }

  private void requireIssue(UUID projectId, UUID issueId, Issue issue) {
    if (issue == null
        || !issueId.equals(issue.getId())
        || !projectId.equals(issue.getProjectId())) {
      throw inconsistent();
    }
  }

  private void requireRunOwner(
      ProjectThreadOwnerContext owner, IssueRun run, IssueRunRole expectedRole) {
    if (run == null
        || !owner.runId().equals(run.getId())
        || !owner.issueId().equals(run.getIssueId())
        || run.getRole() != expectedRole
        || run.getActorType() != IssueRunActorType.AGENT
        || !owner.agentName().equals(run.getAgentName())) {
      throw inconsistent();
    }
  }

  private void requireInputForIssue(UUID issueId, IssueInput input) {
    if (input == null || !issueId.equals(input.getIssueId())) {
      throw inconsistent();
    }
  }

  private void requireRunForIssue(UUID issueId, IssueRun run) {
    if (run == null || run.getId() == null || !issueId.equals(run.getIssueId())) {
      throw inconsistent();
    }
  }

  private IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
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

  public record UpdateIssueCommand(
      boolean hasTitle,
      String title,
      boolean hasDescription,
      String description,
      boolean hasAssignee,
      String assignee,
      boolean hasReviewer,
      String reviewer) {

    public boolean hasAnyMutableField() {
      return hasTitle || hasDescription || hasAssignee || hasReviewer;
    }
  }
}

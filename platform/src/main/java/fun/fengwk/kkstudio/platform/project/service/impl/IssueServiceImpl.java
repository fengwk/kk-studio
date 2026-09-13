package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatusTransition;
import fun.fengwk.kkstudio.platform.project.model.IssueTransitionAction;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueService;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@AllArgsConstructor
@Service
public class IssueServiceImpl implements IssueService {

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueDependencyRepository issueDependencyRepository;
  private final IssueInputRepository issueInputRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueControllerWorkStore controllerWorkStore;

  @Transactional
  @Override
  public Issue createIssue(
      UUID projectId,
      String title,
      String description,
      String assigneeAgentName,
      String reviewerAgentName,
      IssueStatus initialStatus) {
    Objects.requireNonNull(projectId, "projectId");
    String trimmedTitle = ProjectValidationUtils.trimAndValidate(title, "title", 255, true);
    String trimmedAssignee =
        ProjectValidationUtils.trimAndValidate(assigneeAgentName, "assigneeAgentName", 128, false);
    String trimmedReviewer =
        ProjectValidationUtils.trimAndValidate(reviewerAgentName, "reviewerAgentName", 128, false);
    ProjectValidationUtils.validateUtf8Bytes(description, "description", 65536, false);

    // 先锁 Project 再检查 archived 与分配编号
    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot create issue in an archived project");
    }

    IssueStatus status = initialStatus != null ? initialStatus : IssueStatus.BACKLOG;
    if (status != IssueStatus.BACKLOG && status != IssueStatus.TODO) {
      throw new AiValidationException(
          "issue", "Initial issue status must be either BACKLOG or TODO, but was " + status);
    }

    long number = projectRepository.allocateNextIssueNumber(projectId);
    UUID issueId = UUID.randomUUID();
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(number)
            .title(trimmedTitle)
            .description(description != null ? description : "")
            .status(status)
            .assigneeAgentName(trimmedAssignee)
            .reviewerAgentName(trimmedReviewer)
            .version(0L)
            .specRevision(1L)
            .inputSequence(0L)
            .archivedAt(null)
            .build();

    boolean created = issueRepository.create(issue);
    if (!created) {
      throw new AiValidationException("issue", "Failed to create issue");
    }

    if (status == IssueStatus.TODO) {
      controllerWorkStore.requestWork(issueId, Instant.now());
    }
    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue updateIssue(
      UUID issueId,
      long expectedVersion,
      String title,
      String description,
      String assigneeAgentName,
      String reviewerAgentName) {
    Objects.requireNonNull(issueId, "issueId");
    String trimmedTitle = ProjectValidationUtils.trimAndValidate(title, "title", 255, true);
    String trimmedAssignee =
        ProjectValidationUtils.trimAndValidate(assigneeAgentName, "assigneeAgentName", 128, false);
    String trimmedReviewer =
        ProjectValidationUtils.trimAndValidate(reviewerAgentName, "reviewerAgentName", 128, false);
    ProjectValidationUtils.validateUtf8Bytes(description, "description", 65536, false);

    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (current.isArchived()) {
      throw new AiValidationException("issue", "Archived issue cannot be updated");
    }
    if (current.getStatus() != IssueStatus.BACKLOG && current.getStatus() != IssueStatus.TODO) {
      throw new AiValidationException(
          "issue",
          "Issue spec and assignment can only be updated in BACKLOG or TODO, but current status is "
              + current.getStatus());
    }

    String newDescription = description != null ? description : "";
    boolean specChanged =
        !Objects.equals(current.getTitle(), trimmedTitle)
            || !Objects.equals(current.getDescription(), newDescription)
            || !Objects.equals(current.getAssigneeAgentName(), trimmedAssignee)
            || !Objects.equals(current.getReviewerAgentName(), trimmedReviewer);

    current.setTitle(trimmedTitle);
    current.setDescription(newDescription);
    current.setAssigneeAgentName(trimmedAssignee);
    current.setReviewerAgentName(trimmedReviewer);
    if (specChanged) {
      current.setSpecRevision(current.getSpecRevision() + 1);
    }

    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    if (current.getStatus() == IssueStatus.TODO && specChanged) {
      controllerWorkStore.requestWork(issueId, Instant.now());
    }

    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue setStatus(UUID issueId, long expectedVersion, IssueStatus newStatus) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(newStatus, "newStatus");

    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (current.isArchived()) {
      throw new AiValidationException("issue", "Archived issue cannot transition status");
    }

    IssueStatus oldStatus = current.getStatus();
    if (oldStatus == newStatus) {
      return current;
    }

    IssueTransitionAction action;
    if (oldStatus == IssueStatus.BACKLOG && newStatus == IssueStatus.TODO) {
      action = IssueTransitionAction.READY;
    } else if (oldStatus == IssueStatus.TODO && newStatus == IssueStatus.BACKLOG) {
      action = IssueTransitionAction.DEFER;
    } else if ((oldStatus == IssueStatus.DONE || oldStatus == IssueStatus.CANCELED)
        && newStatus == IssueStatus.TODO) {
      action = IssueTransitionAction.REOPEN;
    } else {
      throw new AiValidationException(
          "issue",
          "Invalid explicit status transition from "
              + oldStatus
              + " to "
              + newStatus
              + ". Transitions to IN_PROGRESS, IN_REVIEW, or terminal states must follow execution rules.");
    }

    if (!IssueStatusTransition.isAllowed(oldStatus, newStatus, action)) {
      throw new AiValidationException(
          "issue", "Transition from " + oldStatus + " to " + newStatus + " is not allowed");
    }

    current.setStatus(IssueStatusTransition.transition(oldStatus, action));
    if (action == IssueTransitionAction.REOPEN) {
      current.setSpecRevision(current.getSpecRevision() + 1);
    }

    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    if (newStatus == IssueStatus.TODO) {
      controllerWorkStore.requestWork(issueId, Instant.now());
    }

    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue cancelIssue(UUID issueId, long expectedVersion, String reason) {
    Objects.requireNonNull(issueId, "issueId");
    String normalizedReason =
        (reason == null || reason.isBlank()) ? "Issue cancelled" : reason.trim();
    ProjectValidationUtils.validateUtf8Bytes(normalizedReason, "waiting_reason", 16384, true);

    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    // 锁 Issue
    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (!IssueStatusTransition.isAllowed(
        current.getStatus(), IssueStatus.CANCELED, IssueTransitionAction.CANCEL)) {
      throw new AiValidationException(
          "issue", "Cannot cancel an issue in status " + current.getStatus());
    }

    // 锁 active Run 再迁移
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun != null) {
      if (activeRun.getStatus().canTransitionTo(IssueRunStatus.CANCELLED)) {
        activeRun.setStatus(IssueRunStatus.CANCELLED);
        activeRun.setWaitingReason(normalizedReason);
        activeRun.setCompletedAt(Instant.now());
        boolean runUpdated = issueRunRepository.updateById(activeRun, activeRun.getVersion());
        if (!runUpdated) {
          throw new AiValidationException("issue_run", "Failed to cancel active run");
        }
      }
    }

    current.setStatus(
        IssueStatusTransition.transition(current.getStatus(), IssueTransitionAction.CANCEL));
    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    controllerWorkStore.requestWork(issueId, Instant.now());
    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue archiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");

    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (!current.canBeArchived()) {
      throw new AiValidationException(
          "issue",
          "Only terminal issues (DONE or CANCELED) can be archived, current status is "
              + current.getStatus());
    }
    if (current.isArchived()) {
      return current;
    }

    current.setArchivedAt(Instant.now());
    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue unarchiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");

    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (!current.isArchived()) {
      return current;
    }

    current.setArchivedAt(null);
    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public IssueDependency addDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(dependsOnIssueId, "dependsOnIssueId");
    if (issueId.equals(dependsOnIssueId)) {
      throw new AiValidationException("issue_dependency", "Issue cannot depend on itself");
    }

    // 1. 先预读 target 获得 projectId
    Issue targetPre = issueRepository.getById(issueId);
    if (targetPre == null) {
      throw new AiResourceNotFoundException("issue");
    }
    UUID projectId = targetPre.getProjectId();

    // 2. 锁 Project 行作为 per-project graph mutex
    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot add dependency in an archived project");
    }

    // 3. 按 UUID 顺序锁两端
    UUID first = issueId.compareTo(dependsOnIssueId) < 0 ? issueId : dependsOnIssueId;
    UUID second = issueId.compareTo(dependsOnIssueId) < 0 ? dependsOnIssueId : issueId;
    Issue lockFirst = issueRepository.lockById(first);
    Issue lockSecond = issueRepository.lockById(second);

    Issue targetIssue = issueId.equals(first) ? lockFirst : lockSecond;
    Issue dependsOnIssue = dependsOnIssueId.equals(first) ? lockFirst : lockSecond;

    if (targetIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (dependsOnIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (!targetIssue.getProjectId().equals(projectId)
        || !dependsOnIssue.getProjectId().equals(projectId)) {
      throw new AiValidationException(
          "issue_dependency", "Dependencies are only allowed between issues in the same project");
    }
    if (targetIssue.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue", String.valueOf(expectedVersion), String.valueOf(targetIssue.getVersion()));
    }
    if (targetIssue.isArchived() || dependsOnIssue.isArchived()) {
      throw new AiValidationException(
          "issue_dependency", "Archived issues cannot participate in dependencies");
    }
    if (targetIssue.getStatus() != IssueStatus.BACKLOG
        && targetIssue.getStatus() != IssueStatus.TODO) {
      throw new AiValidationException(
          "issue_dependency",
          "Dependencies can only be added to issues in BACKLOG or TODO, but target issue status is "
              + targetIssue.getStatus());
    }

    // 检查依赖是否已存在
    List<IssueDependency> existingDeps = issueDependencyRepository.listByIssueId(issueId);
    boolean alreadyExists =
        existingDeps.stream().anyMatch(d -> d.getDependsOnIssueId().equals(dependsOnIssueId));
    if (alreadyExists) {
      throw new AiValidationException("issue_dependency", "Dependency already exists");
    }

    // 4. CTE 环检测：检查 dependsOnIssue 是否已通过现有依赖路径到达 targetIssue
    boolean createsCycle = issueDependencyRepository.checkHasPath(dependsOnIssueId, issueId);
    if (createsCycle) {
      throw new AiValidationException(
          "issue_dependency",
          "Cycle detected: adding this dependency would form a cycle in the DAG");
    }

    IssueDependency dependency =
        IssueDependency.builder()
            .issueId(issueId)
            .dependsOnIssueId(dependsOnIssueId)
            .projectId(projectId)
            .build();
    boolean added = issueDependencyRepository.addDependency(dependency);
    if (!added) {
      throw new AiValidationException("issue_dependency", "Failed to add dependency");
    }

    targetIssue.setSpecRevision(targetIssue.getSpecRevision() + 1);
    boolean updated = issueRepository.updateById(targetIssue, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    if (targetIssue.getStatus() == IssueStatus.TODO) {
      controllerWorkStore.requestWork(issueId, Instant.now());
    }
    return dependency;
  }

  @Transactional
  @Override
  public void removeDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(dependsOnIssueId, "dependsOnIssueId");

    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    Issue targetIssue = issueRepository.lockById(issueId);
    if (targetIssue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (targetIssue.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue", String.valueOf(expectedVersion), String.valueOf(targetIssue.getVersion()));
    }
    if (targetIssue.getStatus() != IssueStatus.BACKLOG
        && targetIssue.getStatus() != IssueStatus.TODO) {
      throw new AiValidationException(
          "issue_dependency",
          "Dependencies can only be removed from issues in BACKLOG or TODO, but target issue status is "
              + targetIssue.getStatus());
    }

    boolean removed = issueDependencyRepository.removeDependency(issueId, dependsOnIssueId);
    if (!removed) {
      return;
    }

    targetIssue.setSpecRevision(targetIssue.getSpecRevision() + 1);
    boolean updated = issueRepository.updateById(targetIssue, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    if (targetIssue.getStatus() == IssueStatus.TODO) {
      controllerWorkStore.requestWork(issueId, Instant.now());
    }
  }

  @Transactional
  @Override
  public IssueInput appendInput(
      UUID issueId, IssueInputKind kind, String body, String idempotencyKey) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(kind, "kind");
    String trimmedBody =
        ProjectValidationUtils.trimAndValidate(body, "body", Integer.MAX_VALUE, true);
    ProjectValidationUtils.validateUtf8Bytes(trimmedBody, "body", 1048576, true);
    String trimmedKey =
        ProjectValidationUtils.trimAndValidate(idempotencyKey, "idempotencyKey", 128, false);

    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (current.isArchived()) {
      throw new AiValidationException("issue_input", "Cannot append input to an archived issue");
    }

    if (trimmedKey != null) {
      IssueInput existing = issueInputRepository.findByIdempotencyKey(issueId, trimmedKey);
      if (existing != null) {
        return existing;
      }
    }

    long newSequence = issueRepository.incrementInputSequence(issueId);
    if (newSequence <= 0) {
      throw new AiValidationException("issue", "Failed to increment input sequence");
    }
    IssueInput input =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(newSequence)
            .kind(kind)
            .body(trimmedBody)
            .idempotencyKey(trimmedKey)
            .build();

    boolean appended = issueInputRepository.append(input);
    if (!appended) {
      throw new AiValidationException("issue_input", "Failed to append issue input");
    }

    controllerWorkStore.requestWork(issueId, Instant.now());
    return input;
  }

  @Override
  public boolean isBlocked(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    List<IssueDependency> deps = issueDependencyRepository.listByIssueId(issueId);
    if (deps.isEmpty()) {
      return false;
    }
    for (IssueDependency dep : deps) {
      Issue dependencyIssue = issueRepository.getById(dep.getDependsOnIssueId());
      if (dependencyIssue == null || dependencyIssue.getStatus() != IssueStatus.DONE) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Issue getIssue(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    Issue issue = issueRepository.getById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    return issue;
  }

  @Override
  public Issue getIssueByProjectAndNumber(UUID projectId, long number) {
    Objects.requireNonNull(projectId, "projectId");
    Issue issue = issueRepository.getByProjectAndNumber(projectId, number);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    return issue;
  }

  @Override
  public List<Issue> listIssues(UUID projectId, boolean includeArchived) {
    Objects.requireNonNull(projectId, "projectId");
    if (includeArchived) {
      return issueRepository.listByProjectId(projectId);
    }
    return issueRepository.listByProjectIdAndArchived(projectId, false);
  }

  @Override
  public List<IssueDependency> listDependencies(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueDependencyRepository.listByIssueId(issueId);
  }

  @Override
  public List<IssueDependency> listProjectDependencies(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");
    return issueDependencyRepository.listByProjectId(projectId);
  }

  @Override
  public List<IssueInput> listInputs(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueInputRepository.listByIssueId(issueId);
  }
}

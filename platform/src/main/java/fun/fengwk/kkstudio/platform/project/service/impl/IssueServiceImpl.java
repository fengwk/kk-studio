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
    if (title == null || title.isBlank()) {
      throw new AiValidationException("issue", "Issue title must not be blank");
    }
    Project project = projectRepository.getById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project", projectId.toString());
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
            .title(title.trim())
            .description(description != null ? description : "")
            .status(status)
            .assigneeAgentName(
                assigneeAgentName != null && !assigneeAgentName.isBlank()
                    ? assigneeAgentName.trim()
                    : null)
            .reviewerAgentName(
                reviewerAgentName != null && !reviewerAgentName.isBlank()
                    ? reviewerAgentName.trim()
                    : null)
            .version(0L)
            .specRevision(1L)
            .inputSequence(0L)
            .archivedAt(null)
            .build();

    issueRepository.create(issue);
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
    if (title == null || title.isBlank()) {
      throw new AiValidationException("issue", "Issue title must not be blank");
    }

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue",
          issueId.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
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

    String trimmedTitle = title.trim();
    String newDescription = description != null ? description : "";
    String newAssignee =
        assigneeAgentName != null && !assigneeAgentName.isBlank() ? assigneeAgentName.trim() : null;
    String newReviewer =
        reviewerAgentName != null && !reviewerAgentName.isBlank() ? reviewerAgentName.trim() : null;

    boolean specChanged =
        !Objects.equals(current.getTitle(), trimmedTitle)
            || !Objects.equals(current.getDescription(), newDescription)
            || !Objects.equals(current.getAssigneeAgentName(), newAssignee)
            || !Objects.equals(current.getReviewerAgentName(), newReviewer);

    current.setTitle(trimmedTitle);
    current.setDescription(newDescription);
    current.setAssigneeAgentName(newAssignee);
    current.setReviewerAgentName(newReviewer);
    if (specChanged) {
      current.setSpecRevision(current.getSpecRevision() + 1);
    }

    issueRepository.updateById(current, expectedVersion);

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

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue",
          issueId.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
    }
    if (current.isArchived()) {
      throw new AiValidationException("issue", "Archived issue cannot transition status");
    }

    IssueStatus oldStatus = current.getStatus();
    if (oldStatus == newStatus) {
      return current;
    }

    boolean isReopen =
        (oldStatus == IssueStatus.DONE || oldStatus == IssueStatus.CANCELED)
            && newStatus == IssueStatus.TODO;
    boolean isBacklogToTodo = oldStatus == IssueStatus.BACKLOG && newStatus == IssueStatus.TODO;
    boolean isTodoToBacklog = oldStatus == IssueStatus.TODO && newStatus == IssueStatus.BACKLOG;

    if (!isReopen && !isBacklogToTodo && !isTodoToBacklog) {
      throw new AiValidationException(
          "issue",
          "Invalid explicit status transition from "
              + oldStatus
              + " to "
              + newStatus
              + ". Transitions to IN_PROGRESS, IN_REVIEW, or terminal states must follow execution rules.");
    }

    current.setStatus(newStatus);
    if (isReopen) {
      // Reopen 明确递增 specRevision
      current.setSpecRevision(current.getSpecRevision() + 1);
    }

    issueRepository.updateById(current, expectedVersion);

    if (newStatus == IssueStatus.TODO) {
      controllerWorkStore.requestWork(issueId, Instant.now());
    }

    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue cancelIssue(UUID issueId, long expectedVersion, String reason) {
    Objects.requireNonNull(issueId, "issueId");

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue",
          issueId.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
    }
    if (current.isTerminal()) {
      throw new AiValidationException("issue", "Cannot cancel an already terminal issue");
    }

    // 取消活跃 Run
    IssueRun activeRun = issueRunRepository.findActiveByIssueId(issueId);
    if (activeRun != null) {
      activeRun.setStatus(IssueRunStatus.CANCELLED);
      activeRun.setWaitingReason(reason != null ? reason : "Issue cancelled");
      activeRun.setCompletedAt(Instant.now());
      issueRunRepository.updateById(activeRun, activeRun.getVersion());
    }

    current.setStatus(IssueStatus.CANCELED);
    issueRepository.updateById(current, expectedVersion);

    controllerWorkStore.requestWork(issueId, Instant.now());
    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue archiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue",
          issueId.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
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
    issueRepository.updateById(current, expectedVersion);

    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue unarchiveIssue(UUID issueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue",
          issueId.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
    }
    if (!current.isArchived()) {
      return current;
    }

    current.setArchivedAt(null);
    issueRepository.updateById(current, expectedVersion);

    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public void addDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(dependsOnIssueId, "dependsOnIssueId");
    if (issueId.equals(dependsOnIssueId)) {
      throw new AiValidationException("issue_dependency", "Issue cannot depend on itself");
    }

    // 按稳定 UUID 顺序加锁两个 Issue，避免并发死锁
    UUID first = issueId.compareTo(dependsOnIssueId) < 0 ? issueId : dependsOnIssueId;
    UUID second = issueId.compareTo(dependsOnIssueId) < 0 ? dependsOnIssueId : issueId;
    Issue lockFirst = issueRepository.lockById(first);
    Issue lockSecond = issueRepository.lockById(second);

    Issue targetIssue = issueId.equals(first) ? lockFirst : lockSecond;
    Issue dependsOnIssue = dependsOnIssueId.equals(first) ? lockFirst : lockSecond;

    if (targetIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (dependsOnIssue == null) {
      throw new AiResourceNotFoundException("issue", dependsOnIssueId.toString());
    }
    if (!targetIssue.getProjectId().equals(dependsOnIssue.getProjectId())) {
      throw new AiValidationException(
          "issue_dependency", "Dependencies are only allowed between issues in the same project");
    }
    if (targetIssue.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue",
          issueId.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(targetIssue.getVersion()));
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
      throw new AiValidationException(
          "issue_dependency",
          "Dependency already exists between issue " + issueId + " and " + dependsOnIssueId);
    }

    // 环检测：检查 dependsOnIssue 是否已通过现有依赖路径到达 targetIssue
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
            .projectId(targetIssue.getProjectId())
            .build();
    issueDependencyRepository.addDependency(dependency);

    targetIssue.setSpecRevision(targetIssue.getSpecRevision() + 1);
    issueRepository.updateById(targetIssue, expectedVersion);

    if (targetIssue.getStatus() == IssueStatus.TODO) {
      controllerWorkStore.requestWork(issueId, Instant.now());
    }
  }

  @Transactional
  @Override
  public void removeDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(dependsOnIssueId, "dependsOnIssueId");

    Issue targetIssue = issueRepository.lockById(issueId);
    if (targetIssue == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    if (targetIssue.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "issue",
          issueId.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(targetIssue.getVersion()));
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
    issueRepository.updateById(targetIssue, expectedVersion);

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
    if (body == null || body.isBlank()) {
      throw new AiValidationException("issue_input", "Input body must not be blank");
    }

    String trimmedKey =
        idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
    if (trimmedKey != null) {
      IssueInput existing = issueInputRepository.findByIdempotencyKey(issueId, trimmedKey);
      if (existing != null) {
        return existing;
      }
    }

    Issue current = issueRepository.lockById(issueId);
    if (current == null) {
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }

    long newSequence = issueRepository.incrementInputSequence(issueId);
    IssueInput input =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(newSequence)
            .kind(kind)
            .body(body.trim())
            .idempotencyKey(trimmedKey)
            .build();
    issueInputRepository.append(input);

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
      throw new AiResourceNotFoundException("issue", issueId.toString());
    }
    return issue;
  }

  @Override
  public Issue getIssueByProjectAndNumber(UUID projectId, long number) {
    Objects.requireNonNull(projectId, "projectId");
    Issue issue = issueRepository.getByProjectAndNumber(projectId, number);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue", projectId + "#" + number);
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
  public List<IssueInput> listInputs(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueInputRepository.listByIssueId(issueId);
  }
}

package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatusTransition;
import fun.fengwk.kkstudio.platform.project.model.IssueTransitionAction;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;

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
  private final IssueActivityRepository issueActivityRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueWorkStore workStore;

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

    if (trimmedAssignee != null && trimmedAssignee.equals(trimmedReviewer)) {
      throw new AiValidationException(
          "issue", "assigneeAgentName and reviewerAgentName must not be the same agent");
    }

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
    String body = description != null ? description : "";
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(number)
            .title(trimmedTitle)
            .description(body)
            .status(status)
            .assigneeAgentName(trimmedAssignee)
            .reviewerAgentName(trimmedReviewer)
            .version(0L)
            .archivedAt(null)
            .build();

    boolean created = issueRepository.create(issue);
    if (!created) {
      throw new AiValidationException("issue", "Failed to create issue");
    }

    IssueActivity initialActivity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.SPEC_CHANGE)
            .actorType(IssueActivityActorType.HUMAN)
            .actorAgentName(null)
            .targetRole(null)
            .runId(null)
            .submissionRunId(null)
            .decision(null)
            .body(body)
            .idempotencyKey("initial_spec:" + issueId)
            .build();
    issueActivityRepository.appendOrGet(initialActivity);

    if (status == IssueStatus.TODO) {
      workStore.requestWork(issueId, Instant.now());
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

    if (trimmedAssignee != null && trimmedAssignee.equals(trimmedReviewer)) {
      throw new AiValidationException(
          "issue", "assigneeAgentName and reviewerAgentName must not be the same agent");
    }

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
    if (!current.isRequirementEditable()) {
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

    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    if (specChanged) {
      IssueActivity specActivity =
          IssueActivity.builder()
              .issueId(issueId)
              .kind(IssueActivityKind.SPEC_CHANGE)
              .actorType(IssueActivityActorType.HUMAN)
              .actorAgentName(null)
              .targetRole(null)
              .runId(null)
              .submissionRunId(null)
              .decision(null)
              .body(newDescription)
              .build();
      issueActivityRepository.appendOrGet(specActivity);
    }

    if (current.getStatus() == IssueStatus.TODO && specChanged) {
      workStore.requestWork(issueId, Instant.now());
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
    } else if (oldStatus == IssueStatus.BLOCKED && newStatus == IssueStatus.TODO) {
      action = IssueTransitionAction.RECOVER;
    } else if (oldStatus == IssueStatus.BLOCKED && newStatus == IssueStatus.BACKLOG) {
      action = IssueTransitionAction.RECOVER_TO_BACKLOG;
    } else {
      throw new AiValidationException(
          "issue",
          "Invalid explicit status transition from " + oldStatus + " to " + newStatus + ".");
    }

    if (!IssueStatusTransition.isAllowed(oldStatus, newStatus, action)) {
      throw new AiValidationException(
          "issue", "Transition from " + oldStatus + " to " + newStatus + " is not allowed");
    }

    current.setStatus(newStatus);
    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    if (action == IssueTransitionAction.REOPEN
        || action == IssueTransitionAction.RECOVER
        || action == IssueTransitionAction.RECOVER_TO_BACKLOG) {
      IssueActivity recoveryActivity =
          IssueActivity.builder()
              .issueId(issueId)
              .kind(IssueActivityKind.RECOVERY)
              .actorType(IssueActivityActorType.HUMAN)
              .body(
                  "Status changed from " + oldStatus + " to " + newStatus + " by action " + action)
              .build();
      issueActivityRepository.appendOrGet(recoveryActivity);
    }

    if (newStatus == IssueStatus.TODO) {
      workStore.requestWork(issueId, Instant.now());
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

    current.setStatus(IssueStatus.CANCELED);
    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    workStore.requestWork(issueId, Instant.now());
    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue recoverIssue(UUID issueId, long expectedVersion, boolean toBacklog, String comment) {
    Objects.requireNonNull(issueId, "issueId");
    String normalizedComment =
        (comment != null && !comment.isBlank()) ? comment.trim() : "Issue recovered by human";
    ProjectValidationUtils.validateUtf8Bytes(normalizedComment, "body", 65536, false);

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
    if (current.getStatus() != IssueStatus.BLOCKED) {
      throw new AiValidationException(
          "issue",
          "Only BLOCKED issues can be recovered, current status is " + current.getStatus());
    }

    IssueStatus newStatus = toBacklog ? IssueStatus.BACKLOG : IssueStatus.TODO;
    IssueTransitionAction action =
        toBacklog ? IssueTransitionAction.RECOVER_TO_BACKLOG : IssueTransitionAction.RECOVER;

    current.setStatus(newStatus);
    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    IssueActivity recoveryActivity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.RECOVERY)
            .actorType(IssueActivityActorType.HUMAN)
            .body(normalizedComment)
            .build();
    issueActivityRepository.appendOrGet(recoveryActivity);

    if (newStatus == IssueStatus.TODO) {
      workStore.requestWork(issueId, Instant.now());
    }
    return issueRepository.getById(issueId);
  }

  @Transactional
  @Override
  public Issue blockIssue(UUID issueId, long expectedVersion, String reason) {
    Objects.requireNonNull(issueId, "issueId");
    String normalizedReason =
        (reason != null && !reason.isBlank()) ? reason.trim() : "Issue blocked by human";
    ProjectValidationUtils.validateUtf8Bytes(normalizedReason, "body", 16384, true);

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
    if (current.isTerminal() || current.getStatus() == IssueStatus.BLOCKED) {
      throw new AiValidationException(
          "issue", "Cannot block an issue with status " + current.getStatus());
    }

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

    current.setStatus(IssueStatus.BLOCKED);
    boolean updated = issueRepository.updateById(current, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Blocked: " + normalizedReason)
            .build();
    issueActivityRepository.appendOrGet(activity);

    workStore.requestWork(issueId, Instant.now());
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

    Issue targetPre = issueRepository.getById(issueId);
    if (targetPre == null) {
      throw new AiResourceNotFoundException("issue");
    }
    UUID projectId = targetPre.getProjectId();

    Project project = projectRepository.lockById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new AiValidationException("project", "Cannot add dependency in an archived project");
    }

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
    if (!targetIssue.isRequirementEditable()) {
      throw new AiValidationException(
          "issue_dependency",
          "Dependencies can only be added to issues in BACKLOG or TODO, but target issue status is "
              + targetIssue.getStatus());
    }

    List<IssueDependency> existingDeps = issueDependencyRepository.listByIssueId(issueId);
    boolean alreadyExists =
        existingDeps.stream().anyMatch(d -> d.getDependsOnIssueId().equals(dependsOnIssueId));
    if (alreadyExists) {
      throw new AiValidationException("issue_dependency", "Dependency already exists");
    }

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

    boolean updated = issueRepository.updateById(targetIssue, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    IssueActivity specActivity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.SPEC_CHANGE)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Added dependency on issue " + dependsOnIssue.getNumber())
            .build();
    issueActivityRepository.appendOrGet(specActivity);

    if (targetIssue.getStatus() == IssueStatus.TODO) {
      workStore.requestWork(issueId, Instant.now());
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
    if (!targetIssue.isRequirementEditable()) {
      throw new AiValidationException(
          "issue_dependency",
          "Dependencies can only be removed from issues in BACKLOG or TODO, but target issue status is "
              + targetIssue.getStatus());
    }

    boolean removed = issueDependencyRepository.removeDependency(issueId, dependsOnIssueId);
    if (!removed) {
      return;
    }

    boolean updated = issueRepository.updateById(targetIssue, expectedVersion);
    if (!updated) {
      Issue latest = issueRepository.getById(issueId);
      throw new AiVersionConflictException(
          "issue",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }

    IssueActivity specActivity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.SPEC_CHANGE)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Removed dependency on issue " + dependsOnIssueId)
            .build();
    issueActivityRepository.appendOrGet(specActivity);

    if (targetIssue.getStatus() == IssueStatus.TODO) {
      workStore.requestWork(issueId, Instant.now());
    }
  }

  @Transactional
  @Override
  public IssueActivity appendActivity(IssueActivity activity) {
    Objects.requireNonNull(activity, "activity");
    Objects.requireNonNull(activity.getIssueId(), "issueId");
    Objects.requireNonNull(activity.getKind(), "kind");
    Objects.requireNonNull(activity.getActorType(), "actorType");

    ProjectValidationUtils.validateUtf8Bytes(activity.getBody(), "body", 1048576, false);
    String trimmedKey =
        ProjectValidationUtils.trimAndValidate(
            activity.getIdempotencyKey(), "idempotencyKey", 128, false);
    activity.setIdempotencyKey(trimmedKey);

    Issue initial = issueRepository.getById(activity.getIssueId());
    if (initial == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(initial.getProjectId());

    Issue current = issueRepository.lockById(activity.getIssueId());
    if (current == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (current.isArchived()) {
      throw new AiValidationException(
          "issue_activity", "Cannot append activity to an archived issue");
    }

    IssueActivity appended = issueActivityRepository.appendOrGet(activity);
    if (appended == null) {
      throw new AiValidationException("issue_activity", "Failed to append activity");
    }

    if (activity.getTargetRole() != null
        || activity.getKind() == IssueActivityKind.INSTRUCTION
        || activity.getKind() == IssueActivityKind.HUMAN_INPUT
        || activity.getKind() == IssueActivityKind.RETRY) {
      workStore.requestWork(activity.getIssueId(), Instant.now());
    }

    return appended;
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
  public List<IssueActivity> listActivities(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueActivityRepository.listByIssueId(issueId);
  }

  @Override
  public List<IssueActivity> listActivitiesPage(UUID issueId, long afterSequence, int limit) {
    Objects.requireNonNull(issueId, "issueId");
    if (afterSequence < 0) {
      throw new IllegalArgumentException("afterSequence must not be negative");
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return issueActivityRepository.listPage(issueId, afterSequence, limit);
  }

  @Override
  public long countRejections(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    long windowStart = issueActivityRepository.findReviewWindowStartSequence(issueId);
    return issueActivityRepository.countRejectionsSince(issueId, windowStart);
  }
}

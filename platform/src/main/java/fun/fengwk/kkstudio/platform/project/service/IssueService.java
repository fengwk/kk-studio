package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;

import java.util.List;
import java.util.UUID;

public interface IssueService {

  Issue createIssue(
      UUID projectId,
      String title,
      String description,
      String assigneeAgentName,
      String reviewerAgentName,
      IssueStatus initialStatus);

  Issue updateIssue(
      UUID issueId,
      long expectedVersion,
      String title,
      String description,
      String assigneeAgentName,
      String reviewerAgentName);

  Issue setStatus(UUID issueId, long expectedVersion, IssueStatus newStatus);

  Issue cancelIssue(UUID issueId, long expectedVersion, String reason);

  Issue archiveIssue(UUID issueId, long expectedVersion);

  Issue unarchiveIssue(UUID issueId, long expectedVersion);

  void addDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion);

  void removeDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion);

  IssueInput appendInput(UUID issueId, IssueInputKind kind, String body, String idempotencyKey);

  boolean isBlocked(UUID issueId);

  Issue getIssue(UUID issueId);

  Issue getIssueByProjectAndNumber(UUID projectId, long number);

  List<Issue> listIssues(UUID projectId, boolean includeArchived);

  List<IssueDependency> listDependencies(UUID issueId);

  List<IssueInput> listInputs(UUID issueId);
}

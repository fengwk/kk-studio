package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
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

  Issue recoverIssue(UUID issueId, long expectedVersion, boolean toBacklog, String comment);

  Issue blockIssue(UUID issueId, long expectedVersion, String reason);

  Issue archiveIssue(UUID issueId, long expectedVersion);

  Issue unarchiveIssue(UUID issueId, long expectedVersion);

  IssueDependency addDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion);

  void removeDependency(UUID issueId, UUID dependsOnIssueId, long expectedVersion);

  IssueActivity appendActivity(IssueActivity activity);

  boolean isBlocked(UUID issueId);

  Issue getIssue(UUID issueId);

  Issue getIssueByProjectAndNumber(UUID projectId, long number);

  List<Issue> listIssues(UUID projectId, boolean includeArchived);

  List<IssueDependency> listDependencies(UUID issueId);

  List<IssueDependency> listProjectDependencies(UUID projectId);

  List<IssueActivity> listActivities(UUID issueId);

  /** 返回 {@code sequence > afterSequence} 的最多 {@code limit} 条 Activity，供 Agent 有界分页读取。 */
  List<IssueActivity> listActivitiesPage(UUID issueId, long afterSequence, int limit);

  long countRejections(UUID issueId);
}

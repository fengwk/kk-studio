package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.orchestration.IssueAgentOwnership;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.Optional;
import java.util.UUID;

/** {@link IssueAgentOwnership} 的 Project 实现：按 Project -&gt; Issue 锁序锁定产品层级并读写稳定 Thread 绑定。 */
@AllArgsConstructor
@Service
public class ProjectIssueAgentOwnership implements IssueAgentOwnership {

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;

  @Override
  public Optional<UUID> lockForAcceptance(OwnerRef.IssueAgent owner) {
    Issue issue = issueRepository.getById(owner.issueId());
    if (issue == null) {
      throw new IllegalArgumentException("Issue owner does not exist");
    }
    UUID projectId = issue.getProjectId();
    Project project = projectRepository.lockForKeyShare(projectId);
    if (project == null) {
      throw new IllegalArgumentException("Project owner does not exist");
    }
    if (project.isArchived()) {
      throw new IllegalArgumentException("Cannot accept commands for archived project");
    }
    Issue lockedIssue = issueRepository.lockById(issue.getId());
    if (lockedIssue == null || !lockedIssue.getProjectId().equals(projectId)) {
      throw new IllegalArgumentException("Issue owner hierarchy is inconsistent");
    }
    if (lockedIssue.isArchived()) {
      throw new IllegalArgumentException("Cannot accept commands for archived issue");
    }
    return findBoundThread(owner);
  }

  @Override
  public Optional<UUID> lockForDeletion(OwnerRef.IssueAgent owner) {
    Issue issue = issueRepository.getById(owner.issueId());
    if (issue == null
        || projectRepository.lockById(issue.getProjectId()) == null
        || issueRepository.lockById(owner.issueId()) == null) {
      return Optional.empty();
    }
    return findBoundThread(owner);
  }

  @Override
  public Optional<UUID> findBoundThread(OwnerRef.IssueAgent owner) {
    return Optional.ofNullable(
            issueAgentThreadRepository.findByIssueIdAndAgentName(
                owner.issueId(), owner.agentName()))
        .map(IssueAgentThread::threadId);
  }

  @Override
  public void deleteBinding(OwnerRef.IssueAgent owner) {
    issueAgentThreadRepository.deleteByIssueIdAndAgentName(owner.issueId(), owner.agentName());
  }

  @Override
  public void lockRootForInteraction(UUID rootThreadId) {
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(rootThreadId);
    if (binding == null) {
      return;
    }
    Issue issue = issueRepository.getById(binding.issueId());
    if (issue == null) {
      throw new IllegalStateException("Issue owner does not exist");
    }
    UUID projectId = issue.getProjectId();
    if (projectRepository.lockForKeyShare(projectId) == null) {
      throw new IllegalStateException("Project owner does not exist");
    }
    Issue lockedIssue = issueRepository.lockById(issue.getId());
    if (lockedIssue == null || !lockedIssue.getProjectId().equals(projectId)) {
      throw new IllegalStateException("Issue owner hierarchy is inconsistent");
    }
  }

  @Override
  public Optional<BoundRoot> findBoundRoot(UUID rootThreadId) {
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(rootThreadId);
    if (binding == null) {
      return Optional.empty();
    }
    Issue issue = issueRepository.getById(binding.issueId());
    return Optional.of(
        new BoundRoot(
            binding.issueId(), binding.agentName(), issue == null ? null : issue.getTitle()));
  }
}

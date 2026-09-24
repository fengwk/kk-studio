package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@AllArgsConstructor
@Service
public class ProjectServiceImpl implements ProjectService {

  private static final int DEFAULT_MAX_REVIEW_REJECTIONS = 3;

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueDependencyRepository issueDependencyRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final IssueWorkRepository issueWorkRepository;
  private final SessionDeletionOrchestrator sessionDeletionOrchestrator;
  private final IssueEvidenceService issueEvidenceService;

  @Transactional
  @Override
  public Project createProject(
      String title, String description, boolean yoloEnabled, int maxReviewRejections) {
    String trimmedTitle = ProjectValidationUtils.trimAndValidate(title, "title", 255, true);
    ProjectValidationUtils.validateUtf8Bytes(description, "description", 65536, false);
    int rejections = maxReviewRejections > 0 ? maxReviewRejections : DEFAULT_MAX_REVIEW_REJECTIONS;

    Project project =
        Project.builder()
            .id(UUID.randomUUID())
            .title(trimmedTitle)
            .description(description != null ? description : "")
            .yoloEnabled(yoloEnabled)
            .maxReviewRejections(rejections)
            .nextIssueNumber(1L)
            .version(0L)
            .archivedAt(null)
            .build();
    boolean created = projectRepository.create(project);
    if (!created) {
      throw new AiValidationException("project", "Failed to create project");
    }
    return projectRepository.getById(project.getId());
  }

  @Transactional
  @Override
  public Project updateProject(
      UUID id,
      long expectedVersion,
      String title,
      String description,
      Boolean yoloEnabled,
      Integer maxReviewRejections) {
    Objects.requireNonNull(id, "id");
    Project current = projectRepository.lockById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "project", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (current.isArchived()) {
      throw new AiValidationException("project", "Archived project cannot be modified");
    }
    // Run 使用启动时的 YOLO 快照；持有 Project 锁时禁止在活动 Run 期间切换该策略。
    if (yoloEnabled != null && yoloEnabled != current.isYoloEnabled()) {
      if (issueRunRepository.hasActiveByProjectId(id)) {
        throw new AiValidationException(
            "project", "Cannot change yoloEnabled while the project has active issue runs");
      }
    }
    if (title != null) {
      String trimmedTitle = ProjectValidationUtils.trimAndValidate(title, "title", 255, true);
      current.setTitle(trimmedTitle);
    }
    if (description != null) {
      ProjectValidationUtils.validateUtf8Bytes(description, "description", 65536, false);
      current.setDescription(description);
    }
    if (yoloEnabled != null) {
      current.setYoloEnabled(yoloEnabled);
    }
    if (maxReviewRejections != null) {
      if (maxReviewRejections <= 0) {
        throw new AiValidationException(
            "project", "maxReviewRejections must be a positive integer");
      }
      current.setMaxReviewRejections(maxReviewRejections);
    }
    boolean updated = projectRepository.updateById(current, expectedVersion);
    if (!updated) {
      Project latest = projectRepository.getById(id);
      throw new AiVersionConflictException(
          "project",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }
    return projectRepository.getById(id);
  }

  @Transactional
  @Override
  public Project archiveProject(UUID id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    Project current = projectRepository.lockById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "project", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (current.isArchived()) {
      return current;
    }
    boolean updated = projectRepository.updateArchivedAt(id, Instant.now(), expectedVersion);
    if (!updated) {
      Project latest = projectRepository.getById(id);
      throw new AiVersionConflictException(
          "project",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }
    return projectRepository.getById(id);
  }

  @Transactional
  @Override
  public Project unarchiveProject(UUID id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    Project current = projectRepository.lockById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "project", String.valueOf(expectedVersion), String.valueOf(current.getVersion()));
    }
    if (!current.isArchived()) {
      return current;
    }
    boolean updated = projectRepository.updateArchivedAt(id, null, expectedVersion);
    if (!updated) {
      Project latest = projectRepository.getById(id);
      throw new AiVersionConflictException(
          "project",
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }
    return projectRepository.getById(id);
  }

  @Transactional
  @Override
  public void deleteProject(UUID id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    if (expectedVersion < 0) {
      throw new AiValidationException("project", "expectedVersion must be non-negative");
    }

    // 1. 锁 Project 行并校验存在性与版本
    Project project = projectRepository.lockById(id);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (project.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "project", String.valueOf(expectedVersion), String.valueOf(project.getVersion()));
    }

    // 2. 查出该 Project 下的所有 Issues，并按 UUID 升序锁定
    List<Issue> issues = issueRepository.listByProjectId(id);
    List<Issue> sortedIssues =
        issues.stream().sorted(Comparator.comparing(Issue::getId, UuidOrder.COMPARATOR)).toList();

    for (Issue issue : sortedIssues) {
      Issue lockedIssue = issueRepository.lockById(issue.getId());
      if (lockedIssue == null || !lockedIssue.getProjectId().equals(id)) {
        throw new AiValidationException(
            "issue", "Issue disappeared or belongs to different project");
      }
    }

    // 3. 查出所有 Issues 的所有 Runs，并按 UUID 升序锁定
    List<IssueRun> allRuns = new ArrayList<>();
    for (Issue issue : sortedIssues) {
      allRuns.addAll(issueRunRepository.listByIssueId(issue.getId()));
    }
    List<IssueRun> sortedRuns =
        allRuns.stream()
            .sorted(Comparator.comparing(IssueRun::getId, UuidOrder.COMPARATOR))
            .toList();

    for (IssueRun run : sortedRuns) {
      IssueRun lockedRun = issueRunRepository.lockById(run.getId());
      if (lockedRun == null) {
        throw new AiValidationException("issue_run", "Issue run disappeared");
      }
    }

    // 4. 校验所有 Runs：若任何 run 为 RUNNING / WAITING_HUMAN / UNKNOWN 则明确拒绝
    for (IssueRun run : sortedRuns) {
      IssueRunStatus status = run.getStatus();
      if (status == IssueRunStatus.RUNNING
          || status == IssueRunStatus.WAITING_HUMAN
          || status == IssueRunStatus.UNKNOWN) {
        throw new AiValidationException(
            "issue_run", "Cannot delete project with active or unknown runs");
      }
    }

    // 5. 编排删除：
    // (a) issue work
    for (Issue issue : sortedIssues) {
      issueWorkRepository.deleteByIssueId(issue.getId());
    }

    // (b) issue agent sessions & deleteSessionsByOwner
    for (Issue issue : sortedIssues) {
      if (issue.getAssigneeAgentName() != null) {
        IssueAgentSession s =
            issueAgentSessionRepository.findByIssueIdAndAgentName(
                issue.getId(), issue.getAssigneeAgentName());
        if (s != null) {
          sessionDeletionOrchestrator.deleteSessionsByOwner(
              new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, s.getId()));
        }
      }
      if (issue.getReviewerAgentName() != null) {
        IssueAgentSession s =
            issueAgentSessionRepository.findByIssueIdAndAgentName(
                issue.getId(), issue.getReviewerAgentName());
        if (s != null) {
          sessionDeletionOrchestrator.deleteSessionsByOwner(
              new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, s.getId()));
        }
      }
      issueAgentSessionRepository.deleteByIssueId(issue.getId());
    }

    // (c) issue evidence：Issue 持有的已发布 Blob 引用必须先于 Run 行删除（run FK RESTRICT），
    // 逐行 release Issue 引用；Session 侧引用已由 (b) 的深删除各自释放。
    for (Issue issue : sortedIssues) {
      issueEvidenceService.deleteByIssue(issue.getId());
    }

    // (d) run rows：先删 reviewer (有 submission_run_id)，再删 executor
    List<IssueRun> reviewerRuns =
        sortedRuns.stream().filter(r -> r.getSubmissionRunId() != null).toList();
    List<IssueRun> executorRuns =
        sortedRuns.stream().filter(r -> r.getSubmissionRunId() == null).toList();

    for (IssueRun run : reviewerRuns) {
      boolean deleted = issueRunRepository.deleteById(run.getId(), run.getVersion());
      if (!deleted) {
        throw new AiVersionConflictException(
            "issue_run", String.valueOf(run.getVersion()), "unknown");
      }
    }
    for (IssueRun run : executorRuns) {
      boolean deleted = issueRunRepository.deleteById(run.getId(), run.getVersion());
      if (!deleted) {
        throw new AiVersionConflictException(
            "issue_run", String.valueOf(run.getVersion()), "unknown");
      }
    }

    // (e) activities
    for (Issue issue : sortedIssues) {
      issueActivityRepository.deleteByIssueId(issue.getId());
    }

    // (f) dependency edges
    List<IssueDependency> deps = issueDependencyRepository.listByProjectId(id);
    if (!deps.isEmpty()) {
      int deleted = issueDependencyRepository.deleteByProjectId(id);
      if (deleted != deps.size()) {
        throw new AiValidationException(
            "issue_dependency",
            "Deleted dependencies count mismatch: expected " + deps.size() + ", actual " + deleted);
      }
    }

    // (g) issues
    for (Issue issue : sortedIssues) {
      boolean deleted = issueRepository.deleteById(issue.getId(), issue.getVersion());
      if (!deleted) {
        throw new AiVersionConflictException(
            "issue", String.valueOf(issue.getVersion()), "unknown");
      }
    }

    // (h) project CAS 删除
    boolean projectDeleted = projectRepository.deleteById(id, expectedVersion);
    if (!projectDeleted) {
      throw new AiVersionConflictException("project", String.valueOf(expectedVersion), "unknown");
    }
  }

  @Override
  public Project getProject(UUID id) {
    Objects.requireNonNull(id, "id");
    Project project = projectRepository.getById(id);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    return project;
  }

  @Override
  public List<Project> listProjects(boolean includeArchived) {
    if (includeArchived) {
      return projectRepository.listAll();
    }
    return projectRepository.listByArchived(false);
  }
}

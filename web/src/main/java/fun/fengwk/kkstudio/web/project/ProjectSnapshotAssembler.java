package fun.fengwk.kkstudio.web.project;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.share.project.IssueDependencyDTO;
import fun.fengwk.kkstudio.share.project.ProjectIssueSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Project Snapshot 权威聚合器。
 *
 * <p>读取 Project、未归档 Issues、同项目依赖边、每 Issue 的 blocked 状态与当前/最近 Run， 以及 Coordinator Session / Thread
 * 摘要。
 *
 * <p>对所有跨表实体严格执行 {@code projectId} 与 {@code issueId} 归属校验，任何不一致立即 fail-closed 抛出异常，绝不泄漏外国实体。
 */
@AllArgsConstructor
@Component
public class ProjectSnapshotAssembler {

  private final ProjectService projectService;
  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final HarnessOwnerQueryService harnessOwnerQueryService;
  private final ProjectDtoMapper mapper;

  public ProjectSnapshotDTO assemble(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");

    Project project = projectService.getProject(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    if (!project.getId().equals(projectId)) {
      throw new IllegalStateException("Foreign project returned for snapshot");
    }

    // 1. 读取未归档 Issues
    List<Issue> issues = issueService.listIssues(projectId, false);
    List<ProjectIssueSnapshotDTO> issueSnapshots = new ArrayList<>(issues.size());
    for (Issue issue : issues) {
      if (!issue.getProjectId().equals(projectId)) {
        throw new IllegalStateException("Foreign issue returned for project snapshot");
      }

      boolean blocked = issueService.isBlocked(issue.getId());

      IssueRun activeRun = issueRunService.getActiveRun(issue.getId());
      IssueRun currentOrLatest =
          activeRun != null ? activeRun : issueRunService.getLatestRun(issue.getId());
      if (currentOrLatest != null && !currentOrLatest.getIssueId().equals(issue.getId())) {
        throw new IllegalStateException("Foreign run returned for issue snapshot");
      }

      issueSnapshots.add(
          ProjectIssueSnapshotDTO.builder()
              .issue(mapper.toDto(issue))
              .blocked(blocked)
              .currentOrLatestRun(mapper.toSummaryDto(currentOrLatest))
              .build());
    }

    // 2. 读取同项目依赖边
    List<IssueDependency> dependencies = issueService.listProjectDependencies(projectId);
    List<IssueDependencyDTO> dependencyDTOs = new ArrayList<>(dependencies.size());
    for (IssueDependency dep : dependencies) {
      if (!dep.getProjectId().equals(projectId)) {
        throw new IllegalStateException("Foreign dependency returned for project snapshot");
      }
      dependencyDTOs.add(mapper.toDto(dep));
    }

    // 3. 读取 Coordinator Session 与 Thread projection
    String coordinatorSessionId = null;
    HarnessSessionSummaryDTO coordinatorSession = null;
    HarnessThreadSummaryDTO coordinatorThread = null;

    ProjectSession sessionRelation = projectService.getCoordinatorSession(projectId);
    if (sessionRelation != null) {
      if (!sessionRelation.getProjectId().equals(projectId)) {
        throw new IllegalStateException(
            "Foreign coordinator session relation returned for project snapshot");
      }
      UUID sessionId = sessionRelation.getSessionId();
      coordinatorSessionId = ProjectDtoMapper.formatUuid(sessionId);

      List<HarnessSessionSummaryDTO> sessions =
          harnessOwnerQueryService.listProjectSessions(projectId);
      for (HarnessSessionSummaryDTO s : sessions) {
        if (s.getSessionId().equalsIgnoreCase(coordinatorSessionId)) {
          coordinatorSession = s;
          break;
        }
      }

      List<HarnessThreadSummaryDTO> threads =
          harnessOwnerQueryService.listThreadSummaries(sessionId);
      if (!threads.isEmpty()) {
        coordinatorThread = threads.get(0);
      }
    }

    return ProjectSnapshotDTO.builder()
        .project(mapper.toDto(project))
        .issues(dependencySortOrNatural(issueSnapshots))
        .dependencies(dependencyDTOs)
        .coordinatorSessionId(coordinatorSessionId)
        .coordinatorSession(coordinatorSession)
        .coordinatorThread(coordinatorThread)
        .build();
  }

  private List<ProjectIssueSnapshotDTO> dependencySortOrNatural(
      List<ProjectIssueSnapshotDTO> snapshots) {
    // 保持确定性输出：按 number 升序排列
    return snapshots.stream()
        .sorted(
            (a, b) ->
                Long.compare(
                    Long.parseLong(a.getIssue().getNumber()),
                    Long.parseLong(b.getIssue().getNumber())))
        .toList();
  }
}

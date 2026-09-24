package fun.fengwk.kkstudio.web.project;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
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
 * <p>读取 Project、未归档 Issues、同项目依赖边、每 Issue 的 blocked 状态与当前/最近 Run。
 *
 * <p>对所有跨表实体严格执行 {@code projectId} 与 {@code issueId} 归属校验，任何不一致立即 fail-closed 抛出异常，绝不泄漏外国实体。
 */
@AllArgsConstructor
@Component
public class ProjectSnapshotAssembler {

  private final ProjectService projectService;
  private final IssueService issueService;
  private final IssueRunService issueRunService;
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
      // 打回次数由 IssueService 按当前审查窗口权威给出（含重置/恢复语义），客户端不得从分页 Activity 自行推导
      long reviewRejectionCount = issueService.countRejections(issue.getId());

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
              .reviewRejectionCount(String.valueOf(reviewRejectionCount))
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

    return ProjectSnapshotDTO.builder()
        .project(mapper.toDto(project))
        .issues(dependencySortOrNatural(issueSnapshots))
        .dependencies(dependencyDTOs)
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

package fun.fengwk.kkstudio.web.project;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.share.project.ProjectIssueSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Project Snapshot 权威聚合器。
 *
 * <p>读取 Project、未归档 Issues 与每 Issue 的当前/最近 Run 概要（含解析自稳定 Thread 的 Agent 名称）。
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

    List<Issue> issues = issueService.listIssues(projectId, false);
    List<ProjectIssueSnapshotDTO> issueSnapshots = new ArrayList<>(issues.size());
    for (Issue issue : issues) {
      if (!issue.getProjectId().equals(projectId)) {
        throw new IllegalStateException("Foreign issue returned for project snapshot");
      }

      IssueRun activeRun = issueRunService.getActiveRun(issue.getId());
      IssueRun currentOrLatest =
          activeRun != null ? activeRun : issueRunService.getLatestRun(issue.getId());
      if (currentOrLatest != null && !currentOrLatest.getIssueId().equals(issue.getId())) {
        throw new IllegalStateException("Foreign run returned for issue snapshot");
      }

      String agentName = null;
      if (currentOrLatest != null && currentOrLatest.getThreadId() != null) {
        List<IssueAgentThread> threads = issueService.listAgentThreads(issue.getId());
        for (IssueAgentThread thread : threads) {
          if (currentOrLatest.getThreadId().equals(thread.threadId())) {
            agentName = thread.agentName();
            break;
          }
        }
      }

      issueSnapshots.add(
          ProjectIssueSnapshotDTO.builder()
              .issue(mapper.toDto(issue))
              .currentOrLatestRun(mapper.toSummaryDto(currentOrLatest, agentName))
              .build());
    }

    return ProjectSnapshotDTO.builder()
        .project(mapper.toDto(project))
        .issues(sortByNumber(issueSnapshots))
        .build();
  }

  private List<ProjectIssueSnapshotDTO> sortByNumber(List<ProjectIssueSnapshotDTO> snapshots) {
    return snapshots.stream()
        .sorted(
            (a, b) ->
                Long.compare(
                    Long.parseLong(a.getIssue().getNumber()),
                    Long.parseLong(b.getIssue().getNumber())))
        .toList();
  }
}

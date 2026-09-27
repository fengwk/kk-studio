package fun.fengwk.kkstudio.web.project;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.share.project.ProjectIssueSnapshotDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;

import java.util.ArrayList;
import java.util.Comparator;
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
      throw new ProjectNotFoundException("project");
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

      List<IssueRun> runs = issueRunService.listRuns(issue.getId());
      for (IssueRun run : runs) {
        if (!run.getIssueId().equals(issue.getId())) {
          throw new IllegalStateException("Foreign run returned for issue snapshot");
        }
      }
      IssueRun currentOrLatest =
          runs.stream()
              .filter(
                  run ->
                      run.getStatus() == IssueRunStatus.RUNNING
                          || run.getStatus() == IssueRunStatus.WAITING)
              .findFirst()
              .orElseGet(
                  () ->
                      runs.stream()
                          .max(Comparator.comparingLong(IssueRun::getOrdinal))
                          .orElse(null));

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

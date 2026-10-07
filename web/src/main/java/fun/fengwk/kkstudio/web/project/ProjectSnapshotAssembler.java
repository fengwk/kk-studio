package fun.fengwk.kkstudio.web.project;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Project Snapshot 权威聚合器。
 *
 * <p>读取 Project、未归档 Issues 与每 Issue 的当前/最近 Run 概要（含解析自稳定 Thread 的 Agent 名称）。 所有未归档及归档 Issue
 * 仅共同贡献状态引用集合，归档 Issue 的内容与 Run/Thread 不进入快照。
 *
 * <p>整个组装在同一个只读 {@code REPEATABLE_READ} 事务内完成（声明式，由 Spring 代理生效）：否则多个独立查询之间可能有其它事务提交， 从而拼出旧 Project
 * + 新 Issue/Run 的撕裂响应。
 *
 * <p>对所有跨表实体严格执行 {@code projectId} 与 {@code issueId} 归属校验，任何不一致立即 fail-closed 抛出异常，绝不泄漏外国实体。
 */
@Component
public class ProjectSnapshotAssembler {

  private final ProjectService projectService;
  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final ProjectDtoMapper mapper;

  public ProjectSnapshotAssembler(
      ProjectService projectService,
      IssueService issueService,
      IssueRunService issueRunService,
      ProjectDtoMapper mapper) {
    this.projectService = Objects.requireNonNull(projectService, "projectService");
    this.issueService = Objects.requireNonNull(issueService, "issueService");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
    Set<String> referencedStateCodes = new TreeSet<>();
    collectReferencedStates(projectId, issues, referencedStateCodes);
    collectReferencedStates(
        projectId, issueService.listIssues(projectId, true), referencedStateCodes);
    List<ProjectIssueSnapshotDTO> issueSnapshots = new ArrayList<>(issues.size());
    for (Issue issue : issues) {
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
        .referencedStateCodes(List.copyOf(referencedStateCodes))
        .build();
  }

  private void collectReferencedStates(
      UUID projectId, List<Issue> issues, Set<String> referencedStateCodes) {
    for (Issue issue : issues) {
      if (!issue.getProjectId().equals(projectId)) {
        throw new IllegalStateException("Foreign issue returned for project snapshot");
      }
      referencedStateCodes.add(issue.getState());
      if (issue.getBlockedFromState() != null) {
        referencedStateCodes.add(issue.getBlockedFromState());
      }
    }
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

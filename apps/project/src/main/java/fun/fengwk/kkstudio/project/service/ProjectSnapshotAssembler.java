package fun.fengwk.kkstudio.project.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.dto.ProjectIssueSnapshotDTO;
import fun.fengwk.kkstudio.project.dto.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Project Snapshot 权威聚合器。
 *
 * <p>读取 Project、所有 Issue 与每 Issue 的当前/最近 Run 概要（含解析自稳定 Thread 的 Agent 名称）。
 *
 * <p>看板只消费每 Issue 一条 Run 摘要，因此 Run 与 Agent Thread 绑定都按项目一次读取（每 Issue 至多一行 Run），读取量不随 Run 历史增长。
 *
 * <p>整个组装在同一个只读 {@code REPEATABLE_READ} 事务内完成（声明式，由 Spring 代理生效）：否则多个独立查询之间可能有其它事务提交， 从而拼出旧 Project
 * + 新 Issue/Run 的撕裂响应。
 */
@Component
public class ProjectSnapshotAssembler {

  private final ProjectService projectService;
  private final IssueEditingService issueEditingService;
  private final IssueRunService issueRunService;
  private final ProjectDtoMapper mapper;

  public ProjectSnapshotAssembler(
      ProjectService projectService,
      IssueEditingService issueEditingService,
      IssueRunService issueRunService,
      ProjectDtoMapper mapper) {
    this.projectService = Objects.requireNonNull(projectService, "projectService");
    this.issueEditingService = Objects.requireNonNull(issueEditingService, "issueEditingService");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ProjectSnapshotDTO assemble(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");

    Project project = projectService.getProject(projectId);

    List<Issue> issues = issueEditingService.listIssues(projectId);
    // 两个项目级读取而非每 Issue 一次：Run 只取当前/最近一条，绑定一次取全项目。
    Map<UUID, IssueRun> currentOrLatestRuns = issueRunService.currentOrLatestRuns(projectId);
    Map<UUID, String> agentNamesByThread = agentNamesByThread(projectId);
    Set<String> referencedStateCodes = new TreeSet<>();
    collectReferencedStates(issues, referencedStateCodes);
    List<ProjectIssueSnapshotDTO> issueSnapshots = new ArrayList<>(issues.size());
    for (Issue issue : issues) {
      IssueRun currentOrLatest = currentOrLatestRuns.get(issue.getId());
      String agentName =
          currentOrLatest == null || currentOrLatest.getThreadId() == null
              ? null
              : agentNamesByThread.get(currentOrLatest.getThreadId());

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

  /**
   * 项目内 Thread → Agent 名称索引。
   *
   * <p>{@code project_issue_run(issue_id, thread_id)} 的复合外键保证 Run 的 Thread 必属同一 Issue，且 Thread
   * 身份全局唯一（{@code uk_project_issue_agent_thread_thread}），因此按 threadId 索引等价于按 Issue 查找绑定。
   */
  private Map<UUID, String> agentNamesByThread(UUID projectId) {
    Map<UUID, String> agentNames = new HashMap<>();
    for (IssueAgentThread thread : issueEditingService.listProjectAgentThreads(projectId)) {
      agentNames.put(thread.threadId(), thread.agentName());
    }
    return agentNames;
  }

  private void collectReferencedStates(List<Issue> issues, Set<String> referencedStateCodes) {
    for (Issue issue : issues) {
      referencedStateCodes.add(issue.getState());
      if (issue.getBlockedFromState() != null) {
        referencedStateCodes.add(issue.getBlockedFromState());
      }
    }
  }

  private List<ProjectIssueSnapshotDTO> sortByNumber(List<ProjectIssueSnapshotDTO> snapshots) {
    return snapshots.stream()
        .sorted(Comparator.comparingLong(s -> s.getIssue().getNumber()))
        .toList();
  }
}

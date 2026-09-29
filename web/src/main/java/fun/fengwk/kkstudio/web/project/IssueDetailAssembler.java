package fun.fengwk.kkstudio.web.project;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.IssueService.StageBudgetView;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueAgentThreadDTO;
import fun.fengwk.kkstudio.share.project.IssueDetailDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.IssueStageBudgetDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 详情权威聚合器。
 *
 * <p>把 Issue 事实、活动页、Run 概要、阶段额度与 Agent Thread 组装成一个响应。整段读取在同一个只读 {@code REPEATABLE_READ}
 * 事务内完成（声明式，由 Spring 代理生效），避免多个独立查询之间被其它事务提交穿插，拼出互相矛盾的 Issue/Run 视图。
 */
@Component
public class IssueDetailAssembler {

  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final ProjectService projectService;
  private final ProjectDtoMapper mapper;
  private final ProjectWorkflowJsonCodec workflowCodec;

  public IssueDetailAssembler(
      IssueService issueService,
      IssueRunService issueRunService,
      ProjectService projectService,
      ProjectDtoMapper mapper,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.issueService = Objects.requireNonNull(issueService, "issueService");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.projectService = Objects.requireNonNull(projectService, "projectService");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public IssueDetailDTO assemble(UUID issueId, long afterSequence, int activityLimit) {
    Objects.requireNonNull(issueId, "issueId");

    Issue issue = issueService.getIssue(issueId);
    List<IssueActivity> activities =
        issueService.listActivities(issueId, afterSequence, activityLimit);
    List<IssueActivityDTO> activityDtos = activities.stream().map(mapper::toDto).toList();

    String nextActivityCursor = null;
    if (activities.size() == activityLimit) {
      long lastSequence = activities.get(activities.size() - 1).getSequence();
      nextActivityCursor = String.valueOf(lastSequence);
    }

    List<IssueAgentThread> agentThreads = issueService.listAgentThreads(issueId);
    List<IssueAgentThreadDTO> agentThreadDtos = agentThreads.stream().map(mapper::toDto).toList();
    Map<UUID, String> threadToAgent = new HashMap<>();
    for (IssueAgentThread thread : agentThreads) {
      threadToAgent.put(thread.threadId(), thread.agentName());
    }

    List<IssueRun> runs = issueRunService.listRuns(issueId);
    List<IssueRunDTO> runDtos = new ArrayList<>(runs.size());
    for (IssueRun run : runs) {
      runDtos.add(mapper.toDto(run, threadToAgent.get(run.getThreadId())));
    }

    IssueRun activeRun =
        runs.stream()
            .filter(
                run ->
                    run.getStatus() == IssueRunStatus.RUNNING
                        || run.getStatus() == IssueRunStatus.WAITING)
            .findFirst()
            .orElse(null);
    IssueRunDTO currentRunDto =
        activeRun != null
            ? mapper.toDto(activeRun, threadToAgent.get(activeRun.getThreadId()))
            : null;

    IssueRun latestRun =
        runs.stream().max(Comparator.comparingLong(IssueRun::getOrdinal)).orElse(null);
    IssueRunDTO latestRunDto =
        latestRun != null
            ? mapper.toDto(latestRun, threadToAgent.get(latestRun.getThreadId()))
            : null;

    List<IssueStageBudgetDTO> stageBudgets = new ArrayList<>();
    try {
      Project project = projectService.getProject(issue.getProjectId());
      if (project != null
          && project.getWorkflowJson() != null
          && !project.getWorkflowJson().isBlank()) {
        ProjectWorkflow workflow = workflowCodec.decode(project.getWorkflowJson());
        for (ProjectWorkflowState state : workflow.workStages()) {
          try {
            StageBudgetView view = issueService.getStageBudget(issueId, state.state().value());
            stageBudgets.add(mapper.toDto(view));
          } catch (ProjectNotFoundException ignored) {
            // 阶段额度尚未授权时静默跳过
          }
        }
      }
    } catch (ProjectNotFoundException ignored) {
      // 项目未找到时跳过
    }

    return IssueDetailDTO.builder()
        .issue(mapper.toDto(issue))
        .activities(activityDtos)
        .nextActivityCursor(nextActivityCursor)
        .runs(runDtos)
        .currentRun(currentRunDto)
        .latestRun(latestRunDto)
        .stageBudgets(stageBudgets)
        .agentThreads(agentThreadDtos)
        .build();
  }
}

package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 生产 {@link ProjectIssueTurnResolver}：以稳定 Thread 绑定为入口，按当前 Project workflow 与活动 Run 读取 Issue Agent
 * turn 的权威事实。
 *
 * <p>判定顺序固定为「归属 → Issue/Project 事实 → 当前阶段职责 → 活动 Run 坐标」，每一步都失败关闭：
 *
 * <ul>
 *   <li>Thread 无绑定即普通 branch，返回空；有绑定时 Agent 自然名称必须与 branch 冻结的 Agent 完全一致，否则拒绝（Thread 不可重绑，也 不可被别的
 *       Agent 借用）。
 *   <li>Issue 必须存在、未归档、没有控制暂停门禁，且处于 workflow 中启用且有 Agent 的工作阶段；阶段当前必须仍指派给该绑定 Agent，否则拒绝 （改派后的旧
 *       Thread 不能继续以旧职责执行）。
 *   <li>必须存在活动 Run，且其 Thread、Session 与阶段都与当前 branch/Issue 一致；否则拒绝（旧 Run、别的 Thread 或别的阶段的迟到执行
 *       都不借当前权限）。
 * </ul>
 *
 * <p>阶段职责（instructions 与 Environment）每轮都从实时 workflow 现读，只作为本 turn 的业务输入；归属存在但 Issue/Project 行缺失等数据
 * 不一致抛 {@link IllegalStateException}，绝不返回空来掩盖问题。
 */
public class DatabaseProjectIssueTurnResolver implements ProjectIssueTurnResolver {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueRepository issueRepository;
  private final ProjectRepository projectRepository;
  private final IssueRunRepository issueRunRepository;
  private final ProjectWorkflowJsonCodec workflowCodec;

  public DatabaseProjectIssueTurnResolver(
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueRepository issueRepository,
      ProjectRepository projectRepository,
      IssueRunRepository issueRunRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProjectIssueTurnFacts> resolve(UUID threadId, String agentName, UUID sessionId) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(agentName, "agentName");
    Objects.requireNonNull(sessionId, "sessionId");

    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(threadId);
    if (binding == null) {
      return Optional.empty();
    }
    if (!binding.agentName().equals(agentName)) {
      throw rejection(
          "Issue agent thread is bound to agent " + binding.agentName() + ", not " + agentName);
    }

    Issue issue = issueRepository.getById(binding.issueId());
    if (issue == null) {
      throw inconsistent();
    }
    Project project = projectRepository.getById(issue.getProjectId());
    if (project == null) {
      throw inconsistent();
    }
    if (issue.isArchived()) {
      throw rejection("Issue is archived");
    }
    if (issue.isPaused()) {
      throw rejection("Issue is paused: " + issue.getPauseReason());
    }

    ProjectWorkflowState stage = requireCurrentStage(project, issue);
    if (!stage.agent().equals(binding.agentName())) {
      throw rejection(
          "Issue stage "
              + stage.state()
              + " is assigned to agent "
              + stage.agent()
              + ", not "
              + binding.agentName());
    }

    IssueRun run = requireActiveRun(issue, threadId, sessionId);
    return Optional.of(
        new ProjectIssueTurnFacts(
            issue.getId(),
            project.getId(),
            run.getId(),
            issue.getNumber(),
            issue.getTitle(),
            issue.getDescription(),
            stage.state().value(),
            stage.name(),
            stage.instructions(),
            stage.next().stream().map(ProjectStateCode::value).toList(),
            stage.environment(),
            binding.agentName()));
  }

  /** 当前阶段必须是 workflow 中启用且有 Agent 的工作阶段；保留阶段与停用/人工阶段都不产生 Issue Agent turn。 */
  private ProjectWorkflowState requireCurrentStage(Project project, Issue issue) {
    ProjectWorkflow workflow;
    ProjectStateCode state;
    try {
      workflow = workflowCodec.decode(project.getWorkflowJson());
      state = ProjectStateCode.of(issue.getState());
    } catch (IllegalArgumentException error) {
      throw rejection("invalid project workflow or issue state: " + issue.getState());
    }
    if (ProjectWorkflowReservedState.isReserved(state)) {
      throw rejection("Issue is not in an executable work stage: " + state);
    }
    ProjectWorkflowState stage = workflow.find(state).orElse(null);
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      throw rejection("Issue stage " + state + " has no enabled agent");
    }
    return stage;
  }

  /**
   * 活动主 Run 必须精确属于当前 Thread、Session 与阶段：Run 的 Thread/Session 坐标是冻结历史坐标，只比较阶段字符串无法区分同名阶段的旧
   * Run，因此三者都必须一致。
   */
  private IssueRun requireActiveRun(Issue issue, UUID threadId, UUID sessionId) {
    IssueRun run = issueRunRepository.getActiveByIssueId(issue.getId());
    if (run == null || !run.isActive()) {
      throw rejection("Issue has no active run");
    }
    if (!run.getThreadId().equals(threadId)) {
      throw rejection("Active run belongs to a different thread");
    }
    if (!run.getSessionId().equals(sessionId)) {
      throw rejection("Active run belongs to a different session");
    }
    if (!run.getState().equals(issue.getState())) {
      throw rejection(
          "Active run stage "
              + run.getState()
              + " does not match the current issue stage "
              + issue.getState());
    }
    return run;
  }

  /** Issue+Agent 的稳定绑定与 Issue 行不一致是数据不一致，不是规划失败：原样传播而不是降级为"无角色工具"。 */
  private static IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
  }

  private static ProjectIssueTurnRejection rejection(String message) {
    return new ProjectIssueTurnRejection(message);
  }
}

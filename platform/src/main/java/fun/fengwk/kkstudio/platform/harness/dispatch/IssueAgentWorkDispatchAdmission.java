package fun.fengwk.kkstudio.platform.harness.dispatch;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchAdmission;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
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
import java.util.function.Supplier;

/**
 * Issue+Agent 的产品派发门禁：把「宿主是否允许开始一次新的对外执行」与 Harness 的 READY -&gt; DISPATCHING 状态转换放进同一个物理事务。
 *
 * <p>Harness 在 prepare 短事务内、取任何 Harness 行锁之前调用本实现（见 {@link
 * WorkDispatchAdmission#executeIfAdmitted}）。本实现加入该事务（{@code PROPAGATION_REQUIRED}），按 Project（{@code
 * FOR SHARE}）-&gt; Issue（{@code FOR UPDATE}）-&gt; 活动 Run（{@code FOR UPDATE}）的顺序取产品行锁，复验全部产品事实；只有
 * 判定通过才执行意图，从而让「暂停 / 交接」与「首次对外派发」共享同一个线性化点：人工暂停先取得 Issue 行锁时，派发事务在取锁处等待，随后读到已提交的
 * 暂停事实并被拒绝；派发事务先提交时，暂停在派发完成后才生效，在途执行仍可收尾。锁序与生产写路径（例如 {@code IssueServiceImpl}/{@code
 * IssueRunServiceImpl} 的 Project -&gt; Issue -&gt; Run -&gt; Harness Thread 顺序）一致， 不引入 Thread -&gt;
 * Issue 的反向锁序；这里也绝不等待任何外部 I/O（外部调用只在事务提交后发生）。
 *
 * <p>判定沿用产品事实，每一步都失败关闭：Thread 无绑定即 Chat / 内部委派（没有产品暂停语义，放行）；绑定存在但 Issue/Project
 * 行缺失属于归属事实损坏，拒绝而不是退化成无限放行；Project 已归档、Issue 已归档、Issue 处于控制暂停或当前阶段是保留状态（INIT / BLOCKED / DONE）都拒绝。
 *
 * <p>即使是绑定的 Thread，也必须是当前活动 Run 的 Agent 坐标：活动 Run 必须存在且仍 active，其 Thread、Session 与阶段必须与该 Thread
 * 精确一致，且当前阶段仍指派给该 Agent。旧 Run、别的 Thread/Session、别的阶段的迟到执行都不借当前权限开始新的对外执行。
 *
 * <p>正在进入下一状态（活动 Run 的 {@code nextState} 已登记）时，本 Run 已进入收尾：禁止业务工具写，但允许模型收尾与既有结果物化。工具是否 只读由冻结绑定自带的
 * {@code descriptor().sideEffect()} 判定——它是该次调用冻结的能力声明，不随目录变化，也不需要额外查询；无法判定归属或 只读性的新工具派发一律拒绝（fail
 * closed）。已在途的执行不受此门禁影响：本门禁只被「首次对外执行」的 claim 询问，暂停或收尾都不会阻断在途 模型/工具的轮询、恢复与取消，因此暂停始终能安全收敛。
 */
@Service
public class IssueAgentWorkDispatchAdmission implements WorkDispatchAdmission {

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueRepository issueRepository;
  private final ProjectRepository projectRepository;
  private final IssueRunRepository issueRunRepository;
  private final ProjectWorkflowJsonCodec workflowCodec;

  public IssueAgentWorkDispatchAdmission(
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
  @Transactional(propagation = Propagation.REQUIRED)
  public <T> Optional<T> executeIfAdmitted(WorkDispatchRequest request, Supplier<T> intent) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(intent, "intent");
    if (request.type() == WorkTargetType.THREAD) {
      // 防御：THREAD Work 不产生对外执行，Harness 不会为它询问宿主。
      return Optional.ofNullable(intent.get());
    }
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(request.threadId());
    if (binding == null) {
      return Optional.ofNullable(intent.get());
    }
    Issue peek = issueRepository.getById(binding.issueId());
    if (peek == null) {
      // 绑定存在但 Issue 缺失：归属事实损坏，拒绝而不是退化成无限放行。
      return Optional.empty();
    }
    // 唯一决策边界：先取产品行锁，再在同一事务内执行 Harness 的状态转换意图。
    Project project = projectRepository.lockForShare(peek.getProjectId());
    if (project == null || project.isArchived()) {
      return Optional.empty();
    }
    Issue issue = issueRepository.lockById(peek.getId());
    if (issue == null || issue.isArchived() || issue.isPaused()) {
      return Optional.empty();
    }
    ProjectWorkflowState stage = currentStage(project, issue);
    if (stage == null || !stage.agent().equals(binding.agentName())) {
      return Optional.empty();
    }
    IssueRun run = issueRunRepository.lockActiveByIssueId(issue.getId());
    if (run == null || !run.isActive()) {
      return Optional.empty();
    }
    if (!run.getThreadId().equals(request.threadId())
        || !run.getSessionId().equals(request.sessionId())
        || !run.getState().equals(issue.getState())) {
      return Optional.empty();
    }
    if (run.getNextState() == null) {
      return Optional.ofNullable(intent.get());
    }
    if (request.type() == WorkTargetType.MODEL) {
      return Optional.ofNullable(intent.get());
    }
    // 收尾阶段：只读工具可以继续观察事实，业务写工具与无法判定只读性的工具一律拒绝新的派发。
    if (request.toolBinding().descriptor().sideEffect() == ToolSideEffect.READ_ONLY) {
      return Optional.ofNullable(intent.get());
    }
    return Optional.empty();
  }

  /** 当前阶段必须是启用的工作阶段且配置了 Agent；保留状态、停用阶段与无 Agent 阶段都不产生新的对外执行。 */
  private ProjectWorkflowState currentStage(Project project, Issue issue) {
    ProjectWorkflow workflow;
    ProjectStateCode state;
    try {
      workflow = workflowCodec.decode(project.getWorkflowJson());
      state = ProjectStateCode.of(issue.getState());
    } catch (IllegalArgumentException error) {
      return null;
    }
    if (ProjectWorkflowReservedState.isReserved(state)) {
      return null;
    }
    ProjectWorkflowState stage = workflow.find(state).orElse(null);
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      return null;
    }
    return stage;
  }
}

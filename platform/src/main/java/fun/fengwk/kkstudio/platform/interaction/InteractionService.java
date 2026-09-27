package fun.fengwk.kkstudio.platform.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.Objects;
import java.util.UUID;

/**
 * 人工交互（问卷回答与工具审批）的统一写入口：唯一的产品 owner 授权与锁序边界。
 *
 * <p>写入前先按目标 Thread 解析产品 owner：Issue+Agent Thread 先按 {@code Project SHARE -> Issue UPDATE}
 * 锁定产品层级，再进入 Harness Runtime（Session {@literal ->} Thread {@literal ->} Model {@literal ->} Tool
 * siblings {@literal ->} Work）；Chat 与内部 Thread 没有产品层级，直接进入 Runtime。该顺序保证永不出现「先锁 Thread 再反锁 Issue」
 * 的死锁，并使回答/审批与产品状态迁移（暂停、归档、Run 切换）在同一事务内串行化。
 *
 * <p>暂停（paused）与阻塞（BLOCKED）只阻止后续外部派发，不阻止人工回答/审批被记录：本入口照常落盘 durable 事实并登记 Work，是否继续 派发外部调用由 Issue
 * 工作流在消费 Work 时按当前 Run 身份与授权判定；本服务不代其判定，也不提供任何绕过该判定的通道。
 */
@Service
public class InteractionService {

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueRepository issueRepository;
  private final ProjectRepository projectRepository;
  private final ObjectProvider<HarnessRuntime> runtimes;

  /** 创建统一交互写入口。 */
  public InteractionService(
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueRepository issueRepository,
      ProjectRepository projectRepository,
      ObjectProvider<HarnessRuntime> runtimes) {
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /**
   * 在单一物理事务内接受（或精确 replay）一次人工输入提交。
   *
   * <p>产品层级锁先于 Runtime 的 Session/Thread 锁获取；拒绝与冲突语义全部由 {@link HarnessRuntime#submitToolInput}
   * 决定，本入口只补齐产品 owner 的外层串行化。
   */
  @Transactional
  public ToolInputAcceptance submitInput(ToolInputSubmissionCommand command) {
    Objects.requireNonNull(command, "command");
    lockProductScope(command.threadId());
    return requireRuntime().submitToolInput(command);
  }

  /**
   * 在单一物理事务内决策一次必需的 Tool approval。
   *
   * <p>与问卷回答共用同一产品锁序与门禁：Issue+Agent Thread 先锁产品层级，再进入 Runtime 决策，因此审批不能绕过 Issue 的暂停与 归档约束去推进外部派发。
   */
  @Transactional
  public ToolInvocation decideApproval(ToolApprovalCommand command) {
    Objects.requireNonNull(command, "command");
    lockProductScope(command.threadId());
    return requireRuntime().decideToolApproval(command);
  }

  /**
   * 解析 Thread 的产品归属并锁定产品层级：命中 Issue+Agent 绑定时按 {@code Project SHARE -> Issue UPDATE} 加锁；命中失败说明该
   * Thread 没有产品 Issue 归属（Chat 或内部委派），无需产品锁。
   *
   * <p>Project/Issue 行在绑定仍存在时缺失或层级不一致属于产品归属事实损坏，fail closed 而不是退化成无产品锁的写入。
   */
  private void lockProductScope(UUID threadId) {
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(threadId);
    if (binding == null) {
      return;
    }
    Issue issue = issueRepository.getById(binding.issueId());
    if (issue == null) {
      throw new IllegalStateException("Issue owner does not exist");
    }
    UUID projectId = issue.getProjectId();
    if (projectRepository.lockForKeyShare(projectId) == null) {
      throw new IllegalStateException("Project owner does not exist");
    }
    Issue lockedIssue = issueRepository.lockById(issue.getId());
    if (lockedIssue == null || !lockedIssue.getProjectId().equals(projectId)) {
      throw new IllegalStateException("Issue owner hierarchy is inconsistent");
    }
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}

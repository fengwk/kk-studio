package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.platform.IssueRunTask;
import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;

import java.util.List;
import java.util.UUID;

/**
 * Run 接受的 Harness 派发步骤：解析本次 Run 使用的 Session/Thread，物化 BranchSettings，提交初始命令批，并保证稳定 Thread 绑定存在。
 *
 * <p>调用方必须在自己的 {@code @Transactional} 边界与 owner 锁内使用。同一 Run 的命令幂等键只由 Run id 与角色决定，因此重复接受不会重复入队。
 * 派发失败即抛出，已接受的 Harness 命令随业务事务一起回滚。
 */
@Component
@AllArgsConstructor
final class IssueRunHarness {

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final AgentBranchSettingsMaterializer agentBranchSettings;
  private final ObjectProvider<IssueThreadExecution> threadExecutions;

  /**
   * 派发一次新 Run。
   *
   * <p>Run id 在接受源输入之前分配，同时作为 root Join ticket 的 invocationId；已有 Thread 时冻结当前 head 作为区间起点， 新 Thread
   * 的起点取 Harness 接受的 root entry。
   */
  Dispatched dispatch(Project project, Issue issue, ProjectWorkflowState stage, String agentName) {
    IssueAgentThread binding =
        issueAgentThreadRepository.findByIssueIdAndAgentName(issue.getId(), agentName);
    BranchSettings settings = agentBranchSettings.materialize(agentName);
    UUID runId = UUID.randomUUID();
    UUID sessionId;
    UUID threadId;
    UUID existingHead = null;
    SpawnRequest.Continuation continuation = null;
    if (binding == null) {
      sessionId = UUID.randomUUID();
      threadId = UUID.randomUUID();
    } else {
      threadId = binding.threadId();
      IssueThreadView view = requireThreadExecution().readThread(threadId);
      sessionId = view.sessionId();
      existingHead = view.headEntryId();
      continuation = new SpawnRequest.Continuation(existingHead, view.nextCommandSequence());
    }

    List<NewThreadCommand> commands = IssueRunCommands.build(settings, issue, stage, runId);
    NewThreadCommand taskInput = commands.getLast();
    AcceptedCommands accepted =
        requireThreadExecution()
            .submitRunTask(
                new IssueRunTask(
                    runId,
                    threadId,
                    binding == null ? sessionId : null,
                    settings,
                    binding == null && project.isYoloEnabled(),
                    continuation,
                    commands,
                    taskInput.requestHash()));
    if (binding == null
        && !issueAgentThreadRepository.insert(
            new IssueAgentThread(issue.getId(), agentName, sessionId, threadId))) {
      throw new IllegalStateException("failed to bind issue agent thread");
    }
    return new Dispatched(
        runId, sessionId, threadId, binding == null ? accepted.rootEntry().id() : existingHead);
  }

  /**
   * 读取该 Thread 的 root-to-head 历史路径（设计 §4.5 区间解释依据）。
   *
   * <p>只读快照，不产生锁；部署缺少 Harness Runtime 时确定失败，绝不退化为"无区间校验"的接受。
   */
  EntryPath entryPath(UUID threadId) {
    return requireThreadExecution().readThread(threadId).entryPath();
  }

  private IssueThreadExecution requireThreadExecution() {
    IssueThreadExecution port = threadExecutions.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return port;
  }

  /** 已派发的 Run 身份与收尾区间起点。 */
  record Dispatched(UUID runId, UUID sessionId, UUID threadId, UUID startEntryId) {}
}

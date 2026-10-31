package fun.fengwk.kkstudio.project.platform;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskRequest;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;

import java.util.Optional;
import java.util.UUID;

/**
 * Issue/Run 执行所需的 Thread 事实读取与命令控制。
 *
 * <p>只暴露 Project 真正消费的窄事实与控制面：单个线程的权威执行事实切片、命令接受（含 Join）与停止、以及三类只读回执查询。命令、回执与 Join 值契约复用 Harness
 * 的不可变定义，不暴露线程执行树、存储或运行时聚合。所有方法沿用运行时的事务参与、幂等与异常语义：接受与停止加入调用方已有事务，权威 head/version 与类型化冲突/未找到异常原样传递。
 *
 * <p>运行时以 {@link ObjectProvider} 惰性解析：只装配 Project 的上下文不会因为缺少运行时而在启动期失败；真正需要执行能力时确定失败，绝不退回本地假执行。
 */
@AllArgsConstructor
@Service
public class IssueThreadExecution {

  private final ObjectProvider<HarnessRuntime> runtimes;
  private final AgentTaskService agentTasks;

  /**
   * 读取单个线程的一致权威事实切片：当前 Session、head Entry、version、下一条命令序号、root-to-head 历史路径与统一执行事实。
   *
   * @throws IllegalStateException 目标线程不存在
   */
  public IssueThreadView readThread(UUID threadId) {
    ThreadSnapshot snapshot = requireRuntime().getThreadSnapshot(threadId);
    ThreadState thread = snapshot.thread();
    return new IssueThreadView(
        thread.sessionId(),
        thread.headEntryId(),
        thread.version(),
        thread.nextCommandSequence(),
        snapshot.requireFullPath(),
        snapshot.executionFacts());
  }

  /** 原子接受一批命令及其 Work（NEW_ROOT_SESSION / THREAD 单原语），返回接受结果。 */
  public AcceptedCommands acceptCommands(
      AcceptCommandsCommand command, AcceptancePreflight preflight) {
    return requireRuntime().acceptCommands(command, preflight);
  }

  /**
   * 原子创建（或续用 Issue 稳定 Thread）、接受 Run 的源输入并登记以 Run id 为票据的回执，返回接受结果。
   *
   * <p>Run 任务由控制器发起、没有父回执票据；Issue 的稳定 Thread 续用时只追加输入，并加入调用方的产品事务。
   */
  public AcceptedCommands submitRunTask(IssueRunTask task) {
    return agentTasks
        .submit(
            new AgentTaskRequest(
                task.runId(),
                task.threadId(),
                task.sessionId(),
                task.settings(),
                task.commands(),
                AcceptancePreflight.IDENTITY,
                SpawnTrigger.CONTROLLER,
                null,
                null,
                task.continuation(),
                task.yoloEnabled(),
                new SpawnRequest.Budget(null, 1, 1, 1),
                task.requestHash()))
        .accepted();
  }

  /** 原子停止目标线程并返回其权威 head Entry：收尾区间必须以本次已提交的停止边界（返回的 head）为界，而不是停止前的旧事实。 */
  public UUID stop(StopCommand command) {
    StopResult result = requireRuntime().stop(command);
    return result.thread().headEntryId();
  }

  /** 按 invocationId 读取固定 Join 凭据；未匹配时返回空。 */
  public Optional<ThreadJoin> findJoin(UUID invocationId) {
    return requireRuntime().findJoin(invocationId);
  }

  /** 从已匹配的固定结果投影只读 Join 回执；未匹配时返回空。 */
  public Optional<ThreadJoinReceipt> projectJoinReceipt(UUID invocationId) {
    return requireRuntime().projectJoinReceipt(invocationId);
  }

  /** 按 Thread + idempotencyKey 读取 durable 命令，用于应用层精确重放校验或冻结命令读取。 */
  public Optional<ThreadCommand> findThreadCommand(UUID threadId, UUID idempotencyKey) {
    return requireRuntime().findThreadCommand(threadId, idempotencyKey);
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}

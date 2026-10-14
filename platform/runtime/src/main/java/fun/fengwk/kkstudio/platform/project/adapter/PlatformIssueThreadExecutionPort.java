package fun.fengwk.kkstudio.platform.project.adapter;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.project.port.IssueThreadExecutionPort;
import fun.fengwk.kkstudio.project.port.IssueThreadView;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link IssueThreadExecutionPort} 的 platform 宿主适配：组合 Harness 运行时并投影 Project 需要的窄事实。
 *
 * <p>精确转发命令接受、Join 查询与命令重放查询，不改变其事务参与、幂等、权威 head/version 或异常语义；读取与停止只投影消费方确切需要的字段 （六字段切片与权威 head
 * UUID），绝不把完整执行树、存储或运行时聚合暴露给 Project 领域。
 *
 * <p>运行时以 {@link ObjectProvider} 惰性解析：Platform 自动配置不参与 Harness 组合，只装配 Platform
 * 的上下文不会因为缺少运行时而在启动期失败；真正需要执行能力时确定失败，绝不退回本地假执行。
 */
@AllArgsConstructor
@Service
public class PlatformIssueThreadExecutionPort implements IssueThreadExecutionPort {

  private final ObjectProvider<HarnessRuntime> runtimes;

  @Override
  public IssueThreadView readThread(UUID threadId) {
    ThreadSnapshot snapshot = requireRuntime().getThreadSnapshot(threadId);
    ThreadState thread = snapshot.thread();
    return new IssueThreadView(
        thread.sessionId(),
        thread.headEntryId(),
        thread.version(),
        thread.nextCommandSequence(),
        snapshot.entryPath(),
        snapshot.executionFacts());
  }

  @Override
  public AcceptedCommands acceptCommands(
      AcceptCommandsCommand command, AcceptancePreflight preflight) {
    return requireRuntime().acceptCommands(command, preflight);
  }

  @Override
  public AcceptedCommands acceptCommandsAndJoin(
      AcceptCommandsCommand command, ThreadJoinRequest join, AcceptancePreflight preflight) {
    return requireRuntime().acceptCommandsAndJoin(command, join, preflight);
  }

  @Override
  public UUID stop(StopCommand command) {
    StopResult result = requireRuntime().stop(command);
    return result.thread().headEntryId();
  }

  @Override
  public Optional<ThreadJoin> findJoin(UUID invocationId) {
    return requireRuntime().findJoin(invocationId);
  }

  @Override
  public Optional<ThreadJoinReceipt> projectJoinReceipt(UUID invocationId) {
    return requireRuntime().projectJoinReceipt(invocationId);
  }

  @Override
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

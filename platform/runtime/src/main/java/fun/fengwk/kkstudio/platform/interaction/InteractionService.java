package fun.fengwk.kkstudio.platform.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.api.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.api.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.Objects;

/**
 * 人工交互（问卷回答与工具审批）的统一事务写入口。写入前校验目标 Thread 存在，Runtime 写回来源 invocation 本身。
 *
 * <p>人工回答与审批先落盘 durable 事实并登记 Work；后续是否派发外部调用由消费 Work 的执行流程按当前状态与授权判定，本服务不代其判定。
 */
@Service
public class InteractionService {

  private final InteractionRootResolver rootResolver;

  /** 创建统一交互写入口。 */
  public InteractionService(ObjectProvider<HarnessRuntime> runtimes) {
    this.rootResolver = new InteractionRootResolver(runtimes);
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
    rootResolver.requireRootId(command.threadId());
    return requireRuntime().submitToolInput(command);
  }

  /**
   * 在单一物理事务内决策一次必需的 Tool approval。
   *
   * <p>Runtime 在当前事务中完成审批决策；后续外部派发由消费 Work 的执行流程按当前状态与授权判定。
   */
  @Transactional
  public ToolInvocation decideApproval(ToolApprovalCommand command) {
    Objects.requireNonNull(command, "command");
    rootResolver.requireRootId(command.threadId());
    return requireRuntime().decideToolApproval(command);
  }

  private HarnessRuntime requireRuntime() {
    return rootResolver.requireRuntime();
  }
}

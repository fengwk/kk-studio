package fun.fengwk.kkstudio.platform.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 人工交互（问卷回答与工具审批）的统一写入口：唯一的产品 owner 授权与锁序边界。
 *
 * <p>写入前沿目标 Thread 的不可变祖先链解析真实执行根及其 Session，再让每个产品归属实现获取所需的产品锁，最后进入 Runtime。 审批与回答仍写回来源 invocation
 * 本身；后代任务也按根的产品层级先锁，避免锁序倒置。
 *
 * <p>暂停（paused）与阻塞（BLOCKED）只阻止后续外部派发，不阻止人工回答/审批被记录：本入口照常落盘 durable 事实并登记 Work，是否继续 派发外部调用由 Issue
 * 工作流在消费 Work 时按当前 Run 身份与授权判定；本服务不代其判定，也不提供任何绕过该判定的通道。
 */
@Service
public class InteractionService {

  private final List<SessionOwnership> ownerships;
  private final InteractionRootResolver rootResolver;

  /** 创建统一交互写入口。 */
  public InteractionService(
      List<SessionOwnership> ownerships, ObjectProvider<HarnessRuntime> runtimes) {
    this.ownerships = List.copyOf(ownerships);
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

  /** 按真实执行根及其 Session 调用所有产品锁钩子，先于 Runtime 写入。 */
  private void lockProductScope(UUID threadId) {
    UUID rootThreadId = rootResolver.requireRootId(threadId);
    UUID rootSessionId = requireRuntime().getThreadSnapshot(rootThreadId).thread().sessionId();
    for (SessionOwnership ownership : ownerships) {
      ownership.lockForInteraction(rootThreadId, rootSessionId);
    }
  }

  private HarnessRuntime requireRuntime() {
    return rootResolver.requireRuntime();
  }
}

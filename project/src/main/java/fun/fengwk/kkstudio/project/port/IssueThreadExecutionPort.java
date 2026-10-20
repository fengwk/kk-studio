package fun.fengwk.kkstudio.project.port;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.Optional;
import java.util.UUID;

/**
 * Issue/Run 执行所需的 Thread 事实读取与命令控制能力，由宿主（platform）适配实现。
 *
 * <p>只暴露 Project 真正消费的窄事实与控制面：单个线程的权威执行事实切片、命令接受（含 Join）与停止、以及三类只读回执查询。命令、回执与 Join 值契约复用 Harness
 * 的不可变定义，本端口不重造命令类型、不复制序列化、也不暴露线程执行树、存储或服务。
 *
 * <p>所有方法沿用宿主既有的事务参与、幂等与异常语义：接受与停止加入调用方已有事务，权威 head/version 与类型化冲突/未找到异常由实现原样传递。
 */
public interface IssueThreadExecutionPort {

  /**
   * 读取单个线程的一致权威事实切片：当前 Session、head Entry、version、下一条命令序号、root-to-head 历史路径与统一执行事实。
   *
   * @throws IllegalStateException 目标线程不存在（宿主运行时语义）
   */
  IssueThreadView readThread(UUID threadId);

  /** 原子接受一批命令及其 Work（NEW_ROOT_SESSION / THREAD 单原语），返回接受结果。 */
  AcceptedCommands acceptCommands(AcceptCommandsCommand command, AcceptancePreflight preflight);

  /** 原子创建（或续用 Issue 稳定 Thread）、接受 Run 的源输入并登记以 Run id 为票据的回执，返回接受结果。 */
  AcceptedCommands submitRunTask(IssueRunTask task);

  /** 原子停止目标线程并返回其权威 head Entry：收尾区间必须以本次已提交的停止边界（返回的 head）为界，而不是停止前的旧事实。 */
  UUID stop(StopCommand command);

  /** 按 invocationId 读取固定 Join 凭据；未匹配时返回空。 */
  Optional<ThreadJoin> findJoin(UUID invocationId);

  /** 从已匹配的固定结果投影只读 Join 回执；未匹配时返回空。 */
  Optional<ThreadJoinReceipt> projectJoinReceipt(UUID invocationId);

  /** 按 Thread + idempotencyKey 读取 durable 命令，用于应用层精确重放校验或冻结命令读取。 */
  Optional<ThreadCommand> findThreadCommand(UUID threadId, UUID idempotencyKey);
}

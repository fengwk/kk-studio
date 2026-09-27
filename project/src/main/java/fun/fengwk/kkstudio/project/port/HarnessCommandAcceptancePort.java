package fun.fengwk.kkstudio.project.port;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;

import java.util.UUID;

/**
 * Issue+Agent 归属的 Harness 命令接受能力，由宿主（platform）适配实现。
 *
 * <p>接受必须在调用方事务内原子完成归属判定、归属/附件物化与 Runtime 入队；Port 只暴露 Issue+Agent 这一类 owner，不暴露 Chat/Canvas 专属形态。
 */
public interface HarnessCommandAcceptancePort {

  /**
   * 接受一次 Issue+Agent 归属的命令批。
   *
   * @param issueId Issue 身份
   * @param agentName Agent 自然名称（稳定身份，非模型或提示词标识）
   */
  AcceptedCommands acceptIssueAgentCommands(
      UUID issueId, String agentName, AcceptCommandsCommand command);
}

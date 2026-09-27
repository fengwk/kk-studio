package fun.fengwk.kkstudio.project.port;

import java.util.UUID;

/**
 * Issue+Agent 归属的 Harness Session 深删除能力，由宿主（platform）适配实现。
 *
 * <p>删除必须在调用方事务内按既有锁序原子完成归属 relation、Thread 执行事实与 Session 清理，绝不误删其他 owner 的 Session。
 */
public interface IssueAgentSessionDeletionPort {

  /** 深删除某个 Issue+Agent 归属的全部 Harness Session；归属不存在视为已删（noop）。 */
  void deleteIssueAgentSessions(UUID issueId, String agentName);
}

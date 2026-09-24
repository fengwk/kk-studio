package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;

import java.util.List;
import java.util.UUID;

/**
 * 稳定的 (Issue, Agent) 到 Session 与工作 Branch 的归属仓储。
 *
 * <p>归属随 IssueAgentSession 稳定存在；Run 每次新建但复用自己的 Session 与工作 Branch。
 */
public interface IssueAgentSessionRepository {

  /** 建立归属；同 (issueId, agentName) 或同 session/thread 已存在时返回既有归属而不重复插入。 */
  IssueAgentSession bindOrGet(IssueAgentSession session);

  IssueAgentSession getById(UUID id);

  IssueAgentSession findByIssueIdAndAgentName(UUID issueId, String agentName);

  IssueAgentSession findBySessionId(UUID sessionId);

  IssueAgentSession findByThreadId(UUID threadId);

  /** 该 Issue 当前已绑定的全部 Agent 归属（用于向现有参与者授予已发布证据）。 */
  List<IssueAgentSession> listByIssueId(UUID issueId);

  boolean deleteById(UUID id);

  int deleteByIssueId(UUID issueId);
}

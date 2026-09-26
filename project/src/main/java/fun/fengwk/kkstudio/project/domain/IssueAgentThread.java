package fun.fengwk.kkstudio.project.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Issue+Agent 稳定 Thread 绑定：{@code (issueId, agentName) -> threadId}。
 *
 * <p>首次接受执行时惰性创建，此后不可重绑；同一 Agent 跨阶段、返工和重开复用同一 Thread，不同 Agent 与不同 Issue 不共享 Thread。Agent
 * 自然名称就是稳定身份，修改模型或提示词不改变它；底层 Session 由 Thread 解析， 绑定不保存 Session 指针。
 */
public record IssueAgentThread(UUID issueId, String agentName, UUID threadId) {

  public IssueAgentThread {
    Objects.requireNonNull(issueId, "issueId");
    agentName = ProjectValidation.requireCanonicalName(agentName, "agentName");
    Objects.requireNonNull(threadId, "threadId");
  }
}

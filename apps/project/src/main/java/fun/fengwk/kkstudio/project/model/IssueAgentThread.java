package fun.fengwk.kkstudio.project.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Issue+Agent 稳定 Thread 绑定：{@code (issueId, agentName) -> threadId}。
 *
 * <p>首次接受执行时在同一接受事务内惰性创建，此后不可重绑。Agent 的自然名称就是稳定身份，同一 Agent 跨阶段、返工与重开复用同一 Thread； 不同 Agent 与不同 Issue
 * 不共享 Thread。底层 Session 始终由 Thread 解析，绑定不保存 Session 指针。
 */
public record IssueAgentThread(UUID issueId, String agentName, UUID threadId) {

  public IssueAgentThread {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(agentName, "agentName");
    Objects.requireNonNull(threadId, "threadId");
    if (agentName.isBlank()) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
  }
}

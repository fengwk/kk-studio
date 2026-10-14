package fun.fengwk.kkstudio.platform.orchestration;

import java.util.Objects;
import java.util.UUID;

/**
 * 产品 owner 引用（类型安全的具体归属身份）；归属授权、查询与深删除都以此为单位。
 *
 * <p>Issue+Agent 的自然身份是 {@code (issueId, agentName)}：绑定表只保存该身份到 Thread 的映射，Session 与历史坐标一律经 Thread
 * 解析，调用方不能借 owner 传入任意 Session 或 second ownership。
 */
public sealed interface OwnerRef {

  OwnerType type();

  /** Chat owner：由 {@code chat_session} 直接持有 Session。 */
  record Chat(UUID chatId) implements OwnerRef {

    public Chat {
      Objects.requireNonNull(chatId, "chatId");
    }

    @Override
    public OwnerType type() {
      return OwnerType.CHAT;
    }

    @Override
    public String toString() {
      return type().name() + ":" + chatId;
    }
  }

  /**
   * Issue+Agent owner：Agent 自然名称就是稳定身份，修改模型或提示词不改变身份。
   *
   * <p>{@code agentName} 按 Agent 自然名称规范化（去首尾空白）；空白名称不是合法身份。
   */
  record IssueAgent(UUID issueId, String agentName) implements OwnerRef {

    public IssueAgent {
      Objects.requireNonNull(issueId, "issueId");
      Objects.requireNonNull(agentName, "agentName");
      agentName = agentName.trim();
      if (agentName.isEmpty()) {
        throw new IllegalArgumentException("agentName must not be blank");
      }
    }

    @Override
    public OwnerType type() {
      return OwnerType.ISSUE_AGENT;
    }

    @Override
    public String toString() {
      return type().name() + ":" + issueId + ":" + agentName;
    }
  }
}

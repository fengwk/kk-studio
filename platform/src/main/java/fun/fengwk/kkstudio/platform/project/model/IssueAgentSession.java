package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 稳定的 Issue+Agent Session 与工作 Branch 归属。
 *
 * <p>同一 Issue 的不同 Agent 各有独立 Session，不共享 ROOT、对话或私有附件；同一 (Issue, Agent) 的后续 Run 复用自己的 Session 和工作
 * Branch。Session owner 绑定这条稳定关联，而不是会终结的 Run。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueAgentSession {

  /** 归属 UUID，作为 session_owner 的 owner 标识。 */
  private UUID id;

  private UUID issueId;
  private String agentName;
  private UUID sessionId;

  /** 工作 Branch（Harness Thread）。 */
  private UUID threadId;

  private Instant createdAt;
  private Instant updatedAt;
}

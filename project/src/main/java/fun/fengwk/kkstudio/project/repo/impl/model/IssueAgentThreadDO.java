package fun.fengwk.kkstudio.project.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code project_issue_agent_thread} 稳定绑定行映射。 */
@Data
public class IssueAgentThreadDO {

  private UUID issueId;
  private String agentName;
  private UUID threadId;
}

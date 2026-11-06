package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 与 Agent 的稳定 Thread 绑定 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueAgentThreadDTO {

  private String issueId;
  private String agentName;
  private String threadId;
}

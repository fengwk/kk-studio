package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue Agent 线程绑定 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueAgentThreadDTO {

  private String issueId;
  private String agentName;
  private String threadId;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** IssueRun 概要信息 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRunSummaryDTO {

  private String id;
  private String issueId;
  private String ordinal;
  private String state;
  private String status;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agentName;

  private String startedAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String endedAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

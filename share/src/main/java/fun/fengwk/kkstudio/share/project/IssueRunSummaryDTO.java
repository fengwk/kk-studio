package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** IssueRun 概要信息 DTO，常用于 Snapshot 和列表投影。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRunSummaryDTO {

  private String id;
  private String issueId;
  private String ordinal;
  private String role;
  private String actorType;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String submissionRunId;

  private String status;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String outcome;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String waitingReason;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String createdAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String completedAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** IssueRun 完整执行事实 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRunDTO {

  private String id;
  private String issueId;
  private String ordinal;
  private String state;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agentName;

  private String sessionId;
  private String threadId;
  private String status;
  private String startEntryId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String endEntryId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String finalAnswerEntryId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String nextState;

  private String observedActivitySequence;
  private String remainingExecutionMs;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String error;

  private String version;
  private String startedAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String endedAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

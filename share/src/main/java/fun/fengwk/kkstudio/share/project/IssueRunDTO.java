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
  private String role;
  private String actorType;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String submissionRunId;

  private String status;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String outcome;

  private String observedSpecRevision;
  private String observedInputSequence;
  private Integer continuationCount;
  private Integer maxContinuations;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String deadline;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String waitingReason;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String result;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String terminalActionId;

  private String version;
  private String createdAt;
  private String updatedAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String completedAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String sessionId;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}

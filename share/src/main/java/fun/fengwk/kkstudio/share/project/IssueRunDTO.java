package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IssueRun 完整执行事实 DTO。
 *
 * <p>Run 状态只描述执行：RUNNING/WAITING_HUMAN/COMPLETED/FAILED/CANCELLED/UNKNOWN；{@code outcome}
 * 才是带来源的业务结果 （SUBMITTED/APPROVED/CHANGES_REQUESTED 等）。{@code agentSessionId} 与 {@code sessionId}
 * 是同一 {@code (issueId, agentName)} 跨多次 Run 复用的稳定归属，{@code observedActivitySequence} 是本次 Run 已消费的
 * Activity 位置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRunDTO {

  private String id;
  private String issueId;
  private String ordinal;
  private String role;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String agentSessionId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String sessionId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String submissionRunId;

  private String status;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String outcome;

  private String observedActivitySequence;
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

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

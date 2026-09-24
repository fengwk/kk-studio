package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Issue Activity 公开传输对象：Issue 唯一有序事实流上的一条记录。
 *
 * <p>{@code sequence} 在 Issue 内单调递增，同时是投递位置；{@code actorType} 是 HUMAN/AGENT/SYSTEM； {@code
 * actorAgentName} 仅在 AGENT 时非空；{@code targetRole} 是定向的 EXECUTOR/REVIEWER；{@code runId} 与 {@code
 * submissionRunId} 指向相关 Run；{@code decision} 仅在 {@code REVIEW_DECISION} 时非空。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueActivityDTO {

  private String issueId;
  private String sequence;
  private String kind;
  private String actorType;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String actorAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String targetRole;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String runId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String submissionRunId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String decision;

  private String body;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String idempotencyKey;

  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

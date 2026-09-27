package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 公开证据传输对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueEvidenceDTO {

  private String issueId;
  private String blobId;
  private String uri;
  private String name;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String actorAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String runId;

  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

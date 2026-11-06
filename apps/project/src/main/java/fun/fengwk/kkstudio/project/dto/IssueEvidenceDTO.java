package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** Issue 公开证据传输对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueEvidenceDTO {

  private String issueId;
  private String blobId;
  private String uri;
  private @Nullable String name;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String actorAgentName;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String runId;

  private Instant createdAt;
}

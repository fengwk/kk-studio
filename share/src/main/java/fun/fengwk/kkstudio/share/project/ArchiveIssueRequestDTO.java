package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 归档请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveIssueRequestDTO {

  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}

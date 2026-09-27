package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 人工发布 Issue 证据请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddIssueEvidenceRequestDTO {

  private String uploadId;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}

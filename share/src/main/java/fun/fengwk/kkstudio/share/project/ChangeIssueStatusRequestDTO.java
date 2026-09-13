package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 变更 Issue 状态请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeIssueStatusRequestDTO {

  private String expectedVersion;
  private String status;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}

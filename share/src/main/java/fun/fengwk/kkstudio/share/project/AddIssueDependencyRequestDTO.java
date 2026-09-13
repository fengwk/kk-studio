package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 添加 Issue 依赖请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddIssueDependencyRequestDTO {

  private String dependsOnIssueId;
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}

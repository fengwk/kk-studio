package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 追加 Issue Activity 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendIssueActivityRequestDTO {

  private String expectedVersion;
  private String requestKey;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String kind;

  private String body;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}

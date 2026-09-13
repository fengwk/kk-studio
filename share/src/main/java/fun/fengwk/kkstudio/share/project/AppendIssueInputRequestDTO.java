package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 追加 Issue 输入请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendIssueInputRequestDTO {

  private String kind;
  private String body;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String idempotencyKey;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}

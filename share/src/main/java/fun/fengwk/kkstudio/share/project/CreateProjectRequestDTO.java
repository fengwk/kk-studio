package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建项目请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProjectRequestDTO {

  private String title;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String description;

  private String coordinatorAgentName;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}

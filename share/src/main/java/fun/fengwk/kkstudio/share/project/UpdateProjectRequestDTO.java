package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改项目请求 DTO。
 *
 * <p>可空字段省略即保持当前值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProjectRequestDTO {

  private String expectedVersion;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String title;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String description;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}

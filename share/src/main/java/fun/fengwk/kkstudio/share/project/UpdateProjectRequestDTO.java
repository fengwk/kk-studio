package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改项目请求 DTO，包含 expectedVersion CAS 乐观锁版本。
 *
 * <p>可空字段省略即保持当前值；{@code maxReviewRejections} 只影响此后的正式审查打回，不追溯已有 {@code BLOCKED}。
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

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Boolean yoloEnabled;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Integer maxReviewRejections;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}

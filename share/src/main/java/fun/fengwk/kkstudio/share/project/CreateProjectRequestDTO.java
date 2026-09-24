package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建项目请求 DTO。
 *
 * <p>{@code yoloEnabled} 与 {@code maxReviewRejections} 可省略：省略时取项目默认值（YOLO 开启，阈值 3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProjectRequestDTO {

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

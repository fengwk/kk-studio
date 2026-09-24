package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Project 公开传输对象。
 *
 * <p>UUID 以 canonical lowercase string 传输，版本号与计数以 canonical non-negative decimal string 传输。 {@code
 * yoloEnabled} 是新 Issue Agent Branch 的 Run 启动策略，{@code maxReviewRejections} 是正式审查打回转 {@code
 * BLOCKED} 的项目阈值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDTO {

  private String id;
  private String title;
  private String description;
  private Boolean yoloEnabled;
  private String maxReviewRejections;
  private String nextIssueNumber;
  private String version;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String archivedAt;

  private String createdAt;
  private String updatedAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

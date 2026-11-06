package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** 修改 Issue 需求事实请求 DTO：整体替换标题与描述。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateIssueRequestDTO {

  private String expectedVersion;

  private String title;

  private @Nullable String description;
}

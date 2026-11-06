package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** 修改项目配置请求 DTO：整体替换标题与描述。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProjectRequestDTO {

  private Long expectedVersion;

  private String title;

  private @Nullable String description;
}

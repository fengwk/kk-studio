package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 整体替换项目工作流请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProjectWorkflowRequestDTO {

  private String expectedVersion;
  private ProjectWorkflowDTO workflow;
}

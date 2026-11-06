package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 状态迁移请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransitionIssueRequestDTO {

  private Long expectedVersion;
  private String requestKey;

  /** 目标工作流自然状态编码。 */
  private String toState;
}

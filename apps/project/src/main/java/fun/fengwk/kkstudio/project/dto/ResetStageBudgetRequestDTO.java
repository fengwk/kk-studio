package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 重置阶段执行额度请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetStageBudgetRequestDTO {

  private Long expectedVersion;
  private String requestKey;

  /** 工作流自然状态编码。 */
  private String state;

  private Integer maxRuns;
}

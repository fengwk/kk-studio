package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 阶段执行额度 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueStageBudgetDTO {

  private String state;
  private Integer maxRuns;
  private String budgetAfterOrdinal;
  private String usedRuns;
  private String remainingRuns;
}

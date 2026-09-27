package fun.fengwk.kkstudio.platform.project.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code project_issue_stage_budget} 行映射。 */
@Data
public class IssueStageBudgetDO {

  private UUID issueId;
  private String state;
  private Integer maxRuns;
  private Long budgetAfterOrdinal;
}

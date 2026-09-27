package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * {@code project_issue_stage_budget} 行：按 {@code (issueId, state)} 保存阶段额度。
 *
 * <p>只保存 {@code maxRuns} 与重置高水位 {@code budgetAfterOrdinal}，不保存 Agent、Thread 或 Session，因此切换
 * Agent、返工与重开都不重置额度。 已消耗次数按领域 {@code fun.fengwk.kkstudio.project.domain.IssueStageBudget} 的高水位语义从
 * Run 历史计算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueStageBudgetRow {

  private UUID issueId;
  private String state;
  private int maxRuns;
  private long budgetAfterOrdinal;
}

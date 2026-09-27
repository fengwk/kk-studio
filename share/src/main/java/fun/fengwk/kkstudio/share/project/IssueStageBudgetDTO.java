package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Issue 阶段预算 DTO。 */
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

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}

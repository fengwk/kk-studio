package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 重置阶段预算请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetStageBudgetRequestDTO {

  private String expectedVersion;
  private String requestKey;
  private String state;
  private Integer maxRuns;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown request field");
  }
}

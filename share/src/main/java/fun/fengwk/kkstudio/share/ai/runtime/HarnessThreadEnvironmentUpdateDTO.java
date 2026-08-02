package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Fenced mutation of the Environment selected by a Thread. */
@Data
public class HarnessThreadEnvironmentUpdateDTO {
  private String environmentName;
  private Long expectedExecutionEpoch;
}

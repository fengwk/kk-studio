package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Immutable provider/model/variant selection frozen into a branch settings snapshot. */
@Data
public class HarnessModelSelectionDTO {
  private String providerName;
  private String modelName;
  private String variant;
}

package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Initial Thread Environment selection for Chat-scoped Thread creation. */
@Data
public class HarnessThreadCreateDTO {
  private String environmentName;
}

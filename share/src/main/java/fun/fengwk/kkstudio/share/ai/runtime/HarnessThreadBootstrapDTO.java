package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Thread bootstrap 请求。 */
@Data
public class HarnessThreadBootstrapDTO {
  private String title;
  private String agentDefinitionId;

  /** Nullable Environment target; null leaves runtime execution local. */
  private String environmentName;

  private Boolean yoloEnabled;
  private Long expectedExecutionEpoch;
}

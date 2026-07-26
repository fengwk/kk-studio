package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** UNBOUND Thread bootstrap 请求。 */
@Data
public class HarnessThreadBootstrapDTO {
  private String title;
  private String agentDefinitionId;
  private Boolean yoloEnabled;
  private Long expectedExecutionEpoch;
}

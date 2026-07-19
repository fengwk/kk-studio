package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** 创建 Session 及其稳定 Main Thread。 */
@Data
public class HarnessSessionCreateDTO {
  private String agentDefinitionId;
  private String title;
  private Boolean yoloEnabled;
}

package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** 创建根 Thread（附带 Session+snapshot）或在既有 entry 上新建 Thread cursor。 */
@Data
public class HarnessThreadCreateDTO {
  /** 根创建时必填。 */
  private String agentDefinitionId;

  /** 根创建标题。 */
  private String title;

  /** 在既有 tree 上新建 cursor 时填写。 */
  private String sessionId;

  /** 与 sessionId 一起：新 Thread 的 head。 */
  private String fromEntryId;

  private Boolean yoloEnabled;
}

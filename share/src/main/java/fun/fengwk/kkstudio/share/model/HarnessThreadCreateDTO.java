package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** 从 Session 内 durable Entry 创建 branch Thread。 */
@Data
public class HarnessThreadCreateDTO {
  /** 新 Thread 的 head。 */
  private String fromEntryId;
}

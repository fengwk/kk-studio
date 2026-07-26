package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** 创建纯 Session/Entry Tree，不创建 Thread。 */
@Data
public class HarnessSessionCreateDTO {
  private String title;
  private Boolean yoloEnabled;
}

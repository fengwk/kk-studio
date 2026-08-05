package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 以完整 branch settings 原子创建 Thread 的请求。 */
@Data
public class HarnessThreadCreateDTO {
  private String title;
  private HarnessBranchSettingsDTO branchSettings;
  private Boolean yoloEnabled;
}

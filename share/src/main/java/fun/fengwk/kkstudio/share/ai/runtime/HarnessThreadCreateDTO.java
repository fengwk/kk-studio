package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 以完整 branch settings 原子创建 Thread 的请求。 */
@Data
public class HarnessThreadCreateDTO {
  /** 可选用户可见标题；提供时不得为空白（由 Session 校验）。 */
  private String title;

  /** 必填完整 branch settings：作为新 Session ROOT 的初始设置快照（environmentName 可空）。 */
  private HarnessBranchSettingsDTO branchSettings;

  /** 必填 Boolean：Thread 级冻结 YOLO 运行时策略（true 时工具调用跳过权限评估直接 Allow）。 */
  private Boolean yoloEnabled;
}

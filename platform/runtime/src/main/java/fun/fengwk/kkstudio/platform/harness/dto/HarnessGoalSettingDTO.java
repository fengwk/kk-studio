package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;

/** 用户维护的 branch Goal 快照：不可变 id 与用户原文。 */
@Data
public class HarnessGoalSettingDTO {

  /** 本次用户设置的 canonical UUID；每次设置（即使文本相同）都会产生新 id。 */
  private String id;

  /** 用户原文；只有用户本人可以设置或清除。 */
  private String text;
}

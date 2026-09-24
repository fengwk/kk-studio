package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 用户维护的 branch Goal 快照：不可变 id 与用户原文。 */
@Data
public class HarnessGoalSettingDTO {

  /** 本次用户设置的 canonical UUID；每次设置（即使文本相同）都会产生新 id。 */
  private String id;

  /** 用户原文；只有用户本人可以设置或清除。 */
  private String text;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown goal setting field: " + name);
  }
}

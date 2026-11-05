package fun.fengwk.kkstudio.share.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 检查 Git 渠道 branch HEAD；只更新观察值，不改变当前版本。 */
@Data
public class SkillPackageCheckDTO {

  /** 客户端已展示的 CAS 版本号（十进制字符串）。 */
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package field: " + fieldName);
  }
}

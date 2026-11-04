package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 发布 Git 渠道最近一次检查到的 exact commit。 */
@Data
public class SkillPackageGitPublishDTO {

  /** 客户端已展示的 CAS 版本号（十进制字符串）。 */
  private String expectedVersion;

  /** 要发布的 exact commit；必须等于最近一次检查看到的 branch HEAD。 */
  private String targetCommit;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package field: " + fieldName);
  }
}

package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * {@code PUT /api/ai/catalog/skill-packages/{packageName}} 请求体：只编辑可编辑字段。
 *
 * <p>repository URL 是不可变身份，更换仓库要新建 Package；发布内容由 {@link SkillPackagePublishDTO} 的 exact commit
 * 决定，本请求不改变 current commit 或 manifest。
 */
@Data
public class SkillPackageEditDTO {

  /** 客户端读到的当前 Package version（十进制字符串）；陈旧 Card 返回 version conflict。 */
  private String expectedVersion;

  /** 新的可空 package 描述；null/空白视为未填写。 */
  private String description;

  /** 新的检查来源 branch；只影响后续检查，不改变已发布内容。 */
  private String branch;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package edit field: " + fieldName);
  }
}

package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.ToString;

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

  /**
   * 私有仓库访问令牌（PAT）的三态编辑：省略字段保留既有令牌，JSON null 清除令牌，非空字符串替换令牌。
   *
   * <p>该值加密保存，绝不回显、绝不进入日志或模型上下文。空白字符串既不是省略也不是显式清除，按非法请求拒绝。
   */
  @Setter(AccessLevel.NONE)
  @ToString.Exclude
  private String token;

  /** 请求体是否显式携带了 {@code token} 字段（区分省略与显式 null）。 */
  @JsonIgnore
  @Setter(AccessLevel.NONE)
  private boolean tokenProvided;

  /** 仅在请求体显式包含 {@code token} 字段时被 Jackson 调用，因此可以区分省略与显式 null。 */
  @JsonSetter("token")
  public void applyToken(String token) {
    this.tokenProvided = true;
    this.token = token;
  }

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package edit field: " + fieldName);
  }
}

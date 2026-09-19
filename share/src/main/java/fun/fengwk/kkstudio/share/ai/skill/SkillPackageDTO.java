package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * 当前生效的 Skill package 摘要：不含正文，只暴露一个不可变 package 版本的身份与 Skill 元数据。
 *
 * <p>{@code packageVersion} 是永不复用的不可变版本；{@code skills} 是该 package 版本内按声明顺序的 Skill 列表。
 */
@Data
public class SkillPackageDTO {

  /** package 名（资源路由身份）：非空白、不得包含 {@code : / @ \}、≤128。 */
  private String name;

  /** package 版本（不可变，永不复用）。 */
  private String packageVersion;

  /** 可空描述；null 表示未填写。 */
  private String description;

  /** 该 package 内按声明顺序的 Skill 元数据（不含正文）。 */
  private List<SkillDTO> skills;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package field: " + fieldName);
  }
}

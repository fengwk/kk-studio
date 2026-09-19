package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * 当前生效的 Skill package 详情：在摘要之上携带每个 Skill 的精确正文，使客户端能够编辑后整体替换。
 *
 * <p>{@code skills[].content} 是服务端持久化的精确正文。客户端提交更新时只回传 {@link SkillDefinitionDTO} 的
 * name/description/content，内容身份始终由 {@code (packageName, packageVersion, name)} 三元组确定。
 */
@Data
public class SkillPackageDetailDTO {

  /** package 名（资源路由身份）：非空白、不得包含 {@code : / @ \}、≤128。 */
  private String name;

  /** package 版本（不可变，永不复用）。 */
  private String packageVersion;

  /** 可空描述；null 表示未填写。 */
  private String description;

  /** 该 package 内按声明顺序的 Skill 定义（含精确正文），可直接用于编辑后整体替换。 */
  private List<SkillDefinitionDTO> skills;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package detail field: " + fieldName);
  }
}

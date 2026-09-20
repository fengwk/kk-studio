package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Skill 引用身份：{@code (packageName, name)}。
 *
 * <p>Agent 配置的 {@code skills} 就是这组引用的有序列表。引用只是身份：Skill 是否存在、描述与正文在哪，都由 Package 当前的 manifest 与 Git
 * 内容决定，不在引用里重复。
 */
@Data
public class SkillRefDTO {

  /** Package 名（不可变路由身份）：非空白、无环绕空白、不含 {@code : / @ \}、≤128。 */
  private String packageName;

  /** Package 内的 Skill 名：非空白、无环绕空白、≤128，与 {@code <repository>/<name>/SKILL.md} 的目录名一致。 */
  private String name;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill ref field: " + fieldName);
  }
}

package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 当前生效的 Platform 全局 Skill 元数据。
 *
 * <p>身份是全局唯一的 {@code name}；{@code packageName}/{@code packageVersion} 与 {@code name} 一起定位承载它的精确内容行。
 * 正文不在此 DTO 中，只由 {@code load_skill} 按该三元组取回。
 */
@Data
public class SkillDTO {

  /** Skill canonical 名（全局唯一）：非空白、无环绕空白、单行、≤128，且不含 {@code : / @ \}。 */
  private String name;

  /** Skill 描述（非空、无环绕空白、仅允许 LF 换行、≤1024）。 */
  private String description;

  /** 承载该 Skill 的 package 名。 */
  private String packageName;

  /** 承载该 Skill 的 package 版本（永不复用）。 */
  private String packageVersion;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill field: " + fieldName);
  }
}

package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 当前生效的 Platform 全局 Skill 元数据。
 *
 * <p>身份是全局唯一的 {@code name}；{@code packageName}/{@code packageVersion} 定位承载它的不可变 package 版本， {@code
 * contentRevision} 是该 Skill 内容的精确版本。正文不在此 DTO 中，只由 {@code load_skill} 按精确 revision 取回。
 */
@Data
public class SkillDTO {

  /** Skill canonical 名（全局唯一）：非空白、无环绕空白、单行、≤128，且不含 {@code : / @ \}。 */
  private String name;

  /** Skill 描述（非空、无环绕空白、仅允许 LF 换行、≤1024）。 */
  private String description;

  /** 承载该 Skill 的 package 名。 */
  private String packageName;

  /** 承载该 Skill 的 package 版本（不可变，永不复用）。 */
  private String packageVersion;

  /** 内容 revision：小写 64 位 SHA-256。 */
  private String contentRevision;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill field: " + fieldName);
  }
}

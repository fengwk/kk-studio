package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * {@code POST /api/ai/catalog/skill-packages} 请求体：创建新的不可变 package 版本并使其成为当前版本。
 *
 * <p>{@code packageVersion} 必须是从未使用过的新版本；{@code skills} 至少一个、名称在 package 内唯一且全局未冲突。
 */
@Data
public class SkillPackageCreateDTO {

  /** package 名：非空白、不得包含 {@code : / @ \}、≤128。 */
  private String name;

  /** 新 package 版本：非空白、无控制字符、≤128，且从未被使用过。 */
  private String packageVersion;

  /** 可空 package 描述；null/空白视为未填写。 */
  private String description;

  /** 该 package 的完整 Skill 定义列表；至少一个，名称唯一。 */
  private List<SkillDefinitionDTO> skills;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package create field: " + fieldName);
  }
}

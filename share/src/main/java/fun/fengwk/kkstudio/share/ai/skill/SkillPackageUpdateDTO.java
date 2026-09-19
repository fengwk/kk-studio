package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * {@code PUT /api/ai/catalog/skill-packages/{name}} 请求体：原子安装完整替换并切换当前版本。
 *
 * <p>{@code newPackageVersion} 必须与 {@code expectedPackageVersion} 不同且从未被使用过；{@code skills} 是完整替换列表
 * （不是增量）。被保留的同名 Skill 允许内容变更，但移除仍被任何 Agent 引用的 Skill 会被拒绝。
 */
@Data
public class SkillPackageUpdateDTO {

  /** 客户端读到的当前 package 版本；必须与当前活跃版本一致。 */
  private String expectedPackageVersion;

  /** 新 package 版本：必须不同于 expectedPackageVersion，且从未被使用过。 */
  private String newPackageVersion;

  /** 可空 package 描述；null/空白视为未填写。 */
  private String description;

  /** 完整替换的 Skill 定义列表；至少一个，名称唯一。 */
  private List<SkillDefinitionDTO> skills;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package update field: " + fieldName);
  }
}

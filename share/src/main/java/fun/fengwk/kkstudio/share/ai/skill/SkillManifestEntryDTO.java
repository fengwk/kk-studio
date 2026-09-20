package fun.fengwk.kkstudio.share.ai.skill;

import lombok.Data;

/**
 * Package 当前 commit 派生的单个 Skill manifest 元素。
 *
 * <p>manifest 只保留 {@code <repository>/<name>/SKILL.md} frontmatter 的两项事实，按 name 确定性排序；正文与目录树只存在于
 * Git，因此这里没有 content 字段。
 */
@Data
public class SkillManifestEntryDTO {

  /** Skill 名：非空白、无环绕空白、≤128，与目录 basename 完全一致。 */
  private String name;

  /** Skill 描述（非空、无环绕空白、≤1024），Prompt 与选择器都用它区分同名 Skill。 */
  private String description;
}

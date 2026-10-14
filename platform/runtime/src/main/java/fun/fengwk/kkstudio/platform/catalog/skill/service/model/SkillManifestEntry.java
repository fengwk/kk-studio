package fun.fengwk.kkstudio.platform.catalog.skill.service.model;

import java.util.Objects;

/**
 * Package 当前 commit 派生的单个 Skill manifest 元素：{@code <repository>/<name>/SKILL.md} frontmatter 的两项事实。
 *
 * <p>正文、references、scripts 与 assets 只存在于 Git，因此这里没有内容字段；元素按 {@code name} 确定性排序，Agent 引用与 Prompt
 * 渲染都只消费这一份投影。
 */
public record SkillManifestEntry(String name, String description) {

  public SkillManifestEntry {
    name = Objects.requireNonNull(name, "name");
    description = Objects.requireNonNull(description, "description");
  }
}

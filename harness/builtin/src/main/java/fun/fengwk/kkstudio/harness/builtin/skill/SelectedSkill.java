package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;

/**
 * 当前 Thread Agent 已选中的 Skill 的冻结身份。
 *
 * <p>{@code name} 是全局唯一 Skill 名；{@code packageName}/{@code packageVersion} 与 {@code name} 共同构成
 * 内容的三元组身份，因此 {@code load_skill} 取回的是选择当时的版本，而不是同名的新版本。
 *
 * @param name Skill 全局唯一短名，必填
 * @param packageName 承载该 Skill 的 package 名，必填
 * @param packageVersion 承载该 Skill 的 package 版本，必填
 * @param description 冻结描述，必填
 */
public record SelectedSkill(
    String name, String packageName, String packageVersion, String description) {

  public SelectedSkill {
    name = SkillNames.canonicalSkillName(name);
    packageName = SkillNames.canonicalPackageName(packageName);
    packageVersion = SkillNames.canonicalPackageVersion(packageVersion);
    description = SkillNames.canonicalDescription(description);
  }
}

package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;

/**
 * 冻结到一次 Model invocation 中的不可变 Skill 事实。
 *
 * <p>身份是全局唯一的 {@code name}；{@code packageName}/{@code packageVersion} 与 {@code name} 一起定位
 * 承载它的精确内容行，{@code description} 是规划与执行都要用的描述事实。body 被刻意排除在外：正文由 {@code load_skill} 按 {@code
 * (packageName, packageVersion, name)} 精确取回，不进入 durable 请求。
 *
 * <p>全部字段都是必填：缺少 package 身份的旧形状被明确拒绝，不做 tolerant 解码，因此旧请求不会被静默解释成“当前版本”。
 */
public record SkillBinding(
    String name, String packageName, String packageVersion, String description) {

  public SkillBinding {
    name = SkillNames.canonicalSkillName(name);
    packageName = SkillNames.canonicalPackageName(packageName);
    packageVersion = SkillNames.canonicalPackageVersion(packageVersion);
    description = SkillNames.canonicalDescription(description);
  }
}

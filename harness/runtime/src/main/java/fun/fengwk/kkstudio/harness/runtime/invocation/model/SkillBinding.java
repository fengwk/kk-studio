package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;

/**
 * 冻结到一次 Model invocation 中的不可变 Skill 事实。
 *
 * <p>身份是全局唯一的 {@code name}；{@code packageName}/{@code packageVersion} 定位承载它的不可变 package 版本， {@code
 * contentRevision} 精确锁定该版本内的内容，{@code description} 是规划与执行都要用的描述事实。body 被刻意排除在外： 正文由 {@code
 * load_skill} 按 {@code (packageName, packageVersion, name, contentRevision)} 精确取回，不进入 durable 请求。
 *
 * <p>全部字段都是必填：缺少 package 身份或 revision 的旧形状被明确拒绝，不做 tolerant 解码，因此旧请求不会被静默解释成“当前版本”。
 */
public record SkillBinding(
    String name,
    String packageName,
    String packageVersion,
    String contentRevision,
    String description) {

  public SkillBinding {
    name = SkillNames.canonicalSkillName(name);
    packageName = SkillNames.canonicalPackageName(packageName);
    packageVersion = SkillNames.canonicalPackageVersion(packageVersion);
    contentRevision = SkillNames.contentRevision(contentRevision);
    description = SkillNames.canonicalDescription(description);
  }
}

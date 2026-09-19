/**
 * Platform 全局 Skill 目录的对外 DTO。
 *
 * <p>Skill 身份是全局唯一的短名，版本身份是 {@code (packageName, packageVersion, name)}；package 更新写入一个新的 package
 * 版本，历史版本行永不修改、永不删除，删除只把当前版本与其 Skill 行置为非活跃。正文只在 {@link
 * fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO} 中回传，供客户端编辑后整体替换。
 */
package fun.fengwk.kkstudio.share.ai.skill;

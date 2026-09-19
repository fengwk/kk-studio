/**
 * Platform 全局 Skill 目录的对外 DTO。
 *
 * <p>Skill 身份是全局唯一的短名，由一个不可变的 package 版本承载；package 版本一经创建永不复用，删除只把当前版本置为非活跃。正文只在 {@link
 * fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO} 中回传，供客户端编辑后整体替换。
 */
package fun.fengwk.kkstudio.share.ai.skill;

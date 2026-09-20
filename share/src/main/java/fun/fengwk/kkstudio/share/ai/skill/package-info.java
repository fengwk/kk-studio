/**
 * Platform 全局 Git Skill Package 的对外 DTO。
 *
 * <p>Skill 身份是 {@code (packageName, name)}：Package 以不可变 {@code packageName} 键控一个不可变 repository
 * URL，发布内容由人工确认的 exact commit 决定，Skill 正文、references、scripts 与 assets 全部保留在 Git。这些 DTO
 * 只暴露仓库身份、branch 检查观察值、从 current commit 派生的 manifest 与 CAS {@code version}，不承载任何 Skill 正文。
 */
package fun.fengwk.kkstudio.share.ai.skill;

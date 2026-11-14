/**
 * Platform 侧基于 JGit 的 Skill Package Git 访问与 Manifest 扫描能力。
 *
 * <p>该包只观察远端 branch HEAD，并按 exact commit 浅拉取到一次性临时仓库后遍历对象树，不保留任何本地仓库缓存；同时按契约严格扫描和解析根目录一层 {@code
 * <name>/SKILL.md} 生成 Skill manifest。
 */
package fun.fengwk.kkstudio.platform.catalog.skill.git;

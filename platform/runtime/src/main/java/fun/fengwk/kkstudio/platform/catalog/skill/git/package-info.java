/**
 * Platform 侧基于 JGit 的 Skill Package Git Bare Cache 与 Manifest 扫描能力。
 *
 * <p>该包负责管理本地 bare repository 缓存、解析远端分支 HEAD、按 exact commit 补齐对象， 以及按契约严格扫描和解析根目录一层 {@code
 * <name>/SKILL.md} 生成 Skill manifest。
 */
package fun.fengwk.kkstudio.platform.catalog.skill.git;

/**
 * 生产环境 Environment Daemon 的 Skill Package 安装面。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.skill.SkillPackageInstaller} 是本包唯一的写入口：它把 Platform
 * 指定的 exact commit 物化到 {@code <data-dir>/skills/<package>/}，目标目录里没有 commit 分量，安装事实只由 {@code
 * .kkstudio-commit} marker 记录。安装先在 {@code skill-work/staging} 随机目录 clone 并 checkout exact commit，
 * 再逐路径物化到独立发布暂存目录并校验，最后以目录替换原子发布； 任何一步失败都保留旧目录，并用进程内单包锁串行同一 Package 的并发安装。
 *
 * <p>物化只接受 regular/blob 条目：绝对路径、{@code ..} 逃逸、符号链接与 gitlink 一律拒绝，因此 Git 内容不可能写出 skills
 * 根目录。发布失败时安装器回到上一次可用版本，进程重启后未发布的 staging 与残留备份由构造期恢复逻辑清理。
 *
 * <p>本包只处理本地安装事实，不实现 capability 协议：{@code skill.sync} 的 descriptor、参数校验与终态编码由 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.SkillSyncCapability} 复用通用 capability 执行路径完成，也不进入模型工具集合。
 */
package fun.fengwk.kkstudio.harness.daemon.skill;

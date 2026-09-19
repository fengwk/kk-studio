/**
 * 内置 Skill 加载 Tool 实现与 Prompt 资源加载。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.skill.LoadSkillTool} 作为内部（{@code
 * ToolVisibility.INTERNAL}）工具注册，依据 {@link
 * fun.fengwk.kkstudio.harness.builtin.skill.ThreadSelectedSkillLookup} 校验当前 Thread Agent 的冻结选择，再通过
 * {@link fun.fengwk.kkstudio.harness.builtin.skill.SkillContentLoader} 按冻结的精确 revision 加载 Platform
 * 全局 Skill 正文。
 *
 * <p>本包不依赖 Environment、Daemon 或宿主文件系统路径：Skill 正文是 Platform 自身的目录事实。
 */
package fun.fengwk.kkstudio.harness.builtin.skill;

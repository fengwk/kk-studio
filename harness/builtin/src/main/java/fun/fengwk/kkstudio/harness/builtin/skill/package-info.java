/**
 * 内置 Skill 加载 Tool 实现与 Prompt 资源加载。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.skill.LoadSkillTool} 作为内部（{@code
 * ToolVisibility.INTERNAL}）工具注册，依据 {@link
 * fun.fengwk.kkstudio.harness.builtin.skill.ThreadSelectedSkillLookup} 校验当前 Thread Agent
 * 的冻结选择，再通过绑定 Environment 的 {@code skill.load} Capability 按需加载 Skill 正文。
 *
 * <p>本包不直接暴露宿主文件系统路径，实际正文通过 binding-first 的 Environment 读取； 加载超时必须由外部装配显式提供，严禁缺省配置。
 */
package fun.fengwk.kkstudio.harness.builtin.skill;

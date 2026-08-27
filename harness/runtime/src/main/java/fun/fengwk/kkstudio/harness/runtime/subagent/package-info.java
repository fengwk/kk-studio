/**
 * Task/subagent 委派工具及其进程内控制面。
 *
 * <p>本包承载 {@link TaskTool} 内部 HOST Tool、{@link SubagentConfig} 并发/预算参数、{@link
 * SubagentConfigProvider} 现读通道与 {@link SubagentRunRegistry} 并发 reservation / descendant status
 * relay；{@link SubagentBranchSettingsMaterializer} 是 Core 通过 catalog 物化子 Agent branch settings
 * 的窄端口。Tool 描述与委派段落提示词位于同包 prompts 资源，由 {@link SubagentPrompts} 严格加载。
 */
package fun.fengwk.kkstudio.harness.runtime.subagent;

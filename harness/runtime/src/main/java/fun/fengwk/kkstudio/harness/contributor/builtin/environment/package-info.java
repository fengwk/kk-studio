/**
 * 内置 Environment capability 工具适配与 Prompt 资源加载。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool} 承载五个文件工具（{@code
 * read}、{@code write}、{@code edit}、{@code grep}、{@code find}）：声明 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements#routed()}；{@code resource://}
 * 路径由平台读取，本地路径交给绑定的 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment}。其余宿主环境工具（{@code bash} 与 3 个 LSP
 * 工具）基于 {@link
 * fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentCapabilityTool}，声明 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements#environment()}（即
 * REQUIRED），只能在绑定环境上执行。{@link
 * fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentPrompts} 提供对应工具描述 prompt
 * 的严格加载。
 *
 * <p>本包不负责环境通信网络协议、Daemon 进程管理或 capability catalog 注册；无绑定环境时本地路径确定性返回「未执行」。
 */
package fun.fengwk.kkstudio.harness.contributor.builtin.environment;

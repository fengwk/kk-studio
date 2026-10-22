/**
 * 内置 Environment capability 工具适配与 Prompt 资源加载。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.environment.ReadTool} 是统一读取入口，声明 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements#optionalEnvironment()}，将读取请求委托至注入的
 * {@link fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor}。其余 8 个宿主环境工具基于 {@link
 * fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool}，声明 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements#environment()}（即 REQUIRED），
 * 将文件写编辑、搜索、进程执行与 LSP 等宿主操作委托至执行期绑定的 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment}。 {@link
 * fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentPrompts} 提供对应工具描述 prompt 的严格加载。
 *
 * <p>本包不负责环境通信网络协议、Daemon 进程管理或 capability catalog 注册；无绑定环境时宿主工具确定性返回错误结果。
 */
package fun.fengwk.kkstudio.harness.builtin.environment;

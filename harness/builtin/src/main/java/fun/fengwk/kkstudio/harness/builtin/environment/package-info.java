/**
 * 内置 Environment capability 工具实现与 Prompt 资源加载。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool} 实现统一 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.Tool} SPI， 声明 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements#environment()}， 将文件读写编辑、搜索、进程执行与 LSP
 * 等环境操作委托至执行期注入的 {@link fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment}。 {@link
 * fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentPrompts} 提供对应工具描述 prompt 的严格加载。
 *
 * <p>本包不负责环境通信网络协议、Daemon 进程管理或 capability catalog 注册；无绑定环境时确定性返回错误结果。
 */
package fun.fengwk.kkstudio.harness.builtin.environment;

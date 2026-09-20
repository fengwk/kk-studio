/**
 * MiniMax Mavis 模型工具面的静态定义。
 *
 * <p>模型只看到 15 个静态、可选择的 Tool，它们的名称、description 与 input schema 都是本模块的构建期资源，不在运行时按线上 capability
 * catalog 增删或改写：这是 Agent 配置与 Prompt Cache 前缀稳定的前提。本包负责加载并严格校验这些资源，供上层 HarnessContributor
 * 直接注册，不参与请求发送。
 */
package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

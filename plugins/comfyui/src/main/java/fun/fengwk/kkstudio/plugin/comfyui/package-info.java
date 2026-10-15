/**
 * ComfyUI 构建期插件：以单一 StudioPlugin owner 提供冻结、可配置的 ComfyUI 工作流 Canvas Function。
 *
 * <p>普通配置声明连接与认证、Prompt 预算以及工作流集合；每个工作流的图、参数/媒体绑定与输出 selector 都是配置唯一来源的不可变快照，因此配置修订只替换新实例， 已冻结 Run
 * 的解释保持不变。认证只使用 scoped 凭据端口，按请求解析且绝不静默降级。
 */
package fun.fengwk.kkstudio.plugin.comfyui;

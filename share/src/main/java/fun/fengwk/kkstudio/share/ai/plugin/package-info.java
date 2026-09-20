/**
 * 构建期 Plugin 的对外 DTO。
 *
 * <p>Plugin 完全由 classpath 决定：没有 JAR 就没有 descriptor、管理动作与 Tool，数据库里也没有第二个 enabled
 * 开关。管理面只暴露安全投影（安装身份、状态、region 与有界错误），不接受任意 Plugin JSON schema：认证交互是封闭的 sealed {@link
 * fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO} union，请求体精确为 {@code {region}} 与 {@code
 * {callbackUrl}}，响应绝不包含 access token、密文、加密密钥或 client identity。认证响应统一由 Web 设置 {@code Cache-Control:
 * no-store}。
 */
package fun.fengwk.kkstudio.share.ai.plugin;

/**
 * Plugin 管理面的传输 DTO。
 *
 * <p>Plugin 完全由 classpath 决定：没有 JAR 就没有 descriptor、管理动作与 Tool，数据库里也没有第二个 enabled
 * 开关。管理面只暴露安全投影（安装身份、状态、region 与有界错误），认证交互是封闭的 sealed {@link
 * fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthKindDTO} union，配置值是由 Plugin schema 决定的 JSON
 * 节点，秘密只以说明与布尔出现。认证与连接检查响应由控制器设置 {@code Cache-Control: no-store}。
 */
package fun.fengwk.kkstudio.platform.pluginhost.dto;

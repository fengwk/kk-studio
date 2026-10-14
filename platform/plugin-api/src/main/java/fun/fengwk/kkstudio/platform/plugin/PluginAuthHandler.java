package fun.fengwk.kkstudio.platform.plugin;

/**
 * {@code DEEP_LINK} 认证交互：Plugin 给出所选 region 的固定公开登录地址，并在用户粘贴回调后交还待加密凭据材料。
 *
 * <p>管理面协议是封闭的：prepare 请求只有 {@code region}，complete 请求只有 {@code callbackUrl}，不存在任意 Plugin JSON
 * schema 或动态 UI 代码。实现必须自己严格校验回调（长度、字段、region、token 时效与在线验证），并在失败时抛出异常而不是返回占位材料；异常消息不得回显回调原文或 token。
 */
public interface PluginAuthHandler {

  /**
   * 所选 region 在新窗口打开的官方登录地址。
   *
   * <p>{@code region} 保证是 descriptor 声明的候选；实现仍必须按自己的固定 origin 映射，绝不接受自定义 base URL。
   */
  String loginUrl(String region);

  /**
   * 严格校验回调并交还待加密凭据材料。
   *
   * <p>返回材料中的 {@code region} 必须属于 descriptor 候选，时间必须已过在线校验，{@code payloadJson} 是 Plugin 自己的 opaque
   * JSON。任何失败都必须抛出异常，绝不返回半成品材料。
   */
  PluginCredentialMaterial complete(String callbackUrl);
}

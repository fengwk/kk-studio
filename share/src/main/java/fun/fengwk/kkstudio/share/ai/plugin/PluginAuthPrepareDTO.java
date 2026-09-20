package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;

/**
 * {@code POST /api/plugins/{pluginId}/auth/prepare} 响应体：要在新窗口打开的官方登录链接。
 *
 * <p>链接只能是 Plugin 为所选 region 声明的固定公开 origin，不含任何动态脚本、token 或签名参数。
 */
@Data
public class PluginAuthPrepareDTO {

  /** Plugin 固定公开 origin 上的登录地址。 */
  private String loginUrl;
}

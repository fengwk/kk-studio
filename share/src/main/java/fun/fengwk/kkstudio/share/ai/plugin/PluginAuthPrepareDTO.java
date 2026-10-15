package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/plugins/{pluginId}/auth/prepare} 响应体：本次 pending attempt 的句柄与要在新窗口打开的官方登录链接。
 *
 * <p>链接只能是 Plugin 为所选 region 声明的固定公开 origin，不含任何动态脚本、token 或签名参数；{@code authAttemptId}
 * 是一次性句柄，只用于随后的 complete，不含任何可推导的秘密。
 */
@Data
public class PluginAuthPrepareDTO {

  /** 本次 pending attempt 的一次性句柄：complete 必须原样带上，且只能成功消费一次。该句柄不进入 {@code toString}。 */
  @ToString.Exclude private String authAttemptId;

  /** Plugin 固定公开 origin 上的登录地址。 */
  private String loginUrl;
}

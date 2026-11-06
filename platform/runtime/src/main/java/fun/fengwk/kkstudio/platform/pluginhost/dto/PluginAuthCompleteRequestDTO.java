package fun.fengwk.kkstudio.platform.pluginhost.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/platform/plugins/{pluginId}/auth/complete} 请求体：prepare 返回的一次性句柄与用户粘贴回来的回调地址。
 *
 * <p>{@code callbackUrl} 是只写字段：它只在本次 {@code no-store} 请求内短暂存在，绝不出现在任何响应、{@code toString} 或通用日志中。
 */
@Data
public class PluginAuthCompleteRequestDTO {

  /** prepare 返回的一次性 attempt 句柄。该句柄不进入 {@code toString}。 */
  @ToString.Exclude private String authAttemptId;

  /** 插件回调 deep link 原值（含一次性登录参数）。 */
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @ToString.Exclude
  private String callbackUrl;
}

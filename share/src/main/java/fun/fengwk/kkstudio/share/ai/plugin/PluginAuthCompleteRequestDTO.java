package fun.fengwk.kkstudio.share.ai.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/plugins/{pluginId}/auth/complete} 请求体：用户粘贴回来的回调地址。
 *
 * <p>{@code callbackUrl} 是只写字段：它只在本次 {@code no-store} 请求内短暂存在，绝不出现在任何响应、{@code toString}
 * 或通用日志中，服务端解析后只保留加密凭据。
 */
@Data
public class PluginAuthCompleteRequestDTO {

  /** 插件回调 deep link 原值（含一次性登录参数）。 */
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @ToString.Exclude
  private String callbackUrl;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown plugin auth complete field: " + fieldName);
  }
}

package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** Backend 全局代理（新 HTTP 请求生效）；null proxyUrl 表示明确直连，不支持认证或模块覆盖。 */
@Data
public class SystemSettingsNetworkDTO {

  private @Nullable String proxyUrl;

  private String noProxyHosts;
}

package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** Backend 出站请求的 HTTP 代理（新请求生效）；null proxyUrl 表示明确直连，不支持认证或模块覆盖。 */
@Data
public class SystemSettingsBackendNetworkDTO {

  private @Nullable String proxyUrl;

  private String noProxyHosts;
}

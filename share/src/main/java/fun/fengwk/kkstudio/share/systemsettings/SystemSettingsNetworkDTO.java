package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

/** Backend 全局代理（重启生效）；null proxyUrl 表示明确直连，不支持认证或模块覆盖。 */
@Data
public class SystemSettingsNetworkDTO {

  @JsonDeserialize(using = SystemSettingsNetworkTextDeserializer.class)
  private String proxyUrl;

  @JsonDeserialize(using = SystemSettingsNetworkTextDeserializer.class)
  private String noProxyHosts;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings network field: " + name);
  }
}

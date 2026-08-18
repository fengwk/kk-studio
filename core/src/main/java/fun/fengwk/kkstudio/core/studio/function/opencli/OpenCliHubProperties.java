package fun.fengwk.kkstudio.core.studio.function.opencli;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenCLI Hub 的部署级实例身份；连接/超时/缓冲等运行参数由 SystemSettings.integrations.openCliHub 提供。 */
@ConfigurationProperties("kk-studio.opencli-hub")
@Data
public class OpenCliHubProperties {

  /** 本部署的 Hub 实例身份（可选，1..36 字符），随执行请求透传给 Hub。 */
  private String instanceId;

  public void validate() {
    if (instanceId != null) {
      instanceId = instanceId.strip();
      if (instanceId.isEmpty() || instanceId.length() > 36) {
        throw new IllegalArgumentException("instanceId must be absent or 1..36 characters");
      }
    }
  }
}

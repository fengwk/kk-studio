package fun.fengwk.kkstudio.core.ai.environment.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment Daemon gateway 的部署级密钥；资源/消息边界与超时由 SystemSettings.environment 提供。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.environment-gateway")
public class EnvironmentGatewayProperties {

  /** 部署级共享密钥，敏感字段：HELLO 握手时用于校验 daemon 身份（常量时间比较），不允许出现在日志或公共输出 中。 */
  private String daemonToken;

  /** 返回附加 Environment Daemon 所需的部署级密钥。 */
  public String requireDaemonToken() {
    if (daemonToken == null || daemonToken.isBlank()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.daemon-token must not be blank");
    }
    return daemonToken;
  }
}

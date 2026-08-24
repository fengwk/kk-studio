package fun.fengwk.kkstudio.platform.storage.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Storage Maintenance 的部署级轮询与上传清理 lease 配置。 */
@ConfigurationProperties(prefix = "kk-studio.storage.maintenance")
@Data
public class StorageMaintenanceProperties {

  /** 后台兜底轮询间隔。 */
  private Duration pollDelay = Duration.ofSeconds(30);

  /** 过期上传对象清理的所有权 lease。 */
  private Duration cleanupLease = Duration.ofMinutes(5);
}

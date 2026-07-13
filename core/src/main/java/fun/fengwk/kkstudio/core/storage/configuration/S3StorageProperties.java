package fun.fengwk.kkstudio.core.storage.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 存储配置属性.
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-circle.storage.s3")
@Data
public class S3StorageProperties {

  private boolean enabled;
  private String endpoint;
  private String region = "auto";
  private String bucket;
  private String accessKey;
  private String secretKey;
  private String publicBaseUrl;
}

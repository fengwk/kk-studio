package fun.fengwk.kkstudio.core.storage.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * S3 存储配置属性.
 *
 * @author fengwk
 */
@RefreshScope
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

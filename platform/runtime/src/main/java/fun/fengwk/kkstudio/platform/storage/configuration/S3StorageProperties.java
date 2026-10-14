package fun.fengwk.kkstudio.platform.storage.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 部署连接配置属性。
 *
 * <p>{@code endpoint} 用于服务端 SDK 读写；{@code publicEndpoint}（可选）面向浏览器直传/直下发的签名 URL，为空时回退到 {@code
 * endpoint}。签名 expiry 默认值/上限等非敏感运行参数由 SystemSettings.storageMedia 提供。
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-studio.storage.s3")
@Getter
@Setter
public class S3StorageProperties {

  /** 服务端 SDK 读写的 S3 endpoint（path-style，MinIO 兼容），必填非空白。 */
  private String endpoint;

  /** 面向浏览器直传/直下发的可外部访问 endpoint，可选。 留空时服务端预签名 URL 会回退到 {@code endpoint}，仅用于服务端内网访问的场景。 */
  private String publicEndpoint;

  /** S3 region，按 {@code Region.of(region)} 解析，必填非空白。 */
  private String region;

  /** 存储桶名：固定来自配置，调用方不能选择；必填非空白。 */
  private String bucket;

  /** 访问密钥 ID（AK），敏感凭据：用于构造静态凭据，必填非空白，禁止写入日志。 */
  private String accessKey;

  /** 访问密钥 Secret（SK），敏感凭据：用于构造静态凭据，必填非空白，禁止写入日志。 */
  private String secretKey;

  /** 获取面向浏览器直传的 endpoint，未设置时回退到 {@code endpoint}. */
  public String getEffectivePublicEndpoint() {
    return (publicEndpoint == null || publicEndpoint.isBlank()) ? endpoint : publicEndpoint;
  }
}

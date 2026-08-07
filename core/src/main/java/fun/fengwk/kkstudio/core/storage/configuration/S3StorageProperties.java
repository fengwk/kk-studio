package fun.fengwk.kkstudio.core.storage.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 存储配置属性.
 *
 * <p>{@code endpoint} 用于服务端 SDK 读写；{@code publicEndpoint}（可选）面向浏览器直传/直下发的签名 URL， 为空时回退到 {@code
 * endpoint}。签名相关的 expiry 默认值与上限集中维护在此，供服务端在生成预签名 URL 时使用。
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-studio.storage.s3")
@Data
public class S3StorageProperties {

  /** 默认签名有效期（秒），用于当调用方未显式传入 {@code expiresInSeconds} 时。 */
  public static final long DEFAULT_PRESIGN_EXPIRES_SECONDS = 600L;

  /** 签名有效期的最大上限（秒），超过此值的请求会被拒绝。 */
  public static final long MAX_PRESIGN_EXPIRES_SECONDS = 3600L;

  /**
   * 是否启用 S3 存储：{@code kk-studio.storage.s3.enabled=true} 时才装配 S3Client / S3Presigner 及相关 服务 bean。
   */
  private boolean enabled;

  /** 服务端 SDK 读写的 S3 endpoint（path-style，MinIO 兼容），启用时必填非空白。 */
  private String endpoint;

  /** 面向浏览器直传/直下发的可外部访问 endpoint，可选。 留空时服务端预签名 URL 会回退到 {@code endpoint}，仅用于服务端内网访问的场景。 */
  private String publicEndpoint;

  /** S3 region，默认 {@code "auto"}；按 {@code Region.of(region)} 解析，启用时必填非空白。 */
  private String region = "auto";

  /** 存储桶名：固定来自配置，调用方不能选择；启用时必填非空白。 */
  private String bucket;

  /** 访问密钥 ID（AK），敏感凭据：用于构造静态凭据，启用时必填非空白，禁止写入日志。 */
  private String accessKey;

  /** 访问密钥 Secret（SK），敏感凭据：用于构造静态凭据，启用时必填非空白，禁止写入日志。 */
  private String secretKey;

  /** 公开访问基础 URL（用于服务端合成永久直链；与浏览器预签名 URL 无关）。 */
  private String publicBaseUrl;

  /** 签名默认有效期（秒），未设置时取 {@link #DEFAULT_PRESIGN_EXPIRES_SECONDS}. */
  private Long presignDefaultExpiresSeconds;

  /** 签名最长有效期（秒），未设置时取 {@link #MAX_PRESIGN_EXPIRES_SECONDS}. */
  private Long presignMaxExpiresSeconds;

  /** 获取签名默认有效期（秒），未设置时回退到 {@link #DEFAULT_PRESIGN_EXPIRES_SECONDS}. */
  public long getEffectivePresignDefaultExpiresSeconds() {
    return presignDefaultExpiresSeconds != null
        ? presignDefaultExpiresSeconds
        : DEFAULT_PRESIGN_EXPIRES_SECONDS;
  }

  /** 获取签名最长有效期（秒），未设置时回退到 {@link #MAX_PRESIGN_EXPIRES_SECONDS}. */
  public long getEffectivePresignMaxExpiresSeconds() {
    return presignMaxExpiresSeconds != null
        ? presignMaxExpiresSeconds
        : MAX_PRESIGN_EXPIRES_SECONDS;
  }

  /** 获取面向浏览器直传的 endpoint，未设置时回退到 {@code endpoint}. */
  public String getEffectivePublicEndpoint() {
    return (publicEndpoint == null || publicEndpoint.isBlank()) ? endpoint : publicEndpoint;
  }
}

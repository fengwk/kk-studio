package fun.fengwk.kkstudio.platform.storage;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;

import java.util.Objects;

/**
 * S3 基础设施可用性与就绪探针。
 *
 * <p>校验 S3 必配属性（endpoint, region, bucket, accessKey, secretKey）非空且不输出配置值；作为 Spring bean 初始化时通过
 * {@code headBucket} 校验存储桶可达性，失败包装为不携带底层异常的 {@link IllegalStateException}。
 */
public class S3ReadinessProbe implements InitializingBean {

  private final S3StorageProperties properties;
  private final S3Client s3Client;

  public S3ReadinessProbe(S3StorageProperties properties, S3Client s3Client) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    validateProperties(properties);
  }

  @Override
  public void afterPropertiesSet() {
    checkReadiness();
  }

  /**
   * 校验 S3 桶可达性。
   *
   * @throws IllegalStateException 若 bucket 不存在或不可达
   */
  public void checkReadiness() {
    validateProperties(properties);
    try {
      s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
    } catch (RuntimeException ignored) {
      throw new IllegalStateException(
          "S3 bucket readiness check failed: bucket is unreachable or does not exist");
    }
  }

  public static void validateProperties(S3StorageProperties properties) {
    Objects.requireNonNull(properties, "properties");
    Assert.hasText(properties.getEndpoint(), "kk-studio.storage.s3.endpoint must not be blank");
    Assert.hasText(properties.getRegion(), "kk-studio.storage.s3.region must not be blank");
    Assert.hasText(properties.getBucket(), "kk-studio.storage.s3.bucket must not be blank");
    Assert.hasText(properties.getAccessKey(), "kk-studio.storage.s3.access-key must not be blank");
    Assert.hasText(properties.getSecretKey(), "kk-studio.storage.s3.secret-key must not be blank");
  }
}

package fun.fengwk.kkstudio.core.storage.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3PresignServiceImpl;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.S3StorageServiceImpl;

import java.net.URI;

/**
 * S3 存储配置.
 *
 * <p>同时提供面向服务端的 {@link S3Client}（用于内部读写）和面向浏览器直传/直下发的 {@link S3Presigner} （基于可外部访问的 {@code
 * publicEndpoint}，path-style，MinIO 兼容）。bucket 始终来自配置，调用方不能选择。
 *
 * @author fengwk
 */
@ConditionalOnProperty(prefix = "kk-studio.storage.s3", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(S3StorageProperties.class)
@Configuration
public class S3StorageConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(S3Client.class)
  public S3Client s3Client(S3StorageProperties properties) {
    validateProperties(properties);
    return S3Client.builder()
        .endpointOverride(URI.create(properties.getEndpoint()))
        .region(Region.of(properties.getRegion()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
        .serviceConfiguration(newS3Configuration())
        .httpClientBuilder(ApacheHttpClient.builder())
        .build();
  }

  /**
   * 面向浏览器直传/直下发的预签名客户端，使用 {@code publicEndpoint}（未配置时回退到 {@code endpoint}）， 同样采用 path-style 以兼容
   * MinIO 风格的对象存储。
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(S3Presigner.class)
  public S3Presigner s3Presigner(S3StorageProperties properties) {
    validateProperties(properties);
    String publicEndpoint = properties.getEffectivePublicEndpoint();
    Assert.hasText(publicEndpoint, "kk-studio.storage.s3.public-endpoint must not be blank");
    return S3Presigner.builder()
        .endpointOverride(URI.create(publicEndpoint))
        .region(Region.of(properties.getRegion()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
        .serviceConfiguration(newS3Configuration())
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(S3StorageService.class)
  public S3StorageService s3StorageService(S3StorageProperties properties, S3Client s3Client) {
    return new S3StorageServiceImpl(properties, s3Client);
  }

  @Bean
  @ConditionalOnMissingBean(S3PresignService.class)
  public S3PresignService s3PresignService(
      S3StorageProperties properties, S3Presigner s3Presigner) {
    return new S3PresignServiceImpl(properties, s3Presigner);
  }

  private S3Configuration newS3Configuration() {
    return S3Configuration.builder().pathStyleAccessEnabled(true).build();
  }

  private void validateBaseProperties(S3StorageProperties properties) {
    Assert.hasText(properties.getEndpoint(), "kk-studio.storage.s3.endpoint must not be blank");
    Assert.hasText(properties.getRegion(), "kk-studio.storage.s3.region must not be blank");
    Assert.hasText(properties.getBucket(), "kk-studio.storage.s3.bucket must not be blank");
    Assert.hasText(properties.getAccessKey(), "kk-studio.storage.s3.access-key must not be blank");
    Assert.hasText(properties.getSecretKey(), "kk-studio.storage.s3.secret-key must not be blank");
  }

  private void validateProperties(S3StorageProperties properties) {
    validateBaseProperties(properties);
    long maxExpires = properties.getEffectivePresignMaxExpiresSeconds();
    long defaultExpires = properties.getEffectivePresignDefaultExpiresSeconds();
    Assert.isTrue(
        defaultExpires > 0L,
        "kk-studio.storage.s3.presign-default-expires-seconds must be positive");
    Assert.isTrue(
        maxExpires >= defaultExpires,
        "kk-studio.storage.s3.presign-max-expires-seconds must be >= default");
  }
}

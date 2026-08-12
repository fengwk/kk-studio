package fun.fengwk.kkstudio.core.storage.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import fun.fengwk.kkstudio.core.ai.runtime.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.core.ai.runtime.tool.gateway.GlobalStorageToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3PresignServiceImpl;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.S3StorageServiceImpl;
import fun.fengwk.kkstudio.core.storage.StorageStartupRecovery;
import fun.fengwk.kkstudio.core.storage.persistence.SessionBlobRefRepository;
import fun.fengwk.kkstudio.core.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.core.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.core.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.storage.service.impl.HeadOnlyStorageMediaProbe;
import fun.fengwk.kkstudio.core.storage.service.impl.PostgresqlSessionBlobRefManager;
import fun.fengwk.kkstudio.core.storage.service.impl.PostgresqlStorageBlobIngestService;
import fun.fengwk.kkstudio.core.storage.service.impl.PostgresqlStorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.impl.StorageUploadServiceImpl;

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
@EnableConfigurationProperties({S3StorageProperties.class, StorageProperties.class})
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

  /** 默认媒体事实探针：只记录 HEAD 元数据；后续 Canvas 媒体集成可替换为 Ffmpeg 实现。 */
  @Bean
  @ConditionalOnMissingBean(StorageMediaProbe.class)
  public StorageMediaProbe storageMediaProbe() {
    return new HeadOnlyStorageMediaProbe();
  }

  /** blob 生命周期管理器：retain/release 为 MANDATORY 事务方法，零引用后 afterCommit 回收 S3 对象。 */
  @Bean
  @ConditionalOnMissingBean(StorageBlobManager.class)
  public StorageBlobManager storageBlobManager(
      StorageBlobRepository blobRepository,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService) {
    return new PostgresqlStorageBlobManager(blobRepository, s3StorageService, s3PresignService);
  }

  /** 上传契约服务：reserve/complete/delete 与机会式过期回收。 */
  @Bean
  @ConditionalOnMissingBean(StorageUploadService.class)
  public StorageUploadService storageUploadService(
      StorageUploadRepository uploadRepository,
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService,
      StorageMediaProbe mediaProbe,
      S3StorageProperties s3Properties,
      StorageProperties storageProperties,
      PlatformTransactionManager transactionManager) {
    return new StorageUploadServiceImpl(
        uploadRepository,
        blobRepository,
        blobManager,
        s3StorageService,
        s3PresignService,
        mediaProbe,
        s3Properties,
        storageProperties,
        transactionManager);
  }

  /** 启动一次性恢复：过期上传回收 + DELETING blob 清扫，无周期性后台线程。 */
  @Bean
  public StorageStartupRecovery storageStartupRecovery(
      StorageUploadService storageUploadService, StorageBlobManager storageBlobManager) {
    return new StorageStartupRecovery(storageUploadService, storageBlobManager);
  }

  /** Session blob 引用边的显式 owner：insert/delete 与 blob retain/release 成对维护（MANDATORY 事务）。 */
  @Bean
  @ConditionalOnMissingBean(SessionBlobRefManager.class)
  public SessionBlobRefManager sessionBlobRefManager(
      SessionBlobRefRepository refRepository, StorageBlobManager blobManager) {
    return new PostgresqlSessionBlobRefManager(refRepository, blobManager);
  }

  /** 服务端字节内容的 blob 摄入：Tool/Daemon Resource 外部化的存储入口（MANDATORY 事务）。 */
  @Bean
  @ConditionalOnMissingBean(StorageBlobIngestService.class)
  public StorageBlobIngestService storageBlobIngestService(
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      SessionBlobRefManager refManager,
      S3StorageService s3StorageService) {
    return new PostgresqlStorageBlobIngestService(
        blobRepository, blobManager, refManager, s3StorageService);
  }

  /** Provider attempt 的 Resource 物化端口（每次 attempt 生成新鲜预签名 URL，绝不持久化）。 */
  @Bean
  @ConditionalOnMissingBean(ProviderResourceMaterializer.class)
  public ProviderResourceMaterializer providerResourceMaterializer(
      StorageBlobManager storageBlobManager) {
    return ProviderResourceMaterializer.withStorage(storageBlobManager);
  }

  /** Tool outcome 的 durable history 物化端口：瞬时 Resource 引用外部化为全局 blob 后进入 history。 */
  @Bean
  @ConditionalOnMissingBean(GlobalStorageToolResultHistoryMaterializer.class)
  public GlobalStorageToolResultHistoryMaterializer globalStorageToolResultHistoryMaterializer(
      StorageBlobIngestService ingestService,
      S3StorageService s3StorageService,
      S3StorageProperties s3Properties) {
    return new GlobalStorageToolResultHistoryMaterializer(
        ingestService, s3StorageService, s3Properties);
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

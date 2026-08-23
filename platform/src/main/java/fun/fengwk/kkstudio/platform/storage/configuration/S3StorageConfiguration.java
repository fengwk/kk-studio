package fun.fengwk.kkstudio.platform.storage.configuration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.GlobalStorageToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.S3PresignServiceImpl;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.S3StorageServiceImpl;
import fun.fengwk.kkstudio.platform.storage.StorageStartupRecovery;
import fun.fengwk.kkstudio.platform.storage.persistence.SessionBlobRefRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.impl.HeadOnlyStorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlSessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageBlobIngestService;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.impl.StorageUploadServiceImpl;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;

/**
 * S3 存储配置。
 *
 * <p>同时提供面向服务端的 {@link S3Client}（用于内部读写）和面向浏览器直传/直下发的 {@link S3Presigner} （基于可外部访问的 {@code
 * publicEndpoint}，path-style，MinIO 兼容）。bucket 始终来自配置，调用方不能选择。
 *
 * <p>装配开关来自 SystemSettings.storageMedia.s3Enabled（不再是 @ConfigurationProperties 属性）：关闭时整族 bean 不装配
 * （长生命周期拓扑以启动快照为准，运行时更新需重启），无需提供任何 S3 连接/凭据；开启且 bootstrap 校验失败时启动明确报错。
 *
 * @author fengwk
 */
@EnableConfigurationProperties(S3StorageProperties.class)
@Configuration
public class S3StorageConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(S3Client.class)
  public S3Client s3Client(S3StorageProperties properties, SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
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
  public S3Presigner s3Presigner(S3StorageProperties properties, SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
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
  public S3StorageService s3StorageService(
      S3StorageProperties properties,
      ObjectProvider<S3Client> s3ClientProvider,
      SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new S3StorageServiceImpl(
        properties, Objects.requireNonNull(s3ClientProvider.getIfAvailable(), "s3Client"));
  }

  @Bean
  @ConditionalOnMissingBean(S3PresignService.class)
  public S3PresignService s3PresignService(
      S3StorageProperties properties,
      ObjectProvider<S3Presigner> s3PresignerProvider,
      SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new S3PresignServiceImpl(
        properties,
        Objects.requireNonNull(s3PresignerProvider.getIfAvailable(), "s3Presigner"),
        snapshot.get().storageMedia());
  }

  /** 默认媒体事实探针：只记录 HEAD 元数据；后续 Canvas 媒体集成可替换为 Ffmpeg 实现。 */
  @Bean
  @ConditionalOnMissingBean(StorageMediaProbe.class)
  public StorageMediaProbe storageMediaProbe(SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new HeadOnlyStorageMediaProbe();
  }

  /** blob 生命周期管理器：retain/release 为 MANDATORY 事务方法，零引用后 afterCommit 回收 S3 对象。 */
  @Bean
  @ConditionalOnMissingBean(StorageBlobManager.class)
  public StorageBlobManager storageBlobManager(
      StorageBlobRepository blobRepository,
      ObjectProvider<S3StorageService> s3StorageService,
      ObjectProvider<S3PresignService> s3PresignService,
      SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new PostgresqlStorageBlobManager(
        blobRepository,
        Objects.requireNonNull(s3StorageService.getIfAvailable(), "s3StorageService"),
        Objects.requireNonNull(s3PresignService.getIfAvailable(), "s3PresignService"));
  }

  /** 上传契约服务：reserve/complete/delete 与机会式过期回收。 */
  @Bean
  @ConditionalOnMissingBean(StorageUploadService.class)
  public StorageUploadService storageUploadService(
      StorageUploadRepository uploadRepository,
      StorageBlobRepository blobRepository,
      ObjectProvider<StorageBlobManager> blobManager,
      ObjectProvider<S3StorageService> s3StorageService,
      ObjectProvider<S3PresignService> s3PresignService,
      ObjectProvider<StorageMediaProbe> mediaProbe,
      S3StorageProperties s3Properties,
      SystemSettingsSnapshot snapshot,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new StorageUploadServiceImpl(
        uploadRepository,
        blobRepository,
        Objects.requireNonNull(blobManager.getIfAvailable(), "blobManager"),
        Objects.requireNonNull(s3StorageService.getIfAvailable(), "s3StorageService"),
        Objects.requireNonNull(s3PresignService.getIfAvailable(), "s3PresignService"),
        Objects.requireNonNull(mediaProbe.getIfAvailable(), "mediaProbe"),
        s3Properties,
        snapshot.get().storageMedia(),
        clock,
        transactionManager);
  }

  /** 启动一次性恢复：过期上传回收 + DELETING blob 清扫，无周期性后台线程。始终注册；S3 未启用时内部感知服务缺失并跳过。 */
  @Bean
  public StorageStartupRecovery storageStartupRecovery(
      ObjectProvider<StorageUploadService> storageUploadService,
      ObjectProvider<StorageBlobManager> storageBlobManager) {
    return new StorageStartupRecovery(storageUploadService, storageBlobManager);
  }

  /** Session blob 引用边的显式 owner：insert/delete 与 blob retain/release 成对维护（MANDATORY 事务）。 */
  @Bean
  @ConditionalOnMissingBean(SessionBlobRefManager.class)
  public SessionBlobRefManager sessionBlobRefManager(
      SessionBlobRefRepository refRepository,
      ObjectProvider<StorageBlobManager> blobManager,
      SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new PostgresqlSessionBlobRefManager(
        refRepository, Objects.requireNonNull(blobManager.getIfAvailable(), "blobManager"));
  }

  /** 服务端字节内容的 blob 摄入：Tool/Daemon Resource 外部化的存储入口（MANDATORY 事务）。 */
  @Bean
  @ConditionalOnMissingBean(StorageBlobIngestService.class)
  public StorageBlobIngestService storageBlobIngestService(
      StorageBlobRepository blobRepository,
      ObjectProvider<StorageBlobManager> blobManager,
      ObjectProvider<SessionBlobRefManager> refManager,
      ObjectProvider<S3StorageService> s3StorageService,
      SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new PostgresqlStorageBlobIngestService(
        blobRepository,
        Objects.requireNonNull(blobManager.getIfAvailable(), "blobManager"),
        Objects.requireNonNull(refManager.getIfAvailable(), "refManager"),
        Objects.requireNonNull(s3StorageService.getIfAvailable(), "s3StorageService"));
  }

  /** Provider attempt 的 Resource 物化端口（图片内联、其它媒体按需签名，瞬时 source 绝不持久化）。 */
  @Bean
  @ConditionalOnMissingBean(ProviderResourceMaterializer.class)
  public ProviderResourceMaterializer providerResourceMaterializer(
      ObjectProvider<StorageBlobManager> storageBlobManager,
      ObjectProvider<S3StorageService> s3StorageService,
      SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return ProviderResourceMaterializer.withStorage(
        Objects.requireNonNull(storageBlobManager.getIfAvailable(), "storageBlobManager"),
        Objects.requireNonNull(s3StorageService.getIfAvailable(), "s3StorageService"));
  }

  /** Tool outcome 的 durable history 物化端口：瞬时 Resource 引用外部化为全局 blob 后进入 history。 */
  @Bean
  @ConditionalOnMissingBean(GlobalStorageToolResultHistoryMaterializer.class)
  public GlobalStorageToolResultHistoryMaterializer globalStorageToolResultHistoryMaterializer(
      ObjectProvider<StorageBlobIngestService> ingestService,
      ObjectProvider<S3StorageService> s3StorageService,
      S3StorageProperties s3Properties,
      SystemSettingsSnapshot snapshot) {
    if (!s3Enabled(snapshot)) {
      return null;
    }
    return new GlobalStorageToolResultHistoryMaterializer(
        Objects.requireNonNull(ingestService.getIfAvailable(), "ingestService"),
        Objects.requireNonNull(s3StorageService.getIfAvailable(), "s3StorageService"),
        s3Properties);
  }

  private static boolean s3Enabled(SystemSettingsSnapshot snapshot) {
    return Objects.requireNonNull(snapshot, "snapshot").get().storageMedia().s3Enabled();
  }

  private S3Configuration newS3Configuration() {
    return S3Configuration.builder().pathStyleAccessEnabled(true).build();
  }

  private void validateProperties(S3StorageProperties properties) {
    Assert.hasText(properties.getEndpoint(), "kk-studio.storage.s3.endpoint must not be blank");
    Assert.hasText(properties.getRegion(), "kk-studio.storage.s3.region must not be blank");
    Assert.hasText(properties.getBucket(), "kk-studio.storage.s3.bucket must not be blank");
    Assert.hasText(properties.getAccessKey(), "kk-studio.storage.s3.access-key must not be blank");
    Assert.hasText(properties.getSecretKey(), "kk-studio.storage.s3.secret-key must not be blank");
  }
}

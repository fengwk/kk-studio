package fun.fengwk.kkstudio.platform.storage.configuration;

import org.springframework.beans.factory.ObjectProvider;
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

import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.GlobalStorageToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.S3PresignServiceImpl;
import fun.fengwk.kkstudio.platform.storage.S3ReadinessProbe;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.S3StorageServiceImpl;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenanceWakeup;
import fun.fengwk.kkstudio.platform.storage.persistence.SessionBlobRefRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.impl.HeadOnlyStorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlSessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageBlobIngestService;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.impl.StorageBlobContentServiceImpl;
import fun.fengwk.kkstudio.platform.storage.service.impl.StorageUploadServiceImpl;

import java.net.URI;
import java.time.Clock;

/**
 * S3/全局 Blob 存储配置：必配基础设施，所有 S3/storage bean 固定注册。
 *
 * <p>同时提供面向服务端的 {@link S3Client}（用于内部读写）和面向浏览器直传/直下发的 {@link S3Presigner} （基于可外部访问的 {@code
 * publicEndpoint}，path-style，MinIO 兼容）。bucket 始终来自配置，调用方不能选择。
 *
 * @author fengwk
 */
@EnableConfigurationProperties({S3StorageProperties.class, StorageMaintenanceProperties.class})
@Configuration
public class S3StorageConfiguration {

  @Bean(destroyMethod = "close")
  public S3Client s3Client(S3StorageProperties properties) {
    S3ReadinessProbe.validateProperties(properties);
    try {
      return S3Client.builder()
          .endpointOverride(URI.create(properties.getEndpoint()))
          .region(Region.of(properties.getRegion()))
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
          .serviceConfiguration(newS3Configuration())
          .httpClientBuilder(ApacheHttpClient.builder())
          .build();
    } catch (RuntimeException ignored) {
      throw new IllegalStateException("S3 client configuration is invalid");
    }
  }

  /**
   * 面向浏览器直传/直下发的预签名客户端，使用 {@code publicEndpoint}（未配置时回退到 {@code endpoint}），同样采用 path-style 以兼容
   * MinIO 风格的对象存储。
   */
  @Bean(destroyMethod = "close")
  public S3Presigner s3Presigner(S3StorageProperties properties) {
    S3ReadinessProbe.validateProperties(properties);
    String publicEndpoint = properties.getEffectivePublicEndpoint();
    Assert.hasText(publicEndpoint, "kk-studio.storage.s3.public-endpoint must not be blank");
    try {
      return S3Presigner.builder()
          .endpointOverride(URI.create(publicEndpoint))
          .region(Region.of(properties.getRegion()))
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
          .serviceConfiguration(newS3Configuration())
          .build();
    } catch (RuntimeException ignored) {
      throw new IllegalStateException("S3 presigner configuration is invalid");
    }
  }

  /** S3 readiness/bootstrap 探针：初始化时验证必配参数与 headBucket 可达性。 */
  @Bean
  public S3ReadinessProbe s3ReadinessProbe(S3StorageProperties properties, S3Client s3Client) {
    return new S3ReadinessProbe(properties, s3Client);
  }

  @Bean
  public S3StorageService s3StorageService(S3StorageProperties properties, S3Client s3Client) {
    return new S3StorageServiceImpl(properties, s3Client);
  }

  @Bean
  public S3PresignService s3PresignService(
      S3StorageProperties properties, S3Presigner s3Presigner, SystemSettingsSnapshot snapshot) {
    return new S3PresignServiceImpl(properties, s3Presigner, snapshot.get().storageMedia());
  }

  /** 默认媒体事实探针：只记录 HEAD 元数据；后续 Canvas 媒体集成可替换为 Ffmpeg 实现。 */
  @Bean
  public StorageMediaProbe storageMediaProbe() {
    return new HeadOnlyStorageMediaProbe();
  }

  /** blob 生命周期管理器：retain/release 为 MANDATORY 事务方法，零引用后 afterCommit 只唤醒后台维护。 */
  @Bean
  public StorageBlobManager storageBlobManager(
      StorageBlobRepository blobRepository,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService,
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeup) {
    return new PostgresqlStorageBlobManager(
        blobRepository, s3StorageService, s3PresignService, maintenanceWakeup);
  }

  /** 最小的 Blob 内容读取边界：短事务 retain 权威元数据，事务外 S3 下载，finally 短事务 release。 */
  @Bean
  public StorageBlobContentService storageBlobContentService(
      StorageBlobManager storageBlobManager,
      S3StorageService s3StorageService,
      PlatformTransactionManager transactionManager) {
    return new StorageBlobContentServiceImpl(
        storageBlobManager, s3StorageService, transactionManager);
  }

  /** 上传契约服务：reserve/complete/delete 与 cleanup-lease 过期回收。 */
  @Bean
  public StorageUploadService storageUploadService(
      StorageUploadRepository uploadRepository,
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService,
      StorageMediaProbe mediaProbe,
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeup,
      S3StorageProperties s3Properties,
      StorageMaintenanceProperties maintenanceProperties,
      SystemSettingsSnapshot snapshot,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    return new StorageUploadServiceImpl(
        uploadRepository,
        blobRepository,
        blobManager,
        s3StorageService,
        s3PresignService,
        mediaProbe,
        maintenanceWakeup,
        s3Properties,
        snapshot.get().storageMedia(),
        maintenanceProperties,
        clock,
        transactionManager);
  }

  /** 耐久 Storage Maintenance：startup wake + 合并 wake + fixed-delay poll。 */
  @Bean
  public StorageMaintenance storageMaintenance(
      StorageUploadService storageUploadService,
      StorageBlobManager storageBlobManager,
      StorageMaintenanceProperties properties) {
    return new StorageMaintenance(storageUploadService, storageBlobManager, properties);
  }

  /** Session blob 引用边的显式 owner：insert/delete 与 blob retain/release 成对维护（MANDATORY 事务）。 */
  @Bean
  public SessionBlobRefManager sessionBlobRefManager(
      SessionBlobRefRepository refRepository, StorageBlobManager blobManager) {
    return new PostgresqlSessionBlobRefManager(refRepository, blobManager);
  }

  /** 服务端字节内容的 blob 摄入：Tool/Daemon Resource 外部化的存储入口（MANDATORY 事务）。 */
  @Bean
  public StorageBlobIngestService storageBlobIngestService(
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      SessionBlobRefManager refManager,
      S3StorageService s3StorageService,
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeup,
      PlatformTransactionManager transactionManager) {
    return new PostgresqlStorageBlobIngestService(
        blobRepository,
        blobManager,
        refManager,
        s3StorageService,
        maintenanceWakeup,
        transactionManager);
  }

  /** Provider attempt 的 Resource 物化端口（支持的媒体有界内联为 Base64，绝不产生 URL，瞬时 source 绝不持久化）。 */
  @Bean
  public ProviderResourceMaterializer providerResourceMaterializer(
      StorageBlobManager storageBlobManager, StorageBlobContentService blobContentService) {
    return new ProviderResourceMaterializer(storageBlobManager, blobContentService);
  }

  /** Tool outcome 的 durable history 物化端口：瞬时 Resource 引用外部化为全局 blob 后进入 history。 */
  @Bean
  public GlobalStorageToolResultHistoryMaterializer globalStorageToolResultHistoryMaterializer(
      StorageBlobIngestService ingestService,
      ResourceStore resourceStore,
      SystemSettingsSnapshot snapshot) {
    return new GlobalStorageToolResultHistoryMaterializer(
        ingestService,
        resourceStore,
        Math.toIntExact(snapshot.get().advanced().resourceMaxBytes()));
  }

  private S3Configuration newS3Configuration() {
    return S3Configuration.builder().pathStyleAccessEnabled(true).build();
  }
}

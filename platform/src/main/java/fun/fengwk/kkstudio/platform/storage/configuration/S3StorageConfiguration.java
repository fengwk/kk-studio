package fun.fengwk.kkstudio.platform.storage.configuration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
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
import fun.fengwk.kkstudio.platform.storage.S3ReadinessProbe;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.S3StorageServiceImpl;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenanceWakeup;
import fun.fengwk.kkstudio.platform.storage.persistence.SessionBlobRefRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageObjectCleanupRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobPreviewService;
import fun.fengwk.kkstudio.platform.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.StorageObjectCleanupService;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadOperationLock;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.impl.HeadOnlyStorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlSessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageObjectCleanupService;
import fun.fengwk.kkstudio.platform.storage.service.impl.PostgresqlStorageUploadOperationLock;
import fun.fengwk.kkstudio.platform.storage.service.impl.StorageBlobContentServiceImpl;
import fun.fengwk.kkstudio.platform.storage.service.impl.StorageUploadServiceImpl;

import java.net.URI;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;

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

  /** 阻塞取锁预算：complete/stage 的写窗口通常远短于此；超预算按「上传忙」失败，绝不让请求线程无限挂起。 */
  private static final Duration UPLOAD_LOCK_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);

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
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeup,
      StorageObjectCleanupService storageObjectCleanupService,
      PlatformTransactionManager transactionManager) {
    return new PostgresqlStorageBlobManager(
        blobRepository,
        s3StorageService,
        s3PresignService,
        maintenanceWakeup,
        storageObjectCleanupService,
        transactionManager);
  }

  /** 对象清理记录服务：enqueue 与调用方事实变更同事务，sweepOnce 在事务外幂等删除对象；时间推进全部由数据库时钟换算。 */
  @Bean
  public StorageObjectCleanupService storageObjectCleanupService(
      StorageObjectCleanupRepository cleanupRepository,
      S3StorageService s3StorageService,
      StorageMaintenanceProperties maintenanceProperties) {
    return new PostgresqlStorageObjectCleanupService(
        cleanupRepository, s3StorageService, maintenanceProperties);
  }

  /**
   * upload 操作锁：complete/stage 的写对象窗口与后台清理在同一 upload 上互斥。
   *
   * <p>锁连接是专用 DriverManager 会话，绝不借用业务连接池（持会话锁的连接若来自业务池会耗尽池容量，让需要新连接的嵌套事务永久等待）；连接信息只从 {@link
   * JdbcConnectionDetails} 读取，不打印 URL 与凭据；每次 acquire 新建会话，close 释放锁并关闭会话。阻塞取锁预算由会话局部 {@code
   * statement_timeout} 约束，到点按「上传忙」失败而不是无限等待。
   */
  @Bean
  public StorageUploadOperationLock storageUploadOperationLock(
      JdbcConnectionDetails connectionDetails) {
    String jdbcUrl = connectionDetails.getJdbcUrl();
    Properties credentials = new Properties();
    if (connectionDetails.getUsername() != null) {
      credentials.setProperty("user", connectionDetails.getUsername());
    }
    if (connectionDetails.getPassword() != null) {
      credentials.setProperty("password", connectionDetails.getPassword());
    }
    return new PostgresqlStorageUploadOperationLock(
        () -> DriverManager.getConnection(jdbcUrl, credentials), UPLOAD_LOCK_ACQUIRE_TIMEOUT);
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
      StorageBlobPreviewService blobPreviewService,
      StorageObjectCleanupService storageObjectCleanupService,
      StorageUploadOperationLock storageUploadOperationLock,
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
        blobPreviewService,
        storageObjectCleanupService,
        storageUploadOperationLock,
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
      StorageObjectCleanupService storageObjectCleanupService,
      StorageMaintenanceProperties properties) {
    return new StorageMaintenance(
        storageUploadService, storageBlobManager, storageObjectCleanupService, properties);
  }

  /** Session blob 引用边的显式 owner：insert/delete 与 blob retain/release 成对维护（MANDATORY 事务）。 */
  @Bean
  public SessionBlobRefManager sessionBlobRefManager(
      SessionBlobRefRepository refRepository, StorageBlobManager blobManager) {
    return new PostgresqlSessionBlobRefManager(refRepository, blobManager);
  }

  /** Provider attempt 的 Resource 物化端口（支持的媒体有界内联为 Base64，绝不产生 URL，瞬时 source 绝不持久化）。 */
  @Bean
  public ProviderResourceMaterializer providerResourceMaterializer(
      StorageBlobManager storageBlobManager, StorageBlobContentService blobContentService) {
    return new ProviderResourceMaterializer(storageBlobManager, blobContentService);
  }

  /**
   * Tool outcome 的 durable history 物化端口：瞬时 Resource 引用外部化为全局 blob 后进入 history，Daemon 直传的 {@code
   * blob-upload} 引用则在同一事务内原子转移为 Session 引用。
   */
  @Bean
  public GlobalStorageToolResultHistoryMaterializer globalStorageToolResultHistoryMaterializer(
      StorageUploadService uploadService,
      SessionBlobRefManager refManager,
      StorageBlobManager blobManager,
      SystemSettingsSnapshot snapshot) {
    return new GlobalStorageToolResultHistoryMaterializer(
        uploadService,
        blobManager,
        refManager,
        Math.toIntExact(snapshot.get().advanced().resourceMaxBytes()));
  }

  private S3Configuration newS3Configuration() {
    return S3Configuration.builder().pathStyleAccessEnabled(true).build();
  }
}

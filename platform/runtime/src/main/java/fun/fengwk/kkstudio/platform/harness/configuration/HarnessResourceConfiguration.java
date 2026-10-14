package fun.fengwk.kkstudio.platform.harness.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.GlobalStorageToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

/** Harness Resource 物化装配：消费全局 storage，不向 storage 反向注册执行边界。 */
@Configuration(proxyBeanMethods = false)
public class HarnessResourceConfiguration {

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
}

package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.spi.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.GlobalStorageToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

class HarnessResourceConfigurationTest {

  @Test
  void wiresExactlyOneMaterializerForEachBoundary() {
    // 意图：验证组合根装配各边界单一 bean、具体类型和运行时端口，不引入重复或缺失装配。
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessResourceConfiguration.class)
        .withBean(StorageBlobManager.class, () -> mock(StorageBlobManager.class))
        .withBean(StorageBlobContentService.class, () -> mock(StorageBlobContentService.class))
        .withBean(StorageUploadService.class, () -> mock(StorageUploadService.class))
        .withBean(SessionBlobRefManager.class, () -> mock(SessionBlobRefManager.class))
        .withBean(
            SystemSettingsSnapshot.class,
            () -> new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ProviderResourceMaterializer.class)
                  .hasSingleBean(GlobalStorageToolResultHistoryMaterializer.class)
                  .hasSingleBean(ToolResultHistoryMaterializer.class);
              assertThat(context.getBean("providerResourceMaterializer"))
                  .isSameAs(context.getBean(ProviderResourceMaterializer.class));
              assertThat(context.getBean("globalStorageToolResultHistoryMaterializer"))
                  .isSameAs(context.getBean(ToolResultHistoryMaterializer.class));
            });
  }
}

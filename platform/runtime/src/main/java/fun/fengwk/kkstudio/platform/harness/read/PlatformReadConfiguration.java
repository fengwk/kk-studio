package fun.fengwk.kkstudio.platform.harness.read;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

/** 装配 Platform 统一 {@code read} 工具执行器与相关内容读取器的 Spring 配置。 */
@Configuration(proxyBeanMethods = false)
public class PlatformReadConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public PlatformSkillContentReader platformSkillContentReader(
      SkillPackageRepository skillPackageRepository, SkillGitCache skillGitCache) {
    return new PlatformSkillContentReader(skillPackageRepository, skillGitCache);
  }

  @Bean
  @ConditionalOnMissingBean
  public PlatformResourceContentReader platformResourceContentReader(
      ObjectProvider<HarnessStore> harnessStoreProvider,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageBlobContentService storageBlobContentService) {
    return new PlatformResourceContentReader(
        harnessStoreProvider::getIfAvailable,
        sessionBlobRefManager,
        storageBlobManager,
        storageBlobContentService);
  }

  @Bean
  @ConditionalOnMissingBean
  public ReadToolExecutor readToolExecutor(
      PlatformSkillContentReader skillReader,
      PlatformResourceContentReader resourceReader,
      ObjectMapper objectMapper) {
    return new PlatformReadToolExecutor(skillReader, resourceReader, objectMapper);
  }
}

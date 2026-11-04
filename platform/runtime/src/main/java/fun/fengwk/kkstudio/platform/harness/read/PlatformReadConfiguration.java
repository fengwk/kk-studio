package fun.fengwk.kkstudio.platform.harness.read;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

/** 装配 Platform VFS 与它复用的内容读取器的 Spring 配置。 */
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
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageBlobContentService storageBlobContentService) {
    return new PlatformResourceContentReader(
        sessionBlobRefManager, storageBlobManager, storageBlobContentService);
  }
}

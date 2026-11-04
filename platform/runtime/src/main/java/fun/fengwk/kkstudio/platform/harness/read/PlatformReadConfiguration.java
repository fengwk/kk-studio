package fun.fengwk.kkstudio.platform.harness.read;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

/** 装配 Platform VFS 与它复用的内容读取器的 Spring 配置。 */
@Configuration(proxyBeanMethods = false)
public class PlatformReadConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public SkillArchiveFileReader skillArchiveFileReader(
      StorageBlobManager storageBlobManager, S3StorageService s3StorageService) {
    return new SkillArchiveFileReader(storageBlobManager, s3StorageService);
  }

  @Bean
  @ConditionalOnMissingBean
  public PlatformSkillContentReader platformSkillContentReader(
      SkillPackageRepository skillPackageRepository, SkillArchiveFileReader archiveFileReader) {
    return new PlatformSkillContentReader(skillPackageRepository, archiveFileReader);
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

package fun.fengwk.kkstudio.platform.harness.read;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.harness.spi.SkillLoadGateway;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;
import fun.fengwk.kkstudio.platform.storage.s3.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

/** 装配 Platform 资源 URI 读取器。 */
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
  public SkillLoadGateway platformSkillLoadGateway(
      SkillPackageRepository skillPackageRepository, SkillArchiveFileReader archiveFileReader) {
    return new PlatformSkillLoadGateway(skillPackageRepository, archiveFileReader);
  }

  @Bean
  @ConditionalOnMissingBean
  public PlatformResourceReadGateway platformResourceReadGateway(
      PlatformResourceContentReader reader,
      SkillPackageRepository skillPackageRepository,
      SkillArchiveFileReader skillArchiveFileReader,
      SessionBlobRefManager sessionBlobRefManager,
      PlatformTransactionManager transactionManager) {
    return new PlatformResourceReadGateway(
        reader,
        skillPackageRepository,
        skillArchiveFileReader,
        sessionBlobRefManager,
        transactionManager);
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

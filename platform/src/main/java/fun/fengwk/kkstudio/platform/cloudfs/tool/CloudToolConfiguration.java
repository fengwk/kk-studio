package fun.fengwk.kkstudio.platform.cloudfs.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;

/** Cloud File System 工具与 Contributor Spring 装配配置。 */
@Configuration
public class CloudToolConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public StorageBlobFileReader storageBlobFileReader(
      ObjectProvider<StorageBlobContentService> blobContentServiceProvider) {
    StorageBlobContentService service = blobContentServiceProvider.getIfAvailable();
    return service == null ? null : new StorageBlobFileReader(service);
  }

  @Bean
  @ConditionalOnMissingBean
  public CloudReadTool cloudReadTool(
      CloudFileSystemService fileSystemService,
      ObjectProvider<StorageBlobFileReader> blobFileReaderProvider) {
    return new CloudReadTool(fileSystemService, blobFileReaderProvider.getIfAvailable());
  }

  @Bean
  @ConditionalOnMissingBean
  public CloudWriteTool cloudWriteTool(CloudFileSystemService fileSystemService) {
    return new CloudWriteTool(fileSystemService);
  }

  @Bean
  @ConditionalOnMissingBean
  public CloudEditTool cloudEditTool(CloudFileSystemService fileSystemService) {
    return new CloudEditTool(fileSystemService);
  }

  @Bean
  @ConditionalOnMissingBean
  public CloudFindTool cloudFindTool(CloudFileSystemService fileSystemService) {
    return new CloudFindTool(fileSystemService);
  }

  @Bean
  @ConditionalOnMissingBean
  public CloudGrepTool cloudGrepTool(
      CloudFileSystemService fileSystemService,
      ObjectProvider<StorageBlobFileReader> blobFileReaderProvider) {
    return new CloudGrepTool(fileSystemService, blobFileReaderProvider.getIfAvailable());
  }

  @Bean
  @ConditionalOnMissingBean
  public CloudHarnessContributor cloudHarnessContributor(
      CloudReadTool cloudReadTool,
      CloudWriteTool cloudWriteTool,
      CloudEditTool cloudEditTool,
      CloudFindTool cloudFindTool,
      CloudGrepTool cloudGrepTool) {
    return new CloudHarnessContributor(
        cloudReadTool, cloudWriteTool, cloudEditTool, cloudFindTool, cloudGrepTool);
  }
}

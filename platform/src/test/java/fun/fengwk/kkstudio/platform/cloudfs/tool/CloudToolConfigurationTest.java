package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;

/** 测试 CloudToolConfiguration Spring Bean 装配行为。 */
class CloudToolConfigurationTest {

  @Test
  void testBeanWiring() {
    CloudToolConfiguration config = new CloudToolConfiguration();
    CloudFileSystemService fileSystemService = mock(CloudFileSystemService.class);
    CloudQueryService queryService = mock(CloudQueryService.class);
    StorageBlobContentService blobContentService = mock(StorageBlobContentService.class);

    @SuppressWarnings("unchecked")
    ObjectProvider<StorageBlobContentService> blobServiceProvider = mock(ObjectProvider.class);
    when(blobServiceProvider.getIfAvailable()).thenReturn(blobContentService);

    StorageBlobFileReader reader = config.storageBlobFileReader(blobServiceProvider);
    assertNotNull(reader);

    when(blobServiceProvider.getIfAvailable()).thenReturn(null);
    assertNull(config.storageBlobFileReader(blobServiceProvider));

    @SuppressWarnings("unchecked")
    ObjectProvider<StorageBlobFileReader> readerProvider = mock(ObjectProvider.class);
    when(readerProvider.getIfAvailable()).thenReturn(reader);

    CloudReadTool readTool = config.cloudReadTool(fileSystemService, queryService, readerProvider);
    CloudWriteTool writeTool = config.cloudWriteTool(fileSystemService);
    CloudEditTool editTool = config.cloudEditTool(fileSystemService);
    CloudFindTool findTool = config.cloudFindTool(queryService);
    CloudGrepTool grepTool = config.cloudGrepTool(queryService);

    assertNotNull(readTool);
    assertNotNull(writeTool);
    assertNotNull(editTool);
    assertNotNull(findTool);
    assertNotNull(grepTool);

    CloudHarnessContributor contributor =
        config.cloudHarnessContributor(readTool, writeTool, editTool, findTool, grepTool);
    assertNotNull(contributor);
  }
}

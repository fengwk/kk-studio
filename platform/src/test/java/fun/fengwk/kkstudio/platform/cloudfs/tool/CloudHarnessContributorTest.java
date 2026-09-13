package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;

import java.util.List;

/** 验证 {@link CloudHarnessContributor} 的五个内部工具注册、身份、可见性与副作用契约。 */
class CloudHarnessContributorTest {

  private CloudHarnessContributor contributor;

  @BeforeEach
  void setUp() {
    CloudFileSystemService mockFs = mock(CloudFileSystemService.class);
    StorageBlobFileReader mockBlob = mock(StorageBlobFileReader.class);
    CloudQueryService mockQuery = mock(CloudQueryService.class);
    CloudReadTool readTool = new CloudReadTool(mockFs, mockBlob, mockQuery);
    CloudWriteTool writeTool = new CloudWriteTool(mockFs);
    CloudEditTool editTool = new CloudEditTool(mockFs);
    CloudFindTool findTool = new CloudFindTool(mockQuery);
    CloudGrepTool grepTool = new CloudGrepTool(mockQuery);

    contributor = new CloudHarnessContributor(readTool, writeTool, editTool, findTool, grepTool);
  }

  @Test
  void contributorDescriptorHasCorrectMetadata() {
    // 意图：Contributor ID 固定为 cloud，版本为 1
    assertEquals(CloudHarnessContributor.ID, contributor.descriptor().id());
    assertEquals("Cloud File System", contributor.descriptor().name());
    assertEquals("1", contributor.descriptor().version());
  }

  @Test
  void contributesFiveInternalToolsWithCorrectSideEffects() {
    // 意图：通过 HarnessCatalog 验证五个工具均为 INTERNAL 可见，且副作用分类正确
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    List<ToolContribution> tools = catalog.tools();
    assertEquals(5, tools.size());

    assertTool(
        catalog, "cloud.read", "cloud_read", ToolVisibility.INTERNAL, ToolSideEffect.READ_ONLY);
    assertTool(
        catalog, "cloud.write", "cloud_write", ToolVisibility.INTERNAL, ToolSideEffect.IDEMPOTENT);
    assertTool(
        catalog,
        "cloud.edit",
        "cloud_edit",
        ToolVisibility.INTERNAL,
        ToolSideEffect.NON_IDEMPOTENT);
    assertTool(
        catalog, "cloud.find", "cloud_find", ToolVisibility.INTERNAL, ToolSideEffect.READ_ONLY);
    assertTool(
        catalog, "cloud.grep", "cloud_grep", ToolVisibility.INTERNAL, ToolSideEffect.READ_ONLY);
  }

  private static void assertTool(
      HarnessCatalog catalog,
      String toolId,
      String modelName,
      ToolVisibility visibility,
      ToolSideEffect sideEffect) {
    AgentToolId agentToolId = new AgentToolId(toolId);
    ToolContribution contribution = catalog.findTool(agentToolId).orElse(null);
    assertNotNull(contribution, "tool must be registered: " + toolId);
    assertEquals(visibility, contribution.definition().visibility());
    assertEquals(modelName, contribution.tool().descriptor().name());
    assertEquals(sideEffect, contribution.tool().descriptor().sideEffect());
    assertTrue(contribution.tool().descriptor().rendererKey().length() > 0);
    assertNotNull(contribution.tool().descriptor().inputSchema());
  }
}

package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCapability;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisClient;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisHttpTransport;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisCapabilityCache;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisHarnessContributor;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisResourceAccess;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * MiniMax Mavis 15 项能力工具的静态契约测试。
 *
 * <p>验证工具名精确派生、元数据与静态 schema 一致、超时与副作用映射准确、且无外部环境依赖； 同时验证 HarnessCatalog 注册与冻结状态完全符合 SELECTABLE
 * 工具契约。
 */
class MiniMaxMavisToolContractTest {

  private MiniMaxMavisHarnessContributor contributor;
  private Map<MavisCapability, Tool> tools;

  @BeforeEach
  void setUp() {
    PluginCredentialStore credentialStore = mock(PluginCredentialStore.class);
    MavisHttpTransport transport = mock(MavisHttpTransport.class);
    MavisClient client = new MavisClient(transport);
    MiniMaxMavisCapabilityCache capabilityCache = new MiniMaxMavisCapabilityCache(client);
    MiniMaxMavisResourceAccess resourceAccess = new MiniMaxMavisResourceAccess(null);
    ExecutorService executor = mock(ExecutorService.class);

    MiniMaxMavisHarnessContributor.ToolDependencies dependencies =
        new MiniMaxMavisHarnessContributor.ToolDependencies(
            credentialStore, client, capabilityCache, resourceAccess, executor);
    this.contributor = MiniMaxMavisHarnessContributor.create(dependencies);
    this.tools = contributor.tools();
  }

  /** 逐项验证 15 个能力的工具名、schema 定义、超时契约、无环境支持以及 side effect 映射。 */
  @ParameterizedTest(name = "{0}")
  @EnumSource(MavisCapability.class)
  void toolDescriptorMatchesCapabilityContract(MavisCapability capability) {
    Tool tool = tools.get(capability);
    assertTrue(tool != null, () -> "Tool must exist for capability: " + capability.id());

    MavisToolDefinition expectedDefinition = MavisToolDefinitions.load(capability);
    ToolDescriptor descriptor = tool.descriptor();

    assertEquals(
        capability.toolName(), descriptor.name(), "Tool name must match capability.toolName()");
    assertEquals(
        expectedDefinition.name(), descriptor.name(), "Tool name must match tool definition name");
    assertEquals(
        expectedDefinition.description(),
        descriptor.description(),
        "Tool description must match definition");
    assertEquals(
        expectedDefinition.inputSchema(),
        descriptor.inputSchema(),
        "Tool inputSchema must match definition");
    assertEquals(
        capability.requestTimeout(),
        descriptor.defaultTimeout(),
        "Tool timeout must match capability requestTimeout");

    assertEquals(
        ToolRequirements.none(),
        tool.requirements(),
        "MiniMax Mavis tools must declare EnvironmentSupport.NONE");

    ToolSideEffect expectedSideEffect = expectedSideEffectOf(capability);
    assertEquals(
        expectedSideEffect,
        descriptor.sideEffect(),
        "Side effect mapping must match capability classification");
  }

  /** 验证工具集合大小恰好为 15，且所有工具名均不重复。 */
  @Test
  void exactlyFifteenUniqueToolsAreContributed() {
    assertEquals(15, tools.size(), "MiniMax Mavis contributor must provide exactly 15 tools");
    assertEquals(15, contributor.toolCount(), "Tool count helper must return 15");

    Set<String> uniqueToolNames = new HashSet<>();
    for (Tool tool : tools.values()) {
      uniqueToolNames.add(tool.descriptor().name());
    }
    assertEquals(15, uniqueToolNames.size(), "All 15 tool names must be unique");
  }

  /** 验证通过 HarnessCatalog 冻结后，15 个工具都被标记为 SELECTABLE，contribution id 规范且模型名一一对应。 */
  @Test
  void toolsAreFrozenAsSelectableInHarnessCatalog() {
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    List<ToolContribution> selectables = catalog.selectableTools();

    assertEquals(15, selectables.size(), "Catalog must contain exactly 15 selectable tools");
    assertEquals(15, catalog.tools().size(), "Catalog total tools must equal 15");

    ContributorId contributorId = MiniMaxMavisHarnessContributor.ID;
    for (MavisCapability capability : MavisCapability.values()) {
      ContributionId expectedId = new ContributionId(contributorId, capability.id());
      Optional<ToolContribution> byId = catalog.findTool(expectedId);
      assertTrue(byId.isPresent(), () -> "Tool contribution must exist for id: " + expectedId);

      ToolContribution contribution = byId.get();
      assertEquals(
          expectedId,
          contribution.id(),
          "Contribution id must match minimax-mavis:<capability.id>");
      assertEquals(
          "minimax-mavis:" + capability.id(),
          contribution.id().toString(),
          "Contribution id string must match format");
      assertEquals(
          ToolVisibility.SELECTABLE,
          contribution.definition().visibility(),
          "Tool must be frozen as SELECTABLE");

      Optional<ToolContribution> byName = catalog.findTool(capability.toolName());
      assertTrue(
          byName.isPresent(),
          () -> "Tool must be resolvable by model name: " + capability.toolName());
      assertEquals(
          contribution,
          byName.get(),
          "Lookup by name and lookup by id must yield the same contribution");
    }
  }

  private static ToolSideEffect expectedSideEffectOf(MavisCapability capability) {
    return switch (capability) {
      case WEB_SEARCH,
          EXTRACT_WEB,
          IMAGE_SEARCH,
          REVERSE_IMAGE,
          UNDERSTAND_IMAGE,
          UNDERSTAND_AUDIO,
          UNDERSTAND_VIDEO,
          ASR,
          LIST_VOICES,
          QUERY_VIDEO -> ToolSideEffect.READ_ONLY;
      case TTS, TTS_BATCH, GENERATE_IMAGE, GENERATE_MUSIC, SUBMIT_VIDEO -> ToolSideEffect
          .NON_IDEMPOTENT;
    };
  }
}

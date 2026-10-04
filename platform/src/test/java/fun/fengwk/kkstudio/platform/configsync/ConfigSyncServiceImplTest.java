package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agent;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agentConfig;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.model;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.provider;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncItem;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;

import java.util.List;
import java.util.Map;

/**
 * 测试意图：锁定应用服务编排——inventory/export 在单一只读快照上构图与展开依赖；import 把解析、计划与事务写入串起来，并保证
 * 下游校验失败只暴露安全错误文本（不透传可能含真实值的下游消息）。
 */
class ConfigSyncServiceImplTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();
  private final ConfigSyncGraph graph = new ConfigSyncGraph(ConfigSyncFixtures.AGENT_CONFIG_CODEC);
  private final ConfigSyncExporter exporter =
      new ConfigSyncExporter(
          ConfigSyncFixtures.PROVIDER_CONFIG_CODEC,
          ConfigSyncFixtures.MODEL_CONFIG_PARSER,
          ConfigSyncFixtures.AGENT_CONFIG_CODEC,
          new SystemSettingsCodec(),
          yaml);
  private final ConfigSyncParser parser = ConfigSyncFixtures.parser(yaml);

  private final ConfigSyncSnapshotReader snapshotReader = mock(ConfigSyncSnapshotReader.class);
  private final ConfigSyncApplier applier = mock(ConfigSyncApplier.class);
  private final ConfigSyncMcpDiscovery mcpDiscovery = mock(ConfigSyncMcpDiscovery.class);
  private final SkillGitCache skillGitCache = mock(SkillGitCache.class);
  private final AgentDefinitionRepository agentDefinitionRepository =
      mock(AgentDefinitionRepository.class);
  private final McpServerRepository mcpServerRepository = mock(McpServerRepository.class);
  private final RuntimeToolCatalog toolCatalog = mock(RuntimeToolCatalog.class);
  private final ConfigSyncPlanner planner =
      new ConfigSyncPlanner(
          snapshotReader,
          mcpDiscovery,
          skillGitCache,
          agentDefinitionRepository,
          mcpServerRepository,
          toolCatalog);

  private final ConfigSyncServiceImpl service =
      new ConfigSyncServiceImpl(snapshotReader, graph, exporter, parser, planner, applier);

  private ConfigSyncSnapshot richSnapshot() {
    return snapshot(
        List.of(provider("p")),
        List.of(model("p", "m")),
        List.of(agent("a", "p", "m", agentConfig(List.of(), List.of(), List.of()))),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private ConfigSyncSnapshot emptySnapshot() {
    return snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private static ConfigSyncItem item(
      ConfigSyncInventoryDTO inventory, ConfigSyncKind kind, String name) {
    return inventory.getItems().stream()
        .filter(entry -> entry.getKind() == kind && entry.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void inventoryBuildsItemsWithTransitiveDependencies() {
    when(snapshotReader.read()).thenReturn(richSnapshot());

    ConfigSyncInventoryDTO inventory = service.inventory();

    assertEquals(4, inventory.getItems().size());
    ConfigSyncItem agentItem = item(inventory, ConfigSyncKind.AGENTS, "a");
    assertTrue(
        agentItem.getDependencies().contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(
        agentItem.getDependencies().contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    // 闭包不含自身。
    assertFalse(
        agentItem.getDependencies().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertTrue(item(inventory, ConfigSyncKind.SETTINGS, "settings").getDependencies().isEmpty());
  }

  @Test
  void exportExpandsClosureIntoYaml() {
    when(snapshotReader.read()).thenReturn(richSnapshot());
    ConfigSyncExportRequestDTO request = new ConfigSyncExportRequestDTO();
    request.setItems(List.of(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));

    String exported = service.export(request).getYaml();
    Map<String, Object> document = yaml.parse(exported);

    // 选择 Model 必须补齐其 Provider 闭包，且不携带无关 Agent。
    assertTrue(document.containsKey("providers"));
    assertTrue(document.containsKey("models"));
    assertFalse(document.containsKey("agents"));
  }

  @Test
  void exportWithoutSelectionIsRejected() {
    when(snapshotReader.read()).thenReturn(richSnapshot());

    assertThrows(
        AiValidationException.class, () -> service.export(new ConfigSyncExportRequestDTO()));
    assertThrows(AiValidationException.class, () -> service.export(null));
  }

  @Test
  void importParsesPlansAndApplies() {
    when(snapshotReader.read()).thenReturn(richSnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    ConfigSyncImportRequestDTO request = new ConfigSyncImportRequestDTO();
    request.setYaml(ConfigSyncFixtures.providerAndModelYaml());

    ConfigSyncImportResultDTO result = service.importYaml(request);

    verify(applier).apply(any());
    assertTrue(result.getImported().contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    assertTrue(result.getImported().contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(result.getSkipped().isEmpty());
  }

  @Test
  void importInvalidYamlIsRejectedBeforePlanning() {
    ConfigSyncImportRequestDTO request = new ConfigSyncImportRequestDTO();
    request.setYaml("providers: [not-an-object]\n");

    assertThrows(AiValidationException.class, () -> service.importYaml(request));
    assertThrows(AiValidationException.class, () -> service.importYaml(null));
  }

  @Test
  void checkImportClassifiesCreatedWithoutWriting() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    ConfigSyncImportCheckRequestDTO request = new ConfigSyncImportCheckRequestDTO();
    request.setYaml(ConfigSyncFixtures.providerAndModelYaml());

    ConfigSyncImportCheckDTO check = service.checkImport(request);

    assertTrue(check.getCreated().contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    assertTrue(check.getCreated().contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(check.getUpdated().isEmpty());
    assertTrue(check.getSkipped().isEmpty());
    verify(applier, never()).apply(any());
  }

  @Test
  void checkImportClassifiesExistingAsUpdatedWithoutWriting() {
    when(snapshotReader.read()).thenReturn(richSnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    ConfigSyncImportCheckRequestDTO request = new ConfigSyncImportCheckRequestDTO();
    request.setYaml(ConfigSyncFixtures.providerAndModelYaml());

    ConfigSyncImportCheckDTO check = service.checkImport(request);

    assertTrue(check.getCreated().isEmpty());
    assertTrue(check.getUpdated().contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    assertTrue(check.getUpdated().contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    verify(applier, never()).apply(any());
  }

  @Test
  void importWithSkippedEntriesRequiresExplicitPartialConfirmation() {
    when(snapshotReader.read()).thenReturn(richSnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    ConfigSyncImportRequestDTO request = new ConfigSyncImportRequestDTO();
    request.setYaml("unknownThing: []\n");

    assertThrows(AiValidationException.class, () -> service.importYaml(request));
    verify(applier, never()).apply(any());

    request.setAllowPartial(true);
    ConfigSyncImportResultDTO result = service.importYaml(request);

    assertTrue(result.getImported().isEmpty());
    assertEquals(1, result.getSkipped().size());
    verify(applier, never()).apply(any());
  }

  @Test
  void downstreamValidationErrorIsReplacedWithSafeMessage() {
    when(snapshotReader.read()).thenReturn(richSnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    doThrow(new AiValidationException("config_sync", "leaked-secret-value"))
        .when(applier)
        .apply(any());
    ConfigSyncImportRequestDTO request = new ConfigSyncImportRequestDTO();
    request.setYaml(ConfigSyncFixtures.providerAndModelYaml());

    AiValidationException error =
        assertThrows(AiValidationException.class, () -> service.importYaml(request));

    assertEquals("config import is invalid", error.getMessage());
    assertFalse(error.getMessage().contains("leaked-secret-value"));
  }
}

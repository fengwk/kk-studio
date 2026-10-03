package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpServer;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpTool;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.model;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.provider;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncSkipped;

import java.util.List;
import java.util.Optional;

/**
 * 测试意图：锁定 Planner 的外部准备与依赖判定——MCP 发现失败必须 skip 当前 MCP 及依赖 Agent、disabled MCP 保存配置且不发起发现、
 * 失效/不支持的同名条目从可用依赖池剔除（不能被既有同名绕过），Skill 名称先校验且 Git 错误不回显 URL，settings 携带快照版本。
 */
class ConfigSyncPlannerTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();
  private final ConfigSyncParser parser = new ConfigSyncParser(yaml);
  private final ConfigSyncSnapshotReader snapshotReader = mock(ConfigSyncSnapshotReader.class);
  private final ConfigSyncMcpDiscovery mcpDiscovery = mock(ConfigSyncMcpDiscovery.class);
  private final SkillGitCache skillGitCache = mock(SkillGitCache.class);
  private final AgentDefinitionRepository agentDefinitionRepository =
      mock(AgentDefinitionRepository.class);
  private final McpServerRepository mcpServerRepository = mock(McpServerRepository.class);
  private final RuntimeToolCatalog toolCatalog = mock(RuntimeToolCatalog.class);

  private final ConfigSyncPlanner planner =
      new ConfigSyncPlanner(
          snapshotReader,
          yaml,
          mcpDiscovery,
          skillGitCache,
          agentDefinitionRepository,
          mcpServerRepository,
          new SystemSettingsCodec(),
          toolCatalog);

  private ConfigSyncSnapshot emptySnapshot() {
    return snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private ConfigSyncPlan plan(String yamlText) {
    return planner.plan(parser.parse(yamlText));
  }

  private static final String PROVIDER_AND_MODEL =
      "providers:\n"
          + "  - name: p\n"
          + "    providerType: openai\n"
          + "models:\n"
          + "  - providerName: p\n"
          + "    name: m\n"
          + "    modelId: gpt\n"
          + "    config:\n"
          + "      limit: {context: 1000, output: 100}\n"
          + "      abilities: {tools: true, reasoning: false, inputModalities: [TEXT]}\n"
          + "      pricing:\n"
          + "        currency: USD\n"
          + "        pricingTier: t\n"
          + "        serviceTier: s\n"
          + "        serviceTierMultiplier: 1\n"
          + "        version: v\n"
          + "        inputPerMillionTokens: 0\n"
          + "        outputPerMillionTokens: 0\n"
          + "        cacheReadPerMillionTokens: 0\n"
          + "        cacheWritePerMillionTokens: 0\n"
          + "        cacheWriteLongPerMillionTokens: 0\n"
          + "        reasoningPerMillionTokens: 0\n"
          + "      defaultVariant: default\n"
          + "      variants:\n"
          + "        - id: default\n";

  private static boolean skipped(List<ConfigSyncSkipped> skipped, String kind, String name) {
    return skipped.stream()
        .anyMatch(entry -> kind.equals(entry.getKind()) && name.equals(entry.getName()));
  }

  @Test
  void discoveryFailureSkipsMcpAndDependentAgents() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    when(mcpDiscovery.discoverOrNull(any())).thenReturn(null);

    String yamlText =
        PROVIDER_AND_MODEL
            + "mcpServers:\n"
            + "  - name: mcp\n"
            + "    url: https://mcp.example.com/mcp\n"
            + "agents:\n"
            + "  - name: a\n"
            + "    model: p/m\n"
            + "    config:\n"
            + "      tools: [tool_x]\n"
            + "      skills: []\n"
            + "      subagents: []\n";
    ConfigSyncPlan plan = plan(yamlText);

    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.MCP_SERVERS, "mcp")));
    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertTrue(skipped(plan.skipped(), "mcpServers", "mcp"));
    assertTrue(skipped(plan.skipped(), "agents", "a"));
  }

  @Test
  void disabledMcpIsSavedWithoutDiscovery() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        PROVIDER_AND_MODEL
            + "mcpServers:\n"
            + "  - name: mcp\n"
            + "    url: https://mcp.example.com/mcp\n"
            + "    enabled: false\n";
    ConfigSyncPlan plan = plan(yamlText);

    verify(mcpDiscovery, never()).discoverOrNull(any());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.MCP_SERVERS, "mcp")));
    ConfigSyncPlan.McpImport imported = plan.mcpServers().get(0);
    assertNull(imported.discoveredTools());
    assertEquals(Boolean.FALSE, imported.enabled());
  }

  @Test
  void agentReferencingExistingToolOfDisabledMcpIsNotDropped() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                List.of(),
                List.of(),
                List.of(mcpServer("mcp", false)),
                List.of(mcpTool("tool_x", "mcp"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of("tool_x"));

    String yamlText =
        "providers:\n"
            + "  - name: p\n"
            + "    providerType: openai\n"
            + "models:\n"
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + "    config:\n"
            + "      limit: {context: 1000, output: 100}\n"
            + "      abilities: {tools: true, reasoning: false, inputModalities: [TEXT]}\n"
            + "      pricing:\n"
            + "        currency: USD\n"
            + "        pricingTier: t\n"
            + "        serviceTier: s\n"
            + "        serviceTierMultiplier: 1\n"
            + "        version: v\n"
            + "        inputPerMillionTokens: 0\n"
            + "        outputPerMillionTokens: 0\n"
            + "        cacheReadPerMillionTokens: 0\n"
            + "        cacheWritePerMillionTokens: 0\n"
            + "        cacheWriteLongPerMillionTokens: 0\n"
            + "        reasoningPerMillionTokens: 0\n"
            + "      defaultVariant: default\n"
            + "      variants:\n"
            + "        - id: default\n"
            + "mcpServers:\n"
            + "  - name: mcp\n"
            + "    url: https://mcp.example.com/mcp\n"
            + "    enabled: false\n"
            + "agents:\n"
            + "  - name: a\n"
            + "    model: p/m\n"
            + "    config:\n"
            + "      tools: [tool_x]\n"
            + "      skills: []\n"
            + "      subagents: []\n";
    ConfigSyncPlan plan = plan(yamlText);

    verify(mcpDiscovery, never()).discoverOrNull(any());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void invalidSkillPackageNameIsRejectedBeforeGit() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        "skillPackages:\n"
            + "  - packageName: bad/name\n"
            + "    repositoryUrl: https://example.com/x.git\n"
            + "    branch: main\n"
            + "    currentCommit: "
            + "a".repeat(40)
            + "\n";
    assertThrows(AiValidationException.class, () -> plan(yamlText));
    verify(skillGitCache, never()).ensureCommit(anyString(), anyString(), anyString());
  }

  @Test
  void skillGitFailureIsSkippedWithoutLeakingUrl() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    doThrow(new SkillGitException("cannot fetch https://user:pass@example.com/secret.git"))
        .when(skillGitCache)
        .ensureCommit(anyString(), anyString(), anyString());

    String yamlText =
        "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/x.git\n"
            + "    branch: main\n"
            + "    currentCommit: "
            + "a".repeat(40)
            + "\n";
    ConfigSyncPlan plan = plan(yamlText);

    ConfigSyncSkipped skip =
        plan.skipped().stream()
            .filter(entry -> "skillPackages".equals(entry.getKind()))
            .findFirst()
            .orElseThrow();
    assertEquals("cannot restore exact commit", skip.getReason());
    assertFalse(skip.getReason().contains("user:pass"));
  }

  @Test
  void unsupportedProviderPoisonsModelEvenWhenExistingSameNameProvider() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        "providers:\n"
            + "  - name: p\n"
            + "    providerType: unsupported_protocol\n"
            + "models:\n"
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + "    config:\n"
            + "      limit: {context: 1000, output: 100}\n"
            + "      abilities: {tools: true, reasoning: false, inputModalities: [TEXT]}\n"
            + "      pricing:\n"
            + "        currency: USD\n"
            + "        pricingTier: t\n"
            + "        serviceTier: s\n"
            + "        serviceTierMultiplier: 1\n"
            + "        version: v\n"
            + "        inputPerMillionTokens: 0\n"
            + "        outputPerMillionTokens: 0\n"
            + "        cacheReadPerMillionTokens: 0\n"
            + "        cacheWritePerMillionTokens: 0\n"
            + "        cacheWriteLongPerMillionTokens: 0\n"
            + "        reasoningPerMillionTokens: 0\n"
            + "      defaultVariant: default\n"
            + "      variants:\n"
            + "        - id: default\n";
    ConfigSyncPlan plan = plan(yamlText);

    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(plan.models().isEmpty());
    assertTrue(skipped(plan.skipped(), "models", "p/m"));
  }

  @Test
  void settingsUpdateCarriesSnapshotVersion() {
    when(snapshotReader.read())
        .thenReturn(
            new ConfigSyncSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ConfigSyncFixtures.settings(5L)));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    ConfigSyncPlan plan = plan("settings:\n  tool:\n    defaultYolo: false\n");

    assertNotNull(plan.settings());
    assertEquals("5", plan.settings().expectedVersion());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.SETTINGS, "settings")));
  }

  @Test
  void referencedMcpToolRemovalSkipsServer() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(mcpServer("mcp", true)),
                List.of(mcpTool("tool_x", "mcp"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of("tool_x"));
    McpTool replacement = mcpTool("tool_y", "mcp");
    when(mcpDiscovery.discoverOrNull(any())).thenReturn(List.of(replacement));

    String yamlText =
        "mcpServers:\n" + "  - name: mcp\n" + "    url: https://mcp.example.com/mcp\n";
    ConfigSyncPlan plan = plan(yamlText);

    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.MCP_SERVERS, "mcp")));
    assertTrue(skipped(plan.skipped(), "mcpServers", "mcp"));
  }

  @Test
  void toolCatalogIsUsedForBuiltinTools() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    when(toolCatalog.findTool("builtin")).thenReturn(Optional.empty());

    String yamlText =
        "providers:\n"
            + "  - name: p\n"
            + "    providerType: openai\n"
            + "models:\n"
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + "    config:\n"
            + "      limit: {context: 1000, output: 100}\n"
            + "      abilities: {tools: true, reasoning: false, inputModalities: [TEXT]}\n"
            + "      pricing:\n"
            + "        currency: USD\n"
            + "        pricingTier: t\n"
            + "        serviceTier: s\n"
            + "        serviceTierMultiplier: 1\n"
            + "        version: v\n"
            + "        inputPerMillionTokens: 0\n"
            + "        outputPerMillionTokens: 0\n"
            + "        cacheReadPerMillionTokens: 0\n"
            + "        cacheWritePerMillionTokens: 0\n"
            + "        cacheWriteLongPerMillionTokens: 0\n"
            + "        reasoningPerMillionTokens: 0\n"
            + "      defaultVariant: default\n"
            + "      variants:\n"
            + "        - id: default\n"
            + "agents:\n"
            + "  - name: a\n"
            + "    model: p/m\n"
            + "    config:\n"
            + "      tools: [builtin]\n"
            + "      skills: []\n"
            + "      subagents: []\n";
    ConfigSyncPlan plan = plan(yamlText);

    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertTrue(skipped(plan.skipped(), "agents", "a"));
  }
}

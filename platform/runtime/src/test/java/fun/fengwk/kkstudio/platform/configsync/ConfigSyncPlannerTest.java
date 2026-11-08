package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agentConfig;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpServer;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpTool;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.model;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.provider;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.providerAndModelYaml;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.skillPackage;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshot;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshotWithPlugins;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.platform.testing.TestCatalogSources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 测试意图：锁定 Planner 的依赖判定与外部准备——MCP 发现失败必须 skip 当前 MCP 及依赖 Agent、disabled MCP 保存配置且不发起发现、
 * 失效/不支持的同名条目从可用依赖池剔除（不能被既有同名绕过）、Skill re-import 只在 URL 不变且不丢被引用技能时进行、Git 错误不回显 URL、 Agent
 * 未满足依赖逐条给出明确原因、settings 缺依赖 skip。条目级静态校验（含 Skill 名称）已移入 Parser；这里用真实校验器 fixture 触发。
 */
class ConfigSyncPlannerTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();
  private final ConfigSyncParser parser = ConfigSyncFixtures.parser(yaml);
  private final ConfigSyncSnapshotReader snapshotReader = mock(ConfigSyncSnapshotReader.class);
  private final ConfigSyncMcpDiscovery mcpDiscovery = mock(ConfigSyncMcpDiscovery.class);
  private final McpServerRepository mcpServerRepository = mock(McpServerRepository.class);
  private final RuntimeToolCatalog toolCatalog = mock(RuntimeToolCatalog.class);

  private final ConfigSyncPlanner planner =
      new ConfigSyncPlanner(
          snapshotReader,
          mcpDiscovery,
          mcpServerRepository,
          TestCatalogSources.singleLease(toolCatalog),
          ConfigSyncFixtures.MODEL_CONFIG_PARSER,
          ConfigSyncFixtures.GROUP_CONFIG_CODEC,
          ConfigSyncFixtures.agentDependencies());

  private ConfigSyncSnapshot emptySnapshot() {
    return snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private static final String PLUGIN_ID = "acme.plugin";

  /** 合成系统内置 Agent 名称：只来自快照的内置集合，测试不依赖任何真实内置身份。 */
  private static final String BUILTIN_AGENT = "Compaction";

  private static ConfigSyncFixtures.AgentDependencyPlugin syntheticPlugin() {
    return new ConfigSyncFixtures.AgentDependencyPlugin(PLUGIN_ID);
  }

  /** 与 planner 共用同一安装目录的 Parser，保证插件条目与依赖判定看到同一份声明。 */
  private ConfigSyncParser pluginParser() {
    return ConfigSyncFixtures.parser(yaml, ConfigSyncFixtures.registry(syntheticPlugin()));
  }

  private ConfigSyncPlanner pluginPlanner() {
    return new ConfigSyncPlanner(
        snapshotReader,
        mcpDiscovery,
        mcpServerRepository,
        TestCatalogSources.singleLease(toolCatalog),
        ConfigSyncFixtures.MODEL_CONFIG_PARSER,
        ConfigSyncFixtures.GROUP_CONFIG_CODEC,
        ConfigSyncFixtures.agentDependencies(syntheticPlugin()));
  }

  private static String pluginEntry(String agentName) {
    return "plugins:\n"
        + "  - pluginId: "
        + PLUGIN_ID
        + "\n    valuesJson: '{\"promptAgentName\":\""
        + agentName
        + "\"}'\n";
  }

  private ConfigSyncPlan plan(String yamlText) {
    return planner.plan(parser.parse(yamlText));
  }

  private static final String PROVIDER_AND_MODEL = providerAndModelYaml();

  /** 同一不受支持档位经保存和导入走同一 validation 错误。 */
  @Test
  void saveAndImportRejectUnsupportedRetentionIdentically() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    String unsupported =
        PROVIDER_AND_MODEL
            .replace("providerType: openai", "providerType: google")
            .replace("promptCacheRetention: NONE", "promptCacheRetention: LONG");
    DomainException imported = assertThrows(DomainException.class, () -> plan(unsupported));

    ProviderRepository providers = mock(ProviderRepository.class);
    when(providers.getByNameForUpdate("p"))
        .thenReturn(Provider.builder().name("p").providerType(ProviderType.GOOGLE).build());
    ModelService service =
        new ModelService(
            mock(ModelRepository.class),
            providers,
            new AgentEditableSupport(ConfigSyncFixtures.MAPPER),
            ConfigSyncFixtures.MODEL_CONFIG_PARSER,
            ConfigSyncFixtures.GROUP_CONFIG_CODEC);
    var config = ConfigSyncFixtures.modelConfig();
    config.getVariants().getFirst().setPromptCacheRetention(PromptCacheRetention.LONG);
    SingleModelCreateDTO create = new SingleModelCreateDTO();
    create.setProviderName("p");
    create.setName("m");
    create.setModelId("gpt");
    create.setConfig(config);
    DomainException saved = assertThrows(DomainException.class, () -> service.createModel(create));
    assertEquals(saved.code(), imported.code());
    assertEquals(saved.resource(), imported.resource());
    assertEquals(saved.getMessage(), imported.getMessage());
  }

  /** 拼接一个 document 内的 Agent 条目；configLines 使用 6 空格缩进。 */
  private static String agent(String name, String model, String configLines) {
    return "  - name: "
        + name
        + "\n    execution:\n      driver: builtin\n      settings:\n        modelName: "
        + model
        + "\n        variant: null\n    config:\n"
        + configLines;
  }

  private static String agentConfigLines() {
    return "      tools: []\n      skills: []\n      subagents: []\n";
  }

  private static boolean skipped(List<ConfigSyncSkipped> skipped, String kind, String name) {
    return skipped.stream()
        .anyMatch(
            entry ->
                entry.getKind() != null
                    && kind.equals(entry.getKind().wireValue())
                    && name.equals(entry.getName()));
  }

  private static String reason(List<ConfigSyncSkipped> skipped, String kind, String name) {
    return skipped.stream()
        .filter(
            entry ->
                entry.getKind() != null
                    && kind.equals(entry.getKind().wireValue())
                    && name.equals(entry.getName()))
        .map(ConfigSyncSkipped::getReason)
        .findFirst()
        .orElseThrow();
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
            + agent("a", "m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
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
        PROVIDER_AND_MODEL
            + "mcpServers:\n"
            + "  - name: mcp\n"
            + "    url: https://mcp.example.com/mcp\n"
            + "    enabled: false\n"
            + "agents:\n"
            + agent("a", "m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    verify(mcpDiscovery, never()).discoverOrNull(any());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void invalidSkillPackageNameIsRejected() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        "skillPackages:\n"
            + "  - packageName: bad/name\n"
            + "    repositoryUrl: https://example.com/x.git\n"
            + "    branch: main\n";
    assertThrows(DomainException.class, () -> plan(yamlText));
  }

  @Test
  void existingUploadPackageCannotBeReplacedByGitImport() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(ConfigSyncFixtures.uploadSkillPackage("pkg", "s")),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/pkg.git\n"
            + "    branch: main\n";
    DomainException error = assertThrows(DomainException.class, () -> plan(yamlText));
    assertTrue(
        error.getMessage().contains("uploaded skill package cannot be replaced by a Git import"));
  }

  @Test
  void existingSkillPackageWithSameRepositoryUrlIsPlanned() {
    // 既有 Skill Package 的 repositoryUrl 不变时纳入计划，不访问 Git。
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                List.of(skillPackage("pkg", "s1", "s2")),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        PROVIDER_AND_MODEL
            + "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/pkg.git\n"
            + "    branch: main\n"
            + "agents:\n"
            + agent(
                "a",
                "m",
                "      tools: []\n      skills:\n        - packageName: pkg\n          name: s2\n"
                    + "      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals(1, plan.skillPackages().size());
    ConfigSyncPlan.SkillImport importedSkill = plan.skillPackages().get(0);
    assertEquals("pkg", importedSkill.packageName());
    assertEquals("https://example.com/pkg.git", importedSkill.repositoryUrl());
    assertEquals("main", importedSkill.branch());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void existingSkillPackageRepositoryUrlChangeIsRejected() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(skillPackage("pkg", "s")),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/other.git\n"
            + "    branch: main\n";
    DomainException error = assertThrows(DomainException.class, () -> plan(yamlText));

    assertFalse(error.getMessage().contains("other.git"));
  }

  @Test
  void enabledMcpDiscoveryImportsDiscoveredToolsAndSatisfiesAgents() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    when(mcpDiscovery.discoverOrNull(any())).thenReturn(List.of(mcpTool("tool_x", "mcp")));

    String yamlText =
        PROVIDER_AND_MODEL
            + "mcpServers:\n"
            + "  - name: mcp\n"
            + "    url: https://mcp.example.com/mcp\n"
            + "agents:\n"
            + agent("a", "m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals(1, plan.mcpServers().size());
    assertEquals(1, plan.mcpServers().get(0).discoveredTools().size());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.MCP_SERVERS, "mcp")));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void invalidMcpServerConfigIsRejectedBeforeDiscovery() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText = "mcpServers:\n" + "  - name: mcp\n" + "    url: not-a-url\n";
    assertThrows(DomainException.class, () -> plan(yamlText));
    verify(mcpDiscovery, never()).discoverOrNull(any());
  }

  @Test
  void agentUnresolvedDependenciesAreSkippedWithDistinctReasons() {
    // 既有 provider/model/skill 使依赖可满足；每个 Agent 只留一个未满足依赖，验证原因定位准确。
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(
                    ConfigSyncFixtures.agent(
                        "existing",
                        "m",
                        ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of()))),
                List.of(skillPackage("pkg", "s1")),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        "agents:\n"
            + agent("missing_model", "x", agentConfigLines())
            + agent(
                "missing_pkg",
                "m",
                "      tools: []\n      skills:\n        - packageName: ghost\n          name: s\n      subagents: []\n")
            + agent(
                "missing_skill",
                "m",
                "      tools: []\n      skills:\n        - packageName: pkg\n          name: nope\n      subagents: []\n")
            + agent(
                "missing_sub", "m", "      tools: []\n      skills: []\n      subagents: [ghost]\n")
            + "environments:\n"
            + "  - name: env\n"
            + "    registrationTokenHash: 1a7674eb4ee78df7e1ac439a93c3fa8e3c945784d4dec9fd8e3011738b2f1d62\n"
            + "    daemonConfig:\n"
            + "      studioUrl: https://studio.example.com\n"
            + "      bashExecutable: null\n"
            + "      lsp: null\n"
            + "      proxyUrl: null\n";
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals("missing model: x", reason(plan.skipped(), "agents", "missing_model"));
    assertEquals("missing skill package: ghost", reason(plan.skipped(), "agents", "missing_pkg"));
    assertEquals("missing skill: pkg/nope", reason(plan.skipped(), "agents", "missing_skill"));
    assertEquals("missing subagent: ghost", reason(plan.skipped(), "agents", "missing_sub"));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.ENVIRONMENTS, "env")));
  }

  /** 嵌套 config 内 null skill 引用是结构错误，必须在计划阶段硬拒绝而不是当作依赖 skip。 */
  @Test
  void invalidSkillReferenceIsRejected() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        PROVIDER_AND_MODEL
            + "agents:\n"
            + agent(
                "a", "m", "      tools: []\n      skills:\n        - null\n      subagents: []\n");
    assertThrows(DomainException.class, () -> plan(yamlText));
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
            + ConfigSyncFixtures.resourceYaml("model-entry.yaml")
            + "agents:\n"
            + agent("a", "m", agentConfigLines());
    ConfigSyncPlan plan = plan(yamlText);

    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "m")));
    assertTrue(plan.models().isEmpty());
    assertTrue(skipped(plan.skipped(), "models", "m"));
    // 被 poison 的 Model 不能被 Agent 借用。
    assertEquals("missing model: m", reason(plan.skipped(), "agents", "a"));
    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void newSkillPackageInDocumentSatisfiesAgentDuringPlanning() {
    // 文件中声明的 Skill Package 在计划期不访问 Git，但进入导入计划并满足引用它的 Agent。
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        PROVIDER_AND_MODEL
            + "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/pkg.git\n"
            + "    branch: main\n"
            + "agents:\n"
            + agent(
                "a",
                "m",
                "      tools: []\n      skills:\n        - packageName: pkg\n          name: s\n"
                    + "      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals(1, plan.skillPackages().size());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void skippedExistingMcpToolCannotBeBorrowedByAgentViaCatalog() {
    // 既有 MCP 工具存在但本次 discovery 失败 skip 该 Server：引用其工具名的 Agent 必须 skip，
    // 且不得回退到 RuntimeToolCatalog 兜底。
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                List.of(),
                List.of(),
                List.of(mcpServer("mcp", true)),
                List.of(mcpTool("tool_x", "mcp"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    when(mcpDiscovery.discoverOrNull(any())).thenReturn(null);

    String yamlText =
        PROVIDER_AND_MODEL
            + "mcpServers:\n"
            + "  - name: mcp\n"
            + "    url: https://mcp.example.com/mcp\n"
            + "agents:\n"
            + agent("a", "m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    assertTrue(skipped(plan.skipped(), "mcpServers", "mcp"));
    assertEquals(
        "unsupported or unknown agent tool: tool_x", reason(plan.skipped(), "agents", "a"));
    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    verify(toolCatalog, never()).findTool(anyString());
  }

  @Test
  void settingsUpdateCarriesSnapshotVersion() {
    when(snapshotReader.read())
        .thenReturn(
            new ConfigSyncSnapshot(
                List.of(),
                List.of(),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ConfigSyncFixtures.settings(5L),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    ConfigSyncPlan plan =
        plan(ConfigSyncFixtures.settingsYaml(ConfigSyncFixtures.defaultSettings()));

    assertNotNull(plan.settings());
    assertEquals(5L, plan.settings().expectedVersion());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.SETTINGS, "settings")));
  }

  @Test
  void settingsInvalidFieldIsRejected() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    SystemSettingsSectionsDTO sections = ConfigSyncFixtures.defaultSettings();
    String yamlText =
        ConfigSyncFixtures.settingsYaml(sections)
            .replace("retryBackoffStrategy: EXPONENTIAL", "retryBackoffStrategy: BOGUS");
    assertThrows(DomainException.class, () -> plan(yamlText));
  }

  @Test
  void referencedMcpToolRemovalIsRejected() {
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
    assertThrows(DomainException.class, () -> plan(yamlText));
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
        PROVIDER_AND_MODEL
            + "agents:\n"
            + agent("a", "m", "      tools: [builtin]\n      skills: []\n      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertTrue(skipped(plan.skipped(), "agents", "a"));
  }

  /** 既有 Model 未声明 Agent 显式 variant 时预检查硬拒绝；错误只带安全条目名，不回显 raw variant。缺 Model 仍走依赖 skip，不被此规则遮蔽。 */
  @Test
  void agentVariantMustBeDeclaredByAvailableModel() {
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

    Map<String, Object> unknown =
        document(
            agent(
                "a",
                "m",
                "raw-variant-xyz",
                ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    DomainException error =
        assertThrows(DomainException.class, () -> plan(ConfigSyncFixtures.documentYaml(unknown)));
    assertTrue(error.getMessage().contains("unknown model variant"));
    assertTrue(error.getMessage().contains("a"));
    assertFalse(error.getMessage().contains("raw-variant-xyz"));

    // 缺 Model 的 Agent 不因 variant 被硬拒绝，而是按既有依赖政策 skip。
    Map<String, Object> missingModel =
        document(
            agent(
                "b",
                "x",
                "raw-variant-xyz",
                ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    ConfigSyncPlan plan = plan(ConfigSyncFixtures.documentYaml(missingModel));
    assertEquals("missing model: x", reason(plan.skipped(), "agents", "b"));
  }

  /** null variant 不触发声明校验；空白 variant 不符合新执行选择契约，必须硬拒绝。 */
  @Test
  void agentWithoutVariantIsNotDeclaredChecked() {
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

    AgentConfigDTO config = ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of());
    ConfigSyncPlan plan =
        plan(ConfigSyncFixtures.documentYaml(document(agent("no_variant", "m", null, config))));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "no_variant")));
    assertThrows(
        DomainException.class,
        () ->
            plan(
                ConfigSyncFixtures.documentYaml(
                    document(agent("blank_variant", "m", "   ", config)))));
  }

  /** 文件顺序 Group 先于 Model：计划仍先写 Model 再写 Group，且组成员按全局 Model 名解析。 */
  @Test
  void modelIsWrittenBeforeGroupEvenWhenGroupAppearsFirstInFile() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    Map<String, Object> model =
        ConfigSyncFixtures.mapOf(
            "kind",
            "MODEL",
            "providerName",
            "p",
            "name",
            "m",
            "modelId",
            "gpt",
            "config",
            ConfigSyncFixtures.modelConfigMap(ConfigSyncFixtures.modelConfig()));
    Map<String, Object> group =
        ConfigSyncFixtures.mapOf(
            "kind",
            "GROUP",
            "name",
            "g",
            "group",
            ConfigSyncFixtures.mapOf(
                "members",
                    List.of(ConfigSyncFixtures.mapOf("modelName", "m", "variant", "default")),
                "fallbackStatusCodes", List.of(429),
                "cooldownSeconds", 0));
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("models", List.of(group, model));

    ConfigSyncPlan plan = plan(ConfigSyncFixtures.documentYaml(document));

    assertEquals(
        List.of("m", "g"), plan.models().stream().map(ConfigSyncParser.ModelSpec::name).toList());
    assertEquals(
        "m",
        ((ModelGroupCreateDTO) plan.models().get(1).properties())
            .getGroup()
            .getMembers()
            .get(0)
            .getModelName());
    // 即便组及成员都将被导入，组级 Variant 仍必须在预检中拒绝。
    document.put(
        "agents",
        List.of(
            agent("group-agent", "g", "default", agentConfig(List.of(), List.of(), List.of()))));
    DomainException error =
        assertThrows(DomainException.class, () -> plan(ConfigSyncFixtures.documentYaml(document)));
    assertTrue(error.getMessage().contains("model group with variant"));
  }

  /** 文件内同名 Model config 覆盖快照：文件新增的 variant 通过，快照独有而被移除的 variant 被拒。 */
  @Test
  void fileModelConfigOverridesSnapshotForVariantPrecheck() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(
                    model(
                        "p",
                        "m",
                        ConfigSyncFixtures.modelConfigWithVariants(List.of("old"), "old"))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    Map<String, Object> fileModel =
        ConfigSyncFixtures.mapOf(
            "kind",
            "MODEL",
            "providerName",
            "p",
            "name",
            "m",
            "modelId",
            "gpt",
            "config",
            ConfigSyncFixtures.modelConfigMap(
                ConfigSyncFixtures.modelConfigWithVariants(List.of("new"), "new")));

    Map<String, Object> withNew =
        documentWithModel(
            fileModel,
            agent(
                "a", "m", "new", ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    ConfigSyncPlan plan = plan(ConfigSyncFixtures.documentYaml(withNew));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));

    Map<String, Object> withOld =
        documentWithModel(
            fileModel,
            agent(
                "a", "m", "old", ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    assertThrows(DomainException.class, () -> plan(ConfigSyncFixtures.documentYaml(withOld)));
  }

  private static Map<String, Object> document(Map<String, Object> agent) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("agents", List.of(agent));
    return document;
  }

  private static Map<String, Object> documentWithModel(
      Map<String, Object> model, Map<String, Object> agent) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("models", List.of(model));
    document.put("agents", List.of(agent));
    return document;
  }

  private static Map<String, Object> agent(
      String name, String model, String variant, AgentConfigDTO config) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("name", name);
    entry.put(
        "execution",
        ConfigSyncFixtures.mapOf(
            "driver",
            "builtin",
            "settings",
            ConfigSyncFixtures.mapOf("modelName", model, "variant", variant)));
    entry.put("config", ConfigSyncFixtures.agentConfigMap(config));
    return entry;
  }

  /** 插件条目在快照里有该 Agent 时被导入，并携带计划期的配置修订作为 CAS 坐标。 */
  @Test
  void pluginImportCarriesRevision() {
    when(snapshotReader.read())
        .thenReturn(
            snapshotWithPlugins(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(
                    ConfigSyncFixtures.agent(
                        "h3", "m", agentConfig(List.of(), List.of(), List.of()))),
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    ConfigSyncFixtures.pluginRow(PLUGIN_ID, 7L, "{\"promptAgentName\":\"h3\"}"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    ConfigSyncPlan plan = pluginPlanner().plan(pluginParser().parse(pluginEntry("h3")));

    assertEquals(
        List.of(new ConfigSyncPlan.PluginImport(PLUGIN_ID, 7L, "{\"promptAgentName\":\"h3\"}")),
        plan.plugins());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.PLUGINS, PLUGIN_ID)));
    assertTrue(plan.skipped().isEmpty());
  }

  /** 插件声明的 Agent 不在导入后将生效的 Agent 池中时按既有依赖 skip 模式上报，不写入插件。 */
  @Test
  void pluginWithMissingAgentIsSkipped() {
    when(snapshotReader.read())
        .thenReturn(
            snapshotWithPlugins(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    ConfigSyncFixtures.pluginRow(
                        PLUGIN_ID, 7L, "{\"promptAgentName\":\"ghost\"}"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    ConfigSyncPlan plan = pluginPlanner().plan(pluginParser().parse(pluginEntry("ghost")));

    assertTrue(plan.plugins().isEmpty());
    assertEquals("missing agent: ghost", reason(plan.skipped(), "plugins", PLUGIN_ID));
  }

  /** 文件内声明但失败的 Agent 已被移出依赖池：插件不得靠旧快照同名 Agent 兜底，同样按缺失依赖 skip。 */
  @Test
  void pluginDoesNotFallBackToSkippedSameNameAgent() {
    when(snapshotReader.read())
        .thenReturn(
            snapshotWithPlugins(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(
                    ConfigSyncFixtures.agent(
                        "a", "m", agentConfig(List.of(), List.of(), List.of()))),
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    ConfigSyncFixtures.pluginRow(PLUGIN_ID, 7L, "{\"promptAgentName\":\"a\"}"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    // 文件内同名 Agent 引用不存在的工具：它必须被跳过，且不能以旧快照同名 Agent 满足插件依赖。
    when(toolCatalog.findTool(anyString())).thenReturn(Optional.empty());

    ConfigSyncPlan plan =
        pluginPlanner()
            .plan(
                pluginParser()
                    .parse(
                        pluginEntry("a")
                            + "agents:\n"
                            + agent(
                                "a",
                                "m",
                                "      tools: [ghost_tool]\n      skills: []\n      subagents: []\n")));

    assertTrue(skipped(plan.skipped(), "agents", "a"));
    assertTrue(plan.plugins().isEmpty());
    assertEquals("missing agent: a", reason(plan.skipped(), "plugins", PLUGIN_ID));
  }

  /** 插件声明的 Agent 是系统内置 Agent 时视为已由系统满足：不 skip，插件按计划写入。 */
  @Test
  void pluginDependingOnBuiltinAgentIsPlanned() {
    when(snapshotReader.read())
        .thenReturn(
            snapshotWithPlugins(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                Set.of(BUILTIN_AGENT),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    ConfigSyncFixtures.pluginRow(
                        PLUGIN_ID, 7L, "{\"promptAgentName\":\"" + BUILTIN_AGENT + "\"}"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    ConfigSyncPlan plan = pluginPlanner().plan(pluginParser().parse(pluginEntry(BUILTIN_AGENT)));

    assertEquals(
        List.of(
            new ConfigSyncPlan.PluginImport(
                PLUGIN_ID, 7L, "{\"promptAgentName\":\"" + BUILTIN_AGENT + "\"}")),
        plan.plugins());
    assertTrue(plan.skipped().isEmpty());
  }

  /** 自定义 Agent 把系统内置 Agent 当 subagent：内置身份满足依赖，Agent 不会被 skip。 */
  @Test
  void agentSubagentBuiltinIsSatisfiedByTheSystem() {
    when(snapshotReader.read())
        .thenReturn(
            snapshotWithPlugins(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                Set.of(BUILTIN_AGENT),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    when(toolCatalog.findTool(anyString())).thenReturn(Optional.empty());

    ConfigSyncPlan plan =
        plan(
            "providers:\n  - name: p\n    providerType: openai\n"
                + ConfigSyncFixtures.documentYaml(
                    Map.of(
                        "agents",
                        List.of(
                            agent(
                                "a",
                                "m",
                                "default",
                                ConfigSyncFixtures.agentConfig(
                                    List.of(), List.of(), List.of(BUILTIN_AGENT)))))));

    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertTrue(plan.skipped().isEmpty());
  }

  /** 插件拒绝自身普通配置是已知输入边界：计划必须硬失败，绝不降级为 skip，也不回显配置值。 */
  @Test
  void pluginDeclarationInputErrorFailsThePlan() {
    when(snapshotReader.read())
        .thenReturn(
            snapshotWithPlugins(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    ConfigSyncFixtures.pluginRow(PLUGIN_ID, 7L, "{\"promptAgentName\":\"h3\"}"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> pluginPlanner().plan(pluginParser().parse(pluginEntry("!secret-marker"))));

    assertEquals("plugin configuration is invalid: " + PLUGIN_ID, error.getMessage());
    assertTrue(error.getCause() == null);
  }

  /** 插件声明解析抛出的未知错误原样上抛，绝不伪装成校验失败或 skip。 */
  @Test
  void unexpectedPluginDeclarationErrorPropagates() {
    when(snapshotReader.read())
        .thenReturn(
            snapshotWithPlugins(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(ConfigSyncFixtures.pluginRow(PLUGIN_ID, 1L, "{}"))));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    ConfigSyncPlanner failingPlanner =
        new ConfigSyncPlanner(
            snapshotReader,
            mcpDiscovery,
            mcpServerRepository,
            TestCatalogSources.singleLease(toolCatalog),
            ConfigSyncFixtures.MODEL_CONFIG_PARSER,
            ConfigSyncFixtures.GROUP_CONFIG_CODEC,
            ConfigSyncFixtures.agentDependencies(
                new ConfigSyncFixtures.FailingAgentDeclarationPlugin(PLUGIN_ID)));

    assertThrows(
        IllegalStateException.class,
        () -> failingPlanner.plan(pluginParser().parse(pluginEntry("h3"))));
  }
}

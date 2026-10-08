package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpServer;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpTool;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.model;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.provider;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.providerAndModelYaml;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.skillPackage;
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
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncSkipped;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
          toolCatalog,
          ConfigSyncFixtures.MODEL_CONFIG_PARSER);

  private ConfigSyncSnapshot emptySnapshot() {
    return snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private ConfigSyncPlan plan(String yamlText) {
    return planner.plan(parser.parse(yamlText));
  }

  private static final String PROVIDER_AND_MODEL = providerAndModelYaml();

  /** 拼接一个 document 内的 Agent 条目；configLines 使用 6 空格缩进。 */
  private static String agent(String name, String model, String configLines) {
    return "  - name: " + name + "\n    model: " + model + "\n    config:\n" + configLines;
  }

  private static String agentConfigLines() {
    return "      tools: []\n      skills: []\n      subagents: []\n";
  }

  private static boolean skipped(List<ConfigSyncSkipped> skipped, String kind, String name) {
    return skipped.stream()
        .anyMatch(entry -> kind.equals(entry.getKind()) && name.equals(entry.getName()));
  }

  private static String reason(List<ConfigSyncSkipped> skipped, String kind, String name) {
    return skipped.stream()
        .filter(entry -> kind.equals(entry.getKind()) && name.equals(entry.getName()))
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
            + agent("a", "p/m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
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
            + agent("a", "p/m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
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
    verify(skillGitCache, never()).ensureCommit(anyString(), anyString(), anyString(), any());
  }

  @Test
  void skillGitFailureIsSkippedWithoutLeakingUrl() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    doThrow(new SkillGitException("cannot fetch https://user:pass@example.com/secret.git"))
        .when(skillGitCache)
        .ensureCommit(anyString(), anyString(), anyString(), any());

    String yamlText =
        "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/pkg.git\n"
            + "    branch: main\n"
            + "    currentCommit: "
            + "a".repeat(40)
            + "\n";
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals("cannot restore exact commit", reason(plan.skipped(), "skillPackages", "pkg"));
    assertFalse(reason(plan.skipped(), "skillPackages", "pkg").contains("user:pass"));
  }

  @Test
  void existingSkillPackageIsRescannedAndAdvertised() {
    // 既有 Skill Package 的 repositoryUrl 不变时按 exact commit 重扫 manifest，并把发布技能放入可用依赖池。
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
    when(skillGitCache.scanManifest("pkg", "a".repeat(40)))
        .thenReturn(List.of(new SkillManifestEntry("s2", "published")));

    String yamlText =
        PROVIDER_AND_MODEL
            + "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/pkg.git\n"
            + "    branch: main\n"
            + "    currentCommit: "
            + "a".repeat(40)
            + "\n"
            + "agents:\n"
            + agent(
                "a",
                "p/m",
                "      tools: []\n      skills:\n        - packageName: pkg\n          name: s2\n"
                    + "      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals(1, plan.skillPackages().size());
    assertEquals(
        List.of("s2"),
        plan.skillPackages().get(0).manifest().stream().map(SkillManifestEntry::name).toList());
    verify(skillGitCache).ensureCommit("pkg", "https://example.com/pkg.git", "a".repeat(40), null);
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
            + "    branch: main\n"
            + "    currentCommit: "
            + "a".repeat(40)
            + "\n";
    AiValidationException error = assertThrows(AiValidationException.class, () -> plan(yamlText));

    assertFalse(error.getMessage().contains("other.git"));
    verify(skillGitCache, never()).ensureCommit(anyString(), anyString(), anyString(), any());
  }

  @Test
  void existingSkillPackageLosingReferencedSkillIsRejected() {
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(skillPackage("pkg", "s1", "s2")),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    when(skillGitCache.scanManifest("pkg", "a".repeat(40)))
        .thenReturn(List.of(new SkillManifestEntry("s1", "kept")));
    when(agentDefinitionRepository.existsReferencingSkill("pkg", "s2")).thenReturn(true);

    String yamlText =
        "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/pkg.git\n"
            + "    branch: main\n"
            + "    currentCommit: "
            + "a".repeat(40)
            + "\n";
    assertThrows(AiValidationException.class, () -> plan(yamlText));
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
            + agent("a", "p/m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
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
    assertThrows(AiValidationException.class, () -> plan(yamlText));
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
                        "p",
                        "m",
                        ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of()))),
                List.of(skillPackage("pkg", "s1")),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    String yamlText =
        "agents:\n"
            + agent("missing_model", "q/x", agentConfigLines())
            + agent(
                "missing_pkg",
                "p/m",
                "      tools: []\n      skills:\n        - packageName: ghost\n          name: s\n      subagents: []\n")
            + agent(
                "missing_skill",
                "p/m",
                "      tools: []\n      skills:\n        - packageName: pkg\n          name: nope\n      subagents: []\n")
            + agent(
                "missing_sub",
                "p/m",
                "      tools: []\n      skills: []\n      subagents: [ghost]\n")
            + "environments:\n"
            + "  - name: env\n"
            + "    registrationToken: tok\n";
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals("missing model: q/x", reason(plan.skipped(), "agents", "missing_model"));
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
                "a",
                "p/m",
                "      tools: []\n      skills:\n        - null\n      subagents: []\n");
    assertThrows(AiValidationException.class, () -> plan(yamlText));
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
            + agent("a", "p/m", agentConfigLines());
    ConfigSyncPlan plan = plan(yamlText);

    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(plan.models().isEmpty());
    assertTrue(skipped(plan.skipped(), "models", "p/m"));
    // 被 poison 的 Model 不能被 Agent 借用。
    assertEquals("missing model: p/m", reason(plan.skipped(), "agents", "a"));
    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void existingSkillGitFailurePoisonsAgentsReferencingIt() {
    // 既有 Skill Package 重新导入时 Git 恢复失败：skill 被 skip，引用它的 Agent 也必须一起 skip，
    // 不能退回借用快照中的既有包。
    when(snapshotReader.read())
        .thenReturn(
            snapshot(
                List.of(provider("p")),
                List.of(model("p", "m")),
                List.of(),
                List.of(skillPackage("pkg", "s")),
                List.of(),
                List.of(),
                List.of()));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());
    doThrow(new SkillGitException("boom"))
        .when(skillGitCache)
        .ensureCommit(anyString(), anyString(), anyString(), any());

    String yamlText =
        PROVIDER_AND_MODEL
            + "skillPackages:\n"
            + "  - packageName: pkg\n"
            + "    repositoryUrl: https://example.com/pkg.git\n"
            + "    branch: main\n"
            + "    currentCommit: "
            + "a".repeat(40)
            + "\n"
            + "agents:\n"
            + agent(
                "a",
                "p/m",
                "      tools: []\n      skills:\n        - packageName: pkg\n          name: s\n"
                    + "      subagents: []\n");
    ConfigSyncPlan plan = plan(yamlText);

    assertEquals("cannot restore exact commit", reason(plan.skipped(), "skillPackages", "pkg"));
    assertEquals("missing skill package: pkg", reason(plan.skipped(), "agents", "a"));
    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));
    assertFalse(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
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
            + agent("a", "p/m", "      tools: [tool_x]\n      skills: []\n      subagents: []\n");
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
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ConfigSyncFixtures.settings(5L)));
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    ConfigSyncPlan plan =
        plan(ConfigSyncFixtures.settingsYaml(ConfigSyncFixtures.defaultSettings()));

    assertNotNull(plan.settings());
    assertEquals("5", plan.settings().expectedVersion());
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.SETTINGS, "settings")));
  }

  @Test
  void settingsFallbackModelMissingIsSkipped() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    SystemSettingsSectionsDTO sections = ConfigSyncFixtures.defaultSettings();
    HarnessModelSelectionDTO fallback = new HarnessModelSelectionDTO();
    fallback.setProviderName("p");
    fallback.setModelName("m");
    fallback.setVariant("v");
    sections.getAiRuntime().setCompactionFallbackModel(fallback);

    ConfigSyncPlan plan = plan(ConfigSyncFixtures.settingsYaml(sections));

    assertNull(plan.settings());
    assertEquals("missing fallback model: p/m", reason(plan.skipped(), "settings", "settings"));
  }

  @Test
  void settingsPromptAgentMissingIsSkipped() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    SystemSettingsSectionsDTO sections = ConfigSyncFixtures.defaultSettings();
    sections.getIntegrations().getMinimaxH3().setPromptAgentName("ghost");

    ConfigSyncPlan plan = plan(ConfigSyncFixtures.settingsYaml(sections));

    assertNull(plan.settings());
    assertEquals("missing prompt agent: ghost", reason(plan.skipped(), "settings", "settings"));
  }

  @Test
  void settingsInvalidFieldIsRejected() {
    when(snapshotReader.read()).thenReturn(emptySnapshot());
    when(mcpServerRepository.selectReferencedToolNames()).thenReturn(List.of());

    SystemSettingsSectionsDTO sections = ConfigSyncFixtures.defaultSettings();
    sections.getAiRuntime().setRetryBackoffStrategy("BOGUS");
    String yamlText = ConfigSyncFixtures.settingsYaml(sections);
    assertThrows(AiValidationException.class, () -> plan(yamlText));
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
    assertThrows(AiValidationException.class, () -> plan(yamlText));
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
            + agent("a", "p/m", "      tools: [builtin]\n      skills: []\n      subagents: []\n");
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
                "p/m",
                "raw-variant-xyz",
                ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    AiValidationException error =
        assertThrows(
            AiValidationException.class, () -> plan(ConfigSyncFixtures.documentYaml(unknown)));
    assertTrue(error.getMessage().contains("unknown model variant"));
    assertTrue(error.getMessage().contains("a"));
    assertFalse(error.getMessage().contains("raw-variant-xyz"));

    // 缺 Model 的 Agent 不因 variant 被硬拒绝，而是按既有依赖政策 skip。
    Map<String, Object> missingModel =
        document(
            agent(
                "b",
                "q/x",
                "raw-variant-xyz",
                ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    ConfigSyncPlan plan = plan(ConfigSyncFixtures.documentYaml(missingModel));
    assertEquals("missing model: q/x", reason(plan.skipped(), "agents", "b"));
  }

  /** 无 variant 或仅空白的 Agent 不触发声明校验，维持既有“不覆盖 default”语义。 */
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

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put(
        "agents",
        List.of(
            ConfigSyncFixtures.mapOf(
                "name",
                "no_variant",
                "model",
                "p/m",
                "config",
                ConfigSyncFixtures.agentConfigMap(
                    ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of()))),
            ConfigSyncFixtures.mapOf(
                "name",
                "blank_variant",
                "model",
                "p/m",
                "variant",
                "   ",
                "config",
                ConfigSyncFixtures.agentConfigMap(
                    ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())))));
    ConfigSyncPlan plan = plan(ConfigSyncFixtures.documentYaml(doc));

    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "no_variant")));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "blank_variant")));
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
                "a",
                "p/m",
                "new",
                ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    ConfigSyncPlan plan = plan(ConfigSyncFixtures.documentYaml(withNew));
    assertTrue(plan.imported().contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));

    Map<String, Object> withOld =
        documentWithModel(
            fileModel,
            agent(
                "a",
                "p/m",
                "old",
                ConfigSyncFixtures.agentConfig(List.of(), List.of(), List.of())));
    assertThrows(AiValidationException.class, () -> plan(ConfigSyncFixtures.documentYaml(withOld)));
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
      String name, String model, String variant, AgentDefinitionConfigDTO config) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("name", name);
    entry.put("model", model);
    entry.put("variant", variant);
    entry.put("config", ConfigSyncFixtures.agentConfigMap(config));
    return entry;
  }
}

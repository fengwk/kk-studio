package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.runtime.task.AgentPromptComposer;
import fun.fengwk.kkstudio.core.ai.runtime.task.SubagentConfig;
import fun.fengwk.kkstudio.core.ai.runtime.task.TaskTool;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.plugin.goal.GoalPlugin;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DatabaseTurnResolver 契约：精确的 branch 引用（无回退）、不可变的 EnvironmentName 路由、严格有序的工具/skill 能力、冻结的 thinking
 * 覆盖、语义化消息投影、缓存终结与基础设施异常透传。
 */
class DatabaseTurnResolverTest {

  private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
  private static final long SESSION_ID = 100L;
  private static final EnvironmentName ENV_A = new EnvironmentName("env-1");
  private static final EnvironmentName ENV_B = new EnvironmentName("env-2");
  private static final EnvironmentName ENV_MISSING = new EnvironmentName("env-3");

  @Test
  void resolvesExactBranchModelReferencesWithoutFallback() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    // Agent 自带的 provider/model/variant 引用是旧数据，绝不能被读取。
    fixture.agent.setModelProviderName("old-provider");
    fixture.agent.setModelName("old-model");
    fixture.agent.setVariant("old-variant");
    when(fixture.providers.getByName("old-provider")).thenReturn(null);

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(null, "custom", "low")));

    assertEquals("provider", request.providerRequest().model().providerName());
    assertEquals("model", request.providerRequest().model().modelName());
    assertEquals("custom", request.providerRequest().variant().id());
    assertEquals(0.5, request.providerRequest().variant().temperature());
    assertEquals(2048, request.providerRequest().variant().maxOutputTokens());
  }

  @Test
  void neverFallsBackVariantToModelDefault() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    TurnResolver.Rejected rejected =
        fixture.rejected(fixture.path(settings(null, "missing", "low")));

    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.error().code());
    assertEquals(
        "model variant not found: provider/model variant=missing", rejected.error().message());
  }

  @Test
  void activeToolsComeFromBranchSettingsNotAgentConfig() {
    // Agent config 声明 read，但 branch activeTools 为空：只按 branch 事实绑定。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(ENV_A, "default", "low")));

    assertEquals(List.of(), request.toolBindings());
  }

  @Test
  void rejectsMissingCatalogResourcesAndInvalidConfig() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingAgent();
    assertEquals(
        "agent not found: assistant",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingProvider();
    assertEquals(
        "provider not found: provider",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingModel();
    assertEquals(
        "model not found: provider/model",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.provider.setProviderType(null);
    assertEquals(
        "provider type must not be null",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failModelConfig(new IllegalArgumentException("broken model config"));
    assertEquals(
        "invalid model configuration: broken model config",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failAgentConfig(new IllegalStateException("broken agent config"));
    assertEquals(
        "invalid agent configuration: broken agent config",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());

    fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            false);
    assertEquals(
        "provider factory not found for provider (OPENAI)",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());
  }

  @Test
  void rejectsToolsWhenModelDoesNotSupportTools() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelSupportsTools(false);
    fixture.readyEnvironment(ENV_A);

    TurnResolver.Rejected rejected =
        fixture.rejected(fixture.path(settings(ENV_A, "default", "low", List.of("read"))));

    assertEquals("model does not support tools: provider/model", rejected.error().message());
  }

  @Test
  void resolvesModelOnlyBranchWithoutEnvironmentAndFreezesYolo() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(null, "default", "low")), true);

    assertNull(request.environmentName());
    assertEquals(List.of(), request.toolBindings());
    assertEquals(List.of(), request.skillBindings());
    assertTrue(request.yoloEnabled());
  }

  @Test
  void bindsEnvironmentToolsWithLatestNameEvenWhenBranchHasNoEnvironment() {
    // 分支没有环境路由不再拒绝工具规划：ENVIRONMENT 工具仍按最新（null）名称绑定，实际执行时确定性失败。
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(null, "default", "low", List.of("read"))));
    assertEquals(1, request.toolBindings().size());
    assertEquals(ToolType.ENVIRONMENT, request.toolBindings().getFirst().type());
    assertNull(request.toolBindings().getFirst().environmentName());

    // Agent skills 要求最新选中的 Environment 提供 live descriptors：分支没有名称时精确拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of());
    assertEquals(
        "agent skills require the latest selected environment but the branch has no environmentName",
        fixture.rejected(fixture.path(settings(null, "default", "low"))).error().message());
  }

  @Test
  void missingOrNotReadyLatestEnvironmentDoesNotRejectToolPlanning() {
    // 缺失的 latest 环境：工具按最新名称绑定，规划成功。
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(ENV_MISSING, "default", "low", List.of("read"))));
    assertEquals(ENV_MISSING, request.toolBindings().getFirst().environmentName());

    // 未 READY 的 latest 环境：同样按最新名称绑定，规划成功；绝不回看更旧 branch settings。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.connectingEnvironment(ENV_A);
    request = fixture.resolved(fixture.path(settings(ENV_A, "default", "low", List.of("read"))));
    assertEquals(ENV_A, request.toolBindings().getFirst().environmentName());
  }

  @Test
  void latestSnapshotAgentReferenceWinsOverOlderValidAgent() {
    // 历史 turn 引用有效 agent（assistant 已注册），最新 turn 引用缺失 agent：
    // 只按最新快照的精确名称解析并拒绝，绝不回看更旧 turn 的有效 agent（无历史 repository 查询）。
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings firstTurn = settings(null, "default", "low").withAgentName("assistant");
    BranchSettings latestTurn = settings(null, "default", "low").withAgentName("ghost");

    TurnResolver.Rejected rejected = fixture.rejected(multiTurnPath(firstTurn, latestTurn));

    assertEquals("agent not found: ghost", rejected.error().message());
  }

  @Test
  void latestSnapshotEnvironmentWinsOverOlderLiveEnvironment() {
    // 历史 turn 绑定 live Environment，最新 turn 为 null/缺失名称：
    // 请求只冻结最新快照的 route（null/名称），绝不选中更旧的 live Environment。
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);
    BranchSettings firstTurn = settings(ENV_A, "default", "low", List.of("read"));
    BranchSettings latestTurn = settings(null, "default", "low", List.of("read"));

    ModelInvocationRequest request = fixture.resolved(multiTurnPath(firstTurn, latestTurn));

    assertNull(request.environmentName());
    assertEquals(
        List.of("read"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(ToolType.ENVIRONMENT, request.toolBindings().getFirst().type());
    assertNull(request.toolBindings().getFirst().environmentName());

    // 最新为缺失名称：同样只冻结最新名称，绝不回看更旧 live Environment。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);
    request =
        fixture.resolved(
            multiTurnPath(
                settings(ENV_A, "default", "low", List.of("read")),
                settings(ENV_MISSING, "default", "low", List.of("read"))));
    assertEquals(ENV_MISSING, request.environmentName());
    assertEquals(ENV_MISSING, request.toolBindings().getFirst().environmentName());

    // Agent skills 同样只认最新快照：历史 turn 有 live Environment + skill，最新 null 时按最新精确拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    assertEquals(
        "agent skills require the latest selected environment but the branch has no environmentName",
        fixture
            .rejected(
                multiTurnPath(
                    settings(ENV_A, "default", "low", List.of("load_skill")),
                    settings(null, "default", "low", List.of("load_skill"))))
            .error()
            .message());
  }

  @Test
  void skillsRequireTheLatestSelectedEnvironmentPrecisely() {
    // skills 需要 live descriptors：latest 环境缺失时精确拒绝，绝不回看更旧的 branch settings。
    Fixture fixture =
        new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    assertEquals(
        "agent skills require the latest selected environment which is not live: " + ENV_MISSING,
        fixture
            .rejected(fixture.path(settings(ENV_MISSING, "default", "low", List.of("load_skill"))))
            .error()
            .message());

    // latest 环境未 READY：同样精确拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.connectingEnvironment(ENV_A);
    assertEquals(
        "agent skills require the latest selected environment which is not ready: " + ENV_A,
        fixture
            .rejected(fixture.path(settings(ENV_A, "default", "low", List.of("load_skill"))))
            .error()
            .message());
  }

  @Test
  void routesByExactEnvironmentNameAndNeverFallsBack() {
    // 两个独立 canonical 名称；branch 只认精确名称，绝不回看更旧 settings 或 fallback。
    Fixture fixture =
        new Fixture(List.of(), List.of("dev-b"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev-a"));
    fixture.readyEnvironment(ENV_B, List.of("dev-b"));
    BranchSettings settings = settings(ENV_B, "default", "low", List.of("bash", "load_skill"));

    ModelInvocationRequest request = fixture.resolved(fixture.path(settings));

    assertEquals(ENV_B, request.environmentName());
    assertEquals(
        List.of("bash", "load_skill"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    List<EnvironmentName> boundRoutes =
        request.toolBindings().stream().map(ToolBinding::environmentName).toList();
    assertEquals(ENV_B, boundRoutes.get(0));
    assertNull(boundRoutes.get(1));
    assertEquals(
        List.of(new SkillBinding("dev-b", "dev-b description", ENV_B)), request.skillBindings());
  }

  @Test
  void bindsToolsInExactActiveToolsOrder() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of(platformDescriptor("create_goal")));
    fixture.readyEnvironment(ENV_A);
    BranchSettings settings =
        settings(ENV_A, "default", "low", List.of("bash", "create_goal", "read"));

    ModelInvocationRequest request = fixture.resolved(fixture.path(settings));

    List<String> boundNames =
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList();
    assertEquals(List.of("bash", "create_goal", "read"), boundNames);
    assertEquals(
        List.of(ToolType.ENVIRONMENT, ToolType.PLATFORM, ToolType.ENVIRONMENT),
        request.toolBindings().stream().map(ToolBinding::type).toList());
    // Provider tools 与 bindings 一一对应且顺序一致。
    assertEquals(
        boundNames, request.providerRequest().tools().stream().map(tool -> tool.name()).toList());

    fixture = new Fixture(List.of(), List.of(), List.of());
    assertEquals(
        "tool not found: missing",
        fixture
            .rejected(fixture.path(settings(null, "default", "low", List.of("missing"))))
            .error()
            .message());
  }

  @Test
  void freezesPluginProvenanceAndProjectsBranchScopedGoalContext() {
    PluginCatalog plugins = PluginCatalog.from(List.of(new GoalPlugin()));
    List<ToolDescriptor> descriptors =
        plugins.tools().stream().map(tool -> tool.descriptor()).toList();
    Fixture fixture = new Fixture(List.of(), List.of(), descriptors, plugins);
    BranchSettings settings = settings(null, "default", "low", List.of("create_goal"));
    EntryPath path =
        new EntryPath(
            List.of(
                new Entry(1L, SESSION_ID, null, new RootPayload(settings), NOW),
                new Entry(
                    2L,
                    SESSION_ID,
                    1L,
                    new CustomEntryPayload(
                        "goal",
                        "state",
                        1,
                        "{\"objective\":\"ship\",\"tokenBudget\":null,\"status\":\"active\","
                            + "\"reason\":null,\"createdAt\":\""
                            + NOW
                            + "\",\"updatedAt\":\""
                            + NOW
                            + "\"}"),
                    NOW)));

    ModelInvocationRequest request = fixture.resolved(path);

    ToolBinding binding = request.toolBindings().getFirst();
    assertEquals("goal", binding.plugin().pluginId());
    assertEquals("create", binding.plugin().contributionLocalName());
    assertEquals("state", binding.plugin().stateAccesses().getFirst().customType());
    assertTrue(
        request.providerRequest().messages().stream()
            .map(DatabaseTurnResolverTest::textOf)
            .anyMatch(text -> text.contains("\"objective\":\"ship\"")));
  }

  @Test
  void requiresLoadSkillInActiveToolsWhenAgentHasSkills() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of());
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    assertEquals(
        "agent has skills but activeTools must include load_skill",
        fixture.rejected(fixture.path(settings(ENV_A, "default", "low"))).error().message());
  }

  @Test
  void bindsSkillsExactlyFromSelectedEnvironment() {
    Fixture fixture =
        new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(ENV_A, "default", "low", List.of("load_skill"))));

    assertEquals(
        List.of("load_skill"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(ToolType.PLATFORM, request.toolBindings().getFirst().type());
    assertEquals(
        List.of(new SkillBinding("dev", "dev description", ENV_A)), request.skillBindings());

    // Environment 缺少该 skill：不静默丢弃，typed 拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of());
    assertEquals(
        "skill not found on the latest environment " + ENV_A + ": dev",
        fixture
            .rejected(fixture.path(settings(ENV_A, "default", "low", List.of("load_skill"))))
            .error()
            .message());
  }

  @Test
  void rejectsMissingLoadSkillDescriptor() {
    // Catalog 完全没有 load_skill：工具绑定阶段即拒绝，绝不自动补装。
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of("dev"),
            List.of(),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    assertEquals(
        "tool not found: load_skill",
        fixture
            .rejected(fixture.path(settings(ENV_A, "default", "low", List.of("load_skill"))))
            .error()
            .message());
  }

  @Test
  void bindsLoadSkillAsPlatformEvenWithoutAgentSkills() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(platformDescriptor("load_skill")),
            Set.of("load_skill"),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(null, "default", "low", List.of("load_skill"))));

    assertEquals(
        List.of("load_skill"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(ToolType.PLATFORM, request.toolBindings().getFirst().type());
    assertNull(request.toolBindings().getFirst().environmentName());
    assertEquals(List.of(), request.skillBindings());
  }

  @Test
  void freezesAllowedSubagentsAndComposesTaskPrompt() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review <carefully> & report.");

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(null, "default", "low", List.of(TaskTool.NAME))));

    assertEquals(
        List.of(new SubagentBinding("reviewer", "Review <carefully> & report.")),
        request.subagentBindings());
    assertEquals(
        List.of(TaskTool.NAME),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(ToolType.PLATFORM, request.toolBindings().getFirst().type());
    String system = textOf(request.providerRequest().messages().getFirst());
    assertTrue(system.contains("<available_subagents>"), system);
    assertTrue(system.contains("<name>reviewer</name>"), system);
    assertTrue(
        system.contains("<description>Review &lt;carefully&gt; &amp; report.</description>"),
        system);
    assertTrue(system.contains("task"), system);

    // 请求冻结后 catalog DTO 的后续变更不得扩权或改写描述。
    fixture.agentConfig.setSubagents(List.of("reviewer", "other"));
    fixture.subagent("reviewer", "changed");
    assertEquals(
        List.of(new SubagentBinding("reviewer", "Review <carefully> & report.")),
        request.subagentBindings());
  }

  @Test
  void taskDelegationRequiresExplicitToolAllowlistAndDepthBudget() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review");

    ModelInvocationRequest withoutTask =
        fixture.resolved(fixture.path(settings(null, "default", "low")));
    assertEquals(List.of(), withoutTask.subagentBindings());
    assertFalse(textOf(withoutTask.providerRequest().messages().getFirst()).contains("subagent"));

    fixture.agentConfig.setSubagents(List.of());
    assertEquals(
        "task requires a non-empty Agent subagents allowlist",
        fixture
            .rejected(fixture.path(settings(null, "default", "low", List.of(TaskTool.NAME))))
            .error()
            .message());

    fixture.agentConfig.setSubagents(List.of("reviewer"));
    EntryPath depthLimited =
        new EntryPath(
            List.of(
                new Entry(
                    1L,
                    SESSION_ID,
                    null,
                    new RootPayload(
                        settings(null, "default", "low", List.of(TaskTool.NAME)),
                        new SubagentContext(90L, 80L, 70L, 2)),
                    NOW)));
    assertEquals(
        "task is unavailable at subagent depth 2 (maxDepth=2)",
        fixture.rejected(depthLimited).error().message());
  }

  @Test
  void rejectsMissingSubagentOrTaskDescriptorWithoutSilentFallback() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("ghost"));
    assertEquals(
        "subagent not found: ghost",
        fixture
            .rejected(fixture.path(settings(null, "default", "low", List.of(TaskTool.NAME))))
            .error()
            .message());

    fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review");
    assertEquals(
        "task tool not found",
        fixture
            .rejected(fixture.path(settings(null, "default", "low", List.of(TaskTool.NAME))))
            .error()
            .message());
  }

  @Test
  void appliesThinkingLevelAsFrozenReasoningEffortOverride() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(null, "custom", "high")));
    assertEquals("custom", request.providerRequest().variant().id());
    assertEquals("high", request.providerRequest().variant().reasoningEffort());
    // 其余 variant 字段原样保留。
    assertEquals(2048, request.providerRequest().variant().maxOutputTokens());
    assertEquals(0.5, request.providerRequest().variant().temperature());
    assertEquals(List.of("END"), request.providerRequest().variant().stopSequences());

    request = fixture.resolved(fixture.path(settings(null, "custom", "off")));
    assertNull(request.providerRequest().variant().reasoningEffort());

    // 非 off 的 thinking override 要求模型支持 reasoning。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelSupportsReasoning(false);
    assertEquals(
        "model does not support reasoning: provider/model",
        fixture.rejected(fixture.path(settings(null, "custom", "high"))).error().message());

    // off 显式关闭 reasoning，即使模型不支持也合法。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelSupportsReasoning(false);
    request = fixture.resolved(fixture.path(settings(null, "custom", "off")));
    assertNull(request.providerRequest().variant().reasoningEffort());
  }

  @Test
  void projectsSemanticMessagesInRootToHeadOrderIgnoringBoundariesAndErrors() {
    Fixture fixture =
        new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    BranchSettings settings = settings(ENV_A, "default", "low", List.of("load_skill"));

    ModelInvocationRequest request = fixture.resolved(multiTurnPath(settings));

    List<ProviderMessage> messages = request.providerRequest().messages();
    assertEquals(4, messages.size());
    assertEquals(ProviderMessageRole.SYSTEM, messages.get(0).role());
    assertTrue(textOf(messages.get(0)).startsWith("agent system prompt"));
    assertTrue(textOf(messages.get(0)).contains("<available_skills>"));
    assertTrue(textOf(messages.get(0)).contains("<name>dev</name>"));
    assertEquals(ProviderMessageRole.USER, messages.get(1).role());
    assertEquals("first user", textOf(messages.get(1)));
    assertEquals(ProviderMessageRole.ASSISTANT, messages.get(2).role());
    assertEquals("partial text", textOf(messages.get(2)));
    assertEquals(ProviderMessageRole.USER, messages.get(3).role());
    assertEquals("custom note", textOf(messages.get(3)));
  }

  @Test
  void escapesXmlInAvailableSkillsSection() {
    Fixture fixture =
        new Fixture(List.of(), List.of("a&b<c>"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironmentWithSkills(ENV_A, List.of(new DaemonSkillDescriptor("a&b<c>", "d&e")));

    ModelInvocationRequest request =
        fixture.resolved(fixture.path(settings(ENV_A, "default", "low", List.of("load_skill"))));

    String system = textOf(request.providerRequest().messages().getFirst());
    assertTrue(system.contains("<name>a&amp;b&lt;c&gt;</name>"));
    assertTrue(system.contains("<description>d&amp;e</description>"));
  }

  @Test
  void finalizesPromptCacheControlFromProviderFactoryCapability() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);
    assertEquals(
        PromptCacheRetention.NONE,
        fixture
            .resolved(fixture.path(settings(null, "default", "low")))
            .providerRequest()
            .cacheControl()
            .retention());

    fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            true);
    ModelInvocationRequest affinity =
        fixture.resolved(fixture.path(settings(null, "default", "low")));
    assertEquals(PromptCacheRetention.SHORT, affinity.providerRequest().cacheControl().retention());
    assertTrue(affinity.providerRequest().cacheControl().affinityKey().startsWith("pc1-"));

    fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(platformDescriptor("create_goal")),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.breakpoints(
                Set.of(PromptCacheRetention.SHORT),
                Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            true);
    ModelInvocationRequest breakpoints =
        fixture.resolved(fixture.path(settings(null, "default", "low", List.of("create_goal"))));
    assertEquals(
        PromptCacheRetention.SHORT, breakpoints.providerRequest().cacheControl().retention());
    assertEquals(
        Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        breakpoints.providerRequest().cacheControl().breakpoints());
  }

  @Test
  void propagatesInfrastructureExceptionsInsteadOfRejecting() {
    Fixture missingAgent = new Fixture(List.of(), List.of(), List.of());
    missingAgent.failAgentLookup(new IllegalStateException("db down"));
    assertThrows(
        IllegalStateException.class,
        () ->
            missingAgent.resolver.resolve(
                1L, missingAgent.path(settings(null, "default", "low")), false, null));

    Fixture missingProvider = new Fixture(List.of(), List.of(), List.of());
    missingProvider.failProviderLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingProvider.resolver.resolve(
                1L, missingProvider.path(settings(null, "default", "low")), false, null));

    Fixture missingModel = new Fixture(List.of(), List.of(), List.of());
    missingModel.failModelLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingModel.resolver.resolve(
                1L, missingModel.path(settings(null, "default", "low")), false, null));
  }

  @Test
  void mapsEveryPersistedProviderType() {
    Map<AgentProviderType, ProviderType> mappings =
        Map.of(
            AgentProviderType.openai,
            ProviderType.OPENAI,
            AgentProviderType.openai_response,
            ProviderType.OPENAI_RESPONSES,
            AgentProviderType.anthropic,
            ProviderType.ANTHROPIC,
            AgentProviderType.google,
            ProviderType.GOOGLE);
    for (Map.Entry<AgentProviderType, ProviderType> mapping : mappings.entrySet()) {
      Fixture fixture =
          new Fixture(
              List.of(),
              List.of(),
              List.of(),
              Set.of(),
              mapping.getKey(),
              mapping.getValue(),
              PromptCacheCapability.unsupported(),
              true);
      // 持久 providerType 只用于在解析时选择当前 ProviderFactory；descriptor 不再冻结类型。
      ModelInvocationRequest request =
          fixture.resolved(fixture.path(settings(null, "default", "low")));
      assertEquals("provider", request.providerRequest().model().providerName());
      assertEquals("model", request.providerRequest().model().modelName());
    }
  }

  private static String textOf(ProviderMessage message) {
    StringBuilder text = new StringBuilder();
    for (ProviderContentBlock content : message.contents()) {
      text.append(((ProviderTextBlock) content).text());
    }
    return text.toString();
  }

  private static BranchSettings settings(
      EnvironmentName environmentName, String variant, String thinkingLevel) {
    return settings(environmentName, variant, thinkingLevel, List.of());
  }

  private static BranchSettings settings(
      EnvironmentName environmentName,
      String variant,
      String thinkingLevel,
      List<String> activeTools) {
    return new BranchSettings(
        environmentName,
        "assistant",
        new ModelSelection("provider", "model", variant),
        thinkingLevel,
        activeTools);
  }

  private static EntryPath rootPath(BranchSettings settings) {
    return new EntryPath(List.of(new Entry(1L, SESSION_ID, null, new RootPayload(settings), NOW)));
  }

  /** 两个关闭 Turn：USER + ABORTED + STOPPED；CUSTOM + ASSISTANT_ERROR + FAILED。两个 Turn 使用同一 settings。 */
  private static EntryPath multiTurnPath(BranchSettings settings) {
    return multiTurnPath(settings, settings);
  }

  /** 两个关闭 Turn，各自携带独立的 BranchSettings 快照（验证 latest-snapshot-wins）。 */
  private static EntryPath multiTurnPath(
      BranchSettings firstTurnSettings, BranchSettings latestTurnSettings) {
    long rootId = 1L;
    long turn1Id = 2L;
    long user1Id = 3L;
    long abortedId = 4L;
    long end1Id = 5L;
    long turn2Id = 6L;
    long customId = 7L;
    long errorId = 8L;
    long end2Id = 9L;
    return new EntryPath(
        List.of(
            new Entry(rootId, SESSION_ID, null, new RootPayload(firstTurnSettings), NOW),
            new Entry(
                turn1Id,
                SESSION_ID,
                rootId,
                new TurnStartPayload(TurnStartReason.INPUT, firstTurnSettings),
                NOW),
            new Entry(
                user1Id,
                SESSION_ID,
                turn1Id,
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("first user"))),
                    null,
                    null),
                NOW),
            new Entry(
                abortedId,
                SESSION_ID,
                user1Id,
                new AssistantAbortedPayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(new TextMessageContent("partial text")))),
                NOW),
            new Entry(
                end1Id,
                SESSION_ID,
                abortedId,
                new TurnEndPayload(
                    turn1Id, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, "STOP/1/2"),
                NOW),
            new Entry(
                turn2Id,
                SESSION_ID,
                end1Id,
                new TurnStartPayload(TurnStartReason.INPUT, latestTurnSettings),
                NOW),
            new Entry(
                customId,
                SESSION_ID,
                turn2Id,
                new CustomMessagePayload(
                    CustomMessagePayload.CORE_PLUGIN_ID,
                    CustomMessagePayload.CORE_CUSTOM_TYPE,
                    CustomMessagePayload.CORE_RENDERER_KEY,
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("custom note"))),
                    CustomMessagePayload.CORE_DETAILS_JSON),
                NOW),
            new Entry(
                errorId,
                SESSION_ID,
                customId,
                new AssistantErrorPayload(new AssistantError("PLANNING_FAILED", "boom")),
                NOW),
            new Entry(
                end2Id,
                SESSION_ID,
                errorId,
                new TurnEndPayload(
                    turn2Id, TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
                NOW)));
  }

  private static ToolDescriptor platformDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1",
        ToolType.PLATFORM,
        name + " description",
        name,
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
        "USD",
        "standard",
        "default",
        BigDecimal.ONE,
        "v1",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  @Test
  void compactionRequestIsMinimalAndFreezesPlannerFacts() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default", "low");
    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))));
    CompactionPreparation preparation =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            123L,
            4096L,
            2L,
            3L,
            null,
            null,
            messages);

    ModelInvocationRequest request = fixture.resolved(fixture.path(settings), false, preparation);

    // 冻结的切分事实与 contextWindow 逐字段保留。
    assertEquals(CompactionPhase.FULL, request.compaction().phase());
    assertEquals(CompactionTrigger.THRESHOLD, request.compaction().trigger());
    assertEquals(123L, request.compaction().tokensBefore());
    assertEquals(2L, request.compaction().firstKeptEntryId());
    assertEquals(3L, request.compaction().cutEntryId());
    assertNull(request.compaction().turnPrefixStartEntryId());
    assertEquals(4096, request.contextWindow());
    assertEquals(ENV_A, request.environmentName());
    // 零 tool / skill，无 cache。
    assertEquals(List.of(), request.toolBindings());
    assertEquals(List.of(), request.skillBindings());
    assertEquals(List.of(), request.providerRequest().tools());
    assertEquals(ProviderCacheControl.none(), request.providerRequest().cacheControl());
    // 恰好 SYSTEM + USER 两个消息：summarization system prompt + summary user prompt。
    List<ProviderMessage> providerMessages = request.providerRequest().messages();
    assertEquals(2, providerMessages.size());
    assertEquals(ProviderMessageRole.SYSTEM, providerMessages.get(0).role());
    assertEquals(CompactionPrompts.summarizationSystemPrompt(), textOf(providerMessages.get(0)));
    assertEquals(ProviderMessageRole.USER, providerMessages.get(1).role());
    assertEquals(
        CompactionPrompts.summaryUserPrompt(messages, null), textOf(providerMessages.get(1)));
    // 输出上限 = min(variant 1024, floor(0.8 * 16384) = 13107) = 1024。
    assertEquals(1024, request.providerRequest().variant().maxOutputTokens());
  }

  @Test
  void compactionOutputCapFallsBackToModelGlobalLimitWhenVariantNull() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelGlobalOutputLimit(600);
    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))));
    CompactionPreparation full =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            123L,
            4096L,
            2L,
            3L,
            null,
            null,
            messages);
    CompactionPreparation prefix =
        new CompactionPreparation(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            123L,
            4096L,
            2L,
            3L,
            2L,
            null,
            messages);

    // FULL 预算 13107、TURN_PREFIX 预算 8192：variant 未设置时都回退到模型全局 600（绝不直接使用预算）。
    assertEquals(
        600,
        fixture
            .resolved(fixture.path(settings(null, "default", "low")), false, full)
            .providerRequest()
            .variant()
            .maxOutputTokens());
    assertEquals(
        600,
        fixture
            .resolved(fixture.path(settings(null, "default", "low")), false, prefix)
            .providerRequest()
            .variant()
            .maxOutputTokens());
  }

  @Test
  void normalProjectionUsesLatestSummaryWrapperAndRetainedCutSuffix() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default", "low");

    ModelInvocationRequest request =
        fixture.resolved(projectionPath(settings, "summary text", 2L, 4L));

    List<ProviderMessage> messages = request.providerRequest().messages();
    assertEquals(5, messages.size());
    assertEquals(ProviderMessageRole.SYSTEM, messages.get(0).role());
    assertTrue(textOf(messages.get(0)).startsWith("agent system prompt"));
    assertEquals(ProviderMessageRole.USER, messages.get(1).role());
    assertEquals(CompactionPrompts.compactedContext("summary text"), textOf(messages.get(1)));
    // 从 cut（ASST1）本身开始保留：ASST1、USER2、ASST2 继续投影；COMPACTION 控制 turn 内部不投影。
    assertEquals("first reply", textOf(messages.get(2)));
    assertEquals("second user", textOf(messages.get(3)));
    assertEquals("second reply", textOf(messages.get(4)));
  }

  @Test
  void compactionInternalMessagesAreSuppressedInProjection() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default", "low");

    ModelInvocationRequest request = fixture.resolved(stoppedCompactionProjectionPath(settings));

    List<ProviderMessage> messages = request.providerRequest().messages();
    assertEquals(5, messages.size());
    assertEquals("first user", textOf(messages.get(1)));
    assertEquals("first reply", textOf(messages.get(2)));
    // 被停止压缩 turn 内部的 ABORTED 一律不投影。
    assertEquals("second user", textOf(messages.get(3)));
    assertEquals("second reply", textOf(messages.get(4)));
  }

  @Test
  void corruptCompactionReferencesFailClosedInProjection() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default", "low");
    // cut 不在当前路径。
    EntryPath missingCut = projectionPath(settings, "summary", 2L, 999L);
    assertThrows(IllegalStateException.class, () -> fixture.resolved(missingCut));
    // firstKept 在 cut 之后（顺序非法）。
    EntryPath inverted = projectionPath(settings, "summary", 4L, 3L);
    assertThrows(IllegalStateException.class, () -> fixture.resolved(inverted));
  }

  @Test
  void latestCompleteCompactionWinsOverOlderWrapper() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default", "low");

    ModelInvocationRequest request = fixture.resolved(twoCompactionProjectionPath(settings));

    List<ProviderMessage> messages = request.providerRequest().messages();
    assertEquals(6, messages.size());
    assertEquals(CompactionPrompts.compactedContext("latest summary"), textOf(messages.get(1)));
    // 只有最新压缩的 wrapper；从最新 cut（USER2）起保留。
    assertEquals("second user", textOf(messages.get(2)));
    assertEquals("second reply", textOf(messages.get(3)));
    assertEquals("third user", textOf(messages.get(4)));
    assertEquals("third reply", textOf(messages.get(5)));
  }

  /** 完整压缩投影路径：ROOT + 两个关闭 turn，中间夹一个完成压缩 turn（payload 携带给定 firstKept/cut）。 */
  private static EntryPath projectionPath(
      BranchSettings settings, String summary, long firstKeptEntryId, long cutEntryId) {
    long rootId = 1L;
    long turn1Id = 2L;
    long user1Id = 3L;
    long assistant1Id = 4L;
    long end1Id = 5L;
    long compactionStartId = 6L;
    long compactionId = 7L;
    long end2Id = 8L;
    long turn2Id = 9L;
    long user2Id = 10L;
    long assistant2Id = 11L;
    long end3Id = 12L;
    return new EntryPath(
        List.of(
            new Entry(rootId, SESSION_ID, null, new RootPayload(settings), NOW),
            turnEntry(turn1Id, rootId, settings),
            userEntry(user1Id, turn1Id, "first user"),
            assistantEntry(assistant1Id, user1Id, "first reply"),
            turnEnd(end1Id, assistant1Id, turn1Id),
            compactionStartEntry(compactionStartId, end1Id, settings),
            new Entry(
                compactionId,
                SESSION_ID,
                compactionStartId,
                new CompactionPayload(
                    CompactionPhase.FULL,
                    CompactionTrigger.THRESHOLD,
                    500L,
                    true,
                    summary,
                    firstKeptEntryId,
                    cutEntryId,
                    null),
                NOW),
            turnEnd(end2Id, compactionId, compactionStartId),
            turnEntry(turn2Id, end2Id, settings),
            userEntry(user2Id, turn2Id, "second user"),
            assistantEntry(assistant2Id, user2Id, "second reply"),
            turnEnd(end3Id, assistant2Id, turn2Id)));
  }

  /** 被停止压缩 turn 投影路径：ROOT + turn1 + 停止压缩 turn（ABORTED）+ turn2。 */
  private static EntryPath stoppedCompactionProjectionPath(BranchSettings settings) {
    long rootId = 1L;
    long turn1Id = 2L;
    long user1Id = 3L;
    long assistant1Id = 4L;
    long end1Id = 5L;
    long compactionStartId = 6L;
    long abortedId = 7L;
    long end2Id = 8L;
    long turn2Id = 9L;
    long user2Id = 10L;
    long assistant2Id = 11L;
    long end3Id = 12L;
    return new EntryPath(
        List.of(
            new Entry(rootId, SESSION_ID, null, new RootPayload(settings), NOW),
            turnEntry(turn1Id, rootId, settings),
            userEntry(user1Id, turn1Id, "first user"),
            assistantEntry(assistant1Id, user1Id, "first reply"),
            turnEnd(end1Id, assistant1Id, turn1Id),
            compactionStartEntry(compactionStartId, end1Id, settings),
            new Entry(
                abortedId,
                SESSION_ID,
                compactionStartId,
                new AssistantAbortedPayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(new TextMessageContent("internal aborted")))),
                NOW),
            new Entry(
                end2Id,
                SESSION_ID,
                abortedId,
                new TurnEndPayload(
                    compactionStartId,
                    TurnEndOutcome.STOPPED,
                    false,
                    TurnEndReason.USER_STOP,
                    "s-1"),
                NOW),
            turnEntry(turn2Id, end2Id, settings),
            userEntry(user2Id, turn2Id, "second user"),
            assistantEntry(assistant2Id, user2Id, "second reply"),
            turnEnd(end3Id, assistant2Id, turn2Id)));
  }

  /** 两次完整压缩路径：第二次的 wrapper 与 cut 完全取代第一次。 */
  private static EntryPath twoCompactionProjectionPath(BranchSettings settings) {
    long rootId = 1L;
    long turn1Id = 2L;
    long user1Id = 3L;
    long assistant1Id = 4L;
    long end1Id = 5L;
    long compaction1StartId = 6L;
    long compaction1Id = 7L;
    long end2Id = 8L;
    long turn2Id = 9L;
    long user2Id = 10L;
    long assistant2Id = 11L;
    long end3Id = 12L;
    long compaction2StartId = 13L;
    long compaction2Id = 14L;
    long end4Id = 15L;
    long turn3Id = 16L;
    long user3Id = 17L;
    long assistant3Id = 18L;
    long end5Id = 19L;
    return new EntryPath(
        List.of(
            new Entry(rootId, SESSION_ID, null, new RootPayload(settings), NOW),
            turnEntry(turn1Id, rootId, settings),
            userEntry(user1Id, turn1Id, "first user"),
            assistantEntry(assistant1Id, user1Id, "first reply"),
            turnEnd(end1Id, assistant1Id, turn1Id),
            compactionStartEntry(compaction1StartId, end1Id, settings),
            new Entry(
                compaction1Id,
                SESSION_ID,
                compaction1StartId,
                new CompactionPayload(
                    CompactionPhase.FULL,
                    CompactionTrigger.THRESHOLD,
                    500L,
                    true,
                    "old summary",
                    2L,
                    4L,
                    null),
                NOW),
            turnEnd(end2Id, compaction1Id, compaction1StartId),
            turnEntry(turn2Id, end2Id, settings),
            userEntry(user2Id, turn2Id, "second user"),
            assistantEntry(assistant2Id, user2Id, "second reply"),
            turnEnd(end3Id, assistant2Id, turn2Id),
            compactionStartEntry(compaction2StartId, end3Id, settings),
            new Entry(
                compaction2Id,
                SESSION_ID,
                compaction2StartId,
                new CompactionPayload(
                    CompactionPhase.FULL,
                    CompactionTrigger.THRESHOLD,
                    500L,
                    true,
                    "latest summary",
                    2L,
                    10L,
                    null),
                NOW),
            turnEnd(end4Id, compaction2Id, compaction2StartId),
            turnEntry(turn3Id, end4Id, settings),
            userEntry(user3Id, turn3Id, "third user"),
            assistantEntry(assistant3Id, user3Id, "third reply"),
            turnEnd(end5Id, assistant3Id, turn3Id)));
  }

  private static Entry turnEntry(long id, long parentId, BranchSettings settings) {
    return new Entry(
        id, SESSION_ID, parentId, new TurnStartPayload(TurnStartReason.INPUT, settings), NOW);
  }

  private static Entry userEntry(long id, long parentId, String text) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))),
            null,
            null),
        NOW);
  }

  private static Entry assistantEntry(long id, long parentId, String text) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
            new AssistantMessageMetadata(
                ProviderStopReason.COMPLETED,
                new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
                new ModelCost(
                    "USD",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)),
            null),
        NOW);
  }

  private static Entry compactionStartEntry(long id, long parentId, BranchSettings settings) {
    return new Entry(
        id, SESSION_ID, parentId, new TurnStartPayload(TurnStartReason.COMPACTION, settings), NOW);
  }

  private static Entry turnEnd(long id, long parentId, long turnStartEntryId) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
        NOW);
  }

  private static Fixture taskFixture() {
    return new Fixture(
        List.of(),
        List.of(),
        List.of(platformDescriptor(TaskTool.NAME)),
        Set.of(TaskTool.NAME),
        AgentProviderType.openai,
        ProviderType.OPENAI,
        PromptCacheCapability.unsupported(),
        true);
  }

  private static final class Fixture {

    private final AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    private final AgentModelRepository models = mock(AgentModelRepository.class);
    private final AgentProviderRepository providers = mock(AgentProviderRepository.class);
    private final AgentDefinitionConfigCodec agentConfigCodec =
        mock(AgentDefinitionConfigCodec.class);
    private final AgentModelRuntimeConfigParser modelConfigParser =
        mock(AgentModelRuntimeConfigParser.class);
    private final LiveEnvironmentRegistry environmentRegistry = new LiveEnvironmentRegistry();
    private final AgentDefinition agent = new AgentDefinition();
    private final AgentDefinitionConfigDTO agentConfig = new AgentDefinitionConfigDTO();
    private final AgentProvider provider = new AgentProvider();
    private final DatabaseTurnResolver resolver;

    private Fixture(
        List<String> tools, List<String> skills, List<ToolDescriptor> platformDescriptors) {
      this(tools, skills, platformDescriptors, PluginCatalog.from(List.of()));
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> platformDescriptors,
        PluginCatalog pluginCatalog) {
      this(
          tools,
          skills,
          platformDescriptors,
          Set.of(),
          AgentProviderType.openai,
          ProviderType.OPENAI,
          PromptCacheCapability.unsupported(),
          true,
          pluginCatalog);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> platformDescriptors,
        Set<String> internalPlatformToolNames,
        AgentProviderType persistedProviderType,
        ProviderType factoryType,
        PromptCacheCapability cacheCapability,
        boolean includeProviderFactory) {
      this(
          tools,
          skills,
          platformDescriptors,
          internalPlatformToolNames,
          persistedProviderType,
          factoryType,
          cacheCapability,
          includeProviderFactory,
          PluginCatalog.from(List.of()));
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> platformDescriptors,
        Set<String> internalPlatformToolNames,
        AgentProviderType persistedProviderType,
        ProviderType factoryType,
        PromptCacheCapability cacheCapability,
        boolean includeProviderFactory,
        PluginCatalog pluginCatalog) {
      agent.setName("assistant");
      agent.setSystemPrompt("agent system prompt");
      agent.setConfigJson("agent-config");
      when(agents.getByName("assistant")).thenReturn(agent);

      provider.setName("provider");
      provider.setProviderType(persistedProviderType);
      provider.setVersion(0L);
      when(providers.getByName("provider")).thenReturn(provider);

      AgentModel model = new AgentModel();
      model.setProviderName("provider");
      model.setName("model");
      model.setConfigJson("model-config");
      when(models.getByProviderNameAndName("provider", "model")).thenReturn(model);

      agentConfig.setTools(tools);
      agentConfig.setSkills(skills);
      agentConfig.setSubagents(List.of());
      when(agentConfigCodec.decode("agent-config")).thenReturn(agentConfig);

      modelSupportsTools(true);
      modelSupportsReasoning(true);
      ProviderFactory providerFactory = mock(ProviderFactory.class);
      when(providerFactory.providerType()).thenReturn(factoryType);
      when(providerFactory.promptCacheCapability()).thenReturn(cacheCapability);
      List<ProviderFactory> factories =
          includeProviderFactory ? List.of(providerFactory) : List.of();
      SubagentConfig subagentConfig =
          new SubagentConfig(2, 10, null, Duration.ZERO, 50, Duration.ofMillis(100));
      resolver =
          new DatabaseTurnResolver(
              agents,
              models,
              providers,
              agentConfigCodec,
              modelConfigParser,
              new ProviderFactories(factories),
              new ToolCatalog(platformDescriptors, internalPlatformToolNames),
              pluginCatalog,
              environmentRegistry,
              new EnvironmentGatewayProperties(),
              CompactionConfig.DEFAULTS,
              subagentConfig,
              new AgentPromptComposer(subagentConfig),
              Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void subagent(String name, String description) {
      AgentDefinition subagent = new AgentDefinition();
      subagent.setName(name);
      subagent.setDescription(description);
      when(agents.getByName(name)).thenReturn(subagent);
    }

    private void missingAgent() {
      when(agents.getByName("assistant")).thenReturn(null);
    }

    private void missingProvider() {
      when(providers.getByName("provider")).thenReturn(null);
    }

    private void missingModel() {
      when(models.getByProviderNameAndName("provider", "model")).thenReturn(null);
    }

    private void failAgentLookup(RuntimeException error) {
      when(agents.getByName("assistant")).thenThrow(error);
    }

    private void failProviderLookup(RuntimeException error) {
      when(providers.getByName("provider")).thenThrow(error);
    }

    private void failModelLookup(RuntimeException error) {
      when(models.getByProviderNameAndName("provider", "model")).thenThrow(error);
    }

    private void failAgentConfig(RuntimeException error) {
      when(agentConfigCodec.decode("agent-config")).thenThrow(error);
    }

    private void failModelConfig(RuntimeException error) {
      when(modelConfigParser.parse("model-config")).thenThrow(error);
    }

    private void modelSupportsTools(boolean tools) {
      ParsedAgentModelConfig parsed = parsedModel();
      when(modelConfigParser.parse("model-config"))
          .thenReturn(
              new ParsedAgentModelConfig(
                  parsed.contextWindow(),
                  parsed.maxOutputTokens(),
                  parsed.inputModalities(),
                  tools,
                  parsed.reasoning(),
                  parsed.variants(),
                  parsed.defaultVariant(),
                  parsed.pricing()));
    }

    /** variant.maxOutputTokens 全部置 null，模型全局 limit.output 固定为 {@code maxOutputTokens}。 */
    private void modelGlobalOutputLimit(long maxOutputTokens) {
      ParsedAgentModelConfig parsed = parsedModel();
      when(modelConfigParser.parse("model-config"))
          .thenReturn(
              new ParsedAgentModelConfig(
                  parsed.contextWindow(),
                  maxOutputTokens,
                  parsed.inputModalities(),
                  parsed.tools(),
                  parsed.reasoning(),
                  List.of(
                      new ModelVariant(
                          "default", null, 0.7, null, null, null, null, List.of(), null)),
                  parsed.defaultVariant(),
                  parsed.pricing()));
    }

    private void modelSupportsReasoning(boolean reasoning) {
      ParsedAgentModelConfig parsed = parsedModel();
      when(modelConfigParser.parse("model-config"))
          .thenReturn(
              new ParsedAgentModelConfig(
                  parsed.contextWindow(),
                  parsed.maxOutputTokens(),
                  parsed.inputModalities(),
                  parsed.tools(),
                  reasoning,
                  parsed.variants(),
                  parsed.defaultVariant(),
                  parsed.pricing()));
    }

    private static ParsedAgentModelConfig parsedModel() {
      ModelVariant defaultVariant =
          new ModelVariant("default", 1024, 0.7, null, null, null, null, List.of(), null);
      ModelVariant customVariant =
          new ModelVariant("custom", 2048, 0.5, null, null, null, null, List.of("END"), "medium");
      return new ParsedAgentModelConfig(
          4096,
          1024,
          Set.of(ModelInputModality.TEXT),
          true,
          true,
          List.of(defaultVariant, customVariant),
          "default",
          pricing());
    }

    private EntryPath path(BranchSettings settings) {
      return rootPath(settings);
    }

    private ModelInvocationRequest resolved(EntryPath path) {
      return resolved(path, false);
    }

    private ModelInvocationRequest resolved(EntryPath path, boolean yoloEnabled) {
      TurnResolver.Result result = resolver.resolve(1L, path, yoloEnabled, null);
      return assertInstanceOf(TurnResolver.Resolved.class, result).request();
    }

    private ModelInvocationRequest resolved(
        EntryPath path, boolean yoloEnabled, CompactionPreparation preparation) {
      TurnResolver.Result result = resolver.resolve(1L, path, yoloEnabled, preparation);
      return assertInstanceOf(TurnResolver.Resolved.class, result).request();
    }

    private TurnResolver.Rejected rejected(EntryPath path) {
      TurnResolver.Result result = resolver.resolve(1L, path, false, null);
      return assertInstanceOf(TurnResolver.Rejected.class, result);
    }

    private void connectingEnvironment(EnvironmentName environmentName) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      environmentRegistry.tryBind(environmentName, connection, NOW, Duration.ofSeconds(60));
    }

    private void readyEnvironment(EnvironmentName environmentName) {
      readyEnvironment(environmentName, List.of());
    }

    private void readyEnvironment(EnvironmentName environmentName, List<String> skills) {
      readyEnvironmentWithSkills(
          environmentName,
          skills.stream()
              .map(name -> new DaemonSkillDescriptor(name, name + " description"))
              .toList());
    }

    private void readyEnvironmentWithSkills(
        EnvironmentName environmentName, List<DaemonSkillDescriptor> skills) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      environmentRegistry.tryBind(environmentName, connection, NOW, Duration.ofSeconds(60));
      environmentRegistry.updateCapabilities(
          environmentName,
          connection,
          new DaemonCapabilities(DaemonCapabilities.VERSION, skills, List.of()),
          NOW);
      environmentRegistry.markReady(environmentName, connection, NOW);
    }
  }
}

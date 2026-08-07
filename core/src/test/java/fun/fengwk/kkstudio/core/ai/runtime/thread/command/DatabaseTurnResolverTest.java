package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
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
                1L, missingAgent.path(settings(null, "default", "low")), false));

    Fixture missingProvider = new Fixture(List.of(), List.of(), List.of());
    missingProvider.failProviderLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingProvider.resolver.resolve(
                1L, missingProvider.path(settings(null, "default", "low")), false));

    Fixture missingModel = new Fixture(List.of(), List.of(), List.of());
    missingModel.failModelLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingModel.resolver.resolve(
                1L, missingModel.path(settings(null, "default", "low")), false));
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

  /** 两个关闭 Turn：USER + ABORTED + STOPPED；CUSTOM + ASSISTANT_ERROR + FAILED。 */
  private static EntryPath multiTurnPath(BranchSettings settings) {
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
            new Entry(rootId, SESSION_ID, null, new RootPayload(settings), NOW),
            new Entry(
                turn1Id,
                SESSION_ID,
                rootId,
                new TurnStartPayload(TurnStartReason.INPUT, settings),
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
                new TurnStartPayload(TurnStartReason.INPUT, settings),
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
    private final AgentProvider provider = new AgentProvider();
    private final DatabaseTurnResolver resolver;

    private Fixture(
        List<String> tools, List<String> skills, List<ToolDescriptor> platformDescriptors) {
      this(
          tools,
          skills,
          platformDescriptors,
          Set.of(),
          AgentProviderType.openai,
          ProviderType.OPENAI,
          PromptCacheCapability.unsupported(),
          true);
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

      AgentDefinitionConfigDTO agentConfig = new AgentDefinitionConfigDTO();
      agentConfig.setTools(tools);
      agentConfig.setSkills(skills);
      when(agentConfigCodec.decode("agent-config")).thenReturn(agentConfig);

      modelSupportsTools(true);
      modelSupportsReasoning(true);
      ProviderFactory providerFactory = mock(ProviderFactory.class);
      when(providerFactory.providerType()).thenReturn(factoryType);
      when(providerFactory.promptCacheCapability()).thenReturn(cacheCapability);
      List<ProviderFactory> factories =
          includeProviderFactory ? List.of(providerFactory) : List.of();
      resolver =
          new DatabaseTurnResolver(
              agents,
              models,
              providers,
              agentConfigCodec,
              modelConfigParser,
              new ProviderFactories(factories),
              new ToolCatalog(platformDescriptors, internalPlatformToolNames),
              environmentRegistry,
              new EnvironmentGatewayProperties(),
              Clock.fixed(NOW, ZoneOffset.UTC));
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
      TurnResolver.Result result = resolver.resolve(1L, path, yoloEnabled);
      return assertInstanceOf(TurnResolver.Resolved.class, result).request();
    }

    private TurnResolver.Rejected rejected(EntryPath path) {
      TurnResolver.Result result = resolver.resolve(1L, path, false);
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
      environmentRegistry.updateSkills(environmentName, connection, skills, NOW);
      environmentRegistry.markReady(environmentName, connection, NOW);
    }
  }
}

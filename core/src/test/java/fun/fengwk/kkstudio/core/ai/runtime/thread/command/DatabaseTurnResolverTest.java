package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.runtime.task.AgentPromptComposer;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.core.testing.TestEnvironmentBindings;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugins.goal.GoalPlugin;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
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
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptFailurePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.runtime.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DatabaseTurnResolver 契约：精确的 branch 引用（无回退）、不可变的 EnvironmentName 路由、严格有序的工具/skill 能力、唯一的 catalog
 * Variant 请求预设、语义化消息投影、缓存终结与基础设施异常透传。
 */
class DatabaseTurnResolverTest {

  private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 100L);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static final EnvironmentBinding ENV_A =
      new EnvironmentBinding(new EnvironmentName("env-1"), "projects/web");
  private static final EnvironmentBinding ENV_B = TestEnvironmentBindings.binding("env-2");
  private static final EnvironmentBinding ENV_MISSING = TestEnvironmentBindings.binding("env-3");

  @Test
  void resolvesExactBranchModelReferencesWithoutFallback() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    // Agent 自带的 provider/model/variant 引用是旧数据，绝不能被读取。
    fixture.agent.setModelProviderName("old-provider");
    fixture.agent.setModelName("old-model");
    fixture.agent.setVariant("old-variant");
    when(fixture.providers.getByName("old-provider")).thenReturn(null);
    TurnResolver.Resolved resolved =
        fixture.resolvedResult(fixture.path(settings(null, "custom")), null);
    ModelRequestSpec request = resolved.spec();

    assertEquals("provider", request.model().providerName());
    assertEquals("model", request.model().modelName());
    assertEquals(Set.of(ModelInputModality.TEXT), request.model().inputModalities());
    assertEquals("custom", request.variant().id());
    assertEquals(0.5, request.variant().temperature());
    assertEquals(2048, request.variant().maxOutputTokens());
    assertEquals(4096, resolved.contextWindow());
    assertEquals(2048, resolved.maxOutputTokens());
  }

  @Test
  void neverFallsBackVariantToModelDefault() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    TurnResolver.Rejected rejected = fixture.rejected(fixture.path(settings(null, "missing")));

    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.error().code());
    assertEquals(
        "model variant not found: provider/model variant=missing", rejected.error().message());
  }

  @Test
  void activeToolsComeFromLatestAgentConfigNotBranchSnapshot() {
    // 最新 Agent 声明 read，即使历史 branch activeTools 为空也必须在下一 turn 生效。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);

    ModelRequestSpec request = fixture.resolved(fixture.path(settings(ENV_A, "default")));

    assertEquals(
        List.of("read"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());

    // 最新 Agent 已移除工具时，历史 branch 快照不得继续扩权。
    fixture = new Fixture(List.of(), List.of(), List.of());
    request =
        fixture.resolved(
            fixture.path(settings(ENV_A, "default", List.of("read", "write", "bash"))));
    assertEquals(List.of(), request.toolBindings());
  }

  @Test
  void rejectsMissingCatalogResourcesAndInvalidConfig() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingAgent();
    assertEquals(
        "agent not found: assistant",
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingProvider();
    assertEquals(
        "provider not found: provider",
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingModel();
    assertEquals(
        "model not found: provider/model",
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.provider.setProviderType(null);
    assertEquals(
        "provider type must not be null",
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failModelConfig(new IllegalArgumentException("broken model config"));
    assertEquals(
        "invalid model configuration: broken model config",
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failAgentConfig(new IllegalStateException("broken agent config"));
    assertEquals(
        "invalid agent configuration: broken agent config",
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());

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
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());
  }

  @Test
  void rejectsToolsWhenModelDoesNotSupportTools() {
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.modelSupportsTools(false);
    fixture.readyEnvironment(ENV_A);

    TurnResolver.Rejected rejected =
        fixture.rejected(fixture.path(settings(ENV_A, "default", List.of("read"))));

    assertEquals("model does not support tools: provider/model", rejected.error().message());
  }

  @Test
  void resolvesModelOnlyBranchWithoutEnvironment() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelRequestSpec request = fixture.resolved(fixture.path(settings(null, "default")));

    assertEquals(List.of(), request.toolBindings());
    assertEquals(List.of(), request.skillBindings());
    assertTrue(request.preambleMessages().getFirst().contents().toString().contains("date:"));
  }

  @Test
  void alwaysProjectsNoneCurrentEnvironmentIntoProviderRequest() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelRequestSpec request = fixture.resolved(fixture.path(settings(null, "default")));

    assertEquals(
        "agent system prompt\n\n"
            + "<current_environment>\n"
            + "- date: 2026-08-02\n"
            + "</current_environment>",
        preambleText(request));
  }

  @Test
  void noLiveMetadataUsesServiceClockZoneForNoneAndMissingEnvironment() {
    Clock serviceClock = Clock.fixed(NOW, ZoneId.of("America/Los_Angeles"));
    Fixture fixture = new Fixture(List.of(), List.of(), List.of(), serviceClock);

    String none = preambleText(fixture.resolved(fixture.path(settings(null, "default"))));
    assertFalse(none.contains("- name:"), none);
    assertFalse(none.contains("- system:"), none);
    assertTrue(none.contains("- date: 2026-08-01"), none);
    assertFalse(none.contains("- note:"), none);

    String missing = preambleText(fixture.resolved(fixture.path(settings(ENV_MISSING, "default"))));
    assertTrue(missing.contains("- name: env-3"), missing);
    assertFalse(missing.contains("- system:"), missing);
    assertTrue(missing.contains("- date: 2026-08-01"), missing);
    assertFalse(missing.contains("- note:"), missing);
    assertNoLegacyCurrentEnvironmentFields(missing);
  }

  @Test
  void readyCurrentEnvironmentUsesDaemonMetadataAndTimeZone() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.readyEnvironment(
        ENV_A,
        List.of(),
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.LINUX,
            "America/Los_Angeles",
            "Custom <Linux> & tools.",
            "/home/dev"));

    ModelRequestSpec request = fixture.resolved(fixture.path(settings(ENV_A, "default")));
    String prompt = preambleText(request);

    assertTrue(prompt.contains("- name: env-1"), prompt);
    assertTrue(prompt.contains("- workspace: projects/web"), prompt);
    assertTrue(prompt.contains("- system: linux"), prompt);
    assertTrue(prompt.contains("- date: 2026-08-01"), prompt);
    assertTrue(prompt.contains("- note: Custom &lt;Linux&gt; &amp; tools."), prompt);
    assertNoLegacyCurrentEnvironmentFields(prompt);
  }

  @Test
  void selectedEnvironmentUsesMetadataRegardlessOfHeartbeatReadiness() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.connectingEnvironment(ENV_A);

    String withoutMetadata =
        preambleText(fixture.resolved(fixture.path(settings(ENV_A, "default"))));
    assertTrue(withoutMetadata.contains("- name: env-1"), withoutMetadata);
    assertTrue(withoutMetadata.contains("- workspace: projects/web"), withoutMetadata);
    assertFalse(withoutMetadata.contains("- system:"), withoutMetadata);
    assertTrue(withoutMetadata.contains("- date: 2026-08-02"), withoutMetadata);
    assertFalse(withoutMetadata.contains("- note:"), withoutMetadata);

    DaemonEnvironmentInfo environmentInfo =
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.WSL, "Asia/Tokyo", "Stable WSL environment.", "/home/dev");
    Fixture readyFixture = new Fixture(List.of(), List.of(), List.of());
    readyFixture.readyEnvironment(ENV_A, List.of(), environmentInfo);
    String ready =
        preambleText(readyFixture.resolved(readyFixture.path(settings(ENV_A, "default"))));
    Fixture staleFixture = new Fixture(List.of(), List.of(), List.of());
    staleFixture.staleEnvironment(ENV_A, environmentInfo);
    String stale =
        preambleText(staleFixture.resolved(staleFixture.path(settings(ENV_A, "default"))));
    assertEquals(ready, stale);
    assertTrue(stale.contains("- system: wsl"), stale);
    assertTrue(stale.contains("- date: 2026-08-02"), stale);
    assertTrue(stale.contains("- note: Stable WSL environment."), stale);
    assertNoLegacyCurrentEnvironmentFields(stale);
  }

  @Test
  void ordinaryResolutionReadsClockInstantExactlyOnce() {
    AtomicInteger calls = new AtomicInteger();
    Clock countingClock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            calls.incrementAndGet();
            return NOW;
          }
        };
    Fixture fixture = new Fixture(List.of(), List.of(), List.of(), countingClock);

    fixture.resolved(fixture.path(settings(null, "default")));

    assertEquals(1, calls.get());
  }

  @Test
  void bindsEnvironmentToolsWithLatestNameEvenWhenBranchHasNoEnvironment() {
    // 分支没有环境路由不再拒绝工具规划：ENVIRONMENT 工具仍按最新（null）名称绑定，实际执行时确定性失败。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    ModelRequestSpec request =
        fixture.resolved(fixture.path(settings(null, "default", List.of("read"))));
    assertEquals(1, request.toolBindings().size());
    assertEquals(ToolType.ENVIRONMENT, request.toolBindings().getFirst().type());
    assertNull(request.toolBindings().getFirst().environment());

    // Agent skills 要求最新选中的 Environment 提供 live descriptors：分支没有名称时精确拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of());
    assertEquals(
        "agent skills require the latest selected environment but the branch has no environment",
        fixture.rejected(fixture.path(settings(null, "default"))).error().message());
  }

  @Test
  void missingOrNotReadyLatestEnvironmentDoesNotRejectToolPlanning() {
    // 缺失的 latest 环境：工具按最新名称绑定，规划成功。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    ModelRequestSpec request =
        fixture.resolved(fixture.path(settings(ENV_MISSING, "default", List.of("read"))));
    assertEquals(ENV_MISSING, request.toolBindings().getFirst().environment());

    // 未 READY 的 latest 环境：同样按最新名称绑定，规划成功；绝不回看更旧 branch settings。
    fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.connectingEnvironment(ENV_A);
    request = fixture.resolved(fixture.path(settings(ENV_A, "default", List.of("read"))));
    assertEquals(ENV_A, request.toolBindings().getFirst().environment());
  }

  @Test
  void latestSnapshotAgentReferenceWinsOverOlderValidAgent() {
    // 历史 turn 引用有效 agent（assistant 已注册），最新 turn 引用缺失 agent：
    // 只按最新快照的精确名称解析并拒绝，绝不回看更旧 turn 的有效 agent（无历史 repository 查询）。
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings firstTurn = settings(null, "default").withAgentName("assistant");
    BranchSettings latestTurn = settings(null, "default").withAgentName("ghost");

    TurnResolver.Rejected rejected = fixture.rejected(multiTurnPath(firstTurn, latestTurn));

    assertEquals("agent not found: ghost", rejected.error().message());
  }

  @Test
  void latestSnapshotEnvironmentWinsOverOlderLiveEnvironment() {
    // 历史 turn 绑定 live Environment，最新 turn 为 null/缺失名称：
    // 请求只冻结最新快照的 route（null/名称），绝不选中更旧的 live Environment。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);
    BranchSettings firstTurn = settings(ENV_A, "default", List.of("read"));
    BranchSettings latestTurn = settings(null, "default", List.of("read"));

    ModelRequestSpec request = fixture.resolved(multiTurnPath(firstTurn, latestTurn));

    assertEquals(
        List.of("read"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(ToolType.ENVIRONMENT, request.toolBindings().getFirst().type());
    assertNull(request.toolBindings().getFirst().environment());

    // 最新为缺失名称：同样只冻结最新名称，绝不回看更旧 live Environment。
    fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);
    request =
        fixture.resolved(
            multiTurnPath(
                settings(ENV_A, "default", List.of("read")),
                settings(ENV_MISSING, "default", List.of("read"))));
    assertEquals(ENV_MISSING, request.toolBindings().getFirst().environment());

    // Agent skills 同样只认最新快照：历史 turn 有 live Environment + skill，最新 null 时按最新精确拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    assertEquals(
        "agent skills require the latest selected environment but the branch has no environment",
        fixture
            .rejected(
                multiTurnPath(
                    settings(ENV_A, "default", List.of("load_skill")),
                    settings(null, "default", List.of("load_skill"))))
            .error()
            .message());
  }

  @Test
  void skillsRequireTheLatestSelectedEnvironmentPrecisely() {
    // skills 需要 live descriptors：latest 环境缺失时精确拒绝，绝不回看更旧的 branch settings。
    Fixture fixture =
        new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    assertEquals(
        "agent skills require the latest selected environment which is not live: "
            + ENV_MISSING.environmentName(),
        fixture
            .rejected(fixture.path(settings(ENV_MISSING, "default", List.of("load_skill"))))
            .error()
            .message());

    // latest 环境未 READY：同样精确拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.connectingEnvironment(ENV_A);
    assertEquals(
        "agent skills require the latest selected environment which is not ready: "
            + ENV_A.environmentName(),
        fixture
            .rejected(fixture.path(settings(ENV_A, "default", List.of("load_skill"))))
            .error()
            .message());
  }

  @Test
  void routesByExactEnvironmentNameAndNeverFallsBack() {
    // 两个独立 canonical 名称；branch 只认精确名称，绝不回看更旧 settings 或 fallback。
    Fixture fixture =
        new Fixture(List.of("bash"), List.of("dev-b"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev-a"));
    fixture.readyEnvironment(ENV_B, List.of("dev-b"));
    BranchSettings settings = settings(ENV_B, "default", List.of("bash", "load_skill"));

    ModelRequestSpec request = fixture.resolved(fixture.path(settings));

    assertEquals(
        List.of("bash", "load_skill"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    List<EnvironmentBinding> boundRoutes =
        request.toolBindings().stream().map(ToolBinding::environment).toList();
    assertEquals(ENV_B, boundRoutes.get(0));
    assertNull(boundRoutes.get(1));
    assertEquals(
        List.of(new SkillBinding("dev-b", "dev-b description", ENV_B)), request.skillBindings());
  }

  @Test
  void bindsToolsInExactLatestAgentOrder() {
    Fixture fixture =
        new Fixture(
            List.of("bash", "create_goal", "read"),
            List.of(),
            List.of(platformDescriptor("create_goal")));
    fixture.readyEnvironment(ENV_A);
    BranchSettings settings = settings(ENV_A, "default", List.of("bash", "create_goal", "read"));

    ModelRequestSpec request = fixture.resolved(fixture.path(settings));

    List<String> boundNames =
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList();
    assertEquals(List.of("bash", "create_goal", "read"), boundNames);
    assertEquals(
        List.of(ToolType.ENVIRONMENT, ToolType.PLATFORM, ToolType.ENVIRONMENT),
        request.toolBindings().stream().map(ToolBinding::type).toList());
    // Provider tools 与 bindings 一一对应且顺序一致。
    assertEquals(List.of("bash", "create_goal", "read"), boundNames);

    fixture = new Fixture(List.of("missing"), List.of(), List.of());
    assertEquals(
        "tool not found: missing",
        fixture
            .rejected(fixture.path(settings(null, "default", List.of("missing"))))
            .error()
            .message());
  }

  @Test
  void freezesPluginProvenanceAndProjectsBranchScopedGoalContext() {
    PluginCatalog plugins = PluginCatalog.from(List.of(new GoalPlugin()));
    List<ToolDescriptor> descriptors =
        plugins.tools().stream().map(tool -> tool.descriptor()).toList();
    Fixture fixture = new Fixture(List.of("create_goal"), List.of(), descriptors, plugins);
    BranchSettings settings = settings(null, "default", List.of("create_goal"));
    EntryPath path =
        new EntryPath(
            List.of(
                new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW),
                new Entry(
                    id(2),
                    SESSION_ID,
                    id(1),
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

    ModelRequestSpec request = fixture.resolved(path);

    ToolBinding binding = request.toolBindings().getFirst();
    assertEquals("goal", binding.plugin().pluginId());
    assertEquals("create", binding.plugin().contributionLocalName());
    assertEquals("state", binding.plugin().stateAccesses().getFirst().customType());
    assertTrue(
        materialized(path, request).stream()
            .map(DatabaseTurnResolverTest::textOf)
            .anyMatch(text -> text.contains("\"objective\":\"ship\"")));
  }

  @Test
  void derivesLoadSkillFromLatestAgentSkills() {
    Fixture fixture =
        new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));

    ModelRequestSpec request = fixture.resolved(fixture.path(settings(ENV_A, "default")));

    assertEquals(
        List.of("load_skill"),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(
        List.of(new SkillBinding("dev", "dev description", ENV_A)), request.skillBindings());
  }

  @Test
  void bindsSkillsExactlyFromSelectedEnvironment() {
    Fixture fixture =
        new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));

    ModelRequestSpec request =
        fixture.resolved(fixture.path(settings(ENV_A, "default", List.of("load_skill"))));

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
            .rejected(fixture.path(settings(ENV_A, "default", List.of("load_skill"))))
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
            .rejected(fixture.path(settings(ENV_A, "default", List.of("load_skill"))))
            .error()
            .message());
  }

  @Test
  void ignoresStaleLoadSkillWhenLatestAgentHasNoSkills() {
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

    ModelRequestSpec request =
        fixture.resolved(fixture.path(settings(null, "default", List.of("load_skill"))));

    assertEquals(List.of(), request.toolBindings());
    assertEquals(List.of(), request.skillBindings());
  }

  @Test
  void freezesAllowedSubagentsAndComposesTaskPrompt() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review <carefully> & report.");

    ModelRequestSpec request =
        fixture.resolved(fixture.path(settings(null, "default", List.of(TaskTool.NAME))));

    assertEquals(
        List.of(new SubagentBinding("reviewer", "Review <carefully> & report.")),
        request.subagentBindings());
    assertEquals(
        List.of(TaskTool.NAME),
        request.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(ToolType.PLATFORM, request.toolBindings().getFirst().type());
    String system = preambleText(request);
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
  void taskDelegationComesFromLatestAllowlistAndDepthBudget() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review");

    ModelRequestSpec withTask = fixture.resolved(fixture.path(settings(null, "default")));
    assertEquals(List.of(new SubagentBinding("reviewer", "Review")), withTask.subagentBindings());
    assertEquals(
        List.of(TaskTool.NAME),
        withTask.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());

    fixture.agentConfig.setSubagents(List.of());
    ModelRequestSpec withoutTask =
        fixture.resolved(fixture.path(settings(null, "default", List.of(TaskTool.NAME))));
    assertEquals(List.of(), withoutTask.subagentBindings());
    assertEquals(List.of(), withoutTask.toolBindings());
    assertFalse(preambleText(withoutTask).contains("subagent"));

    fixture.agentConfig.setSubagents(List.of("reviewer"));
    EntryPath depthLimited =
        new EntryPath(
            List.of(
                new Entry(
                    id(1),
                    SESSION_ID,
                    null,
                    new RootPayload(
                        settings(null, "default", List.of(TaskTool.NAME)),
                        new SubagentContext(id(90), id(80), id(70), 2)),
                    NOW)));
    ModelRequestSpec depthLimitedRequest = fixture.resolved(depthLimited);
    assertEquals(List.of(), depthLimitedRequest.subagentBindings());
    assertEquals(List.of(), depthLimitedRequest.toolBindings());
  }

  /** allowlist 指向不存在的 subagent 时立即拒绝，绝不静默跳过。 */
  @Test
  void rejectsMissingSubagentWithoutSilentFallback() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("ghost"));
    assertEquals(
        "subagent not found: ghost",
        fixture
            .rejected(fixture.path(settings(null, "default", List.of(TaskTool.NAME))))
            .error()
            .message());
  }

  /** task descriptor 来自注入的 TaskTool 现拼，不依赖 ToolCatalog 启动快照。 */
  @Test
  void bindsLiveTaskDescriptorWhenCatalogSnapshotOmitsTask() {
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
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review");
    ModelRequestSpec request =
        fixture.resolved(fixture.path(settings(null, "default", List.of(TaskTool.NAME))));
    assertEquals(TaskTool.NAME, request.toolBindings().getFirst().descriptor().name());
    assertEquals(TaskTool.VERSION, request.toolBindings().getFirst().descriptor().version());
  }

  @Test
  void usesCatalogVariantReasoningEffortDirectly() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelRequestSpec request = fixture.resolved(fixture.path(settings(null, "custom")));
    assertEquals("custom", request.variant().id());
    // 所选 catalog variant 的 reasoningEffort 原样生效，不存在运行时 override。
    assertEquals("medium", request.variant().reasoningEffort());
    // 其余 variant 字段原样保留。
    assertEquals(2048, request.variant().maxOutputTokens());
    assertEquals(0.5, request.variant().temperature());
    assertEquals(List.of("END"), request.variant().stopSequences());

    request = fixture.resolved(fixture.path(settings(null, "default")));
    assertNull(request.variant().reasoningEffort());

    // variant 携带 reasoningEffort 时要求模型支持 reasoning。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelSupportsReasoning(false);
    assertEquals(
        "model does not support reasoning: provider/model",
        fixture.rejected(fixture.path(settings(null, "custom"))).error().message());

    // reasoningEffort 为 null 的 variant 即使模型不支持 reasoning 也合法。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelSupportsReasoning(false);
    request = fixture.resolved(fixture.path(settings(null, "default")));
    assertNull(request.variant().reasoningEffort());
  }

  @Test
  void projectsSemanticMessagesInRootToHeadOrderIgnoringBoundariesAndErrors() {
    Fixture fixture =
        new Fixture(List.of(), List.of("dev"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    BranchSettings settings = settings(ENV_A, "default", List.of("load_skill"));

    EntryPath path = multiTurnPath(settings);
    ModelRequestSpec request = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, request);
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

  /** failed-attempt 与 terminal error partial 都是 UI/audit 事实，绝不进入 Provider messages。 */
  @Test
  void excludesModelAttemptFailuresAndAssistantErrorPartialsFromProviderContext() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(null, "default");

    EntryPath path = failedAttemptPath(settings);
    ModelRequestSpec request = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, request);
    assertEquals(2, messages.size());
    assertEquals(ProviderMessageRole.SYSTEM, messages.get(0).role());
    assertEquals(ProviderMessageRole.USER, messages.get(1).role());
    assertEquals("visible user", textOf(messages.get(1)));
    String projected = messages.stream().map(DatabaseTurnResolverTest::textOf).toList().toString();
    assertFalse(projected.contains("retry-only-secret"));
    assertFalse(projected.contains("terminal-only-secret"));
    assertFalse(projected.contains("provider exploded"));
  }

  @Test
  void escapesXmlInAvailableSkillsSection() {
    Fixture fixture =
        new Fixture(List.of(), List.of("a&b<c>"), List.of(platformDescriptor("load_skill")));
    fixture.readyEnvironmentWithSkills(ENV_A, List.of(new DaemonSkillDescriptor("a&b<c>", "d&e")));

    ModelRequestSpec request =
        fixture.resolved(fixture.path(settings(ENV_A, "default", List.of("load_skill"))));

    String system = preambleText(request);
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
        fixture.resolved(fixture.path(settings(null, "default"))).cacheControl().retention());

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
    ModelRequestSpec affinity = fixture.resolved(fixture.path(settings(null, "default")));
    assertEquals(PromptCacheRetention.SHORT, affinity.cacheControl().retention());
    assertTrue(affinity.cacheControl().affinityKey().startsWith("pc1-"));

    fixture =
        new Fixture(
            List.of("create_goal"),
            List.of(),
            List.of(platformDescriptor("create_goal")),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.breakpoints(
                Set.of(PromptCacheRetention.SHORT),
                Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            true);
    ModelRequestSpec breakpoints =
        fixture.resolved(fixture.path(settings(null, "default", List.of("create_goal"))));
    assertEquals(PromptCacheRetention.SHORT, breakpoints.cacheControl().retention());
    assertEquals(
        Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        breakpoints.cacheControl().breakpoints());
  }

  @Test
  void propagatesInfrastructureExceptionsInsteadOfRejecting() {
    Fixture missingAgent = new Fixture(List.of(), List.of(), List.of());
    missingAgent.failAgentLookup(new IllegalStateException("db down"));
    assertThrows(
        IllegalStateException.class,
        () ->
            missingAgent.resolver.resolve(
                THREAD_ID, missingAgent.path(settings(null, "default")), null));

    Fixture missingProvider = new Fixture(List.of(), List.of(), List.of());
    missingProvider.failProviderLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingProvider.resolver.resolve(
                THREAD_ID, missingProvider.path(settings(null, "default")), null));

    Fixture missingModel = new Fixture(List.of(), List.of(), List.of());
    missingModel.failModelLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingModel.resolver.resolve(
                THREAD_ID, missingModel.path(settings(null, "default")), null));
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
      ModelRequestSpec request = fixture.resolved(fixture.path(settings(null, "default")));
      assertEquals("provider", request.model().providerName());
      assertEquals("model", request.model().modelName());
      assertEquals(mapping.getValue(), request.providerType());
    }
  }

  @Test
  void resolvedSpecStaysFrozenAfterCatalogAndConfigChange() {
    Fixture fixture =
        new Fixture(List.of("create_goal"), List.of(), List.of(platformDescriptor("create_goal")));
    EntryPath path = fixture.path(settings(null, "custom"));
    ModelRequestSpec frozen = fixture.resolved(path);
    String preamble = preambleText(frozen);
    ModelDescriptor model = frozen.model();
    ModelVariant variant = frozen.variant();
    List<ToolBinding> tools = frozen.toolBindings();
    ProviderRequest before = new ModelRequestMaterializer().materialize(path, frozen);

    fixture.agent.setSystemPrompt("changed system prompt");
    fixture.agentConfig.setTools(List.of());
    fixture.modelSupportsTools(false);

    // 二次 resolve 证明 mutation 真实生效：live spec 的 preamble 与 tools 都变了，而 frozen spec 不受影响。
    ModelRequestSpec live = fixture.resolved(path);
    assertNotEquals(preamble, preambleText(live));
    assertTrue(live.toolBindings().isEmpty());

    assertEquals(preamble, preambleText(frozen));
    assertEquals(model, frozen.model());
    assertEquals(variant, frozen.variant());
    assertEquals(tools, frozen.toolBindings());
    assertEquals("custom", frozen.variant().id());
    assertEquals(
        List.of("create_goal"),
        tools.stream().map(binding -> binding.descriptor().name()).toList());
    ProviderRequest after = new ModelRequestMaterializer().materialize(path, frozen);
    assertEquals(before, after);
    assertEquals(preamble, textOf(after.messages().getFirst()));
    assertEquals(List.of("create_goal"), after.tools().stream().map(tool -> tool.name()).toList());
  }

  private static String textOf(ProviderMessage message) {
    StringBuilder text = new StringBuilder();
    for (ProviderContentBlock content : message.contents()) {
      text.append(((ProviderTextBlock) content).text());
    }
    return text.toString();
  }

  private static String preambleText(ModelRequestSpec spec) {
    return textOf(new ProviderMessageProjector().project(spec.preambleMessages()).getFirst());
  }

  private static List<ProviderMessage> materialized(EntryPath path, ModelRequestSpec spec) {
    return new ModelRequestMaterializer().materialize(path, spec).messages();
  }

  private static void assertNoLegacyCurrentEnvironmentFields(String prompt) {
    assertFalse(prompt.contains("status"), prompt);
    assertFalse(prompt.contains("working_directory"), prompt);
    assertFalse(prompt.contains("current_time"), prompt);
    assertFalse(prompt.contains("time_zone"), prompt);
  }

  private static BranchSettings settings(EnvironmentBinding environment, String variant) {
    return settings(environment, variant, List.of());
  }

  private static BranchSettings settings(
      EnvironmentBinding environment, String variant, List<String> activeTools) {
    return new BranchSettings(
        environment, "assistant", new ModelSelection("provider", "model", variant), activeTools);
  }

  private static EntryPath rootPath(BranchSettings settings) {
    return new EntryPath(
        List.of(new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW)));
  }

  /** ROOT + INPUT turn + USER + ASSISTANT，供压缩 materialize 按冻结 Entry IDs 取摘要范围。 */
  private static EntryPath compactionHistoryPath(
      BranchSettings settings, CompactionPreparation preparation) {
    return new EntryPath(
        List.of(
            new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW),
            new Entry(
                id(2),
                SESSION_ID,
                id(1),
                new TurnStartPayload(TurnStartReason.INPUT, settings, THREAD_ID),
                NOW.plusSeconds(1)),
            new Entry(
                id(3),
                SESSION_ID,
                id(2),
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                    null,
                    null),
                NOW.plusSeconds(2)),
            new Entry(
                id(4),
                SESSION_ID,
                id(3),
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("reply"))),
                    new AssistantMessageMetadata(
                        GenerationStopReason.COMPLETE,
                        new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L),
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
                NOW.plusSeconds(3)),
            new Entry(
                id(5),
                SESSION_ID,
                id(4),
                new TurnEndPayload(id(2), TurnEndOutcome.COMPLETED, false, null, null),
                NOW.plusSeconds(4)),
            new Entry(
                id(6),
                SESSION_ID,
                id(5),
                new TurnStartPayload(
                    TurnStartReason.COMPACTION,
                    settings,
                    THREAD_ID,
                    null,
                    null,
                    preparation.frozenStart()),
                NOW.plusSeconds(5))));
  }

  /** 两个关闭 Turn：USER + ABORTED + STOPPED；CUSTOM + ASSISTANT_ERROR + FAILED。两个 Turn 使用同一 settings。 */
  private static EntryPath multiTurnPath(BranchSettings settings) {
    return multiTurnPath(settings, settings);
  }

  /** 两个关闭 Turn，各自携带独立的 BranchSettings 快照（验证 latest-snapshot-wins）。 */
  private static EntryPath multiTurnPath(
      BranchSettings firstTurnSettings, BranchSettings latestTurnSettings) {
    return new EntryPath(
        List.of(
            new Entry(id(1), SESSION_ID, null, new RootPayload(firstTurnSettings), NOW),
            new Entry(
                id(2),
                SESSION_ID,
                id(1),
                new TurnStartPayload(TurnStartReason.INPUT, firstTurnSettings, THREAD_ID),
                NOW),
            new Entry(
                id(3),
                SESSION_ID,
                id(2),
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("first user"))),
                    null,
                    null),
                NOW),
            new Entry(
                id(4),
                SESSION_ID,
                id(3),
                new AssistantAbortedPayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(new TextMessageContent("partial text")))),
                NOW),
            new Entry(
                id(5),
                SESSION_ID,
                id(4),
                new TurnEndPayload(
                    id(2), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(1)),
                NOW),
            new Entry(
                id(6),
                SESSION_ID,
                id(5),
                new TurnStartPayload(TurnStartReason.INPUT, latestTurnSettings, THREAD_ID),
                NOW),
            new Entry(
                id(7),
                SESSION_ID,
                id(6),
                new CustomMessagePayload(
                    CustomMessagePayload.CORE_PLUGIN_ID,
                    CustomMessagePayload.CORE_CUSTOM_TYPE,
                    CustomMessagePayload.CORE_RENDERER_KEY,
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("custom note"))),
                    CustomMessagePayload.CORE_DETAILS_JSON),
                NOW),
            new Entry(
                id(8),
                SESSION_ID,
                id(7),
                new AssistantErrorPayload(new AssistantError("PLANNING_FAILED", "boom"), null),
                NOW),
            new Entry(
                id(9),
                SESSION_ID,
                id(8),
                new TurnEndPayload(
                    id(6), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
                NOW)));
  }

  private static EntryPath failedAttemptPath(BranchSettings settings) {
    return new EntryPath(
        List.of(
            new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW),
            new Entry(
                id(2),
                SESSION_ID,
                id(1),
                new TurnStartPayload(TurnStartReason.INPUT, settings, THREAD_ID),
                NOW),
            new Entry(
                id(3),
                SESSION_ID,
                id(2),
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("visible user"))),
                    null,
                    null),
                NOW),
            new Entry(
                id(4),
                SESSION_ID,
                id(3),
                new ModelAttemptFailurePayload(
                    new ModelAttemptSnapshot(1, 4, "retry-only-secret", ""),
                    new AssistantError("TRANSIENT", "provider unavailable"),
                    NOW.plusSeconds(2)),
                NOW),
            new Entry(
                id(5),
                SESSION_ID,
                id(4),
                new AssistantErrorPayload(
                    new AssistantError("TRANSIENT", "provider exploded"),
                    new ModelAttemptSnapshot(2, 6, "terminal-only-secret", "")),
                NOW.plusSeconds(3)),
            new Entry(
                id(6),
                SESSION_ID,
                id(5),
                new TurnEndPayload(
                    id(2), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
                NOW.plusSeconds(3))));
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
  void compactionSpecOnlyFreezesExecutionModelAndResolvedBudget() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default");
    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))));
    CompactionPreparation preparation =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            settings.model(),
            id(4),
            null,
            null,
            null,
            messages,
            123L);

    EntryPath historyPath = compactionHistoryPath(settings, preparation);
    TurnResolver.Resolved resolved = fixture.resolvedResult(historyPath, preparation);
    ModelRequestSpec request = resolved.spec();

    // 切分事实只在 candidate path 的 TURN_START 中冻结；spec 只保留 descriptor/variant。
    assertEquals(Set.of(ModelInputModality.TEXT), request.model().inputModalities());
    assertEquals(4096, resolved.contextWindow());
    assertEquals(123, resolved.maxOutputTokens());
    assertEquals(123, request.variant().maxOutputTokens());
    assertEquals(List.of(), request.preambleMessages());
    assertEquals(List.of(), request.toolBindings());
    assertEquals(List.of(), request.skillBindings());
    assertEquals(List.of(), request.subagentBindings());
    assertEquals(ProviderCacheControl.none(), request.cacheControl());
    // materializer 从 candidate path 的 CompactionStart 重建摘要 prompt，而不是从 spec 读取切分元数据。
    List<ProviderMessage> providerMessages = materialized(historyPath, request);
    assertEquals(2, providerMessages.size());
    assertEquals(ProviderMessageRole.SYSTEM, providerMessages.get(0).role());
    assertEquals(CompactionPrompts.summarizationSystemPrompt(), textOf(providerMessages.get(0)));
    assertFalse(textOf(providerMessages.get(0)).contains("<current_environment>"));
    assertEquals(ProviderMessageRole.USER, providerMessages.get(1).role());
    assertEquals(
        CompactionPrompts.summaryUserPrompt(messages, null), textOf(providerMessages.get(1)));
    // FULL 预算为 min(1024, floor(0.8 * 1024) = 819, removedPrefixTokens=123) = 123。
  }

  @Test
  void compactionOutputBudgetUsesActualModelLimitAndRemovedPrefixTokens() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelGlobalOutputLimit(600);
    ModelSelection executionModel = settings(null, "default").model();
    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))));
    CompactionPreparation fullSmall =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            executionModel,
            id(3),
            null,
            null,
            null,
            messages,
            123L);
    CompactionPreparation fullLarge =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            executionModel,
            id(3),
            null,
            null,
            null,
            messages,
            50_000L);
    CompactionPreparation prefix =
        new CompactionPreparation(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            executionModel,
            id(3),
            id(2),
            null,
            null,
            messages,
            50_000L);

    // 未显式设置 variant 上限时先取实际 model 全局 600，再由 outputBudget 阶段公式裁剪。
    assertEquals(
        123,
        fixture
            .resolvedResult(fixture.path(settings(null, "default")), fullSmall)
            .maxOutputTokens());
    assertEquals(
        480,
        fixture
            .resolvedResult(fixture.path(settings(null, "default")), fullLarge)
            .maxOutputTokens());
    assertEquals(
        300,
        fixture.resolvedResult(fixture.path(settings(null, "default")), prefix).maxOutputTokens());
    assertEquals(
        480,
        fixture
            .resolved(fixture.path(settings(null, "default")), fullLarge)
            .variant()
            .maxOutputTokens());
    assertEquals(
        300,
        fixture
            .resolved(fixture.path(settings(null, "default")), prefix)
            .variant()
            .maxOutputTokens());
  }

  /** 逆证：fallback executionModel 必须改变 resolver 的 catalog 解析目标，而不是继续读取 branch settings.model。 */
  @Test
  void compactionResolvesProviderModelAndVariantFromFallbackExecutionModel() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    ModelSelection fallback = new ModelSelection("fallback-provider", "fallback-model", "fallback");
    fixture.addModel(
        fallback,
        new ParsedAgentModelConfig(
            8192,
            4096,
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            List.of(
                new ModelVariant("fallback", 1500, 0.2, null, null, null, null, List.of(), null)),
            "fallback",
            pricing()));
    when(fixture.providers.getByName("provider"))
        .thenThrow(new AssertionError("compaction must not resolve branch settings.model"));
    when(fixture.models.getByProviderNameAndName("provider", "model"))
        .thenThrow(new AssertionError("compaction must not resolve branch settings.model"));

    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))));
    CompactionPreparation preparation =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            fallback,
            id(3),
            null,
            null,
            null,
            messages,
            50_000L);

    TurnResolver.Resolved resolved =
        fixture.resolvedResult(fixture.path(settings(null, "default")), preparation);

    assertEquals("fallback-provider", resolved.spec().model().providerName());
    assertEquals("fallback-model", resolved.spec().model().modelName());
    assertEquals("fallback", resolved.spec().variant().id());
    assertEquals(8192, resolved.contextWindow());
    // fallback variant 的实际上限 1500 经 FULL 预算公式裁剪为 1200。
    assertEquals(1200, resolved.maxOutputTokens());
    assertEquals(1200, resolved.spec().variant().maxOutputTokens());
  }

  /** CompactionPreparation 保持最小，派生 token/window 与 compaction metadata 不进入 ModelRequestSpec。 */
  @Test
  void compactionFactsStayOutOfPreparationAndModelRequestSpec() {
    assertThrows(
        NoSuchMethodException.class,
        () -> CompactionPreparation.class.getDeclaredMethod("tokensBefore"));
    assertThrows(
        NoSuchMethodException.class,
        () -> CompactionPreparation.class.getDeclaredMethod("contextWindow"));
    assertThrows(
        NoSuchMethodException.class,
        () -> CompactionPreparation.class.getDeclaredMethod("firstKeptEntryId"));
    assertThrows(
        NoSuchMethodException.class, () -> ModelRequestSpec.class.getDeclaredMethod("compaction"));
  }

  @Test
  void normalProjectionUsesLatestSummaryWrapperAndRetainedCutSuffix() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default");

    EntryPath path = projectionPath(settings, "summary text", id(4));
    ModelRequestSpec request = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, request);
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
    BranchSettings settings = settings(ENV_A, "default");

    EntryPath path = stoppedCompactionProjectionPath(settings);
    ModelRequestSpec request = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, request);
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
    BranchSettings settings = settings(ENV_A, "default");
    // cut 不在当前路径。
    EntryPath missingCut = projectionPath(settings, "summary", id(999));
    ModelRequestSpec missingSpec = fixture.resolved(missingCut);
    assertThrows(IllegalStateException.class, () -> materialized(missingCut, missingSpec));
  }

  @Test
  void latestCompleteCompactionWinsOverOlderWrapper() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings(ENV_A, "default");

    EntryPath path = twoCompactionProjectionPath(settings);
    ModelRequestSpec request = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, request);
    assertEquals(6, messages.size());
    assertEquals(CompactionPrompts.compactedContext("latest summary"), textOf(messages.get(1)));
    // 只有最新压缩的 wrapper；从最新 cut（USER2）起保留。
    assertEquals("second user", textOf(messages.get(2)));
    assertEquals("second reply", textOf(messages.get(3)));
    assertEquals("third user", textOf(messages.get(4)));
    assertEquals("third reply", textOf(messages.get(5)));
  }

  /** 完整压缩投影路径：ROOT + 两个关闭 turn，中间夹一个完成压缩 turn。 */
  private static EntryPath projectionPath(
      BranchSettings settings, String summary, UUID cutEntryId) {
    return new EntryPath(
        List.of(
            new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW),
            turnEntry(id(2), id(1), settings),
            userEntry(id(3), id(2), "first user"),
            assistantEntry(id(4), id(3), "first reply"),
            turnEnd(id(5), id(4), id(2)),
            compactionStartEntry(id(6), id(5), settings, cutEntryId),
            new Entry(
                id(7),
                SESSION_ID,
                id(6),
                // CompactionStart carries the cut; the result payload carries only the summary.
                new CompactionPayload(summary),
                NOW),
            turnEnd(id(8), id(7), id(6)),
            turnEntry(id(9), id(8), settings),
            userEntry(id(10), id(9), "second user"),
            assistantEntry(id(11), id(10), "second reply"),
            turnEnd(id(12), id(11), id(9))));
  }

  /** 被停止压缩 turn 投影路径：ROOT + turn1 + 停止压缩 turn（ABORTED）+ turn2。 */
  private static EntryPath stoppedCompactionProjectionPath(BranchSettings settings) {
    return new EntryPath(
        List.of(
            new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW),
            turnEntry(id(2), id(1), settings),
            userEntry(id(3), id(2), "first user"),
            assistantEntry(id(4), id(3), "first reply"),
            turnEnd(id(5), id(4), id(2)),
            compactionStartEntry(id(6), id(5), settings, id(4)),
            new Entry(
                id(7),
                SESSION_ID,
                id(6),
                new AssistantAbortedPayload(
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT,
                        List.of(new TextMessageContent("internal aborted")))),
                NOW),
            new Entry(
                id(8),
                SESSION_ID,
                id(7),
                new TurnEndPayload(
                    id(6), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(1)),
                NOW),
            turnEntry(id(9), id(8), settings),
            userEntry(id(10), id(9), "second user"),
            assistantEntry(id(11), id(10), "second reply"),
            turnEnd(id(12), id(11), id(9))));
  }

  /** 两次完整压缩路径：第二次的 wrapper 与 cut 完全取代第一次。 */
  private static EntryPath twoCompactionProjectionPath(BranchSettings settings) {
    return new EntryPath(
        List.of(
            new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW),
            turnEntry(id(2), id(1), settings),
            userEntry(id(3), id(2), "first user"),
            assistantEntry(id(4), id(3), "first reply"),
            turnEnd(id(5), id(4), id(2)),
            compactionStartEntry(id(6), id(5), settings, id(4)),
            new Entry(id(7), SESSION_ID, id(6), new CompactionPayload("old summary"), NOW),
            turnEnd(id(8), id(7), id(6)),
            turnEntry(id(9), id(8), settings),
            userEntry(id(10), id(9), "second user"),
            assistantEntry(id(11), id(10), "second reply"),
            turnEnd(id(12), id(11), id(9)),
            compactionStartEntry(id(13), id(12), settings, id(10)),
            new Entry(id(14), SESSION_ID, id(13), new CompactionPayload("latest summary"), NOW),
            turnEnd(id(15), id(14), id(13)),
            turnEntry(id(16), id(15), settings),
            userEntry(id(17), id(16), "third user"),
            assistantEntry(id(18), id(17), "third reply"),
            turnEnd(id(19), id(18), id(16))));
  }

  private static Entry turnEntry(UUID id, UUID parentId, BranchSettings settings) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new TurnStartPayload(TurnStartReason.INPUT, settings, THREAD_ID),
        NOW);
  }

  private static Entry userEntry(UUID id, UUID parentId, String text) {
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

  private static Entry assistantEntry(UUID id, UUID parentId, String text) {
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
            new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE,
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

  private static Entry compactionStartEntry(
      UUID id, UUID parentId, BranchSettings settings, UUID cutEntryId) {
    CompactionStart compaction =
        new CompactionStart(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            settings.model(),
            cutEntryId,
            null,
            null);
    return new Entry(
        id,
        SESSION_ID,
        parentId,
        new TurnStartPayload(
            TurnStartReason.COMPACTION, settings, THREAD_ID, null, null, compaction),
        NOW);
  }

  private static Entry turnEnd(UUID id, UUID parentId, UUID turnStartEntryId) {
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
        Clock clock) {
      this(tools, skills, platformDescriptors, PluginCatalog.from(List.of()), clock);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> platformDescriptors,
        PluginCatalog pluginCatalog) {
      this(tools, skills, platformDescriptors, pluginCatalog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> platformDescriptors,
        PluginCatalog pluginCatalog,
        Clock clock) {
      this(
          tools,
          skills,
          platformDescriptors,
          Set.of(),
          AgentProviderType.openai,
          ProviderType.OPENAI,
          PromptCacheCapability.unsupported(),
          true,
          pluginCatalog,
          clock);
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
      this(
          tools,
          skills,
          platformDescriptors,
          internalPlatformToolNames,
          persistedProviderType,
          factoryType,
          cacheCapability,
          includeProviderFactory,
          pluginCatalog,
          Clock.fixed(NOW, ZoneOffset.UTC));
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
        PluginCatalog pluginCatalog,
        Clock clock) {
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
      SubagentConfig subagentConfig = new SubagentConfig(2, 10, 0, Duration.ZERO, 50);
      TaskTool taskTool = mock(TaskTool.class);
      when(taskTool.descriptor())
          .thenReturn(
              new ToolDescriptor(
                  TaskTool.NAME,
                  TaskTool.VERSION,
                  ToolType.PLATFORM,
                  "task",
                  TaskTool.RENDERER_KEY,
                  new ToolParamsSchema("task", Map.of(), Set.of(), false),
                  ToolSideEffect.NON_IDEMPOTENT,
                  Duration.ZERO));
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
              new SystemSettingsSnapshot(SystemSettings.DEFAULT),
              () -> new CompactionConfig(20_000, null),
              () -> subagentConfig,
              new AgentPromptComposer(() -> subagentConfig),
              taskTool,
              clock);
    }

    private void addModel(ModelSelection selection, ParsedAgentModelConfig parsedModel) {
      AgentProvider fallbackProvider = new AgentProvider();
      fallbackProvider.setName(selection.providerName());
      fallbackProvider.setProviderType(AgentProviderType.openai);
      fallbackProvider.setVersion(0L);
      when(providers.getByName(selection.providerName())).thenReturn(fallbackProvider);

      String configJson = selection.providerName() + "-config";
      AgentModel fallbackModel = new AgentModel();
      fallbackModel.setProviderName(selection.providerName());
      fallbackModel.setName(selection.modelName());
      fallbackModel.setConfigJson(configJson);
      when(models.getByProviderNameAndName(selection.providerName(), selection.modelName()))
          .thenReturn(fallbackModel);
      when(modelConfigParser.parse(configJson)).thenReturn(parsedModel);
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

    private ModelRequestSpec resolved(EntryPath path) {
      return resolvedResult(path, null).spec();
    }

    private ModelRequestSpec resolved(EntryPath path, CompactionPreparation preparation) {
      return resolvedResult(path, preparation).spec();
    }

    private TurnResolver.Resolved resolvedResult(
        EntryPath path, CompactionPreparation preparation) {
      TurnResolver.Result result = resolver.resolve(THREAD_ID, path, preparation);
      return assertInstanceOf(TurnResolver.Resolved.class, result);
    }

    private TurnResolver.Rejected rejected(EntryPath path) {
      TurnResolver.Result result = resolver.resolve(THREAD_ID, path, null);
      return assertInstanceOf(TurnResolver.Rejected.class, result);
    }

    private void connectingEnvironment(EnvironmentBinding environment) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      environmentRegistry.tryBind(
          environment.environmentName(), connection, NOW, Duration.ofSeconds(60));
    }

    private void readyEnvironment(EnvironmentBinding environment) {
      readyEnvironment(environment, List.of());
    }

    private void readyEnvironment(EnvironmentBinding environment, List<String> skills) {
      readyEnvironment(
          environment,
          skills,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"));
    }

    private void readyEnvironment(
        EnvironmentBinding environment,
        List<String> skills,
        DaemonEnvironmentInfo environmentInfo) {
      readyEnvironmentWithSkills(
          environment,
          skills.stream()
              .map(name -> new DaemonSkillDescriptor(name, name + " description"))
              .toList(),
          environmentInfo,
          NOW);
    }

    private void readyEnvironmentWithSkills(
        EnvironmentBinding environment, List<DaemonSkillDescriptor> skills) {
      readyEnvironmentWithSkills(
          environment,
          skills,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          NOW);
    }

    private void staleEnvironment(
        EnvironmentBinding environment, DaemonEnvironmentInfo environmentInfo) {
      readyEnvironmentWithSkills(
          environment, List.of(), environmentInfo, NOW.minus(Duration.ofSeconds(61)));
    }

    private void readyEnvironmentWithSkills(
        EnvironmentBinding environment,
        List<DaemonSkillDescriptor> skills,
        DaemonEnvironmentInfo environmentInfo,
        Instant lastSeenAt) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      environmentRegistry.tryBind(
          environment.environmentName(), connection, lastSeenAt, Duration.ofSeconds(60));
      environmentRegistry.updateCapabilities(
          environment.environmentName(),
          connection,
          new DaemonCapabilities(DaemonCapabilities.VERSION, environmentInfo, skills, List.of()),
          lastSeenAt);
      environmentRegistry.markReady(environment.environmentName(), connection, lastSeenAt);
    }
  }
}

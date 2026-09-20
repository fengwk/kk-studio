package fun.fengwk.kkstudio.platform.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContextFragment;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
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
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.tool.CompositeRuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.harness.tool.HarnessToolCatalogAdapter;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.project.tool.ProjectHarnessContributor;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRole;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleContextProjector;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleTool;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleToolSelector;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleToolService;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleToolType;
import fun.fengwk.kkstudio.platform.project.tool.ProjectThreadOwnerResolver;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DatabaseTurnResolver 契约：精确的 branch 引用（无回退）、不可变的 EnvironmentId 路由、严格有序的工具/skill 能力、唯一的 registry
 * Variant 请求预设、语义化消息投影、缓存终结与基础设施异常透传。
 */
class DatabaseTurnResolverTest {

  private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 100L);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static final EnvironmentId ENV_A =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId ENV_B =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final EnvironmentId ENV_MISSING =
      EnvironmentId.parse("33333333-3333-3333-3333-333333333333");

  /** Branch settings 冻结的 Environment name：resolver 只按它查库解析，绝不读 Agent definition。 */
  private static final String ENV_A_NAME = "env-a";

  private static final String ENV_B_NAME = "env-b";
  private static final String ENV_MISSING_NAME = "env-missing";

  /** 测试用全局目录 Skill 名称与描述对。 */
  private static SkillName skillName(String name, String description) {
    return new SkillName(name, description);
  }

  private record SkillName(String name, String description) {}

  @Test
  void resolvesExactBranchModelReferencesWithoutFallback() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    // Agent 自带的 provider/model/variant 引用是旧数据，绝不能被读取。
    fixture.agent.setModelProviderName("old-provider");
    fixture.agent.setModelName("old-model");
    fixture.agent.setVariant("old-variant");
    when(fixture.providers.getByName("old-provider")).thenReturn(null);
    TurnResolver.Resolved resolved = fixture.resolvedResult(fixture.path(settings("custom")), null);
    ModelRequestSpec requestSpec = resolved.spec();

    assertEquals("provider", requestSpec.model().providerName());
    assertEquals("model", requestSpec.model().modelName());
    assertEquals(new UUID(0L, 42L), requestSpec.providerConnectionGenerationId());
    // 上游 wire 标识来自 AgentModel.modelId，与 catalog 逻辑名相互独立。
    assertEquals("wire-model", requestSpec.model().modelId());
    assertEquals(Set.of(ModelInputModality.TEXT), requestSpec.model().inputModalities());
    assertEquals("custom", requestSpec.variant().id());
    assertEquals("medium", requestSpec.variant().reasoningEffort());
    assertEquals(4096, resolved.contextWindow());
    // 输出预算只来自 model 级 limit.output 与剩余上下文；variant 不再携带任何输出控制。
    assertEquals(1024, resolved.maxOutputTokens());
    assertEquals(resolved.maxOutputTokens(), requestSpec.outputTokens());
  }

  @Test
  void neverFallsBackVariantToModelDefault() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    TurnResolver.Rejected rejected = fixture.rejected(fixture.path(settings("missing")));

    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.error().code());
    assertEquals(
        "model variant not found: provider/model variant=missing", rejected.error().message());
  }

  @Test
  void agentToolsComeFromLatestAgentConfig() {
    // 最新 Agent 声明 read，工具选择不再依赖 branch settings。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    assertEquals(
        List.of("read"),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());

    // 最新 Agent 已移除工具时，branch settings 不得继续扩权。
    fixture = new Fixture(List.of(), List.of(), List.of());
    requestSpec = fixture.resolved(fixture.path(settings("default")));
    assertEquals(
        List.of(),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
  }

  @Test
  void rejectsMissingCatalogResourcesAndInvalidConfig() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingAgent();
    assertEquals(
        "agent not found: assistant",
        fixture.rejected(fixture.path(settings("default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingProvider();
    assertEquals(
        "provider not found: provider",
        fixture.rejected(fixture.path(settings("default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingModel();
    assertEquals(
        "model not found: provider/model",
        fixture.rejected(fixture.path(settings("default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.provider.setProviderType(null);
    assertEquals(
        "provider type must not be null",
        fixture.rejected(fixture.path(settings("default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.provider.setConnectionGenerationId(null);
    assertEquals(
        "provider connection generation id must not be null",
        fixture.rejected(fixture.path(settings("default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failModelConfig(new IllegalArgumentException("broken model config"));
    assertEquals(
        "invalid model configuration: broken model config",
        fixture.rejected(fixture.path(settings("default"))).error().message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failAgentConfig(new IllegalStateException("broken agent config"));
    assertEquals(
        "invalid agent configuration: broken agent config",
        fixture.rejected(fixture.path(settings("default"))).error().message());

    fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            false);
    assertEquals(
        "provider factory not found for provider (OPENAI)",
        fixture.rejected(fixture.path(settings("default"))).error().message());
  }

  @Test
  void rejectsToolsWhenModelDoesNotSupportTools() {
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.modelSupportsTools(false);
    fixture.readyEnvironment(ENV_A);

    TurnResolver.Rejected rejected = fixture.rejected(fixture.path(settings("default")));

    assertEquals("model does not support tools: provider/model", rejected.error().message());
  }

  @Test
  void resolvesModelOnlyBranchWithoutEnvironment() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(unboundSettings("default")));

    assertEquals(
        List.of(),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(List.of(), requestSpec.skillBindings());
    assertTrue(requestSpec.systemInstruction().contains("date:"));
  }

  @Test
  void alwaysProjectsNoneCurrentEnvironmentIntoProviderRequest() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(unboundSettings("default")));

    assertEquals(
        "agent system prompt\n\n"
            + "<current_environment>\n"
            + "- date: 2026-08-02\n"
            + "</current_environment>",
        instructionText(requestSpec));
  }

  @Test
  void noLiveMetadataUsesServiceClockZoneForNoneAndUnreportedEnvironment() {
    Clock serviceClock = Clock.fixed(NOW, ZoneId.of("America/Los_Angeles"));
    Fixture fixture = new Fixture(List.of(), List.of(), List.of(), serviceClock);

    // 未选择 Environment：空环境上下文只保留服务端时钟派生的日期。
    String none = instructionText(fixture.resolved(fixture.path(unboundSettings("default"))));
    assertFalse(none.contains("- name:"), none);
    assertFalse(none.contains("- system:"), none);
    assertTrue(none.contains("- date: 2026-08-01"), none);
    assertFalse(none.contains("- note:"), none);

    // 已选择 Environment 但既无 live daemon 也无持久报告：回退到服务端时钟，规划仍然成功。
    String unreported = instructionText(fixture.resolved(fixture.path(settings("default"))));
    assertFalse(unreported.contains("- system:"), unreported);
    assertTrue(unreported.contains("- date: 2026-08-01"), unreported);
    assertFalse(unreported.contains("- note:"), unreported);
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

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));
    String prompt = instructionText(requestSpec);

    assertTrue(prompt.contains("- system: linux"), prompt);
    assertTrue(prompt.contains("- date: 2026-08-01"), prompt);
    assertTrue(prompt.contains("- note: Custom &lt;Linux&gt; &amp; tools."), prompt);
  }

  @Test
  void selectedEnvironmentUsesMetadataRegardlessOfHeartbeatReadiness() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.connectingEnvironment(ENV_A);

    String withoutMetadata = instructionText(fixture.resolved(fixture.path(settings("default"))));
    assertFalse(withoutMetadata.contains("- system:"), withoutMetadata);
    assertTrue(withoutMetadata.contains("- date: 2026-08-02"), withoutMetadata);
    assertFalse(withoutMetadata.contains("- note:"), withoutMetadata);

    DaemonEnvironmentInfo environmentInfo =
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.WSL, "Asia/Tokyo", "Stable WSL environment.", "/home/dev");
    Fixture readyFixture = new Fixture(List.of(), List.of(), List.of());
    readyFixture.readyEnvironment(ENV_A, List.of(), environmentInfo);
    String ready = instructionText(readyFixture.resolved(readyFixture.path(settings("default"))));
    Fixture staleFixture = new Fixture(List.of(), List.of(), List.of());
    staleFixture.staleEnvironment(ENV_A, environmentInfo);
    String stale = instructionText(staleFixture.resolved(staleFixture.path(settings("default"))));
    assertEquals(ready, stale);
    assertTrue(stale.contains("- system: wsl"), stale);
    assertTrue(stale.contains("- date: 2026-08-02"), stale);
    assertTrue(stale.contains("- note: Stable WSL environment."), stale);
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

    fixture.resolved(fixture.path(settings("default")));

    assertEquals(1, calls.get());
  }

  @Test
  void keepsEnvironmentToolDeclarationsWhenBranchHasNoEnvironment() {
    // 未选择 Environment 的 branch 仍能规划：环境工具保持声明，但冻结为 null 路由，调用时才失败。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(unboundSettings("default")));

    assertEquals(
        List.of("read"),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertTrue(requestSpec.toolBindings().getFirst().environmentRequired());
    assertNull(requestSpec.toolBindings().getFirst().environmentId());

    // Platform Skill 与 Environment 解耦：未选择 Environment 仍可冻结并声明 load_skill。
    Fixture skillsFixture =
        new Fixture(List.of(), List.of("dev"), List.of(hostDescriptor("load_skill")));
    ModelRequestSpec skillSpec =
        skillsFixture.resolved(skillsFixture.path(unboundSettings("default")));
    assertEquals(
        List.of("dev"), skillSpec.skillBindings().stream().map(SkillBinding::name).toList());
    assertEquals(
        List.of("load_skill"),
        skillSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
  }

  @Test
  void resolvesEnvironmentIdFromImmutableBranchNameAndRejectsUnknownName() {
    // branch 的 Environment name 解析为内部路由身份；缺失 name 时确定性拒绝规划，绝不回退 Agent 配置。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));
    assertEquals(ENV_A, requestSpec.toolBindings().getFirst().environmentId());

    BranchSettings missingName =
        new BranchSettings(
            "assistant", new ModelSelection("provider", "model", "default"), ENV_MISSING_NAME);
    TurnResolver.Rejected rejected = fixture.rejected(fixture.path(missingName));
    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.error().code());
    assertEquals("environment not found: " + ENV_MISSING_NAME, rejected.error().message());
  }

  @Test
  void notReadySelectedEnvironmentDoesNotRejectToolPlanning() {
    // 未 READY 的已选 Environment：按 name 解析出的精确身份绑定，规划成功，不做 READY 检查。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.connectingEnvironment(ENV_A);

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    assertEquals(ENV_A, requestSpec.toolBindings().getFirst().environmentId());
  }

  @Test
  void fixedRequiredEnvironmentIsFrozenAndOnlyConflictsWithAnotherSelectedEnvironment() {
    HarnessCatalog fixedCatalog = fixedEnvironmentCatalog("fixed_tool", ENV_B);

    // branch 选择的正是该固定环境：正常冻结。
    Fixture matching = new Fixture(List.of("fixed_tool"), List.of(), List.of(), fixedCatalog);
    assertEquals(
        ENV_B,
        matching
            .resolved(
                matching.path(
                    new BranchSettings(
                        "assistant",
                        new ModelSelection("provider", "model", "default"),
                        ENV_B_NAME)))
            .toolBindings()
            .getFirst()
            .environmentId());

    // branch 选择了另一个非 null 环境：确定性拒绝规划。
    Fixture conflicting = new Fixture(List.of("fixed_tool"), List.of(), List.of(), fixedCatalog);
    TurnResolver.Rejected rejected = conflicting.rejected(conflicting.path(settings("default")));
    assertEquals(
        "tool fixed_tool requires environment " + ENV_B + " but branch has environment " + ENV_A,
        rejected.error().message());

    // branch 未选择环境：仍冻结 null，调用时得到 ENVIRONMENT_NOT_SELECTED，而不是规划失败。
    Fixture unbound = new Fixture(List.of("fixed_tool"), List.of(), List.of(), fixedCatalog);
    ModelRequestSpec unboundSpec = unbound.resolved(unbound.path(unboundSettings("default")));
    assertTrue(unboundSpec.toolBindings().getFirst().environmentRequired());
    assertNull(unboundSpec.toolBindings().getFirst().environmentId());
  }

  @Test
  void latestSnapshotAgentReferenceWinsOverOlderValidAgent() {
    // 历史 turn 引用有效 agent（assistant 已注册），最新 turn 引用缺失 agent：
    // 只按最新快照的精确名称解析并拒绝，绝不回看更旧 turn 的有效 agent（无历史 repository 查询）。
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings firstTurn = settings("default").withAgentName("assistant");
    BranchSettings latestTurn = settings("default").withAgentName("ghost");

    TurnResolver.Rejected rejected = fixture.rejected(multiTurnPath(firstTurn, latestTurn));

    assertEquals("agent not found: ghost", rejected.error().message());
  }

  @Test
  void environmentBindingComesOnlyFromLatestBranchSettingsName() {
    // 未选择 Environment 的 branch：环境工具保持声明但冻结 null 路由，历史参数不能提供隐式绑定。
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.readyEnvironment(ENV_A);

    ModelRequestSpec unbound =
        fixture.resolved(multiTurnPath(unboundSettings("default"), unboundSettings("default")));
    assertEquals(
        List.of("read"),
        unbound.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertNull(unbound.toolBindings().getFirst().environmentId());

    // 最新 turn 选择 ENV_A_NAME：按 name 解析出的精确身份绑定，绝不回看更旧 settings。
    Fixture rebound = new Fixture(List.of("read"), List.of(), List.of());
    rebound.readyEnvironment(ENV_A);
    ModelRequestSpec bound =
        rebound.resolved(multiTurnPath(unboundSettings("default"), settings("default")));
    assertEquals(ENV_A, bound.toolBindings().getFirst().environmentId());

    // 历史 turn 选择过的 name 不能为最新未选择环境提供隐式绑定。
    Fixture cleared = new Fixture(List.of("read"), List.of(), List.of());
    cleared.readyEnvironment(ENV_A);
    ModelRequestSpec clearedSpec =
        cleared.resolved(multiTurnPath(settings("default"), unboundSettings("default")));
    assertNull(clearedSpec.toolBindings().getFirst().environmentId());
  }

  @Test
  void skillsRequireTheSelectedBranchEnvironmentNameToExist() {
    // name 无法解析时 skills 与工具同样确定性拒绝规划。
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of(hostDescriptor("load_skill")));
    assertEquals(
        "environment not found: " + ENV_MISSING_NAME,
        fixture
            .rejected(
                fixture.path(
                    new BranchSettings(
                        "assistant",
                        new ModelSelection("provider", "model", "default"),
                        ENV_MISSING_NAME)))
            .error()
            .message());
  }

  /** 测试意图：即使 Daemon 离线（无 live connection），全局目录 Skill 仍能完成规划并冻结完整事实；环境块只按连接行保留事实渲染。 */
  @Test
  void skillsPlanningSucceedsFromGlobalCatalogWhenDaemonIsOffline() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of("dev"),
            List.of(hostDescriptor("load_skill")),
            Clock.fixed(NOW, ZoneOffset.UTC));

    fixture.publishEnvironment(ENV_A);
    when(fixture.environmentRegistry.find(ENV_A)).thenReturn(Optional.empty());
    when(fixture.environmentRegistry.hasReadyLease(ENV_A)).thenReturn(false);

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));
    assertEquals(
        List.of(new SkillBinding("dev", "test-package", "1.0.0", "dev description")),
        requestSpec.skillBindings());
    assertFalse(instructionText(requestSpec).contains("- system:"));
  }

  /** 测试意图：环境块只取自连接行保留的宿主 metadata；无连接行时不伪造 OS/note。 */
  @Test
  void environmentContextComesOnlyFromRetainedHostMetadata() {
    Fixture live = new Fixture(List.of(), List.of(), List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
    live.readyEnvironment(ENV_A);
    ModelRequestSpec liveSpec = live.resolved(live.path(settings("default")));
    assertTrue(instructionText(liveSpec).contains("- system: linux"));

    Fixture offline =
        new Fixture(List.of(), List.of(), List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
    offline.connectingEnvironment(ENV_A);
    ModelRequestSpec offlineSpec = offline.resolved(offline.path(settings("default")));
    assertFalse(instructionText(offlineSpec).contains("- system:"));
    assertFalse(instructionText(offlineSpec).contains("- note:"));
  }

  /** 测试意图：CONNECTING 连接行保留的最近一次 READY 宿主 metadata 仍进入环境块（断线不清空）。 */
  @Test
  void connectingEnvironmentStillProjectsRetainedHostMetadata() {
    Fixture fixture =
        new Fixture(List.of(), List.of(), List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
    fixture.connectingEnvironmentWithRetainedMetadata(ENV_A);

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    assertTrue(instructionText(requestSpec).contains("- system: wsl"));
    assertTrue(instructionText(requestSpec).contains("- note: Retained note"));
  }

  @Test
  void routesByExactEnvironmentIdAndNeverFallsBack() {
    // 两个独立不可变 name；branch 只认 settings 中那一个 name，绝不回看更旧 settings 或 fallback。
    Fixture fixture =
        new Fixture(List.of("bash"), List.of("dev-b"), List.of(hostDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev-a"));
    fixture.readyEnvironment(ENV_B, List.of("dev-b"));
    BranchSettings settings =
        new BranchSettings(
            "assistant", new ModelSelection("provider", "model", "default"), ENV_B_NAME);

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings));

    assertEquals(
        List.of("bash", "load_skill"),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    List<EnvironmentId> boundRoutes =
        requestSpec.toolBindings().stream().map(ToolBinding::environmentId).toList();
    assertEquals(ENV_B, boundRoutes.get(0));
    assertNull(boundRoutes.get(1));
    for (int i = 2; i < boundRoutes.size(); i++) {
      assertNull(boundRoutes.get(i));
    }
    assertEquals(
        List.of(new SkillBinding("dev-b", "test-package", "1.0.0", "dev-b description")),
        requestSpec.skillBindings());
  }

  @Test
  void bindsToolsInExactLatestAgentOrder() {
    Fixture fixture =
        new Fixture(
            List.of("bash", "create_goal", "read"),
            List.of(),
            List.of(hostDescriptor("create_goal")));
    fixture.readyEnvironment(ENV_A);
    BranchSettings settings = settings("default");

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings));

    List<String> boundNames =
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList();
    assertEquals(List.of("bash", "create_goal", "read"), boundNames);
    assertEquals(
        List.of(true, false, true),
        requestSpec.toolBindings().stream().map(ToolBinding::environmentRequired).toList());
    // Provider tools 与 bindings 一一对应且顺序一致。
    assertEquals(List.of("bash", "create_goal", "read"), boundNames);

    Fixture missingFixture =
        new Fixture(List.of("missing"), List.of(), List.of(), HarnessCatalog.from(List.of()));
    assertEquals(
        "tool not found: missing",
        missingFixture.rejected(missingFixture.path(settings("default"))).error().message());
  }

  @Test
  void freezesContributorProvenanceAndProjectsBranchScopedGoalContext() {
    Tool dummyLoadSkill = mock(Tool.class);
    when(dummyLoadSkill.descriptor()).thenReturn(hostDescriptor("load_skill"));
    when(dummyLoadSkill.requirements()).thenReturn(ToolRequirements.none());
    Tool dummyTask = mock(Tool.class);
    when(dummyTask.descriptor()).thenReturn(hostDescriptor("task"));
    when(dummyTask.requirements()).thenReturn(ToolRequirements.none());
    BuiltinHarnessContributor builtin = new BuiltinHarnessContributor(dummyLoadSkill, dummyTask);
    HarnessCatalog catalog = HarnessCatalog.from(List.of(builtin));
    Fixture fixture = new Fixture(List.of("create_goal"), List.of(), List.of(), catalog);
    BranchSettings settings = settings("default");
    EntryPath path =
        new EntryPath(
            List.of(
                new Entry(id(1), SESSION_ID, null, new RootPayload(settings), NOW),
                new Entry(
                    id(2),
                    SESSION_ID,
                    id(1),
                    new CustomEntryPayload(
                        "builtin",
                        "goal.state",
                        1,
                        "{\"objective\":\"ship\",\"tokenBudget\":null,\"status\":\"active\","
                            + "\"reason\":null,\"createdAt\":\""
                            + NOW
                            + "\",\"updatedAt\":\""
                            + NOW
                            + "\"}"),
                    NOW)));

    ModelRequestSpec requestSpec = fixture.resolved(path);

    ToolBinding binding = requestSpec.toolBindings().getFirst();
    assertEquals("builtin", binding.contributor().contributorId());
    assertEquals("goal.create", binding.contributor().localName());
    assertEquals("goal.state", binding.contributor().stateAccesses().getFirst().customType());
    assertTrue(
        requestSpec.systemInstruction().contains("\"objective\":\"ship\""),
        requestSpec.systemInstruction());
  }

  @Test
  void derivesLoadSkillFromLatestAgentSkills() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of(hostDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    assertEquals(
        List.of("load_skill"),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(
        List.of(new SkillBinding("dev", "test-package", "1.0.0", "dev description")),
        requestSpec.skillBindings());
  }

  @Test
  void bindsSkillsExactlyFromSelectedEnvironment() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of(hostDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    assertEquals(
        List.of("load_skill"),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertFalse(requestSpec.toolBindings().getFirst().environmentRequired());
    assertEquals(
        List.of(new SkillBinding("dev", "test-package", "1.0.0", "dev description")),
        requestSpec.skillBindings());

    // 全局目录缺少该 Skill：不静默丢弃，typed 拒绝。
    fixture = new Fixture(List.of(), List.of("dev"), List.of(hostDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of());
    fixture.missingGlobalSkills();
    assertEquals(
        "agent skill not found in the global catalog: dev",
        fixture.rejected(fixture.path(settings("default"))).error().message());
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
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true,
            HarnessCatalog.from(List.of()));
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    assertEquals(
        "tool not found: load_skill",
        fixture.rejected(fixture.path(settings("default"))).error().message());
  }

  @Test
  void ignoresStaleLoadSkillWhenLatestAgentHasNoSkills() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(hostDescriptor("load_skill")),
            Set.of("load_skill"),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    assertEquals(
        List.of(),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertEquals(List.of(), requestSpec.skillBindings());
  }

  @Test
  void freezesAllowedSubagentsAndComposesTaskPrompt() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review <carefully> & report.");

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    assertEquals(
        List.of(new SubagentBinding("reviewer", "Review <carefully> & report.")),
        requestSpec.subagentBindings());
    assertEquals(
        List.of(TaskTool.NAME),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertFalse(requestSpec.toolBindings().getFirst().environmentRequired());
    String system = instructionText(requestSpec);
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
        requestSpec.subagentBindings());
  }

  @Test
  void taskDelegationComesFromLatestAllowlistOnly() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review");

    ModelRequestSpec withTask = fixture.resolved(fixture.path(settings("default")));
    assertEquals(List.of(new SubagentBinding("reviewer", "Review")), withTask.subagentBindings());
    assertEquals(
        List.of(TaskTool.NAME),
        withTask.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());

    fixture.agentConfig.setSubagents(List.of());
    ModelRequestSpec withoutTask = fixture.resolved(fixture.path(settings("default")));
    assertEquals(List.of(), withoutTask.subagentBindings());
    assertEquals(
        List.of(),
        withoutTask.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
    assertFalse(instructionText(withoutTask).contains("subagent"));
  }

  /**
   * 测试意图：验证 task declaration 与冻结的 subagent binding 只由非空 allowlist 决定，绝不按 Session depth 动态移除；递归深度是
   * task 调用期 gate，由 DatabaseSubagentRunner 拒绝。
   */
  @Test
  void keepsTaskDeclarationAndSubagentBindingsAtMaxDepth() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review");

    // maxDepth=2：depth=2 的 subagent session 已到达部署上限，但规划仍然声明 task 并冻结 binding。
    EntryPath depthLimited =
        new EntryPath(
            List.of(
                new Entry(
                    id(1),
                    SESSION_ID,
                    null,
                    new RootPayload(
                        settings("default"), new SubagentContext(id(90), id(80), id(70), 2)),
                    NOW)));

    ModelRequestSpec requestSpec = fixture.resolved(depthLimited);
    assertEquals(
        List.of(new SubagentBinding("reviewer", "Review")), requestSpec.subagentBindings());
    assertEquals(
        List.of(TaskTool.NAME),
        requestSpec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());
  }

  /** allowlist 指向不存在的 subagent 时立即拒绝，绝不静默跳过。 */
  @Test
  void rejectsMissingSubagentWithoutSilentFallback() {
    Fixture fixture = taskFixture();
    fixture.agentConfig.setSubagents(List.of("ghost"));
    assertEquals(
        "subagent not found: ghost",
        fixture.rejected(fixture.path(settings("default"))).error().message());
  }

  /** task 必须来自统一 catalog；catalog 缺少 task 时立即拒绝。 */
  @Test
  void rejectsTaskWhenCatalogOmitsTask() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true,
            HarnessCatalog.from(List.of()));
    fixture.agentConfig.setSubagents(List.of("reviewer"));
    fixture.subagent("reviewer", "Review");
    assertEquals(
        "tool not found: task",
        fixture.rejected(fixture.path(settings("default"))).error().message());
  }

  @Test
  void usesCatalogVariantReasoningEffortDirectly() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("custom")));
    assertEquals("custom", requestSpec.variant().id());
    // 所选 catalog variant 的 reasoningEffort 原样生效，不存在运行时 override。
    assertEquals("medium", requestSpec.variant().reasoningEffort());
    // variant 只承载 reasoning effort：输出预算由 model 级 limit.output 决定。
    assertEquals(1024, requestSpec.outputTokens());

    requestSpec = fixture.resolved(fixture.path(settings("default")));
    assertNull(requestSpec.variant().reasoningEffort());

    // variant 携带 reasoningEffort 时要求模型支持 reasoning。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelSupportsReasoning(false);
    assertEquals(
        "model does not support reasoning: provider/model",
        fixture.rejected(fixture.path(settings("custom"))).error().message());

    // reasoningEffort 为 null 的 variant 即使模型不支持 reasoning 也合法。
    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelSupportsReasoning(false);
    requestSpec = fixture.resolved(fixture.path(settings("default")));
    assertNull(requestSpec.variant().reasoningEffort());
  }

  @Test
  void projectsSemanticMessagesInRootToHeadOrderIgnoringBoundariesAndErrors() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of(hostDescriptor("load_skill")));
    fixture.readyEnvironment(ENV_A, List.of("dev"));
    BranchSettings settings = settings("default");

    EntryPath path = multiTurnPath(settings);
    ModelRequestSpec requestSpec = fixture.resolved(path);

    // 系统指令不再以 SYSTEM 消息进入会话投影：它只在 systemInstruction 中，messages 只含对话。
    String instruction = requestSpec.systemInstruction();
    assertTrue(instruction.startsWith("agent system prompt"), instruction);
    // 确定性空行拼接顺序：Agent 正文 → Environment → Skills，段间恰为空行分隔。
    int environmentAt = instruction.indexOf("<current_environment>");
    int skillsAt = instruction.indexOf("<available_skills>");
    assertTrue(environmentAt > "agent system prompt".length(), instruction);
    assertTrue(skillsAt > environmentAt, instruction);
    assertTrue(
        instruction.contains("</current_environment>\n\nThe following skills provide"),
        instruction);
    assertTrue(instruction.contains("<name>dev</name>"), instruction);

    List<ProviderMessage> messages = materialized(path, requestSpec);
    assertEquals(3, messages.size());
    assertEquals(ProviderMessageRole.USER, messages.get(0).role());
    assertEquals("first user", textOf(messages.get(0)));
    assertEquals(ProviderMessageRole.ASSISTANT, messages.get(1).role());
    assertEquals("partial text", textOf(messages.get(1)));
    assertEquals(ProviderMessageRole.USER, messages.get(2).role());
    assertEquals("custom note", textOf(messages.get(2)));
  }

  /** failed-attempt 与 terminal error partial 都是 UI/audit 事实，绝不进入 Provider messages。 */
  @Test
  void excludesModelAttemptFailuresAndAssistantErrorPartialsFromProviderContext() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings("default");

    EntryPath path = failedAttemptPath(settings);
    ModelRequestSpec requestSpec = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, requestSpec);
    // 会话投影只含可见对话消息；系统指令单独承载，绝不作为 SYSTEM 消息出现在 messages 中。
    assertEquals(1, messages.size());
    assertEquals(ProviderMessageRole.USER, messages.get(0).role());
    assertEquals("visible user", textOf(messages.get(0)));
    String projected = messages.stream().map(DatabaseTurnResolverTest::textOf).toList().toString();
    assertFalse(projected.contains("retry-only-secret"));
    assertFalse(projected.contains("terminal-only-secret"));
    assertFalse(projected.contains("provider exploded"));
  }

  @Test
  void resolvesDynamicMcpToolAndPreservesStaticProjectors() {
    // 意图：验证动态 MCP 工具能通过 RuntimeToolCatalog 正常解析到 ModelRequestSpec 的 tool bindings 中，同时
    // HarnessCatalog 中的静态 context projectors 依然正常工作
    McpServerRepository repo = mock(McpServerRepository.class);
    McpToolCatalog mcpCatalog = new McpToolCatalog(repo, mock(ExecutorService.class));

    McpServer server = new McpServer();
    server.setName("srv");
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    server.setEnabled(true);
    server.setVersion(1L);
    server.setUrl("http://localhost:8080/mcp");
    server.setHeaders(Map.of());
    server.setTimeoutMillis(5000L);

    McpTool mcpTool = new McpTool();
    mcpTool.setName("mcp_srv_echo");
    mcpTool.setServerName("srv");
    mcpTool.setSourceName("echo");
    mcpTool.setDescription("echo tool");
    mcpTool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");

    when(repo.getTool("mcp_srv_echo")).thenReturn(Optional.of(mcpTool));
    when(repo.getByName("srv")).thenReturn(Optional.of(server));
    when(repo.listAllServers()).thenReturn(List.of(server));
    when(repo.listTools("srv")).thenReturn(List.of(mcpTool));

    HarnessContributor projectorContributor =
        HarnessContributor.of(
            new ContributorDescriptor(new ContributorId("proj"), "Proj", "1", Set.of()),
            registrar ->
                registrar.registerContextProjector(
                    "meta",
                    branch -> List.of(new ContextFragment("projected-context-fragment")),
                    0));
    HarnessCatalog catalogWithProjector = HarnessCatalog.from(List.of(projectorContributor));

    RuntimeToolCatalog composite =
        new CompositeRuntimeToolCatalog(
            List.of(new HarnessToolCatalogAdapter(catalogWithProjector), mcpCatalog));

    Fixture fixture =
        new Fixture(
            List.of("mcp_srv_echo"),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true,
            catalogWithProjector,
            composite,
            Clock.fixed(NOW, ZoneOffset.UTC));

    EntryPath path = fixture.path(settings("default"));
    ModelRequestSpec spec = fixture.resolved(path);

    // 验证 MCP 工具已成功解析为唯一 ToolBinding。
    assertEquals(1, spec.toolBindings().size());
    assertEquals(
        List.of("mcp_srv_echo"),
        spec.toolBindings().stream().map(b -> b.descriptor().name()).toList());
    ToolBinding toolBinding = spec.toolBindings().get(0);
    assertEquals("mcp_srv_echo", toolBinding.definition().descriptor().name());

    // 验证 static context projector 依旧被 HarnessCatalog 正确投影进唯一系统指令
    assertTrue(
        spec.systemInstruction().contains("projected-context-fragment"),
        "systemInstruction must contain text from static context projector");
  }

  @Test
  void plansPersistedMcpToolsOfUnavailableServersAndIsolatesOnlyThatServer() {
    // 意图：MCP 持久化定义与 server 可用性解耦。server 被禁用、未验证或发现失败时，其已持久化工具仍必须
    // 规划为冻结 ToolBinding（而不是 PLANNING_FAILED / tool-not-found）；可用性只留到调用期 fail closed。
    // 同一 Agent 的兄弟工具（同一不可用 server 下的另一个工具，以及静态工具 read）不受影响。
    HarnessCatalog staticCatalog =
        HarnessCatalog.from(List.of(defaultBuiltinContributor(), defaultProjectContributor()));

    for (McpDiscoveryStatus status : McpDiscoveryStatus.values()) {
      for (boolean enabled : List.of(true, false)) {
        McpServer unavailableServer = mcpServer(status, enabled);
        McpTool echo = mcpTool("mcp_srv_echo", "echo");
        McpTool sibling = mcpTool("mcp_srv_sibling", "sibling");

        McpServerRepository repo = mock(McpServerRepository.class);
        when(repo.getByName("srv")).thenReturn(Optional.of(unavailableServer));
        when(repo.getTool("mcp_srv_echo")).thenReturn(Optional.of(echo));
        when(repo.getTool("mcp_srv_sibling")).thenReturn(Optional.of(sibling));
        when(repo.listAllServers()).thenReturn(List.of(unavailableServer));
        when(repo.listTools("srv")).thenReturn(List.of(echo, sibling));

        McpToolCatalog mcpCatalog = new McpToolCatalog(repo, mock(ExecutorService.class));
        RuntimeToolCatalog composite =
            new CompositeRuntimeToolCatalog(
                List.of(new HarnessToolCatalogAdapter(staticCatalog), mcpCatalog));

        // 可选面（UI/配置选择）仍严格按 enabled + AVAILABLE 收窄：该 server 不满足时两个工具都不可见，
        // 但它们依然在下面的 planning 中被解析并冻结。
        boolean selectable = enabled && status == McpDiscoveryStatus.AVAILABLE;
        assertEquals(
            selectable ? List.of("mcp_srv_echo", "mcp_srv_sibling") : List.of(),
            mcpCatalog.selectableTools().stream()
                .map(contribution -> contribution.definition().descriptor().name())
                .toList(),
            "status=" + status + " enabled=" + enabled);

        Fixture fixture =
            new Fixture(
                List.of("mcp_srv_echo", "read", "mcp_srv_sibling"),
                List.of(),
                List.of(),
                Set.of(),
                ProviderType.OPENAI,
                ProviderType.OPENAI,
                PromptCacheCapability.unsupported(),
                true,
                staticCatalog,
                composite,
                Clock.fixed(NOW, ZoneOffset.UTC));
        fixture.readyEnvironment(ENV_A);

        ModelRequestSpec spec = fixture.resolved(fixture.path(settings("default")));

        assertEquals(
            List.of("mcp_srv_echo", "read", "mcp_srv_sibling"),
            spec.toolBindings().stream().map(binding -> binding.descriptor().name()).toList(),
            "status=" + status + " enabled=" + enabled);
        ToolBinding echoBinding = spec.toolBindings().get(0);
        assertEquals(
            McpToolCatalog.CONTRIBUTOR_ID.value(), echoBinding.contributor().contributorId());
        assertEquals("mcp-srv-echo", echoBinding.contributor().localName());
        assertEquals(5000L, echoBinding.definition().descriptor().defaultTimeout().toMillis());
        ToolBinding siblingBinding = spec.toolBindings().get(2);
        assertEquals("mcp-srv-sibling", siblingBinding.contributor().localName());
      }
    }

    // 反例边界：工具行真正不存在时 planning 仍必须 fail closed，绝不因可用性与定义解耦而合成任何定义。
    McpServerRepository emptyRepo = mock(McpServerRepository.class);
    when(emptyRepo.getTool("mcp_srv_echo")).thenReturn(Optional.empty());
    RuntimeToolCatalog emptyComposite =
        new CompositeRuntimeToolCatalog(
            List.of(
                new HarnessToolCatalogAdapter(staticCatalog),
                new McpToolCatalog(emptyRepo, mock(ExecutorService.class))));
    Fixture missingToolFixture =
        new Fixture(
            List.of("mcp_srv_echo"),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true,
            staticCatalog,
            emptyComposite,
            Clock.fixed(NOW, ZoneOffset.UTC));
    missingToolFixture.readyEnvironment(ENV_A);
    assertEquals(
        "tool not found: mcp_srv_echo",
        missingToolFixture
            .rejected(missingToolFixture.path(settings("default")))
            .error()
            .message());
  }

  private static McpServer mcpServer(McpDiscoveryStatus status, boolean enabled) {
    McpServer server = new McpServer();
    server.setName("srv");
    server.setDiscoveryStatus(status);
    server.setEnabled(enabled);
    server.setVersion(1L);
    server.setUrl("http://localhost:8080/mcp");
    server.setHeaders(Map.of());
    server.setTimeoutMillis(5000L);
    return server;
  }

  private static McpTool mcpTool(String name, String sourceName) {
    McpTool tool = new McpTool();
    tool.setName(name);
    tool.setServerName("srv");
    tool.setSourceName(sourceName);
    tool.setDescription(sourceName + " tool");
    tool.setInputSchemaJson(
        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}");
    return tool;
  }

  @Test
  void escapesXmlInAvailableSkillsSection() {
    Fixture fixture =
        new Fixture(List.of(), List.of("a&b<c>"), List.of(hostDescriptor("load_skill")));
    fixture.readyEnvironmentWithSkills(ENV_A, List.of(skillName("a&b<c>", "d&e")));

    ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));

    String system = instructionText(requestSpec);
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
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);
    assertEquals(
        PromptCacheRetention.NONE,
        fixture.resolved(fixture.path(settings("default"))).cacheControl().retention());

    fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            true);
    ModelRequestSpec affinity = fixture.resolved(fixture.path(settings("default")));
    assertEquals(PromptCacheRetention.SHORT, affinity.cacheControl().retention());
    assertTrue(affinity.cacheControl().affinityKey().startsWith("pc2-"));

    fixture =
        new Fixture(
            List.of("create_goal"),
            List.of(),
            List.of(hostDescriptor("create_goal")),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.breakpoints(
                Set.of(PromptCacheRetention.SHORT),
                Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            true);
    ModelRequestSpec breakpoints = fixture.resolved(fixture.path(settings("default")));
    assertEquals(PromptCacheRetention.SHORT, breakpoints.cacheControl().retention());
    assertEquals(
        Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        breakpoints.cacheControl().breakpoints());
  }

  /**
   * 意图：Responses 缺省为 AUTOMATIC，planner 规划产出 ProviderCacheControl.none()； 而显式 LEGACY 模式下必须冻结稳定
   * affinity key（同 session 同前缀稳定、跨 session 不同）。
   */
  @Test
  void defaultResponsesConfigResolvesAutomaticAndLegacyFreezesStableAffinityCacheKey() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI_RESPONSES,
            ProviderType.OPENAI_RESPONSES,
            // 与生产 OpenAiResponsesProviderFactory 完全一致的能力解析（configJson 为 null 即缺省配置）
            OpenAiResponsesProviderAdapter.resolvePromptCacheCapability(null),
            true);
    EntryPath path = fixture.path(settings("default"));

    ModelRequestSpec defaultSpec = fixture.resolved(path);
    assertEquals(PromptCacheRetention.NONE, defaultSpec.cacheControl().retention());
    assertEquals(ProviderCacheControl.none(), defaultSpec.cacheControl());

    // 显式 LEGACY 配置下：planner 必须冻结稳定 affinity key
    String legacyConfig = "{\"openAiPromptCacheMode\":\"LEGACY\"}";
    fixture.provider.setConfigJson(legacyConfig);
    ProviderFactory factory = fixture.resolverProviderFactory();
    when(factory.promptCacheCapability(legacyConfig))
        .thenReturn(OpenAiResponsesProviderAdapter.resolvePromptCacheCapability(legacyConfig));

    ModelRequestSpec first = fixture.resolved(path);
    ModelRequestSpec sameSessionAgain = fixture.resolved(fixture.path(settings("default")));
    assertEquals(PromptCacheRetention.SHORT, first.cacheControl().retention());
    assertTrue(first.cacheControl().affinityKey().startsWith("pc2-"));
    assertEquals(first.cacheControl().affinityKey(), sameSessionAgain.cacheControl().affinityKey());

    // 同前缀但不同 session 必须派生出不同 key，避免跨会话缓存串扰
    EntryPath otherSessionPath =
        new EntryPath(
            List.of(
                new Entry(
                    id(996), new UUID(0L, 995L), null, new RootPayload(settings("default")), NOW)));
    ModelRequestSpec otherSession = fixture.resolved(otherSessionPath);
    assertEquals(PromptCacheRetention.SHORT, otherSession.cacheControl().retention());
    assertNotEquals(first.cacheControl().affinityKey(), otherSession.cacheControl().affinityKey());

    // 同 session 切换 provider 连接代际也必须切 key，避免 endpoint/credential 更新后复用旧身份。
    fixture.provider.setConnectionGenerationId(new UUID(0L, 997L));
    ModelRequestSpec otherConnectionGeneration =
        fixture.resolved(fixture.path(settings("default")));
    assertNotEquals(
        first.cacheControl().affinityKey(), otherConnectionGeneration.cacheControl().affinityKey());
  }

  /** 意图：验证 live turn 规划会正确传递当前 provider 的 configJson 解析动态 promptCacheCapability。 */
  @Test
  void planningPassesProviderConfigJsonToResolvePromptCacheCapability() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.automatic(),
            true);
    String dynamicConfig = "{\"customCache\":true}";
    fixture.provider.setConfigJson(dynamicConfig);

    // 针对指定 configJson 返回带有 SHORT retention 的 affinity 能力
    ProviderFactory factory = fixture.resolverProviderFactory();
    when(factory.promptCacheCapability(dynamicConfig))
        .thenReturn(PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));

    ModelRequestSpec spec = fixture.resolved(fixture.path(settings("default")));
    assertEquals(PromptCacheRetention.SHORT, spec.cacheControl().retention());
    assertTrue(spec.cacheControl().affinityKey().startsWith("pc2-"));
  }

  /** 意图：当 provider 配置导致 PromptCacheCapability 解析抛出异常或返回 null 时，Turn planning 确定性拒绝且不泄漏 config。 */
  @Test
  void planningRejectsInvalidProviderSpecificConfigDeterministically() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.automatic(),
            true);
    String secretConfig = "{\"apiKey\":\"super-secret-token\",\"malformed\":true}";
    fixture.provider.setConfigJson(secretConfig);

    ProviderFactory factory = fixture.resolverProviderFactory();
    when(factory.promptCacheCapability(secretConfig))
        .thenThrow(new IllegalArgumentException("syntax error in super-secret-token"));

    TurnResolver.Rejected rejected = fixture.rejected(fixture.path(settings("default")));
    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.error().code());
    assertEquals(
        "invalid prompt cache configuration for provider: provider", rejected.error().message());
    assertFalse(rejected.error().message().contains("super-secret-token"));
    assertFalse(rejected.error().message().contains("syntax error"));
  }

  @Test
  void propagatesInfrastructureExceptionsInsteadOfRejecting() {
    Fixture missingAgent = new Fixture(List.of(), List.of(), List.of());
    missingAgent.failAgentLookup(new IllegalStateException("db down"));
    assertThrows(
        IllegalStateException.class,
        () ->
            missingAgent.resolver.resolve(THREAD_ID, missingAgent.path(settings("default")), null));

    Fixture missingProvider = new Fixture(List.of(), List.of(), List.of());
    missingProvider.failProviderLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingProvider.resolver.resolve(
                THREAD_ID, missingProvider.path(settings("default")), null));

    Fixture missingModel = new Fixture(List.of(), List.of(), List.of());
    missingModel.failModelLookup(new RuntimeException("db down"));
    assertThrows(
        RuntimeException.class,
        () ->
            missingModel.resolver.resolve(THREAD_ID, missingModel.path(settings("default")), null));
  }

  @Test
  void resolvesEveryProviderTypeDirectlyFromProvider() {
    // ProviderType 是领域对象的唯一类型；每个 factory 必须直接使用同一个值。
    for (ProviderType providerType : ProviderType.values()) {
      Fixture fixture =
          new Fixture(
              List.of(),
              List.of(),
              List.of(),
              Set.of(),
              providerType,
              providerType,
              PromptCacheCapability.unsupported(),
              true);
      // 持久 providerType 直接用于选择当前 ProviderFactory，并冻结到 spec。
      ModelRequestSpec requestSpec = fixture.resolved(fixture.path(settings("default")));
      assertEquals("provider", requestSpec.model().providerName());
      assertEquals("model", requestSpec.model().modelName());
      assertEquals(providerType, requestSpec.providerType());
    }
  }

  @Test
  void resolvedSpecStaysFrozenAfterCatalogAndConfigChange() {
    Fixture fixture =
        new Fixture(List.of("create_goal"), List.of(), List.of(hostDescriptor("create_goal")));
    EntryPath path = fixture.path(settings("custom"));
    ModelRequestSpec frozen = fixture.resolved(path);
    String preamble = instructionText(frozen);
    ModelDescriptor model = frozen.model();
    ModelVariant variant = frozen.variant();
    List<ToolBinding> tools = frozen.toolBindings();
    ProviderRequest before = new ModelRequestMaterializer().materialize(path, frozen);

    fixture.agent.setSystemPrompt("changed system prompt");
    fixture.agentConfig.setTools(List.of());

    // 二次 resolve 证明 mutation 真实生效：live spec 的 systemInstruction 与 tools 都变了，而 frozen spec 不受影响。
    ModelRequestSpec live = fixture.resolved(path);
    assertNotEquals(preamble, instructionText(live));
    assertNotEquals(tools, live.toolBindings());
    assertEquals(
        List.of(),
        live.toolBindings().stream().map(binding -> binding.descriptor().name()).toList());

    assertEquals(preamble, instructionText(frozen));
    assertEquals(preamble, frozen.systemInstruction());
    assertEquals(model, frozen.model());
    assertEquals(variant, frozen.variant());
    assertEquals(tools, frozen.toolBindings());
    assertEquals("custom", frozen.variant().id());
    assertEquals(
        List.of("create_goal"),
        tools.stream().map(binding -> binding.descriptor().name()).toList());
    ProviderRequest after = new ModelRequestMaterializer().materialize(path, frozen);
    assertEquals(before, after);
    // 物化边界把冻结的 instruction 原样带到 Provider 请求，不再合成 SYSTEM 消息。
    assertEquals(preamble, after.systemInstruction());
    assertTrue(after.messages().stream().noneMatch(m -> textOf(m).equals(preamble)));
    assertEquals(List.of("create_goal"), after.tools().stream().map(tool -> tool.name()).toList());
  }

  private static String textOf(ProviderMessage message) {
    StringBuilder text = new StringBuilder();
    for (ProviderContentBlock content : message.contents()) {
      text.append(((ProviderTextBlock) content).text());
    }
    return text.toString();
  }

  /** 唯一系统指令正文：spec.systemInstruction() 即本次请求唯一的系统指令，不存在 preamble 消息列表。 */
  private static String instructionText(ModelRequestSpec spec) {
    return spec.systemInstruction();
  }

  private static List<ProviderMessage> materialized(EntryPath path, ModelRequestSpec spec) {
    return new ModelRequestMaterializer().materialize(path, spec).messages();
  }

  /** BranchSettings 冻结 agent/model 与可空 environmentName；解析出的 EnvironmentId 只能来自该 name。 */
  private static BranchSettings settings(String variant) {
    return new BranchSettings(
        "assistant", new ModelSelection("provider", "model", variant), ENV_A_NAME);
  }

  /** 未选择 Environment 的 branch：环境工具仍需声明并可规划，调用时才失败。 */
  private static BranchSettings unboundSettings(String variant) {
    return new BranchSettings("assistant", new ModelSelection("provider", "model", variant), null);
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
                    CustomMessagePayload.CORE_CONTRIBUTOR_ID,
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

  static BuiltinHarnessContributor defaultBuiltinContributor() {
    Tool dummyLoadSkill = mock(Tool.class);
    when(dummyLoadSkill.descriptor()).thenReturn(hostDescriptor("load_skill"));
    when(dummyLoadSkill.requirements()).thenReturn(ToolRequirements.none());
    Tool dummyTask = mock(Tool.class);
    when(dummyTask.descriptor()).thenReturn(hostDescriptor("task"));
    when(dummyTask.requirements()).thenReturn(ToolRequirements.none());
    return new BuiltinHarnessContributor(dummyLoadSkill, dummyTask);
  }

  static ProjectHarnessContributor defaultProjectContributor() {
    List<ProjectRoleTool> tools =
        Arrays.stream(ProjectRoleToolType.values())
            .map(
                type ->
                    new ProjectRoleTool(
                        type,
                        mock(ProjectThreadOwnerResolver.class),
                        mock(ProjectRoleToolService.class)))
            .toList();
    return new ProjectHarnessContributor(tools);
  }

  private static ToolDescriptor hostDescriptor(String name) {
    return new ToolDescriptor(
        name,
        name + " description",
        name,
        new InputSchema(null, Map.of(), Set.of(), false),
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
    BranchSettings settings = settings("default");
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
    ModelRequestSpec requestSpec = resolved.spec();

    // 切分事实只在 candidate path 的 TURN_START 中冻结；spec 只保留 descriptor/variant。
    assertEquals(Set.of(ModelInputModality.TEXT), requestSpec.model().inputModalities());
    assertEquals(4096, resolved.contextWindow());
    assertEquals(123, resolved.maxOutputTokens());
    // 压缩预算写入 spec.outputTokens，variant 不再承载输出上限。
    assertEquals(123, requestSpec.outputTokens());
    // 压缩 turn 的 spec 冻结摘要 system prompt：唯一指令，不存在 preamble 消息列表。
    assertEquals(CompactionPrompts.summarizationSystemPrompt(), requestSpec.systemInstruction());
    assertEquals(List.of(), requestSpec.toolBindings());
    assertEquals(List.of(), requestSpec.skillBindings());
    assertEquals(List.of(), requestSpec.subagentBindings());
    assertEquals(ProviderCacheControl.none(), requestSpec.cacheControl());
    assertFalse(requestSpec.systemInstruction().contains("<current_environment>"));
    // materializer 从 candidate path 的 CompactionStart 重建摘要 prompt，而不是从 spec 读取切分元数据。
    List<ProviderMessage> providerMessages = materialized(historyPath, requestSpec);
    // 摘要调用只投影一条 USER（conversation + summary prompt）；system 指令走 systemInstruction。
    assertEquals(1, providerMessages.size());
    assertEquals(ProviderMessageRole.USER, providerMessages.get(0).role());
    assertEquals(
        CompactionPrompts.summaryUserPrompt(messages, null), textOf(providerMessages.get(0)));
    // FULL 预算为 min(1024, floor(0.8 * 1024) = 819, removedPrefixTokens=123) = 123。
  }

  /** 输出预算只来自 model 级 limit.output 与剩余上下文：variant 不参与，也不存在任何 variant 级回退。 */
  @Test
  void outputBudgetComesFromModelLimitAndRemainingContext() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelGlobalOutputLimit(600);
    EntryPath path = fixture.path(settings("default"));

    TurnResolver.Resolved resolved = fixture.resolvedResult(path, null);

    // model 全局 limit.output=600 且剩余上下文充足时，预算为 600。
    assertEquals(600, resolved.maxOutputTokens());
    assertEquals(600, resolved.spec().outputTokens());

    // 上下文只剩 37 token 时严格取 remaining，不应用正数钳位或 variant 回退。
    long estimatedInputTokens =
        CompactionPlanner.estimateRequestTokens(path, resolved.spec().systemInstruction());
    fixture.modelLimits(estimatedInputTokens + 37, 600);
    resolved = fixture.resolvedResult(path, null);
    assertEquals(37, resolved.maxOutputTokens());
    assertEquals(37, resolved.spec().outputTokens());

    // 上下文已耗尽时必须明确拒绝，不能伪造 1-token 请求。
    fixture.modelLimits(estimatedInputTokens, 600);
    assertEquals(
        "remaining context leaves no positive output budget: contextWindow="
            + estimatedInputTokens
            + ", estimatedInputTokens="
            + estimatedInputTokens,
        fixture.rejected(path).error().message());

    // 未改写 model limit 时取该 model 自己的 limit.output（1024），与所选 variant 无关。
    Fixture explicit = new Fixture(List.of(), List.of(), List.of());
    assertEquals(1024, explicit.resolved(explicit.path(settings("default"))).outputTokens());
    assertEquals(1024, explicit.resolved(explicit.path(settings("custom"))).outputTokens());
  }

  @Test
  void compactionOutputBudgetUsesActualModelLimitAndRemovedPrefixTokens() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.modelGlobalOutputLimit(600);
    ModelSelection executionModel = settings("default").model();
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

    // 预算先取实际 model 全局 600，再由 outputBudget 阶段公式裁剪；spec.outputTokens 与 Resolved 一致。
    assertEquals(
        123,
        fixture.resolvedResult(fixture.path(settings("default")), fullSmall).maxOutputTokens());
    assertEquals(
        480,
        fixture.resolvedResult(fixture.path(settings("default")), fullLarge).maxOutputTokens());
    assertEquals(
        300, fixture.resolvedResult(fixture.path(settings("default")), prefix).maxOutputTokens());
    assertEquals(
        480, fixture.resolved(fixture.path(settings("default")), fullLarge).outputTokens());
    assertEquals(300, fixture.resolved(fixture.path(settings("default")), prefix).outputTokens());
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
            List.of(new ModelVariant("fallback")),
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
        fixture.resolvedResult(fixture.path(settings("default")), preparation);

    assertEquals("fallback-provider", resolved.spec().model().providerName());
    assertEquals("fallback-model", resolved.spec().model().modelName());
    assertEquals("fallback-model", resolved.spec().model().modelId());
    assertEquals(new UUID(0L, 43L), resolved.spec().providerConnectionGenerationId());
    assertEquals("fallback", resolved.spec().variant().id());
    assertEquals(8192, resolved.contextWindow());
    // fallback model 的 limit.output = 4096 经 FULL 预算公式 floor(0.8 * 4096) = 3276 裁剪。
    assertEquals(3276, resolved.maxOutputTokens());
    assertEquals(3276, resolved.spec().outputTokens());
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
    BranchSettings settings = settings("default");

    EntryPath path = projectionPath(settings, "summary text", id(4));
    ModelRequestSpec requestSpec = fixture.resolved(path);

    // 系统指令不再作为 SYSTEM 消息出现在投影首位；summary wrapper 是第一条 USER。
    assertTrue(requestSpec.systemInstruction().startsWith("agent system prompt"));
    List<ProviderMessage> messages = materialized(path, requestSpec);
    assertEquals(4, messages.size());
    assertEquals(ProviderMessageRole.USER, messages.get(0).role());
    assertEquals(CompactionPrompts.compactedContext("summary text"), textOf(messages.get(0)));
    // 从 cut（ASST1）本身开始保留：ASST1、USER2、ASST2 继续投影；COMPACTION 控制 turn 内部不投影。
    assertEquals("first reply", textOf(messages.get(1)));
    assertEquals("second user", textOf(messages.get(2)));
    assertEquals("second reply", textOf(messages.get(3)));
  }

  @Test
  void compactionInternalMessagesAreSuppressedInProjection() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings("default");

    EntryPath path = stoppedCompactionProjectionPath(settings);
    ModelRequestSpec requestSpec = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, requestSpec);
    // 投影里没有 SYSTEM 消息：messages 只含对话（被停止压缩 turn 内部的 ABORTED 不投影）。
    assertEquals(4, messages.size());
    assertEquals("first user", textOf(messages.get(0)));
    assertEquals("first reply", textOf(messages.get(1)));
    assertEquals("second user", textOf(messages.get(2)));
    assertEquals("second reply", textOf(messages.get(3)));
  }

  /** 损坏的压缩引用必须 fail closed：解析阶段（输出预算需要投影 cut）即确定性抛错，绝不产出静默降级的请求。 */
  @Test
  void corruptCompactionReferencesFailClosedInProjection() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings("default");
    // cut 不在当前路径。
    EntryPath missingCut = projectionPath(settings, "summary", id(999));
    assertThrows(IllegalStateException.class, () -> fixture.resolved(missingCut));
  }

  @Test
  void latestCompleteCompactionWinsOverOlderWrapper() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    BranchSettings settings = settings("default");

    EntryPath path = twoCompactionProjectionPath(settings);
    ModelRequestSpec requestSpec = fixture.resolved(path);

    List<ProviderMessage> messages = materialized(path, requestSpec);
    // 投影里没有 SYSTEM 消息：wrapper summary 是第一条 USER，索引整体前移一位。
    assertEquals(5, messages.size());
    assertEquals(CompactionPrompts.compactedContext("latest summary"), textOf(messages.get(0)));
    // 只有最新压缩的 wrapper；从最新 cut（USER2）起保留。
    assertEquals("second user", textOf(messages.get(1)));
    assertEquals("second reply", textOf(messages.get(2)));
    assertEquals("third user", textOf(messages.get(3)));
    assertEquals("third reply", textOf(messages.get(4)));
  }

  @Test
  void branchWithoutEnvironmentHasNoImplicitTools() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    ModelRequestSpec spec = fixture.resolved(fixture.path(unboundSettings("default")));

    assertEquals(List.of(), spec.toolBindings());
  }

  @Test
  void selectableToolsPreserveOrderAndBindings() {
    Fixture fixture =
        new Fixture(
            List.of("bash", "create_goal", "read"),
            List.of(),
            List.of(hostDescriptor("create_goal")));
    fixture.readyEnvironment(ENV_A);

    ModelRequestSpec spec = fixture.resolved(fixture.path(settings("default")));

    List<String> names = spec.toolBindings().stream().map(b -> b.descriptor().name()).toList();
    assertEquals(List.of("bash", "create_goal", "read"), names);
    assertEquals(
        List.of(true, false, true),
        spec.toolBindings().stream().map(ToolBinding::environmentRequired).toList());
    assertEquals(ENV_A, spec.toolBindings().get(0).environmentId());
    assertNull(spec.toolBindings().get(1).environmentId());
    assertEquals(ENV_A, spec.toolBindings().get(2).environmentId());
  }

  @Test
  void toolBindingEnvironmentNameReflectsBranchEnvironmentSelection() {
    // 1. branch 选择了环境：environmentRequired 的工具冻结环境名，非环境工具冻结 null
    Fixture fixture =
        new Fixture(
            List.of("bash", "create_goal", "read"),
            List.of(),
            List.of(hostDescriptor("create_goal")));
    fixture.readyEnvironment(ENV_A);

    ModelRequestSpec boundSpec = fixture.resolved(fixture.path(settings("default")));
    List<ToolBinding> boundTools = boundSpec.toolBindings();
    assertEquals(3, boundTools.size());
    // bash (environmentRequired) -> ENV_A_NAME
    assertTrue(boundTools.get(0).environmentRequired());
    assertEquals(ENV_A_NAME, boundTools.get(0).environmentName());
    // create_goal (not environmentRequired) -> null
    assertFalse(boundTools.get(1).environmentRequired());
    assertNull(boundTools.get(1).environmentName());
    // read (environmentRequired) -> ENV_A_NAME
    assertTrue(boundTools.get(2).environmentRequired());
    assertEquals(ENV_A_NAME, boundTools.get(2).environmentName());

    // 2. branch 未选择环境：environment-required 工具保持声明但环境名冻结为 null
    ModelRequestSpec unboundSpec = fixture.resolved(fixture.path(unboundSettings("default")));
    List<ToolBinding> unboundTools = unboundSpec.toolBindings();
    assertEquals(3, unboundTools.size());
    assertTrue(unboundTools.get(0).environmentRequired());
    assertNull(unboundTools.get(0).environmentName());
    assertFalse(unboundTools.get(1).environmentRequired());
    assertNull(unboundTools.get(1).environmentName());
    assertTrue(unboundTools.get(2).environmentRequired());
    assertNull(unboundTools.get(2).environmentName());

    // 3. 环境已被清除（历史 turn 选择了环境，最新 turn 未选择环境）：environment-required 工具 binding 的 environmentName 冻结为
    // null
    ModelRequestSpec clearedSpec =
        fixture.resolved(multiTurnPath(settings("default"), unboundSettings("default")));
    List<ToolBinding> clearedTools = clearedSpec.toolBindings();
    assertEquals(3, clearedTools.size());
    assertTrue(clearedTools.get(0).environmentRequired());
    assertNull(clearedTools.get(0).environmentName());
    assertFalse(clearedTools.get(1).environmentRequired());
    assertNull(clearedTools.get(1).environmentName());
    assertTrue(clearedTools.get(2).environmentRequired());
    assertNull(clearedTools.get(2).environmentName());
  }

  @Test
  void projectRoleThreadsInjectExactRoleTools() {
    // 1. Coordinator: 包含所有 Coordinator 工具
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.roleTools(THREAD_ID, ProjectRoleToolType.namesForRole(ProjectRole.COORDINATOR));

    ModelRequestSpec coordinatorSpec = fixture.resolved(fixture.path(settings("default")));
    List<String> coordinatorTools =
        coordinatorSpec.toolBindings().stream().map(b -> b.descriptor().name()).toList();
    List<String> expectedCoordinatorTools =
        List.of(
            "project_read",
            "issue_read",
            "issue_list",
            "issue_create",
            "issue_update",
            "issue_add_dependency",
            "issue_remove_dependency",
            "issue_set_status",
            "issue_cancel");
    assertEquals(expectedCoordinatorTools, coordinatorTools);

    // 2. Executor: 仅 issue_submit 与 issue_request_input
    fixture.roleTools(THREAD_ID, ProjectRoleToolType.namesForRole(ProjectRole.EXECUTOR));
    ModelRequestSpec executorSpec = fixture.resolved(fixture.path(settings("default")));
    List<String> executorTools =
        executorSpec.toolBindings().stream().map(b -> b.descriptor().name()).toList();
    List<String> expectedExecutorTools = List.of("issue_submit", "issue_request_input");
    assertEquals(expectedExecutorTools, executorTools);

    // 3. Reviewer: 仅 issue_review
    fixture.roleTools(THREAD_ID, ProjectRoleToolType.namesForRole(ProjectRole.REVIEWER));
    ModelRequestSpec reviewerSpec = fixture.resolved(fixture.path(settings("default")));
    List<String> reviewerTools =
        reviewerSpec.toolBindings().stream().map(b -> b.descriptor().name()).toList();
    List<String> expectedReviewerTools = List.of("issue_review");
    assertEquals(expectedReviewerTools, reviewerTools);
  }

  @Test
  void ordinaryThreadHasZeroRoleTools() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.roleTools(THREAD_ID, List.of());

    ModelRequestSpec spec = fixture.resolved(fixture.path(settings("default")));
    assertEquals(List.of(), spec.toolBindings());
  }

  @Test
  void projectRoleDynamicContextAppendedInOrder() {
    HarnessContributor contributorWithProjector =
        HarnessContributor.of(
            new ContributorDescriptor(new ContributorId("test"), "Test", "1", Set.of()),
            registrar ->
                registrar.registerContextProjector(
                    "test.projector",
                    branch -> List.of(new ContextFragment("contributor-fragment-text"))));
    HarnessCatalog catalog =
        HarnessCatalog.from(
            List.of(
                defaultBuiltinContributor(),
                defaultProjectContributor(),
                contributorWithProjector));

    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            ProviderType.OPENAI,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true,
            catalog,
            Clock.fixed(NOW, ZoneOffset.UTC));

    String roleContextText = "# Issue Execution Context: Executor\n## Directives\n...";
    fixture.roleContext(THREAD_ID, roleContextText);

    ModelRequestSpec spec = fixture.resolved(fixture.path(settings("default")));

    // 唯一系统指令按确定性顺序串联：Agent 正文 → Environment → Project role context → Contributor fragment，
    // 段间恰为空行（composer 对环境段 stripTrailing，因此连接处只有一个空行）。
    String instruction = spec.systemInstruction();
    assertEquals(
        "agent system prompt\n\n"
            + "<current_environment>\n"
            + "- date: 2026-08-02\n"
            + "</current_environment>"
            + "\n\n"
            + roleContextText
            + "\n\n"
            + "contributor-fragment-text",
        instruction);
    int agentAt = instruction.indexOf("agent system prompt");
    int roleAt = instruction.indexOf(roleContextText);
    int contributorAt = instruction.indexOf("contributor-fragment-text");
    assertEquals(0, agentAt);
    assertTrue(roleAt > agentAt, instruction);
    assertTrue(contributorAt > roleAt, instruction);

    // 普通 thread (Optional.empty): 角色 context 段整体省略，顺序与空行分隔不变。
    fixture.roleContext(THREAD_ID, null);
    ModelRequestSpec ordinarySpec = fixture.resolved(fixture.path(settings("default")));
    String ordinaryInstruction = ordinarySpec.systemInstruction();
    assertFalse(ordinaryInstruction.contains(roleContextText));
    assertTrue(ordinaryInstruction.endsWith("\n\ncontributor-fragment-text"), ordinaryInstruction);
    assertTrue(ordinaryInstruction.startsWith("agent system prompt"), ordinaryInstruction);
  }

  @Test
  void rejectsWhenCatalogIsMissingRoleTool() {
    // Catalog 缺失角色工具时 fail-closed。
    HarnessCatalog catalogWithoutRoleTools =
        HarnessCatalog.from(List.of(defaultBuiltinContributor()));
    Fixture fixtureWithoutRoleTools =
        new Fixture(List.of(), List.of(), List.of(), catalogWithoutRoleTools);
    fixtureWithoutRoleTools.roleTools(THREAD_ID, List.of("project_read"));
    TurnResolver.Rejected rejectedRole =
        fixtureWithoutRoleTools.rejected(fixtureWithoutRoleTools.path(settings("default")));
    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejectedRole.error().code());
    assertEquals("tool not found: project_read", rejectedRole.error().message());
  }

  @Test
  void rejectsWhenAgentConfiguresInternalProjectTool() {
    Fixture projectToolInConfig = new Fixture(List.of("project_read"), List.of(), List.of());
    TurnResolver.Rejected rejectedProject =
        projectToolInConfig.rejected(projectToolInConfig.path(settings("default")));
    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejectedProject.error().code());
    assertEquals(
        "internal tool cannot be selected by an Agent: project_read",
        rejectedProject.error().message());
  }

  @Test
  void rejectsDuplicateToolNameInAgentConfig() {
    Fixture fixture = new Fixture(List.of("bash"), List.of(), List.of());
    fixture.agentConfig.setTools(List.of("bash", "bash"));
    TurnResolver.Rejected rejected = fixture.rejected(fixture.path(settings("default")));
    assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.error().code());
    assertEquals("duplicate agent tool name: bash", rejected.error().message());
  }

  @Test
  void propagatesInconsistentOwnershipException() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failRoleToolLookup(
        THREAD_ID, new IllegalStateException("Project thread ownership is inconsistent"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.resolver.resolve(THREAD_ID, fixture.path(settings("default")), null));
    assertEquals("Project thread ownership is inconsistent", error.getMessage());

    Fixture contextFixture = new Fixture(List.of(), List.of(), List.of());
    contextFixture.failRoleContextLookup(
        THREAD_ID, new IllegalStateException("Project thread ownership is inconsistent"));

    IllegalStateException contextError =
        assertThrows(
            IllegalStateException.class,
            () ->
                contextFixture.resolver.resolve(
                    THREAD_ID, contextFixture.path(settings("default")), null));
    assertEquals("Project thread ownership is inconsistent", contextError.getMessage());
  }

  @Test
  void compactionDoesNotInvokeSelectorOrProjectorAndHasZeroTools() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.roleTools(THREAD_ID, ProjectRoleToolType.namesForRole(ProjectRole.COORDINATOR));
    fixture.roleContext(THREAD_ID, "role context");

    List<AgentMessage> messages =
        List.of(new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))));
    CompactionPreparation preparation =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            new ModelSelection("provider", "model", "default"),
            id(4),
            null,
            null,
            null,
            messages,
            123L);

    ModelRequestSpec spec = fixture.resolved(fixture.path(settings("default")), preparation);

    verify(fixture.roleToolSelector, never()).select(any());
    verify(fixture.roleContextProjector, never()).project(any());
    assertEquals(List.of(), spec.toolBindings());
    assertEquals(List.of(), spec.skillBindings());
    // 压缩 spec 的指令是摘要 system prompt，与 live resolver 的 Agent 组合指令完全不同。
    assertEquals(CompactionPrompts.summarizationSystemPrompt(), spec.systemInstruction());
    assertFalse(spec.systemInstruction().contains("agent system prompt"));
  }

  @Test
  void rejectsNullThreadIdOnLiveTurn() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    assertThrows(
        NullPointerException.class,
        () -> fixture.resolver.resolve(null, fixture.path(settings("default")), null));
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

  /** 声明固定 {@code requiredEnvironmentId} 的 SELECTABLE 工具 catalog。 */
  private static HarnessCatalog fixedEnvironmentCatalog(
      String toolName, EnvironmentId requiredEnvironmentId) {
    HarnessContributor fixedContributor =
        HarnessContributor.of(
            new ContributorDescriptor(new ContributorId("fixed"), "Fixed", "1", Set.of()),
            registrar -> {
              Tool tool = mock(Tool.class);
              when(tool.descriptor()).thenReturn(hostDescriptor(toolName));
              when(tool.requirements())
                  .thenReturn(ToolRequirements.environment(requiredEnvironmentId));
              registrar.registerTool(
                  toolName.replace('_', '.'), tool, ToolVisibility.SELECTABLE, 0);
            });
    return HarnessCatalog.from(List.of(defaultBuiltinContributor(), fixedContributor));
  }

  private static Fixture taskFixture() {
    return new Fixture(
        List.of(),
        List.of(),
        List.of(hostDescriptor(TaskTool.NAME)),
        Set.of(TaskTool.NAME),
        ProviderType.OPENAI,
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
    private final EnvironmentRegistry environmentRegistry = mock(EnvironmentRegistry.class);
    private final EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
    private final SkillCatalogQueryService skillCatalogQueryService =
        mock(SkillCatalogQueryService.class);
    private final Map<String, Skill> activeSkills = new LinkedHashMap<>();
    private final AgentDefinition agent = new AgentDefinition();
    private final AgentDefinitionConfigDTO agentConfig = new AgentDefinitionConfigDTO();
    private final AgentProvider provider = new AgentProvider();
    private final ProviderFactory providerFactory = mock(ProviderFactory.class);
    private final ProjectRoleToolSelector roleToolSelector = mock(ProjectRoleToolSelector.class);
    private final ProjectRoleContextProjector roleContextProjector =
        mock(ProjectRoleContextProjector.class);
    private final DatabaseTurnResolver resolver;

    private Fixture(List<String> tools, List<String> skills, List<ToolDescriptor> hostDescriptors) {
      this(tools, skills, hostDescriptors, (HarnessCatalog) null);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> hostDescriptors,
        Clock clock) {
      this(tools, skills, hostDescriptors, (HarnessCatalog) null, clock);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> hostDescriptors,
        HarnessCatalog catalog) {
      this(tools, skills, hostDescriptors, catalog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> hostDescriptors,
        HarnessCatalog catalog,
        Clock clock) {
      this(
          tools,
          skills,
          hostDescriptors,
          Set.of(),
          ProviderType.OPENAI,
          ProviderType.OPENAI,
          PromptCacheCapability.unsupported(),
          true,
          catalog,
          clock);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> hostDescriptors,
        Set<String> internalHostToolNames,
        ProviderType persistedProviderType,
        ProviderType factoryType,
        PromptCacheCapability cacheCapability,
        boolean includeProviderFactory) {
      this(
          tools,
          skills,
          hostDescriptors,
          internalHostToolNames,
          persistedProviderType,
          factoryType,
          cacheCapability,
          includeProviderFactory,
          (HarnessCatalog) null);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> hostDescriptors,
        Set<String> internalHostToolNames,
        ProviderType persistedProviderType,
        ProviderType factoryType,
        PromptCacheCapability cacheCapability,
        boolean includeProviderFactory,
        HarnessCatalog catalog) {
      this(
          tools,
          skills,
          hostDescriptors,
          internalHostToolNames,
          persistedProviderType,
          factoryType,
          cacheCapability,
          includeProviderFactory,
          catalog,
          Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> hostDescriptors,
        Set<String> internalHostToolNames,
        ProviderType persistedProviderType,
        ProviderType factoryType,
        PromptCacheCapability cacheCapability,
        boolean includeProviderFactory,
        HarnessCatalog providedCatalog,
        Clock clock) {
      this(
          tools,
          skills,
          hostDescriptors,
          internalHostToolNames,
          persistedProviderType,
          factoryType,
          cacheCapability,
          includeProviderFactory,
          providedCatalog,
          null,
          clock);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> hostDescriptors,
        Set<String> internalHostToolNames,
        ProviderType persistedProviderType,
        ProviderType factoryType,
        PromptCacheCapability cacheCapability,
        boolean includeProviderFactory,
        HarnessCatalog providedCatalog,
        RuntimeToolCatalog providedToolCatalog,
        Clock clock) {
      agent.setName("assistant");
      agent.setSystemPrompt("agent system prompt");
      agent.setConfigJson("agent-config");
      when(agents.getByName("assistant")).thenReturn(agent);

      // 默认 branch 选择 ENV_A_NAME：解析为内部路由身份 ENV_A。
      bindEnvironment(ENV_A_NAME, ENV_A);
      bindEnvironment(ENV_B_NAME, ENV_B);

      provider.setName("provider");
      provider.setProviderType(persistedProviderType);
      provider.setConnectionGenerationId(new UUID(0L, 42L));
      provider.setVersion(0L);
      when(providers.getByName("provider")).thenReturn(provider);

      AgentModel model = new AgentModel();
      model.setProviderName("provider");
      model.setName("model");
      model.setModelId("wire-model");
      model.setConfigJson("model-config");
      when(models.getByProviderNameAndName("provider", "model")).thenReturn(model);

      agentConfig.setTools(List.copyOf(tools));
      agentConfig.setSkills(List.copyOf(skills));
      agentConfig.setSubagents(List.of());
      when(agentConfigCodec.decode("agent-config")).thenReturn(agentConfig);
      for (String skill : skills) {
        activeSkills.put(skill, activeSkill(skill, skill + " description"));
      }
      when(skillCatalogQueryService.activeSkillsByName())
          .thenAnswer(invocation -> Map.copyOf(activeSkills));

      modelSupportsTools(true);
      modelSupportsReasoning(true);
      when(providerFactory.providerType()).thenReturn(factoryType);
      when(providerFactory.promptCacheCapability()).thenReturn(cacheCapability);
      when(providerFactory.promptCacheCapability(any())).thenReturn(cacheCapability);
      when(roleToolSelector.select(any())).thenReturn(List.of());
      when(roleContextProjector.project(any())).thenReturn(Optional.empty());
      List<ProviderFactory> factories =
          includeProviderFactory ? List.of(providerFactory) : List.of();
      SubagentConfig subagentConfig = new SubagentConfig(2, 10, 0, Duration.ZERO, 50);

      HarnessCatalog catalog;
      if (providedCatalog != null) {
        catalog = providedCatalog;
      } else {
        List<HarnessContributor> contributors = new ArrayList<>();
        contributors.add(defaultBuiltinContributor());
        contributors.add(defaultProjectContributor());

        if (!hostDescriptors.isEmpty()) {
          contributors.add(
              HarnessContributor.of(
                  new ContributorDescriptor(new ContributorId("host"), "Host", "1", Set.of()),
                  registrar -> {
                    for (ToolDescriptor descriptor : hostDescriptors) {
                      if (descriptor.name().equals("load_skill")
                          || descriptor.name().equals("task")
                          || descriptor.name().equals("create_goal")
                          || descriptor.name().equals("get_goal")
                          || descriptor.name().equals("update_goal")
                          || descriptor.name().equals("bash")
                          || descriptor.name().equals("read")
                          || descriptor.name().equals("write")
                          || descriptor.name().equals("edit")
                          || descriptor.name().equals("grep")
                          || descriptor.name().equals("find")
                          || descriptor.name().startsWith("lsp_")
                          || descriptor.name().startsWith("mcp_")) {
                        continue;
                      }
                      Tool tool = mock(Tool.class);
                      when(tool.descriptor()).thenReturn(descriptor);
                      when(tool.requirements()).thenReturn(ToolRequirements.none());
                      registrar.registerTool(
                          descriptor.name(),
                          tool,
                          internalHostToolNames.contains(descriptor.name())
                              ? ToolVisibility.INTERNAL
                              : ToolVisibility.SELECTABLE,
                          0);
                    }
                  }));
        }
        catalog = HarnessCatalog.from(contributors);
      }

      RuntimeToolCatalog toolCatalog =
          providedToolCatalog != null
              ? providedToolCatalog
              : new HarnessToolCatalogAdapter(catalog);

      resolver =
          new DatabaseTurnResolver(
              agents,
              models,
              providers,
              agentConfigCodec,
              modelConfigParser,
              new ProviderFactories(factories),
              toolCatalog,
              catalog,
              environmentRegistry,
              environmentRepository,
              skillCatalogQueryService,
              () -> new CompactionConfig(20_000, null),
              new AgentPromptComposer(() -> subagentConfig),
              roleToolSelector,
              roleContextProjector,
              clock);
    }

    /** 把 branch 可见的不可变 Environment name 注册为内部路由身份。 */
    private void bindEnvironment(String name, EnvironmentId environmentId) {
      Environment environment = new Environment();
      environment.setId(environmentId.value());
      environment.setName(name);
      when(environmentRepository.getByName(name)).thenReturn(environment);
    }

    private void roleTools(UUID threadId, List<String> tools) {
      when(roleToolSelector.select(threadId)).thenReturn(tools);
    }

    private void roleContext(UUID threadId, String context) {
      when(roleContextProjector.project(threadId)).thenReturn(Optional.ofNullable(context));
    }

    private void failRoleToolLookup(UUID threadId, RuntimeException error) {
      when(roleToolSelector.select(threadId)).thenThrow(error);
    }

    private void failRoleContextLookup(UUID threadId, RuntimeException error) {
      when(roleContextProjector.project(threadId)).thenThrow(error);
    }

    private void addModel(ModelSelection selection, ParsedAgentModelConfig parsedModel) {
      AgentProvider fallbackProvider = new AgentProvider();
      fallbackProvider.setName(selection.providerName());
      fallbackProvider.setProviderType(ProviderType.OPENAI);
      fallbackProvider.setConnectionGenerationId(new UUID(0L, 43L));
      fallbackProvider.setVersion(0L);
      when(providers.getByName(selection.providerName())).thenReturn(fallbackProvider);

      String configJson = selection.providerName() + "-config";
      AgentModel fallbackModel = new AgentModel();
      fallbackModel.setProviderName(selection.providerName());
      fallbackModel.setName(selection.modelName());
      fallbackModel.setModelId(selection.modelName());
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

    private void missingGlobalSkills() {
      activeSkills.clear();
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

    /** model 级 limit.output 固定为 {@code maxOutputTokens}；variant 只保留 id。 */
    private void modelGlobalOutputLimit(long maxOutputTokens) {
      modelLimits(parsedModel().contextWindow(), maxOutputTokens);
    }

    private void modelLimits(long contextWindow, long maxOutputTokens) {
      ParsedAgentModelConfig parsed = parsedModel();
      when(modelConfigParser.parse("model-config"))
          .thenReturn(
              new ParsedAgentModelConfig(
                  contextWindow,
                  maxOutputTokens,
                  parsed.inputModalities(),
                  parsed.tools(),
                  parsed.reasoning(),
                  List.of(new ModelVariant("default")),
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
      ModelVariant defaultVariant = new ModelVariant("default");
      ModelVariant customVariant = new ModelVariant("custom", "medium");
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

    private ProviderFactory resolverProviderFactory() {
      return providerFactory;
    }

    private void connectingEnvironment(EnvironmentId environmentId) {
      EnvironmentConnection env =
          new EnvironmentConnection(
              environmentId,
              UUID.randomUUID(),
              UUID.randomUUID(),
              LiveEnvironmentStatus.CONNECTING,
              null,
              NOW,
              NOW.plusSeconds(60));
      when(environmentRegistry.find(environmentId)).thenReturn(Optional.of(env));
      when(environmentRegistry.hasReadyLease(environmentId)).thenReturn(false);
    }

    /** CONNECTING 但保留最近一次 READY 宿主 metadata：证明断线后环境块仍按保留事实渲染。 */
    private void connectingEnvironmentWithRetainedMetadata(EnvironmentId environmentId) {
      EnvironmentConnection env =
          new EnvironmentConnection(
              environmentId,
              UUID.randomUUID(),
              UUID.randomUUID(),
              LiveEnvironmentStatus.CONNECTING,
              new DaemonCapabilities(
                  DaemonCapabilities.VERSION,
                  new DaemonEnvironmentInfo(
                      DaemonOperatingSystem.WSL, "UTC", "Retained note", "/retained/root")),
              NOW,
              NOW.plusSeconds(60));
      when(environmentRegistry.find(environmentId)).thenReturn(Optional.of(env));
      when(environmentRegistry.hasReadyLease(environmentId)).thenReturn(false);
    }

    private void readyEnvironment(EnvironmentId environmentId) {
      readyEnvironment(environmentId, List.of());
    }

    private void readyEnvironment(EnvironmentId environmentId, List<String> skills) {
      readyEnvironment(
          environmentId,
          skills,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"));
    }

    private void readyEnvironment(
        EnvironmentId environmentId, List<String> skills, DaemonEnvironmentInfo environmentInfo) {
      publishGlobalSkills(skills);
      readyEnvironmentWithSkills(environmentId, environmentInfo, NOW);
    }

    private void readyEnvironmentWithSkills(
        EnvironmentId environmentId,
        List<SkillName> skills,
        DaemonEnvironmentInfo environmentInfo) {
      publishGlobalSkills(skills);
      readyEnvironmentWithSkills(environmentId, environmentInfo, NOW);
    }

    private void readyEnvironmentWithSkills(EnvironmentId environmentId, List<SkillName> skills) {
      readyEnvironmentWithSkills(
          environmentId,
          skills,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"));
    }

    /** 只注册该 Environment 的路由身份，不涉及任何 Skill 事实（Skill 只来自全局目录）。 */
    private void publishEnvironment(EnvironmentId environmentId) {
      readyEnvironmentWithSkills(
          environmentId,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          NOW);
    }

    private void staleEnvironment(
        EnvironmentId environmentId, DaemonEnvironmentInfo environmentInfo) {
      readyEnvironmentWithSkills(environmentId, environmentInfo, NOW.minus(Duration.ofSeconds(61)));
      when(environmentRegistry.hasReadyLease(environmentId)).thenReturn(false);
    }

    private void readyEnvironmentWithSkills(
        EnvironmentId environmentId, DaemonEnvironmentInfo environmentInfo, Instant lastSeenAt) {
      EnvironmentConnection env =
          new EnvironmentConnection(
              environmentId,
              UUID.randomUUID(),
              UUID.randomUUID(),
              LiveEnvironmentStatus.READY,
              new DaemonCapabilities(DaemonCapabilities.VERSION, environmentInfo),
              lastSeenAt,
              lastSeenAt.plusSeconds(60));
      when(environmentRegistry.find(environmentId)).thenReturn(Optional.of(env));
      when(environmentRegistry.hasReadyLease(environmentId)).thenReturn(true);
    }

    /** 只发布名称的全局目录条目，描述固定为 {@code <name> description}。 */
    private void publishGlobalSkills(List<String> names) {
      for (String name : names) {
        activeSkills.put(name, activeSkill(name, name + " description"));
      }
    }

    /** 发布带精确描述的全局目录条目（XML 转义等断言依赖描述原文）。 */
    private void publishGlobalSkills(Iterable<SkillName> skills) {
      for (SkillName skill : skills) {
        activeSkills.put(skill.name(), activeSkill(skill.name(), skill.description()));
      }
    }

    private Skill activeSkill(String name, String description) {
      Skill skill = new Skill();
      skill.setName(name);
      skill.setPackageName("test-package");
      skill.setPackageVersion("1.0.0");
      skill.setDescription(description);
      skill.setContent("# " + name);
      return skill;
    }
  }
}

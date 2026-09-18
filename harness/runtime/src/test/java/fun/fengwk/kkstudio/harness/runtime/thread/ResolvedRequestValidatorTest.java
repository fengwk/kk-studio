package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 直接校验 Resolver 结果与 candidate path 的机械契约，覆盖正常 turn、压缩 turn 和环境边界。 */
class ResolvedRequestValidatorTest {
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"));
  private static final EnvironmentId ENV_BINDING =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId OTHER_ENVIRONMENT_ID =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final UUID SESSION_ID = new UUID(0L, 1L);
  private static final UUID THREAD_ID = new UUID(0L, 2L);
  private static final UUID ROOT_ENTRY_ID = new UUID(0L, 3L);
  private static final UUID TURN_START_ENTRY_ID = new UUID(0L, 4L);
  private static final UUID CUT_ENTRY_ID = new UUID(0L, 5L);
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

  @Test
  void rejectsNullValidationInputsBeforeReadingCandidateFacts() {
    // 参数缺失必须在读取 path/resolved 字段前失败，保持边界契约清晰。
    assertThrows(
        NullPointerException.class,
        () -> ResolvedRequestValidator.validate(null, null, resolved(normalSpec(SETTINGS))));
    assertThrows(
        NullPointerException.class,
        () -> ResolvedRequestValidator.validate(normalPath(SETTINGS), null, null));
  }

  @Test
  void acceptsValidNormalRequestWithMatchingToolAndSkillRoutes() {
    ModelRequestSpec spec =
        normalSpec(
            SETTINGS,
            List.of(environmentTool(ENV_BINDING)),
            List.of(skillBinding("dev", ENV_BINDING)),
            List.of(),
            ProviderCacheControl.none());

    // 正常 turn 允许匹配 candidate environment 的 tool/skill binding。
    assertDoesNotThrow(
        () -> ResolvedRequestValidator.validate(normalPath(SETTINGS), null, resolved(spec)));
  }

  @Test
  void rejectsNormalTurnThatCarriesCompactionMetadata() {
    CompactionPreparation preparation = preparation();
    EntryPath path = compactionPath(SETTINGS, preparation.frozenStart());

    // normal 校验不得借 candidate path 末尾的 COMPACTION TURN_START 携带压缩元数据。
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> ResolvedRequestValidator.validate(path, null, resolved(normalSpec(SETTINGS))));
    assertEquals("a normal turn must not carry compaction TURN_START metadata", error.getMessage());
  }

  @Test
  void rejectsCompactionCandidateWithDifferentFrozenMetadata() {
    CompactionPreparation preparation = preparation();
    CompactionStart mismatchedStart =
        new CompactionStart(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            preparation.executionModel(),
            new UUID(0L, 6L),
            null,
            null);

    // candidate TURN_START 必须原样携带 planner 冻结的切分事实，不能只匹配 phase。
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                ResolvedRequestValidator.validate(
                    compactionPath(SETTINGS, mismatchedStart),
                    preparation,
                    resolved(compactionSpec(preparation))));
    assertEquals(
        "a compaction candidate TURN_START must carry the exact frozen preparation metadata",
        error.getMessage());
  }

  @Test
  void rejectsPreambleOnCompactionRequest() {
    // 压缩请求的输入上下文由 candidate path 重建，spec 不得额外携带 preamble。
    assertCompactionRejects(
        preparation(),
        List.of(AgentMessage.system("unexpected")),
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  @Test
  void rejectsToolBindingOnCompactionRequest() {
    // 压缩只发送 model/variant，不能把任何 tool binding 带入 Provider 请求。
    assertCompactionRejects(
        preparation(),
        List.of(),
        List.of(hostTool()),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  @Test
  void rejectsSkillBindingOnCompactionRequest() {
    // 压缩调用不加载 skill；skill 绑定必须留在正常 turn。
    assertCompactionRejects(
        preparation(),
        List.of(),
        List.of(),
        List.of(skillBinding("dev", ENV_BINDING)),
        List.of(),
        ProviderCacheControl.none());
  }

  @Test
  void rejectsSubagentBindingOnCompactionRequest() {
    // 压缩摘要不应携带子 Agent 委派能力。
    assertCompactionRejects(
        preparation(),
        List.of(),
        List.of(),
        List.of(),
        List.of(new SubagentBinding("reviewer", "review the summary")),
        ProviderCacheControl.none());
  }

  @Test
  void rejectsProviderCacheControlOnCompactionRequest() {
    // 压缩请求必须禁用 Provider cache，避免摘要请求复用正常 turn 的缓存策略。
    assertCompactionRejects(
        preparation(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "compaction-cache"));
  }

  @Test
  void acceptsValidCompactionRequestWithFrozenExecutionModel() {
    CompactionPreparation preparation = preparation();

    // executionModel、冻结元数据及空 binding/cache none 全部一致时，压缩候选应通过校验。
    assertDoesNotThrow(
        () ->
            ResolvedRequestValidator.validate(
                compactionPath(SETTINGS, preparation.frozenStart()),
                preparation,
                resolved(compactionSpec(preparation))));
  }

  @Test
  void acceptsAnyEnvironmentIdBecauseBranchSettingsCarryNoDirectory() {
    // branch 只冻结 agent/model；environment 选择由 Agent definition 每轮解析，因此不同 environmentId 的
    // tool/skill 与该 branch 都不构成 Resolved 契约冲突。
    assertDoesNotThrow(
        () ->
            ResolvedRequestValidator.validate(
                normalPath(SETTINGS),
                null,
                resolved(
                    normalSpec(
                        SETTINGS,
                        List.of(environmentTool(OTHER_ENVIRONMENT_ID)),
                        List.of(skillBinding("dev", OTHER_ENVIRONMENT_ID)),
                        List.of(),
                        ProviderCacheControl.none()))));
  }

  /** 冻结的六字段 skill binding fixture：身份、描述、baseDirectory 与 revision 全部必填。 */
  private static SkillBinding skillBinding(String name, EnvironmentId sourceEnvironmentId) {
    return new SkillBinding(
        sourceEnvironmentId,
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        name,
        "developer rules",
        "/home/dev/.agents/skills/" + name,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
  }

  private static void assertCompactionRejects(
      CompactionPreparation preparation,
      List<AgentMessage> preamble,
      List<ToolBinding> tools,
      List<SkillBinding> skills,
      List<SubagentBinding> subagents,
      ProviderCacheControl cacheControl) {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                ResolvedRequestValidator.validate(
                    compactionPath(SETTINGS, preparation.frozenStart()),
                    preparation,
                    resolved(
                        normalSpec(
                            preparation.executionModel(),
                            preamble,
                            tools,
                            skills,
                            subagents,
                            cacheControl))));
    assertEquals(
        "compaction requests must carry only model/variant with cache disabled",
        error.getMessage());
  }

  private static TurnResolver.Resolved resolved(ModelRequestSpec spec) {
    return new TurnResolver.Resolved(spec, 100_000, 16_384);
  }

  private static EntryPath normalPath(BranchSettings settings) {
    return new EntryPath(
        List.of(new Entry(ROOT_ENTRY_ID, SESSION_ID, null, new RootPayload(settings), NOW)));
  }

  private static EntryPath compactionPath(BranchSettings settings, CompactionStart compaction) {
    return new EntryPath(
        List.of(
            new Entry(ROOT_ENTRY_ID, SESSION_ID, null, new RootPayload(settings), NOW),
            new Entry(
                TURN_START_ENTRY_ID,
                SESSION_ID,
                ROOT_ENTRY_ID,
                new TurnStartPayload(
                    TurnStartReason.COMPACTION, settings, THREAD_ID, 100_000, 16_384, compaction),
                NOW)));
  }

  private static CompactionPreparation preparation() {
    return new CompactionPreparation(
        CompactionPhase.FULL,
        CompactionTrigger.THRESHOLD,
        SETTINGS.model(),
        CUT_ENTRY_ID,
        null,
        null,
        null,
        List.of(AgentMessage.user("history")),
        100L);
  }

  private static ModelRequestSpec compactionSpec(CompactionPreparation preparation) {
    return normalSpec(
        preparation.executionModel(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelRequestSpec normalSpec(BranchSettings settings) {
    return normalSpec(settings, List.of(), List.of(), List.of(), ProviderCacheControl.none());
  }

  private static ModelRequestSpec normalSpec(
      BranchSettings settings,
      List<ToolBinding> tools,
      List<SkillBinding> skills,
      List<SubagentBinding> subagents,
      ProviderCacheControl cacheControl) {
    return normalSpec(settings.model(), List.of(), tools, skills, subagents, cacheControl);
  }

  private static ModelRequestSpec normalSpec(
      ModelSelection model,
      List<AgentMessage> preamble,
      List<ToolBinding> tools,
      List<SkillBinding> skills,
      List<SubagentBinding> subagents,
      ProviderCacheControl cacheControl) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        new ModelDescriptor(
            model.providerName(),
            model.modelName(),
            model.modelName(),
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            new ModelPricing(
                "USD",
                "standard",
                "standard",
                BigDecimal.ONE,
                "1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
        new ModelVariant(model.variant()),
        1024,
        preamble,
        tools,
        skills,
        subagents,
        cacheControl);
  }

  private static ToolBinding environmentTool(EnvironmentId environment) {
    return new ToolBinding(
        toolDefinition("test.fs"),
        new ContributorBinding("base", "fs", List.of()),
        true,
        environment);
  }

  private static ToolBinding hostTool() {
    return new ToolBinding(
        toolDefinition("test.bash"),
        new ContributorBinding("core", "bash", List.of()),
        false,
        null);
  }

  private static AgentToolDefinition toolDefinition(String id) {
    return new AgentToolDefinition(
        new ToolDescriptor(
            id.substring(id.indexOf('.') + 1),
            "test tool",
            "test",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30)),
        ToolVisibility.SELECTABLE);
  }
}

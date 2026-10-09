package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
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
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 直接校验 Resolver 结果与 candidate path 的机械契约，覆盖正常 turn 和环境边界。 */
class ResolvedRequestValidatorTest {
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
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
        () -> ResolvedRequestValidator.validate(null, resolved(normalSpec(SETTINGS))));
    assertThrows(
        NullPointerException.class,
        () -> ResolvedRequestValidator.validate(normalPath(SETTINGS), null));
  }

  @Test
  void acceptsValidNormalRequestWithMatchingToolRoutes() {
    ModelRequestSpec spec =
        normalSpec(
            SETTINGS,
            List.of(environmentTool(ENV_BINDING)),
            List.of(),
            ProviderCacheControl.none());

    // 正常 turn 允许匹配 candidate environment 的 tool binding。
    assertDoesNotThrow(
        () -> ResolvedRequestValidator.validate(normalPath(SETTINGS), resolved(spec)));
  }

  @Test
  void rejectsNormalTurnThatCarriesCompactionMetadata() {
    CompactionStart start =
        CompactionStart.pending(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, CUT_ENTRY_ID, null, null);
    EntryPath path = compactionPath(SETTINGS, start);

    // normal 校验不得借 candidate path 末尾的 COMPACTION TURN_START 携带压缩元数据。
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> ResolvedRequestValidator.validate(path, resolved(normalSpec(SETTINGS))));
    assertEquals("a normal turn must not carry a COMPACTION TURN_START", error.getMessage());
  }

  @Test
  void acceptsAnyEnvironmentIdBecauseBranchSettingsCarryNoDirectory() {
    // branch settings 只冻结 agent/model/environmentName，不携带路由身份；Environment 每轮按 name 解析，
    // 因此候选 Spec 中任意 environmentId 的 tool 都不与 branch 构成 Resolved 契约冲突。
    assertDoesNotThrow(
        () ->
            ResolvedRequestValidator.validate(
                normalPath(SETTINGS),
                resolved(
                    normalSpec(
                        SETTINGS,
                        List.of(environmentTool(OTHER_ENVIRONMENT_ID)),
                        List.of(),
                        ProviderCacheControl.none()))));
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

  private static ModelRequestSpec normalSpec(BranchSettings settings) {
    return normalSpec(settings, List.of(), List.of(), ProviderCacheControl.none());
  }

  private static ModelRequestSpec normalSpec(
      BranchSettings settings,
      List<ToolBinding> tools,
      List<SubagentBinding> subagents,
      ProviderCacheControl cacheControl) {
    return normalSpec(settings.model(), tools, subagents, cacheControl);
  }

  private static ModelRequestSpec normalSpec(
      ModelSelection model,
      List<ToolBinding> tools,
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
            true),
        new ModelVariant(model.variant()),
        1024,
        "Test system instruction.",
        tools,
        subagents,
        cacheControl);
  }

  private static ToolBinding environmentTool(EnvironmentId environment) {
    return new ToolBinding(
        toolDefinition("test.fs"),
        new ContributorBinding("base", "fs", List.of()),
        EnvironmentSupport.REQUIRED,
        environment,
        "dev");
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

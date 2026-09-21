package fun.fengwk.kkstudio.platform.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link LiveTurnPlan} 型不变量的契约测试。
 *
 * <p>这些不变量就是「预览不可能与真实请求漂移」与「Debug 只回显稳定 code」的形式化表达：候选顺序必须是「SENT 在前、FILTERED 在后」，状态与过滤原因必须一致，LOCAL
 * 交付必须建立在精确安装之上，拒绝必须自带稳定 code。任何违反都在构造期立即失败， 绝不留下半合法的计划。
 */
class LiveTurnPlanTest {

  @Test
  void candidateStatesMustCarryExactlyMatchingFilterFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            candidate(
                LiveTurnPlan.ToolState.SENT, LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED));
    assertThrows(
        IllegalArgumentException.class, () -> candidate(LiveTurnPlan.ToolState.FILTERED, null));

    LiveTurnPlan.CandidateTool sent = candidate(LiveTurnPlan.ToolState.SENT, null);
    assertEquals(LiveTurnPlan.ToolState.SENT, sent.state());
    LiveTurnPlan.CandidateTool filtered =
        candidate(
            LiveTurnPlan.ToolState.FILTERED, LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED);
    assertEquals(LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED, filtered.filterReason());
  }

  /** 测试意图：被过滤的候选只能整体位于发送候选之后，保证投影顺序与「候选事实在前、被过滤在后」的契约一致。 */
  @Test
  void sentCandidatesMustPrecedeFilteredCandidates() {
    LiveTurnPlan.CandidateTool filtered =
        candidate(
            LiveTurnPlan.ToolState.FILTERED, LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LiveTurnPlan.Planned(
                spec(),
                4096,
                List.of(filtered, candidate(LiveTurnPlan.ToolState.SENT, null)),
                List.of()));
    assertEquals(
        2,
        new LiveTurnPlan.Planned(
                spec(),
                4096,
                List.of(candidate(LiveTurnPlan.ToolState.SENT, null), filtered),
                List.of())
            .candidateTools()
            .size());
  }

  /** 测试意图：contextWindow 与 spec 是投影基础事实，缺失或非正立即失败而不是产生无法解释的预览。 */
  @Test
  void plannedRequiresSpecAndPositiveContextWindow() {
    assertThrows(
        NullPointerException.class,
        () -> new LiveTurnPlan.Planned(null, 4096, List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LiveTurnPlan.Planned(spec(), 0, List.of(), List.of()));
  }

  /** 测试意图：LOCAL 交付只能建立在「已安装 commit 与 currentCommit 完全一致」之上，其余组合立即失败。 */
  @Test
  void localDeliveryRequiresTheExactInstalledCommit() {
    assertEquals(
        SkillPromptResolution.Delivery.LOCAL,
        skill(SkillPromptResolution.Delivery.LOCAL, "commit-a", "commit-a").delivery());
    assertThrows(
        IllegalArgumentException.class,
        () -> skill(SkillPromptResolution.Delivery.LOCAL, "commit-a", "commit-b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> skill(SkillPromptResolution.Delivery.LOCAL, "commit-a", null));
    assertEquals(
        SkillPromptResolution.Delivery.PLATFORM,
        skill(SkillPromptResolution.Delivery.PLATFORM, "commit-a", null).delivery());
  }

  /** 测试意图：拒绝必须自带稳定 error code，缺失时立即失败，绝不产生无 code 的降级投影。 */
  @Test
  void rejectionRequiresStableErrorCode() {
    assertThrows(NullPointerException.class, () -> new LiveTurnPlan.Rejected(null, "detail"));
    assertEquals(
        DatabaseTurnResolver.REJECTION_CODE,
        new LiveTurnPlan.Rejected(DatabaseTurnResolver.REJECTION_CODE, "detail").errorCode());
  }

  /** 最小可用 spec：LiveTurnPlan 只把 spec 当作不可为空的冻结契约载体。 */
  private static ModelRequestSpec spec() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 42L),
        new ModelDescriptor(
            "provider",
            "model",
            "wire-model",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            new ModelPricing(
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
                BigDecimal.ZERO)),
        new ModelVariant("default"),
        512,
        "system instruction",
        List.of(),
        List.of(),
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT, "affinity", Set.of(PromptCacheBreakpoint.SYSTEM)));
  }

  private static LiveTurnPlan.CandidateTool candidate(
      LiveTurnPlan.ToolState state, LiveTurnPlan.FilterReason filterReason) {
    return new LiveTurnPlan.CandidateTool(
        "read",
        "Read a file.",
        "{\"type\":\"object\"}",
        EnvironmentSupport.OPTIONAL,
        null,
        "builtin:read",
        state,
        filterReason);
  }

  private static LiveTurnPlan.PlannedSkill skill(
      SkillPromptResolution.Delivery delivery, String currentCommit, String installedCommit) {
    return new LiveTurnPlan.PlannedSkill(
        "pkg",
        "dev",
        "description",
        "path",
        delivery,
        currentCommit,
        "observed-head",
        installedCommit);
  }
}

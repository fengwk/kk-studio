package fun.fengwk.kkstudio.platform.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.harness.task.SkillPromptEntry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** {@link LiveTurnPlan} invariants are enforced at construction boundaries. */
class LiveTurnPlanTest {

  @Test
  void plannedRetainsAgentToolOrder() {
    LiveTurnPlan.Planned plan =
        new LiveTurnPlan.Planned(
            spec(), 4096, List.of(candidate("bash"), candidate("read")), List.of());

    assertEquals(
        List.of("bash", "read"),
        plan.candidateTools().stream().map(LiveTurnPlan.CandidateTool::name).toList());
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

  /** 测试意图：Skill 目录事实只包含 Agent 配置的名称、描述与 Package 版本。 */
  @Test
  void plannedSkillContainsOnlyCatalogPromptFacts() {
    LiveTurnPlan.PlannedSkill skill = skill();
    assertEquals("pkg", skill.packageName());
    assertEquals("dev", skill.name());
    assertEquals("description", skill.description());
    assertEquals("v1", skill.packageVersion());
    assertEquals("observed-head", skill.headCommit());
    assertEquals(new SkillPromptEntry("dev", "description"), skill.promptEntry());
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
            "provider", "model", "wire-model", Set.of(ModelInputModality.TEXT), true, false),
        new ModelVariant("default", null, PromptCacheRetention.SHORT, null),
        List.of(new ModelVariant("default", null, PromptCacheRetention.SHORT, null)),
        512,
        "system instruction",
        List.of(),
        List.of(),
        null,
        Map.of("system", "system instruction"));
  }

  private static LiveTurnPlan.CandidateTool candidate(String name) {
    return new LiveTurnPlan.CandidateTool(
        name, "Read a file.", "{\"type\":\"object\"}", EnvironmentSupport.ROUTED, "builtin:read");
  }

  private static LiveTurnPlan.PlannedSkill skill() {
    return new LiveTurnPlan.PlannedSkill("pkg", "dev", "description", "v1", "observed-head");
  }
}

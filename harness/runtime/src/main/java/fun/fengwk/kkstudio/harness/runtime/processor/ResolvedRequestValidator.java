package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.Objects;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验（Harness 边界）。
 *
 * <p>可直接对照 candidate {@link TurnPlan#candidatePath()} 最新 {@link BranchSettings} 的字段只有
 * provider/model/variant 选择，以及 environment-bound tool/skill 的 route。YOLO 不进入 spec，也不参与校验。
 *
 * <p>压缩 turn 必须携带与 preparation 逐字段一致的 {@link CompactionRequest} 元数据与相同 {@code contextWindow}，且
 * tool/skill binding 必须为空；正常 turn 必须携带 {@code compaction == null}。任何不一致都是 Resolver 契约 / 编程错误：抛清晰的
 * {@link IllegalStateException}。
 */
final class ResolvedRequestValidator {

  private ResolvedRequestValidator() {}

  static void validate(TurnPlan plan, TurnResolver.Resolved resolved) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(resolved, "resolved");
    ModelRequestSpec spec = resolved.spec();
    validateCompactionPurpose(plan, spec, resolved.contextWindow());
    BranchSettings settings = plan.candidatePath().baseSettings();
    for (ToolBinding binding : spec.toolBindings()) {
      if (binding.type() == ToolType.ENVIRONMENT
          && !Objects.equals(binding.environment(), settings.environment())) {
        throw new IllegalStateException(
            "resolved tool environment="
                + binding.environment()
                + " does not match candidate branch environment="
                + settings.environment());
      }
    }
    for (SkillBinding skill : spec.skillBindings()) {
      if (skill.sourceEnvironment() != null
          && !skill.sourceEnvironment().equals(settings.environment())) {
        throw new IllegalStateException(
            "resolved skill source environment="
                + skill.sourceEnvironment()
                + " does not match candidate branch environment="
                + settings.environment());
      }
    }
    if (!spec.model().providerName().equals(settings.model().providerName())
        || !spec.model().modelName().equals(settings.model().modelName())) {
      throw new IllegalStateException(
          "resolved request model "
              + spec.model().providerName()
              + "/"
              + spec.model().modelName()
              + " does not match candidate branch model "
              + settings.model().providerName()
              + "/"
              + settings.model().modelName());
    }
    if (!spec.variant().id().equals(settings.model().variant())) {
      throw new IllegalStateException(
          "resolved request variant "
              + spec.variant().id()
              + " does not match candidate branch variant "
              + settings.model().variant());
    }
  }

  private static void validateCompactionPurpose(
      TurnPlan plan, ModelRequestSpec spec, int contextWindow) {
    CompactionPreparation preparation = plan.preparation();
    if (preparation == null) {
      if (spec.compaction() != null) {
        throw new IllegalStateException("a normal turn must not carry compaction request metadata");
      }
      return;
    }
    CompactionRequest compaction = spec.compaction();
    if (compaction == null) {
      throw new IllegalStateException("a compaction turn requires compaction request metadata");
    }
    if (compaction.phase() != preparation.phase()) {
      throw new IllegalStateException(
          "resolved compaction phase "
              + compaction.phase()
              + " does not match plan preparation phase "
              + preparation.phase());
    }
    if (compaction.trigger() != preparation.trigger()) {
      throw new IllegalStateException(
          "resolved compaction trigger "
              + compaction.trigger()
              + " does not match plan preparation trigger "
              + preparation.trigger());
    }
    if (compaction.tokensBefore() != preparation.tokensBefore()) {
      throw new IllegalStateException(
          "resolved compaction tokensBefore does not match plan preparation");
    }
    if (!compaction.firstKeptEntryId().equals(preparation.firstKeptEntryId())) {
      throw new IllegalStateException(
          "resolved compaction firstKeptEntryId does not match plan preparation");
    }
    if (!compaction.cutEntryId().equals(preparation.cutEntryId())) {
      throw new IllegalStateException(
          "resolved compaction cutEntryId does not match plan preparation");
    }
    if (!Objects.equals(
        compaction.turnPrefixStartEntryId(), preparation.turnPrefixStartEntryId())) {
      throw new IllegalStateException(
          "resolved compaction turnPrefixStartEntryId does not match plan preparation");
    }
    if (contextWindow != preparation.contextWindow()) {
      throw new IllegalStateException(
          "resolved compaction contextWindow="
              + contextWindow
              + " does not match plan preparation contextWindow="
              + preparation.contextWindow());
    }
    if (!spec.toolBindings().isEmpty() || !spec.skillBindings().isEmpty()) {
      throw new IllegalStateException("compaction requests must not carry tool or skill bindings");
    }
  }
}

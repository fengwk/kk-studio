package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;

import java.util.Objects;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验（Harness 边界）。
 *
 * <p>Resolver 返回的 {@link ModelInvocationRequest} 中可以直接对照 candidate {@link TurnPlan#candidatePath()}
 * 最新 {@link BranchSettings} 的字段只有：{@code yoloEnabled}、完整 Environment binding （{@code environment}）与
 * provider/model/variant 选择。Agent tools/skills/subagents 每个新 turn 从最新 Agent catalog 派生，不再与 branch
 * 中的历史 activeTools 快照机械比较；agentName 在 request 中没有直接的 canonical 字段，不做校验。
 *
 * <p>压缩 turn（{@code plan.preparation()} 非空）必须携带与 preparation 逐字段一致的 {@link CompactionRequest}
 * 元数据与相同 {@code contextWindow}，且 tool/skill binding 必须为空（正常 turn 的 tool 名称序列校验不适用于压缩请求）； 正常 turn
 * 必须携带 {@code compaction == null}。任何不一致都是 Resolver 契约 / 编程错误：抛清晰的 {@link
 * IllegalStateException}，调用方必须保证此时尚未发生任何 durable mutation，绝不转换为 typed user rejection 或 reschedule。
 */
final class ResolvedRequestValidator {

  private ResolvedRequestValidator() {}

  /** 校验通过时无副作用；任一字段不一致抛 {@link IllegalStateException}。 */
  static void validate(TurnPlan plan, ModelInvocationRequest request) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(request, "request");
    validateCompactionPurpose(plan, request);
    BranchSettings settings = plan.candidatePath().baseSettings();
    if (request.yoloEnabled() != plan.finalYoloEnabled()) {
      throw new IllegalStateException(
          "resolved request yoloEnabled="
              + request.yoloEnabled()
              + " does not match candidate branch yoloEnabled="
              + plan.finalYoloEnabled());
    }
    if (!Objects.equals(request.environment(), settings.environment())) {
      throw new IllegalStateException(
          "resolved request environment="
              + request.environment()
              + " does not match candidate branch environment="
              + settings.environment());
    }
    if (!request.providerRequest().model().providerName().equals(settings.model().providerName())
        || !request.providerRequest().model().modelName().equals(settings.model().modelName())) {
      throw new IllegalStateException(
          "resolved request model "
              + request.providerRequest().model().providerName()
              + "/"
              + request.providerRequest().model().modelName()
              + " does not match candidate branch model "
              + settings.model().providerName()
              + "/"
              + settings.model().modelName());
    }
    if (!request.providerRequest().variant().id().equals(settings.model().variant())) {
      throw new IllegalStateException(
          "resolved request variant "
              + request.providerRequest().variant().id()
              + " does not match candidate branch variant "
              + settings.model().variant());
    }
  }

  /** reason COMPACTION iff preparation/request metadata 存在，且逐字段严格一致；压缩请求零 tool/skill。 */
  private static void validateCompactionPurpose(TurnPlan plan, ModelInvocationRequest request) {
    CompactionPreparation preparation = plan.preparation();
    if (preparation == null) {
      if (request.compaction() != null) {
        throw new IllegalStateException("a normal turn must not carry compaction request metadata");
      }
      return;
    }
    CompactionRequest compaction = request.compaction();
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
    if (request.contextWindow() != preparation.contextWindow()) {
      throw new IllegalStateException(
          "resolved compaction contextWindow="
              + request.contextWindow()
              + " does not match plan preparation contextWindow="
              + preparation.contextWindow());
    }
    if (!request.toolBindings().isEmpty() || !request.skillBindings().isEmpty()) {
      throw new IllegalStateException("compaction requests must not carry tool or skill bindings");
    }
  }
}

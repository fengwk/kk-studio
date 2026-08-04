package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验（Harness 边界）。
 *
 * <p>Resolver 返回的 {@link ModelInvocationRequest} 中可以直接对照 candidate {@link TurnPlan#candidatePath()}
 * 最新 {@link BranchSettings} 的字段只有：{@code yoloEnabled}、route （{@code
 * environmentId}）、provider/model/variant 选择与有序 tool bindings（request 构造器已保证 provider tools 与
 * bindings 一一对应，只需对照名称序列）。agentName / thinkingLevel 在 request 中没有直接的 canonical 字段，不做校验。
 *
 * <p>任何不一致都是 Resolver 契约 / 编程错误：抛清晰的 {@link IllegalStateException}，调用方必须保证此时尚未发生任何 durable
 * mutation，绝不转换为 typed user rejection 或 reschedule。
 */
final class ResolvedRequestValidator {

  private ResolvedRequestValidator() {}

  /** 校验通过时无副作用；任一字段不一致抛 {@link IllegalStateException}。 */
  static void validate(TurnPlan plan, ModelInvocationRequest request) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(request, "request");
    BranchSettings settings = plan.candidatePath().baseSettings();
    if (request.yoloEnabled() != plan.finalYoloEnabled()) {
      throw new IllegalStateException(
          "resolved request yoloEnabled="
              + request.yoloEnabled()
              + " does not match candidate branch yoloEnabled="
              + plan.finalYoloEnabled());
    }
    if (!Objects.equals(request.environmentId(), settings.environmentId())) {
      throw new IllegalStateException(
          "resolved request environmentId="
              + request.environmentId()
              + " does not match candidate branch environmentId="
              + settings.environmentId());
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
    List<String> boundToolNames = new ArrayList<>(request.toolBindings().size());
    for (ToolBinding binding : request.toolBindings()) {
      boundToolNames.add(binding.descriptor().name());
    }
    if (!boundToolNames.equals(settings.activeTools())) {
      throw new IllegalStateException(
          "resolved request tool bindings "
              + boundToolNames
              + " do not match candidate branch activeTools "
              + settings.activeTools());
    }
  }
}

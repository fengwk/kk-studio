package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 在 terminal ProviderResponse 持久化前冻结每个可执行 Tool 调用的历史 action。
 *
 * <p>成功资格与执行路径共用同一判定：{@link ModelResponsePlanner} 已完成 frozen binding 查找与 schema 归一化，因此只有 READY 槽位
 * （binding 存在且参数可确定性归一化）才请求渲染；其余调用保持 null，投影时使用通用回退，durable arguments 仍完整保留。
 *
 * <p>容错边界：无 resolver、无 Tool 映射、贡献已变化、渲染抛异常、返回 null/blank 一律视为“未提供映射”。本类的任何路径都不得让模型请求 失败，
 * 因此连规划这类基础设施步骤也整体兜底：任何意外异常都退化为“无 action”。
 */
@Slf4j
final class ToolHistoryActions {

  private static final ModelResponsePlanner PLANNER = new ModelResponsePlanner();

  private ToolHistoryActions() {}

  /** 返回携带冻结 action 的 response；没有任何可冻结 action 或冻结过程失败时原样返回输入。 */
  static ProviderResponse freeze(
      ProviderResponse response, List<ToolBinding> bindings, ToolHistoryActionResolver resolver) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(bindings, "bindings");
    if (resolver == null || response.toolCalls().isEmpty()) {
      return response;
    }
    try {
      return freezeLocked(response, bindings, resolver);
    } catch (RuntimeException failure) {
      log.warn(
          "tool history action freezing failed for {} tool call(s): {}",
          response.toolCalls().size(),
          ProcessorExceptions.describe(failure));
      return response;
    }
  }

  private static ProviderResponse freezeLocked(
      ProviderResponse response, List<ToolBinding> bindings, ToolHistoryActionResolver resolver) {
    if (!(PLANNER.plan(response, bindings) instanceof ModelResponsePlan.ToolBatch batch)) {
      return response;
    }
    // 规划契约保证每个 observed call 恰好对应一个同序 ToolSlot，因此按位置改写即可，无需回查 id。
    List<ProviderToolCall> calls = response.toolCalls();
    List<ProviderToolCall> frozen = null;
    for (int i = 0; i < calls.size(); i++) {
      ModelResponsePlan.ToolSlot slot = batch.tools().get(i);
      if (slot.status() != ToolInvocationStatus.READY) {
        continue;
      }
      String action = resolve(resolver, slot.binding(), slot.call());
      if (action == null) {
        continue;
      }
      if (frozen == null) {
        frozen = new ArrayList<>(calls);
      }
      frozen.set(i, calls.get(i).withHistoryAction(action));
    }
    return frozen == null ? response : response.withToolCalls(List.copyOf(frozen));
  }

  /** 渲染一次调用；任何异常 / empty / blank 都按“未提供映射”处理并记录，绝不上抛。 */
  private static String resolve(
      ToolHistoryActionResolver resolver, ToolBinding binding, ToolCall rawCall) {
    try {
      ToolCall normalized = rawCall.validateFor(binding.descriptor());
      Optional<String> rendered = resolver.resolve(binding, normalized);
      if (rendered == null || rendered.isEmpty() || rendered.get().isBlank()) {
        return null;
      }
      return rendered.get();
    } catch (RuntimeException failure) {
      log.warn(
          "tool history action rendering failed for tool {}: {}",
          binding.descriptor().name(),
          ProcessorExceptions.describe(failure));
      return null;
    }
  }
}

package fun.fengwk.kkstudio.harness.runtime.model.plan;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;

import java.util.Objects;

/**
 * 已解析的 ModelInvocation 计划：固化 {@code sourceHeadEntryId}、{@code ProviderRequest} 与 debt prefix
 * 实际使用的运行时配置。
 *
 * <p>Snapshot 中存在该计划表示当前 head 欠一次 Model 响应。Reconciler 必须立即按该计划创建 ModelInvocation 并释放 Thread
 * lease；不允许在此之前再 harvest 后续配置或消息，否则响应快照会被后续 Input 污染。
 */
public record ModelInvocationPlan(
    long sourceHeadEntryId, ProviderRequest request, RuntimeConfigSnapshot configSnapshot) {

  public ModelInvocationPlan {
    if (sourceHeadEntryId <= 0) {
      throw new IllegalArgumentException("sourceHeadEntryId must be positive");
    }
    request = Objects.requireNonNull(request, "request");
    configSnapshot = Objects.requireNonNull(configSnapshot, "configSnapshot");
  }
}

package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;

import java.util.Objects;

/**
 * 一次 frozen ProviderRequest 的短生命周期执行资源。
 *
 * <p>资源由外层 adapter 使用稳定 credential reference 解析；credential value 不进入 ModelInvocation aggregate 或本
 * record 的公开字段。{@code timeoutPolicy} 在当前 attempt 内固定：首次 claim 使用其 total timeout 建立 durable
 * deadline，所有 attempt 使用其 idle timeout；retry claim 必须保留原 deadline，不能用新解析资源延长总时钟。
 */
public record ModelExecutionResource(ModelExecutor executor, ModelCallTimeoutPolicy timeoutPolicy) {

  public ModelExecutionResource {
    executor = Objects.requireNonNull(executor, "executor");
    timeoutPolicy = Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
  }
}

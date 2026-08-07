package fun.fengwk.kkstudio.core.ai.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;

import java.util.Objects;
import java.util.function.Function;

/**
 * 把持久化的 {@link ProviderRequest} 解析为可直接调用的 {@link ModelProvider}、传输用 {@link ModelCallTimeoutPolicy}
 * 与最终有效 {@link ProviderRequest}。
 *
 * <p>实现必须在每次 Model attempt 开始时按 {@code request.model().providerName()} 读取当前 {@code agent_provider}
 * 行，确认该行当前 {@code ProviderType} 有对应的 Harness {@code ProviderFactory} 注册，并冻结一个短生命周期 Provider adapter
 * 资源。有效 request 按当前 factory capability 重新派生 {@code ProviderCacheControl}，transport 必须使用 它而不是持久化的原
 * request；adapter 只在 Provider I/O executor 上、持久 deadline 封顶传输超时之后创建 SDK 绑定的 {@link ModelProvider}。
 */
public interface ProviderResolutionService {

  ResolvedExecution resolve(ProviderRequest request);

  /** 冻结的解析结果：包含最终有效 {@link ProviderRequest}、私有的 Provider opener 与非敏感的 timeout policy。 */
  final class ResolvedExecution {

    private final ProviderRequest effectiveRequest;
    private final ModelCallTimeoutPolicy timeoutPolicy;
    private final Function<ModelCallTimeoutPolicy, ModelProvider> providerOpener;

    ResolvedExecution(
        ProviderRequest effectiveRequest,
        ModelCallTimeoutPolicy timeoutPolicy,
        Function<ModelCallTimeoutPolicy, ModelProvider> providerOpener) {
      this.effectiveRequest = Objects.requireNonNull(effectiveRequest, "effectiveRequest");
      this.timeoutPolicy = Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
      this.providerOpener = Objects.requireNonNull(providerOpener, "providerOpener");
    }

    /** 最终有效请求：transport 必须使用它，而不是持久化的原 request。 */
    public ProviderRequest effectiveRequest() {
      return effectiveRequest;
    }

    public ModelCallTimeoutPolicy timeoutPolicy() {
      return timeoutPolicy;
    }

    ModelProvider openProvider(ModelCallTimeoutPolicy effectiveTimeoutPolicy) {
      return Objects.requireNonNull(
          providerOpener.apply(
              Objects.requireNonNull(effectiveTimeoutPolicy, "effectiveTimeoutPolicy")),
          "providerOpener returned null");
    }
  }
}

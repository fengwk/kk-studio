package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.spi.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ModelProcessor 的部署级配置。
 *
 * <p>{@code leaseConfigProvider} 在新 claim / execution 使用时读取 lease 与 heartbeat 配置；{@code
 * streamConfig} 给出实时 delta 的推送合并窗口与独立的 checkpoint 落库节奏；{@code retryPolicyProvider} 在每次 TRANSIENT 失败
 * retry 判定点现读；{@code httpErrorPolicyProvider} 在每次带 HTTP 状态的失败 retry 判定点按冻结 Provider 身份现读；{@code
 * admitFailureDelayProvider} 是 Gateway admit 抛异常（肯定未发出请求）或启动前本地放弃（close / cancel / heartbeat
 * 无法启动）时的 reschedule 延迟；{@code toolHistoryActionResolver} 在成功响应持久化前冻结 Tool-owned 历史 action（可为
 * null，表示全部回退）。ToolProcessor 的配置与之不同，因此本配置命名明确限定为 Model。
 */
public record ModelProcessorConfig(
    Supplier<ProcessorLeaseConfig> leaseConfigProvider,
    Supplier<InvocationRetryPolicy> retryPolicyProvider,
    Supplier<Duration> admitFailureDelayProvider,
    ModelStreamConfig streamConfig,
    ToolHistoryActionResolver toolHistoryActionResolver,
    Function<String, ModelHttpErrorPolicy> httpErrorPolicyProvider) {

  public ModelProcessorConfig {
    leaseConfigProvider = Objects.requireNonNull(leaseConfigProvider, "leaseConfigProvider");
    retryPolicyProvider = Objects.requireNonNull(retryPolicyProvider, "retryPolicyProvider");
    admitFailureDelayProvider =
        Objects.requireNonNull(admitFailureDelayProvider, "admitFailureDelayProvider");
    streamConfig = Objects.requireNonNull(streamConfig, "streamConfig");
    httpErrorPolicyProvider =
        Objects.requireNonNull(httpErrorPolicyProvider, "httpErrorPolicyProvider");
  }

  public ModelProcessorConfig(
      Supplier<ProcessorLeaseConfig> leaseConfigProvider,
      Supplier<InvocationRetryPolicy> retryPolicyProvider,
      Supplier<Duration> admitFailureDelayProvider,
      ModelStreamConfig streamConfig,
      ToolHistoryActionResolver toolHistoryActionResolver) {
    this(
        leaseConfigProvider,
        retryPolicyProvider,
        admitFailureDelayProvider,
        streamConfig,
        toolHistoryActionResolver,
        providerName -> ModelHttpErrorPolicy.DEFAULT);
  }

  public ModelProcessorConfig(
      Supplier<ProcessorLeaseConfig> leaseConfigProvider,
      Supplier<InvocationRetryPolicy> retryPolicyProvider,
      Supplier<Duration> admitFailureDelayProvider,
      ModelStreamConfig streamConfig) {
    this(leaseConfigProvider, retryPolicyProvider, admitFailureDelayProvider, streamConfig, null);
  }

  public ModelProcessorConfig(
      Supplier<ProcessorLeaseConfig> leaseConfigProvider,
      Supplier<InvocationRetryPolicy> retryPolicyProvider,
      Supplier<Duration> admitFailureDelayProvider) {
    this(
        leaseConfigProvider,
        retryPolicyProvider,
        admitFailureDelayProvider,
        ModelStreamConfig.DEFAULT,
        null);
  }
}

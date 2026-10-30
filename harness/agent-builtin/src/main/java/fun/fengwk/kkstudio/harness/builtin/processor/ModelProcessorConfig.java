package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;

/**
 * ModelProcessor 的部署级配置。
 *
 * <p>{@code leaseConfig} 控制 claim lease 与 heartbeat；{@code streamConfig} 给出实时 delta 的推送合并窗口与独立的
 * checkpoint 落库节奏；{@code retryPolicyProvider} 在每次 TRANSIENT 失败 retry 判定点现读；{@code
 * httpErrorPolicyProvider} 在每次带 HTTP 状态的失败 retry 判定点按冻结 Provider 身份现读；{@code admitFailureDelay} 是
 * Gateway admit 抛异常（肯定未发出请求）或启动前本地放弃（close / cancel / heartbeat 无法启动）时的 reschedule 延迟；{@code
 * toolHistoryActionResolver} 在成功响应持久化前冻结 Tool-owned 历史 action（可为 null，表示全部回退）。ToolProcessor
 * 的配置与之不同，因此本配置命名明确限定为 Model。
 */
public record ModelProcessorConfig(
    ProcessorLeaseConfig leaseConfig,
    InvocationRetryPolicyProvider retryPolicyProvider,
    Duration admitFailureDelay,
    ModelStreamConfig streamConfig,
    ToolHistoryActionResolver toolHistoryActionResolver,
    ModelHttpErrorPolicyProvider httpErrorPolicyProvider) {

  public ModelProcessorConfig {
    leaseConfig = Objects.requireNonNull(leaseConfig, "leaseConfig");
    retryPolicyProvider = Objects.requireNonNull(retryPolicyProvider, "retryPolicyProvider");
    admitFailureDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(admitFailureDelay, "admitFailureDelay");
    streamConfig = Objects.requireNonNull(streamConfig, "streamConfig");
    httpErrorPolicyProvider =
        Objects.requireNonNull(httpErrorPolicyProvider, "httpErrorPolicyProvider");
  }

  public ModelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      Duration admitFailureDelay,
      ModelStreamConfig streamConfig,
      ToolHistoryActionResolver toolHistoryActionResolver) {
    this(
        leaseConfig,
        retryPolicyProvider,
        admitFailureDelay,
        streamConfig,
        toolHistoryActionResolver,
        ModelHttpErrorPolicyProvider.DEFAULT);
  }

  public ModelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      Duration admitFailureDelay,
      ModelStreamConfig streamConfig) {
    this(leaseConfig, retryPolicyProvider, admitFailureDelay, streamConfig, null);
  }

  public ModelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      Duration admitFailureDelay) {
    this(leaseConfig, retryPolicyProvider, admitFailureDelay, ModelStreamConfig.DEFAULT, null);
  }
}

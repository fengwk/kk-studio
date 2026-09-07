package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Model 执行 admission / stream 端口：把一次冻结的 Model invocation 提交给外部 Gateway。
 *
 * <p>execution key 是 {@code (invocationId, proposedAttempt)}，{@code proposedAttempt}
 * 必须为正数；execution 携带冻结 {@link ProviderType} 与内存 {@link ProviderRequest}。{@link #start} 返回前不得同步调用任何
 * Listener 回调；回调可能因进程崩溃 / lease 恢复而重复或迟到，去重与所有权围栏 / 陈旧回调围栏由 Runtime（Processor）负责，Gateway 不保证
 * exactly-once。
 *
 * <p>两阶段激活：{@link #start} 返回 {@link Started} 时不得打开任何回调门控（即便 Provider 同步回调也只能缓冲）， {@link
 * Handle#activate} 由 Processor 在 attach handle + 持久化 markRunning 之后、打开自身 Listener 回调门控之前调用，此时
 * Gateway 才允许打开回调门控 / 启动外部执行；activate 抛异常表示激活失败，Processor 恰好收敛一次 UNKNOWN。
 *
 * <p>admission certainty：{@link Busy} 表示肯定未开始（稍后重试）；{@link Rejected} 表示肯定未开始且不可重试（terminate
 * invocation）；{@link Indeterminate} 表示可能已开始（terminate 为 UNKNOWN）。{@link Handle#cancel} 是 best
 * effort 且必须幂等。
 */
public interface ModelGateway {

  /**
   * 提交一次 execution。只有当实现能证明外部 Gateway 尚未接受该 execution 时才允许抛异常：异常后 durable 仍是 DISPATCHING，Processor
   * 将 invocation 转回 READY 并 reschedule（attempt 不变）。如果发送 / 接受状态不确定 （execution 可能已开始），必须返回 {@link
   * Indeterminate}，绝不能抛。
   */
  StartResult start(Execution execution, Listener listener);

  /**
   * 一次 Model execution 的不可变描述：key 为 {@code (invocationId, proposedAttempt)}，携带冻结协议类型与内存
   * ProviderRequest。
   */
  record Execution(
      UUID invocationId, int proposedAttempt, ProviderType providerType, ProviderRequest request) {

    public Execution {
      Objects.requireNonNull(invocationId, "invocationId");
      if (proposedAttempt <= 0) {
        throw new IllegalArgumentException("proposedAttempt must be positive");
      }
      providerType = Objects.requireNonNull(providerType, "providerType");
      request = Objects.requireNonNull(request, "request");
    }
  }

  /** admission 结果；每个结果对应唯一确定的 durable 后续 transition。 */
  sealed interface StartResult
      permits ModelGateway.Started,
          ModelGateway.Busy,
          ModelGateway.Rejected,
          ModelGateway.Indeterminate {}

  /** 准入成功并已准备 {@code handle}；外部执行须等待 {@link Handle#activate()}，尚未开始。 */
  record Started(Handle handle) implements StartResult {
    public Started {
      handle = Objects.requireNonNull(handle, "handle");
    }
  }

  /** 肯定未开始；调用方应稍后按 {@code retryAfter} 重新 dispatch（attempt 不变）。 */
  record Busy(Duration retryAfter) implements StartResult {
    public Busy {
      retryAfter = HarnessStoreTime.requireWholeMillisecondDuration(retryAfter, "retryAfter");
    }
  }

  /** 肯定未开始且不可重试；调用方 terminate invocation（attempt 不变）。 */
  record Rejected(ModelInvocationError error) implements StartResult {
    public Rejected {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 可能已开始；调用方 terminate invocation 为 UNKNOWN（attempt + 1）。 */
  record Indeterminate(ModelInvocationError error) implements StartResult {
    public Indeterminate {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /**
   * 一次 execution 的本地取消控制；best effort 且幂等，不保证远程停止。两阶段激活契约：Gateway 在 {@link #start} 返回 {@link
   * Started} 时不得打开任何回调门控，{@link #activate} 由 Processor 在 attach handle + 持久化 markRunning 之后调用，此时
   * Gateway 才允许打开回调门控 / 启动外部执行。
   */
  interface Handle {

    void cancel();

    /** 打开 Gateway 回调门控；只在 {@link Started} 返回后由 Processor 调用；抛异常即激活失败（收敛一次 UNKNOWN）。 */
    void activate();
  }

  /** 流与 terminal 回调；重复与陈旧的 Listener 回调由 Runtime 实施所有权围栏与陈旧回调围栏拦截。 */
  interface Listener {

    /** 交付一个非 terminal Provider delta。 */
    void onEvent(ProviderStreamEvent event);

    /** 交付完整 Provider 响应与可选的 native replay 状态。 */
    default void onSucceeded(ProviderCompletion completion) {
      onSucceeded(completion.response());
    }

    /** 交付完整 Provider 响应。 */
    default void onSucceeded(ProviderResponse response) {
      onSucceeded(new ProviderCompletion(response, null));
    }

    /** 交付已确认失败的 terminal error。 */
    void onFailed(ModelInvocationError error);

    /** 交付无法确认是否成功 / 是否执行的 terminal error。 */
    void onUnknown(ModelInvocationError error);
  }
}

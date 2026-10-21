package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Model 执行端口：先做纯本地准入，再在调用线程上阻塞执行一次冻结的 Model invocation。
 *
 * <p>execution key 是 {@code (invocationId, proposedAttempt)}，{@code proposedAttempt}
 * 必须为正数；execution 携带冻结 {@link ProviderType}、Provider connection generation 与内存 {@link
 * ProviderRequest}。
 *
 * <p>调用顺序：{@link #admit} 不产生任何外部副作用，只回答「本进程现在能否执行」；{@link Admitted#run} 阻塞到 Provider 调用结束并返回 {@link
 * Outcome}。取消就是中断运行线程：实现必须随之关闭连接并尽快返回，返回值此时被调用方忽略。
 *
 * <p>确定性语义：{@link Busy} 与 {@link Rejected} 都发生在任何请求发出之前（前者稍后重试、后者终止 invocation）；{@link Unknown}
 * 表示请求可能已发出且结果无法确认，不可重放；{@link Failed} 是已确认失败（可重试与否由 error kind 决定）。
 */
public interface ModelGateway {

  /** 纯本地准入检查；{@link Admitted} 持有一个本地执行名额，调用方必须 {@link Admitted#run} 或 {@link Admitted#close} 之一。 */
  Admission admit(Execution execution);

  /**
   * 一次 Model execution 的不可变描述：key 为 {@code (invocationId, proposedAttempt)}，携带冻结协议类型、Provider
   * connection generation 与内存 ProviderRequest。
   */
  record Execution(
      UUID invocationId,
      int proposedAttempt,
      ProviderType providerType,
      UUID providerConnectionGenerationId,
      ProviderRequest request) {

    public Execution {
      Objects.requireNonNull(invocationId, "invocationId");
      if (proposedAttempt <= 0) {
        throw new IllegalArgumentException("proposedAttempt must be positive");
      }
      providerType = Objects.requireNonNull(providerType, "providerType");
      providerConnectionGenerationId =
          Objects.requireNonNull(providerConnectionGenerationId, "providerConnectionGenerationId");
      request = Objects.requireNonNull(request, "request");
    }
  }

  /** 准入结果。 */
  sealed interface Admission permits Admitted, Busy, Rejected {}

  /** 准入成功并占用本地执行名额；{@link #run} 返回或 {@link #close} 后名额释放。 */
  non-sealed interface Admitted extends Admission, AutoCloseable {

    /**
     * 在调用线程上阻塞执行，非终止增量同步交给 {@code sink}。Provider 失败以 {@link Failed} / {@link Unknown}
     * 返回而不是抛出；线程被中断时关闭连接并 返回。名额在返回时自动释放。
     */
    Outcome run(Sink sink);

    /** 放弃尚未运行的准入并释放名额；幂等。 */
    @Override
    void close();
  }

  /** 肯定未发出请求；调用方应稍后按 {@code retryAfter} 重新调度（attempt 不变）。 */
  record Busy(Duration retryAfter) implements Admission {
    public Busy {
      retryAfter = HarnessStoreTime.requireWholeMillisecondDuration(retryAfter, "retryAfter");
    }
  }

  /** 肯定未发出请求且不可重试；调用方终止 invocation（attempt 不变）。 */
  record Rejected(ModelInvocationError error) implements Admission {
    public Rejected {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 一次执行的终态。 */
  sealed interface Outcome permits Succeeded, Failed, Unknown {}

  /** 完整 Provider 响应与可选的 native replay 状态。 */
  record Succeeded(ProviderCompletion completion) implements Outcome {
    public Succeeded {
      completion = Objects.requireNonNull(completion, "completion");
    }
  }

  /** 已确认失败。 */
  record Failed(ModelInvocationError error) implements Outcome {
    public Failed {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 请求可能已发出，结果无法确认；调用方终止为 UNKNOWN，绝不重放。 */
  record Unknown(ModelInvocationError error) implements Outcome {
    public Unknown {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 非终止增量接收器；在运行线程上同步调用。 */
  interface Sink {

    void onEvent(ProviderStreamEvent event);
  }
}

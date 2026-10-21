package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 以 PostgreSQL provider 资源为支撑的生产 {@link ModelGateway} 适配器。
 *
 * <p>{@link #admit} 通过 {@link ProviderResolutionService} 同步解析冻结 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType} 与内存 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest}（含 connection generation
 * 校验），并无等待取得一个并发名额；不发出任何请求。 确定性解析失败（{@link IllegalArgumentException}）返回携带 {@link
 * ProviderErrorKind#INVALID_REQUEST} 的 {@link Rejected}；其他解析失败原样传播，由 Processor 重新调度；容量耗尽返回携带配置延迟的
 * {@link Busy}。
 *
 * <p>{@link ModelGateway.Admitted#run} 在调用线程（Processor 的虚拟线程）上阻塞执行 {@link
 * ModelProvider#stream}，本类不拥有任何线程池或回调桥： 增量同步转交 Runtime sink，分类过的 {@link ProviderException} 连同 kind
 * 以 {@link Failed} 返回；null 的 provider payload 与 adapter 装配错误是确定性 {@code INVALID_REQUEST} 失败；未分类异常（含
 * sink 抛出的异常）或 Provider 在没有终态时返回，以 {@link Unknown} 返回，因为结果无法确认。线程被中断即取消：Provider 关闭连接并无终态返回，此时返回
 * {@code CANCELLED} 失败，调用方通常已 abandon 并忽略它。名额在 {@code run} 返回或 {@code close} 时释放。gateway 绝不读写
 * HarnessStore。
 */
public final class PlatformModelGateway implements ModelGateway {

  private final ProviderResolutionService providerResolution;
  private final Supplier<Duration> busyRetryDelay;
  private final ConcurrencyAdmission admission;

  /**
   * 生产与测试共用的唯一构造器。{@code busyRetryDelay} 在每次 {@code Busy} 判定时现读，由装配方决定来源——生产装配传入
   * SystemSettingsSnapshot 的 live supplier（每次 admit 从 {@code tool.modelGatewayBusyRetryMillis}
   * 现读）。admission 必须由装配方或测试显式提供， 不允许以无界容量绕过执行上限。
   */
  PlatformModelGateway(
      ProviderResolutionService providerResolution,
      Supplier<Duration> busyRetryDelay,
      ConcurrencyAdmission admission) {
    this.providerResolution = Objects.requireNonNull(providerResolution, "providerResolution");
    this.busyRetryDelay = Objects.requireNonNull(busyRetryDelay, "busyRetryDelay");
    this.admission = Objects.requireNonNull(admission, "admission");
  }

  @Override
  public Admission admit(Execution execution) {
    Objects.requireNonNull(execution, "execution");
    ProviderResolutionService.ResolvedExecution resolved;
    try {
      resolved =
          providerResolution.resolve(
              execution.providerType(),
              execution.providerConnectionGenerationId(),
              execution.request());
    } catch (IllegalArgumentException setupFailure) {
      // 发出请求前的确定性装配失败：终止 invocation，绝不重试。
      return new Rejected(
          new ModelInvocationError(
              ProviderErrorKind.INVALID_REQUEST,
              message(setupFailure, "cannot resolve provider for model invocation")));
    }
    Optional<ConcurrencyAdmission.Lease> acquired = admission.tryAcquire();
    if (acquired.isEmpty()) {
      return new Busy(busyRetryDelay.get());
    }
    return new AdmittedExecution(resolved, acquired.orElseThrow());
  }

  /** 持有并发名额的准入；{@link #run} 与 {@link #close} 都释放它。 */
  private static final class AdmittedExecution implements Admitted {

    private final ProviderResolutionService.ResolvedExecution resolved;
    private final ConcurrencyAdmission.Lease lease;

    private AdmittedExecution(
        ProviderResolutionService.ResolvedExecution resolved, ConcurrencyAdmission.Lease lease) {
      this.resolved = resolved;
      this.lease = lease;
    }

    @Override
    public Outcome run(Sink sink) {
      Objects.requireNonNull(sink, "sink");
      try {
        return stream(sink);
      } finally {
        lease.close();
      }
    }

    @Override
    public void close() {
      lease.close();
    }

    private Outcome stream(Sink sink) {
      ModelProvider provider;
      try {
        provider = resolved.openProvider(resolved.timeoutPolicy());
      } catch (RuntimeException setupFailure) {
        return invalid(
            "cannot create provider for model invocation: "
                + message(setupFailure, "setup failure"));
      }
      OutcomeHandler handler = new OutcomeHandler(sink);
      try {
        provider.stream(resolved.effectiveRequest(), handler);
      } catch (ProviderException classified) {
        return new Failed(toInvocationError(classified));
      } catch (RuntimeException unclassified) {
        // 未分类异常的消息可能携带请求细节或凭据，只记录异常类型。
        return unknown(
            "provider stream failed with an unclassified error; outcome cannot be confirmed: "
                + unclassified.getClass().getSimpleName());
      }
      if (handler.outcome != null) {
        return handler.outcome;
      }
      if (Thread.currentThread().isInterrupted()) {
        return new Failed(
            new ModelInvocationError(ProviderErrorKind.CANCELLED, "model call cancelled"));
      }
      return unknown("provider returned without a terminal result; outcome cannot be confirmed");
    }
  }

  /** 把 Provider 的同线程回调收敛为 sink 增量与唯一的终态 {@link Outcome}。 */
  private static final class OutcomeHandler implements ProviderStreamHandler {

    private final Sink sink;
    private Outcome outcome;

    private OutcomeHandler(Sink sink) {
      this.sink = sink;
    }

    @Override
    public void onEvent(ProviderStreamEvent event) {
      if (outcome != null) {
        return;
      }
      if (event == null) {
        outcome = invalid("provider returned a null stream event");
        return;
      }
      sink.onEvent(event);
    }

    @Override
    public void onComplete(ProviderCompletion completion) {
      if (outcome != null) {
        return;
      }
      outcome =
          completion == null
              ? invalid("provider returned a null completion")
              : new Succeeded(completion);
    }

    @Override
    public void onError(ProviderException error) {
      if (outcome != null) {
        return;
      }
      outcome = error == null ? invalid("provider returned a null failure") : failed(error);
    }

    private static Outcome failed(ProviderException error) {
      return new Failed(toInvocationError(error));
    }
  }

  private static Outcome invalid(String message) {
    return new Failed(new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, message));
  }

  private static Outcome unknown(String message) {
    return new Unknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, message));
  }

  private static ModelInvocationError toInvocationError(ProviderException error) {
    return new ModelInvocationError(
        error.kind(), message(error, "provider failure"), error.httpStatus());
  }

  /** 非抛出的消息提取：adversarial getMessage 只影响 detail，绝不 bypass 状态转换。 */
  private static String message(RuntimeException failure, String fallback) {
    if (failure == null) {
      return fallback;
    }
    String detail;
    try {
      detail = failure.getMessage();
    } catch (RuntimeException ignored) {
      detail = null;
    }
    return detail == null || detail.isBlank() ? fallback : detail;
  }
}

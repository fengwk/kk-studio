package fun.fengwk.kkstudio.harness.provider.transport;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK 21 {@link HttpClient}、受管 {@link ExecutorService} 与受管 {@link ScheduledExecutorService}
 * 的安全有界 SSE 流传输器。
 *
 * <p>核心并发与安全契约：
 *
 * <ul>
 *   <li>构造时要求注入独立的 worker 执行器与定时调度执行器，并强制拒绝跟随重定向（{@code HttpClient.Redirect.NEVER}）。
 *   <li>{@link #stream} 方法先将 I/O 工作任务提交至受管 worker 执行器，再将单调时钟 Watchdog 提交至定时调度器；启动门（start
 *       gate）确保两者均排期成功后才允许工作任务发起网络请求。
 *   <li>任一阶段被执行器拒绝时，能够绝对证明工作任务尚未接触 {@link HttpClient}，并同步抛出 {@link
 *       TransportErrorKind#EXECUTOR_REJECTED} 异常。
 *   <li>受管 worker 运行在阻塞模式 {@link HttpClient#send} 上，完全规避底层异步默认线程池逃逸。
 *   <li>取消操作（{@link ProviderStream#cancel}）保证线性化仲裁，返回后绝不启动新回调，且所有回调严格串行化派发，支持安全重入取消。
 *   <li>对外回调抛出的异常安全隔离，绝不破坏终态语义，绝不记录未清洗的异常日志。
 * </ul>
 */
public class JdkHttpSseTransport {

  private final HttpClient httpClient;
  private final ExecutorService workerExecutor;
  private final ScheduledExecutorService scheduler;

  public JdkHttpSseTransport(
      HttpClient httpClient, ExecutorService workerExecutor, ScheduledExecutorService scheduler) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException("HttpClient must be configured with Redirect.NEVER");
    }
  }

  /**
   * 启动 HTTP/SSE 请求并立即返回受管可取消流句柄。
   *
   * @param request 已构造的 JDK HTTP 请求
   * @param timeoutPolicy 模型调用总超时与闲置超时策略
   * @param limits 有界字节限制配置
   * @param callback 流事件与终态回调接收器
   * @return 幂等取消句柄
   * @throws TransportException 当受管执行器或调度器拒绝提交任务时同步抛出
   */
  public ProviderStream stream(
      HttpRequest request,
      ModelCallTimeoutPolicy timeoutPolicy,
      HttpSseLimits limits,
      HttpSseCallback callback) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(callback, "callback must not be null");

    HttpSseStreamExecution execution =
        new HttpSseStreamExecution(httpClient, request, timeoutPolicy, limits, callback);

    Future<?> workerFuture;
    try {
      workerFuture = workerExecutor.submit(execution::runWorker);
    } catch (RejectedExecutionException e) {
      execution.abortAdmission();
      throw new TransportException(
          TransportErrorKind.EXECUTOR_REJECTED, "Worker executor rejected execution", e);
    }

    ScheduledFuture<?> watchdogFuture;
    try {
      watchdogFuture =
          scheduler.scheduleWithFixedDelay(execution::checkWatchdog, 25, 25, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // 调度器拒绝时，worker 仍在 start gate 阻塞，绝对未发起任何网络请求
      execution.abortAdmission();
      if (workerFuture != null) {
        workerFuture.cancel(true);
      }
      throw new TransportException(
          TransportErrorKind.EXECUTOR_REJECTED, "Scheduler rejected watchdog task", e);
    }

    execution.attachFutures(workerFuture, watchdogFuture);
    execution.openStartGate();
    return execution;
  }
}

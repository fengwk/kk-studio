package fun.fengwk.kkstudio.core.harness.thread.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadActivationDispatcher;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thread activation 的生产 wiring。
 *
 * <p>协作路径为：Redis activation subscriber -> {@link ThreadKick} -> {@link ThreadActivationDispatcher}
 * -> bounded reconcile executor -> {@link ThreadReconciler} -> {@link
 * ThreadReconcileTransactions}。Dispatcher 在 Reconciler 交还 blocker 后经 {@link ActivationNotifier} 发送
 * best-effort wake；跨节点所有权仍只由 PostgreSQL lease/fencing 决定。
 */
@Configuration
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessThreadWorkerConfiguration {

  @Bean(destroyMethod = "shutdown")
  public ExecutorService threadReconcileExecutor(HarnessRuntimeProperties properties) {
    int concurrency = properties.getThreadWorkerConcurrency();
    if (concurrency <= 0) {
      throw new IllegalArgumentException("thread worker concurrency must be positive");
    }
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        60L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(Math.max(concurrency * 4, concurrency)),
        r -> {
          Thread t = new Thread(r, "thread-reconcile");
          t.setDaemon(true);
          return t;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  public ThreadReconciler threadReconciler(
      ThreadReconcileTransactions transactions, HarnessRuntimeProperties properties) {
    int maxSteps = properties.getThreadReconcilerMaxSteps();
    if (maxSteps <= 0) {
      throw new IllegalArgumentException("thread reconciler max steps must be positive");
    }
    return new ThreadReconciler(transactions, Clock.systemUTC(), maxSteps);
  }

  @Bean
  public ThreadKick threadKick(
      ThreadReconciler reconciler,
      @Qualifier("threadReconcileExecutor") ExecutorService threadReconcileExecutor,
      ActivationNotifier activationNotifier) {
    return new ThreadActivationDispatcher(
        reconciler::reconcile, threadReconcileExecutor, activationNotifier);
  }
}

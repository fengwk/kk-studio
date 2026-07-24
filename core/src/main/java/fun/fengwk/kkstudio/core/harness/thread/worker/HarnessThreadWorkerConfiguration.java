package fun.fengwk.kkstudio.core.harness.thread.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.thread.reconcile.ThreadReconcileMapper;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadActivationDispatcher;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadReconciler;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** final ThreadReconciler、activation dispatcher 和恢复扫描的生产 wiring。 */
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

  @Bean(name = "harnessWorkerScheduler", destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "harnessWorkerScheduler")
  public ScheduledExecutorService harnessWorkerScheduler() {
    return Executors.newScheduledThreadPool(
        2,
        r -> {
          Thread t = new Thread(r, "harness-worker");
          t.setDaemon(true);
          return t;
        });
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

  @Bean
  public ThreadRecoveryLifecycle threadRecoveryLifecycle(
      HarnessRuntimeProperties properties,
      ThreadReconcileMapper threadMapper,
      ThreadKick threadKick,
      @Qualifier("harnessWorkerScheduler") ScheduledExecutorService harnessWorkerScheduler) {
    return new ThreadRecoveryLifecycle(
        properties,
        threadMapper,
        threadKick,
        harnessWorkerScheduler,
        properties.getThreadRecoveryInterval(),
        properties.getThreadRecoveryBatchSize());
  }
}

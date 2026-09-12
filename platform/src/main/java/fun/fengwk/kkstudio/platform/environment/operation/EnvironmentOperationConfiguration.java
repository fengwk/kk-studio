package fun.fengwk.kkstudio.platform.environment.operation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Environment Skill 来源管理操作执行所需的底层调度器与线程池装配。 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentOperationConfiguration {

  @Bean(destroyMethod = "shutdownNow")
  @Qualifier("environmentOperationDrainExecutor")
  public ExecutorService environmentOperationDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon(true).name("env-op-drain-", 0).factory());
  }

  @Bean(destroyMethod = "shutdownNow")
  @Qualifier("environmentOperationWorkerExecutor")
  public ExecutorService environmentOperationWorkerExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean(destroyMethod = "shutdownNow")
  @Qualifier("environmentOperationPollScheduler")
  public ScheduledExecutorService environmentOperationPollScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("env-op-poll-", 0).factory());
  }
}

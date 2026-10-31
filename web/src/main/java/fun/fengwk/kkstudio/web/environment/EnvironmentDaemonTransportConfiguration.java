package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Daemon WebSocket 传输层基础设施配置。 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentDaemonTransportConfiguration {

  @Bean(name = "daemonTransportScheduler", initMethod = "start", destroyMethod = "stop")
  public Scheduler daemonTransportScheduler() {
    return new ScheduledExecutorScheduler("daemon-transport-", false);
  }

  @Bean(name = "daemonTransportExecutor")
  public Executor daemonTransportExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean(name = "daemonTransportByteBufferPool")
  public ByteBufferPool daemonTransportByteBufferPool() {
    return new ArrayByteBufferPool();
  }
}

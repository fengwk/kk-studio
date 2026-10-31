package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.ee11.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Daemon WebSocket 传输层基础设施配置。 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentDaemonTransportConfiguration {

  @Bean(name = "daemonTransportScheduler", initMethod = "start", destroyMethod = "stop")
  public Scheduler daemonTransportScheduler() {
    return new ScheduledExecutorScheduler("daemon-transport-", false);
  }

  @Bean
  public ServletContextInitializer daemonWebSocketServerContainerInitializer() {
    return servletContext -> {
      JettyWebSocketServerContainer.ensureContainer(servletContext);
    };
  }
}

package fun.fengwk.kkstudio.web.environment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import fun.fengwk.kkstudio.share.notification.NotificationCarrier;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Daemon 端点（WebSocket 路径 `/api/harness/environment-daemon/v1`）的传输注册。
 *
 * <p>所有物理帧都是共享 carrier 的片，物理上限固定为 {@link NotificationCarrier#PAYLOAD_LIMIT}；即使是大 {@code READY}
 * 能力目录也会被分片，因此 JSR-356 文本/二进制缓冲区只需按该物理上限设置，不再需要抬到 16 MiB。非容器 Spring 上下文中 factory 保持 no-op，以便
 * MockMvc 与 {@code WebEnvironment.MOCK} 测试可以启动。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(EnvironmentDaemonTransportProperties.class)
public class EnvironmentDaemonWebSocketConfiguration implements WebSocketConfigurer {

  private final EnvironmentDaemonWebSocketHandler handler;

  public EnvironmentDaemonWebSocketConfiguration(EnvironmentDaemonWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, EnvironmentDaemonWebSocketHandler.PATH);
  }

  /** 全进程共享的 deadline 时钟；取消的帧立即移出队列，随 Spring 上下文关闭。 */
  @Bean(destroyMethod = "shutdown")
  public static ScheduledThreadPoolExecutor environmentDaemonSendDeadlineTimer() {
    ScheduledThreadPoolExecutor timer =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "environment-daemon-send-deadline");
              thread.setDaemon(true);
              return thread;
            });
    timer.setRemoveOnCancelPolicy(true);
    timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return timer;
  }

  /**
   * 通过 Spring 标准 factory bean 把 JSR-356 文本/二进制帧缓冲区设为 carrier 的固定物理上限。宽容子类会检测缺失的 {@code
   * ServerContainer} 属性（非容器上下文）并跳过配置而不是抛异常。
   */
  @Bean
  public ServletServerContainerFactoryBean environmentDaemonWebSocketContainer() {
    ServletServerContainerFactoryBean container =
        new EnvironmentDaemonWebSocketContainerFactoryBean();
    container.setMaxTextMessageBufferSize(NotificationCarrier.PAYLOAD_LIMIT);
    container.setMaxBinaryMessageBufferSize(NotificationCarrier.PAYLOAD_LIMIT);
    return container;
  }
}

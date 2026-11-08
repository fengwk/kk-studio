package fun.fengwk.kkstudio.platform.environment.server;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonRegistration;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentConfigSource;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentServerSettings;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentUpdateListener;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Environment 会话核心的 Platform 装配：唯一的 {@link EnvironmentDaemonServer} bean 拥有全部连接/lease/invocation
 * 状态。
 *
 * <p>Platform 只提供窄端口实现：{@link EnvironmentRegistry} 提供租约存储围栏，{@link EnvironmentRepository} 提供注册凭据解析，
 * {@link EnvironmentConfigSource} 提供权威卡片配置读取，{@link EnvironmentSessionListener} 接收 READY 事件，{@link
 * DaemonResourceTicketService} 把调用作用域上传映射到全局 Blob 上传契约，{@link SystemSettingsSnapshot} 提供每次判定现读的心跳超时与资源上限。
 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentServerConfiguration {

  /** 每个配置协调并发槽位可排队的任务数；单飞状态保证同一 Environment 不会重复排队。 */
  private static final int CONFIG_QUEUE_PER_SLOT = 128;

  @Bean(name = "environmentConfigExecutor", destroyMethod = "shutdown")
  public ExecutorService environmentConfigExecutor() {
    return new ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(2 * CONFIG_QUEUE_PER_SLOT),
        Thread.ofVirtual().name("environment-config-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  public EnvironmentDaemonServer environmentDaemonServer(
      EnvironmentRegistry environmentRegistry,
      EnvironmentRepository environmentRepository,
      EnvironmentConfigSource environmentConfigSource,
      EnvironmentSessionListener environmentSessionListener,
      ObjectProvider<EnvironmentUpdateListener> updateListenerProvider,
      DaemonResourceTicketService ticketService,
      SystemSettingsSnapshot snapshot,
      @Qualifier("environmentConfigExecutor") ExecutorService environmentConfigExecutor) {
    return new EnvironmentDaemonServer(
        environmentRegistry,
        token -> toRegistration(environmentRepository, token),
        environmentConfigSource,
        environmentSessionListener,
        (environmentId, result) -> {
          EnvironmentUpdateListener listener = updateListenerProvider.getIfAvailable();
          if (listener != null) {
            listener.onUpdateResult(environmentId, result);
          }
        },
        ticketService,
        () -> toSettings(snapshot.get()),
        environmentConfigExecutor);
  }

  private static Optional<DaemonRegistration> toRegistration(
      EnvironmentRepository environmentRepository, String registrationToken) {
    Environment environment =
        environmentRepository.getByRegistrationTokenHash(
            RegistrationTokens.hash(registrationToken));
    if (environment == null) {
      return Optional.empty();
    }
    return Optional.of(new DaemonRegistration(EnvironmentId.of(environment.getId())));
  }

  private static EnvironmentServerSettings toSettings(SystemSettings settings) {
    SystemSettings.Environment environment = settings.environment();
    SystemSettings.StorageMedia storageMedia = settings.storageMedia();
    return new EnvironmentServerSettings(
        Duration.ofMillis(environment.heartbeatTimeoutMillis()),
        environment.maxResourceBytes(),
        storageMedia.temporaryResourceTtlSeconds(),
        storageMedia.temporaryResourceCleanupIntervalSeconds());
  }
}

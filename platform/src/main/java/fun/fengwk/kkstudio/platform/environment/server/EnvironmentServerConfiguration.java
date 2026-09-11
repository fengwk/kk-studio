package fun.fengwk.kkstudio.platform.environment.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonRegistration;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentServerSettings;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Duration;
import java.util.Optional;

/**
 * Environment 会话核心的 Platform 装配：唯一的 {@link EnvironmentDaemonServer} bean 拥有全部连接/lease/invocation
 * 状态。
 *
 * <p>Platform 只提供窄端口实现：{@link EnvironmentRegistry} 提供租约存储围栏，{@link EnvironmentRepository} 提供注册凭据解析，
 * {@link EnvironmentSessionListener} 接收 READY 事件，{@link SystemSettingsSnapshot} 提供每次判定现读的心跳超时与资源上限。
 * WebSocket 传输与目录/skill 产品映射分别由其他适配器承担，核心本身不依赖 Spring。
 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentServerConfiguration {

  @Bean
  public EnvironmentDaemonServer environmentDaemonServer(
      EnvironmentRegistry environmentRegistry,
      EnvironmentRepository environmentRepository,
      EnvironmentSessionListener environmentSessionListener,
      SystemSettingsSnapshot snapshot) {
    return new EnvironmentDaemonServer(
        environmentRegistry,
        token -> toRegistration(environmentRepository, token),
        environmentSessionListener,
        () -> toSettings(snapshot.get()));
  }

  private static Optional<DaemonRegistration> toRegistration(
      EnvironmentRepository environmentRepository, String registrationToken) {
    Environment environment = environmentRepository.getByRegistrationToken(registrationToken);
    if (environment == null) {
      return Optional.empty();
    }
    return Optional.of(
        new DaemonRegistration(EnvironmentId.of(environment.getId()), environment.getName()));
  }

  private static EnvironmentServerSettings toSettings(SystemSettings settings) {
    SystemSettings.Environment environment = settings.environment();
    return new EnvironmentServerSettings(
        Duration.ofMillis(environment.heartbeatTimeoutMillis()), environment.maxResourceBytes());
  }
}

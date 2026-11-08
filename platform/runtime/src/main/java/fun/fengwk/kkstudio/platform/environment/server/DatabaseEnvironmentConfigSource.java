package fun.fengwk.kkstudio.platform.environment.server;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentConfigSnapshot;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentConfigSource;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;

import java.util.Objects;
import java.util.Optional;

/** 使用 runtime Repository 与严格 codec 实现 daemon-server 的权威卡片配置窄端口。 */
@Component
public final class DatabaseEnvironmentConfigSource implements EnvironmentConfigSource {

  private final EnvironmentRepository environmentRepository;

  public DatabaseEnvironmentConfigSource(EnvironmentRepository environmentRepository) {
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
  }

  @Override
  public Optional<EnvironmentConfigSnapshot> find(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Environment environment = environmentRepository.getById(environmentId.value());
    if (environment == null) {
      return Optional.empty();
    }
    return Optional.of(
        new EnvironmentConfigSnapshot(
            environment.getVersion(),
            DaemonConfigProtocolMapper.toProtocol(environment.getDaemonConfig())));
  }
}

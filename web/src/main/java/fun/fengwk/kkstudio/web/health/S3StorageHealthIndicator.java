package fun.fengwk.kkstudio.web.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.storage.S3ReadinessProbe;

import java.util.Objects;

/**
 * Actuator S3 / 全局 Blob 存储健康指标。
 *
 * <p>复用平台 {@link S3ReadinessProbe} 校验 bucket 可达性；成功 UP，异常 DOWN。 绝不向 Health response 泄露
 * bucket、endpoint、凭据或异常详情。
 */
@Component
public class S3StorageHealthIndicator implements HealthIndicator {

  private final S3ReadinessProbe s3ReadinessProbe;

  public S3StorageHealthIndicator(S3ReadinessProbe s3ReadinessProbe) {
    this.s3ReadinessProbe = Objects.requireNonNull(s3ReadinessProbe, "s3ReadinessProbe");
  }

  @Override
  public Health health() {
    try {
      s3ReadinessProbe.checkReadiness();
      return Health.up().build();
    } catch (RuntimeException ignored) {
      return Health.down().build();
    }
  }
}

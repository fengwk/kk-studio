package fun.fengwk.kkstudio.web.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import fun.fengwk.kkstudio.platform.storage.S3ReadinessProbe;

/** S3StorageHealthIndicator 测试：验证 probe 正常时返回 UP，异常时返回 DOWN，且严禁暴露 bucket、endpoint 与凭据详情。 */
class S3StorageHealthIndicatorTest {

  @Test
  void returnsUpWhenProbeSucceeds() {
    // 测试意图：验证当底层 S3ReadinessProbe 检查通过时，Actuator Health 状态为 UP。
    S3ReadinessProbe probe = mock(S3ReadinessProbe.class);
    doNothing().when(probe).checkReadiness();

    S3StorageHealthIndicator indicator = new S3StorageHealthIndicator(probe);
    Health health = indicator.health();

    assertEquals(Status.UP, health.getStatus());
  }

  @Test
  void returnsDownWithoutLeakingDetailsWhenProbeFails() {
    // 测试意图：验证当底层 S3 探针异常时，Health 状态为 DOWN，且 details 为空，不泄露存储桶、端点或异常详情。
    S3ReadinessProbe probe = mock(S3ReadinessProbe.class);
    doThrow(new IllegalStateException("S3 bucket readiness check failed: bucket unreachable"))
        .when(probe)
        .checkReadiness();

    S3StorageHealthIndicator indicator = new S3StorageHealthIndicator(probe);
    Health health = indicator.health();

    assertEquals(Status.DOWN, health.getStatus());
    assertTrue(
        health.getDetails().isEmpty(),
        "Health details must not expose bucket, endpoint or sensitive error details");
  }
}

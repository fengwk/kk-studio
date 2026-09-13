package fun.fengwk.kkstudio.platform.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * {@link IssueControllerProperties} 部署配置校验测试。
 *
 * <p>测试意图：验证默认值合法性与非空、毫秒精度、正数约束门禁。
 */
class IssueControllerPropertiesTest {

  @Test
  void defaultValuesAreValid() {
    IssueControllerProperties properties = new IssueControllerProperties();

    assertEquals(Duration.ofSeconds(30), properties.getLeaseDuration());
    assertEquals(Duration.ofSeconds(1), properties.getPollInterval());
    assertEquals(Duration.ofSeconds(1), properties.getRejectionDelay());
    assertEquals(Duration.ofSeconds(5), properties.getRetryDelay());
    assertEquals(Duration.ofSeconds(1), properties.getActiveDelay());
    assertEquals(Duration.ofSeconds(60), properties.getBlockedDelay());
    assertEquals(Duration.ofMinutes(30), properties.getRunTimeout());
    assertEquals(10, properties.getMaxContinuations());
    assertEquals(64, properties.getMaxDispatchTasks());

    assertNotNull(properties.getWorker());
    assertEquals(8, properties.getWorker().getConcurrency());
    assertEquals(64, properties.getWorker().getQueueCapacity());
  }

  @Test
  void durationSettersAndValidations() {
    IssueControllerProperties properties = new IssueControllerProperties();

    properties.setLeaseDuration(Duration.ofSeconds(10));
    assertEquals(Duration.ofSeconds(10), properties.getLeaseDuration());

    properties.setPollInterval(Duration.ofMillis(500));
    assertEquals(Duration.ofMillis(500), properties.getPollInterval());

    properties.setRejectionDelay(Duration.ofMillis(200));
    assertEquals(Duration.ofMillis(200), properties.getRejectionDelay());

    properties.setRetryDelay(Duration.ofSeconds(3));
    assertEquals(Duration.ofSeconds(3), properties.getRetryDelay());

    properties.setActiveDelay(Duration.ofMillis(800));
    assertEquals(Duration.ofMillis(800), properties.getActiveDelay());

    properties.setBlockedDelay(Duration.ofSeconds(45));
    assertEquals(Duration.ofSeconds(45), properties.getBlockedDelay());

    properties.setRunTimeout(Duration.ofHours(1));
    assertEquals(Duration.ofHours(1), properties.getRunTimeout());

    assertThrows(IllegalArgumentException.class, () -> properties.setLeaseDuration(null));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setLeaseDuration(Duration.ofNanos(123)));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setLeaseDuration(Duration.ofSeconds(0)));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setLeaseDuration(Duration.ofSeconds(-1)));

    assertThrows(IllegalArgumentException.class, () -> properties.setPollInterval(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setRejectionDelay(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setRetryDelay(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setActiveDelay(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setBlockedDelay(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setRunTimeout(null));
  }

  @Test
  void workerAndCapacityValidations() {
    IssueControllerProperties properties = new IssueControllerProperties();

    properties.setMaxContinuations(5);
    assertEquals(5, properties.getMaxContinuations());
    assertThrows(IllegalArgumentException.class, () -> properties.setMaxContinuations(-1));

    properties.setMaxDispatchTasks(16);
    assertEquals(16, properties.getMaxDispatchTasks());
    assertThrows(IllegalArgumentException.class, () -> properties.setMaxDispatchTasks(0));
    assertThrows(IllegalArgumentException.class, () -> properties.setMaxDispatchTasks(-1));

    IssueControllerProperties.Worker worker = properties.getWorker();
    worker.setConcurrency(4);
    assertEquals(4, worker.getConcurrency());
    assertThrows(IllegalArgumentException.class, () -> worker.setConcurrency(0));
    assertThrows(IllegalArgumentException.class, () -> worker.setConcurrency(-1));

    worker.setQueueCapacity(32);
    assertEquals(32, worker.getQueueCapacity());
    assertThrows(IllegalArgumentException.class, () -> worker.setQueueCapacity(0));
    assertThrows(IllegalArgumentException.class, () -> worker.setQueueCapacity(-1));
  }
}

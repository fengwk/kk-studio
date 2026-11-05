package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class HarnessDispatcherPropertiesTest {

  /** 部署未覆盖配置时使用明确的容量与调度默认值。 */
  @Test
  void providesStableDeploymentDefaults() {
    HarnessDispatcherProperties properties = new HarnessDispatcherProperties();

    assertEquals(Duration.ofSeconds(30), properties.getLeaseDuration());
    assertEquals(Duration.ofSeconds(1), properties.getPollInterval());
    assertEquals(Duration.ofSeconds(1), properties.getRejectionDelay());
    assertEquals(64, properties.getMaxDispatchTasks());
    assertEquals(16, properties.getWorker().getConcurrency());
    assertEquals(64, properties.getWorker().getQueueCapacity());
  }

  /** JavaBean 绑定 setter 接受合法的正整毫秒时长与正整数容量覆盖。 */
  @Test
  void acceptsValidOverrides() {
    HarnessDispatcherProperties properties = new HarnessDispatcherProperties();
    HarnessDispatcherProperties.Worker worker = properties.getWorker();

    properties.setLeaseDuration(Duration.ofSeconds(45));
    properties.setPollInterval(Duration.ofMillis(250));
    properties.setRejectionDelay(Duration.ofMillis(500));
    properties.setMaxDispatchTasks(7);
    worker.setConcurrency(3);
    worker.setQueueCapacity(5);

    assertEquals(Duration.ofSeconds(45), properties.getLeaseDuration());
    assertEquals(Duration.ofMillis(250), properties.getPollInterval());
    assertEquals(Duration.ofMillis(500), properties.getRejectionDelay());
    assertEquals(7, properties.getMaxDispatchTasks());
    assertEquals(3, worker.getConcurrency());
    assertEquals(5, worker.getQueueCapacity());
  }
}
